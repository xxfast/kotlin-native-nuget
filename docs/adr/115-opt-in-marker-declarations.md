# ADR-115: `@RequiresOptIn`-marked declarations are out of the exported surface

## Status
Accepted

## Context

Issue #113. A Kotlin author marks a declaration with their own `@RequiresOptIn` marker:

```kotlin
@RequiresOptIn(level = RequiresOptIn.Level.ERROR)
annotation class InternalApi

data class State(
  @property:InternalApi val extra: Extra? = null,
)
```

The forward pipeline treats it as an ordinary public member, with two consequences:

1. The generated `CNameExports.kt` *reads* the marked declaration, so the generated Kotlin does not
   compile until the consuming module opts in module-wide. The error points at code the author
   never wrote:
   `e: CNameExports.kt:1560:98 This is an internal API and should not be used by external clients.`
2. Once opted in, every marked member lands in the C# public surface, so library-internal API
   leaks to C# consumers.

The asymmetry that makes this a design decision rather than a bug fix: **C# has no way to honour a
Kotlin opt-in requirement.** A Kotlin consumer of a marked declaration is forced to acknowledge it
(`@OptIn` or a compiler flag). A C# consumer of the generated binding sees a plain public member
with no signal at all. So "export it and let the consumer decide" is not available to us: exporting
a marked declaration always erases the marker's entire purpose.

This decision sits on top of ADR-063's package-level export scoping, which already anticipated it:
ADR-063 considered and deferred a `@HiddenFromObjC`-style per-declaration opt-out, noting it "can be
layered *on top of* the package filter". An opt-in marker is that per-declaration signal, except the
author already wrote it for another reason.

## Verified mechanism findings

Everything in this section was **Verified** by a scratch KSP2 spike (a two-module Gradle project in
`mktemp -d`, KSP 2.3.10 / Kotlin 2.4.10, driving `KotlinSymbolProcessing` through the same
programmatic entry point as the ADR-060 Tier 1 harness, with a probe `SymbolProcessor` that dumps
every `KSAnnotated`'s annotations, use-site targets, and each annotation type's own annotations).
Fixture and real output are reproduced below.

Fixture (excerpt):

```kotlin
@RequiresOptIn(level = RequiresOptIn.Level.ERROR)
annotation class InternalApi

@RequiresOptIn(level = RequiresOptIn.Level.WARNING)
annotation class ExperimentalApi

@SubclassOptInRequired(InternalApi::class)
interface OpenForSubclass

data class State(
  @property:InternalApi val extra: Extra? = null,
  @InternalApi val defaultTarget: Int = 0,
  val ok: String = "",
)

@InternalApi class MarkedClass { fun f(): Int = 1 }

class Holder {
  @InternalApi fun markedFun(): Int = 1
  @InternalApi val markedProp: Int = 1
  @get:InternalApi val getterMarked: Int = 1
  @ExperimentalApi val experimental: Int = 1
  @OptIn(InternalApi::class) fun consumesInternal(): Int = 1
  fun depReturn(): dep.DepMarkedClass? = null   // marker + class from a separate compiled artifact
}
```

**Finding 1 (Verified): a `@property:`-targeted marker IS visible on the `KSPropertyDeclaration`,
and is NOT visible on the constructor `KSValueParameter`.** This is the issue's exact shape and the
single claim the whole feature rests on.

```
 DECL KSPropertyDeclarationImpl fixture.State.extra
  decl.annotations ->
      @InternalApi[target=PROPERTY] declFqn=fixture.InternalApi metaAnns=[RequiresOptIn] isMarker=true
  getter.annotations -> (none)
 PARAM extra
  param.annotations -> (none)
```

**Finding 2 (Verified): a default-target marker on a constructor `val` is visible on BOTH the
`KSPropertyDeclaration` (target=null) and the `KSValueParameter` (target=null)**, and also on the
synthesized `copy`/`<init>` parameters. So reading `KSPropertyDeclaration.annotations` covers the
`@property:` case *and* the default-target case; no special handling of the constructor parameter is
needed for the skip to fire.

```
 PARAM defaultTarget
  param.annotations -> @InternalApi[target=null] ... isMarker=true
 DECL KSPropertyDeclarationImpl fixture.State.defaultTarget
  decl.annotations -> @InternalApi[target=null] ... isMarker=true
```

**Finding 3 (Verified): a `@get:`-targeted marker is NOT on the property, only on
`KSPropertyDeclaration.getter.annotations` (target=GET).** A check that reads only
`declaration.annotations` silently misses `@get:InternalApi`.

```
 DECL KSPropertyDeclarationImpl fixture.Holder.getterMarked
  decl.annotations -> (none)
  getter.annotations -> @InternalApi[target=GET] ... isMarker=true
```

**Finding 4 (Verified): the two-hop marker recognition works.** Resolving an annotation's own
declaration and asking for *its* annotations returns `kotlin.RequiresOptIn` with resolvable
`qualifiedName`. This works for a marker declared in the module under compilation **and** for a
marker declared in a separate compiled artifact on `libraries`:

```
DEP CLASS dep.DepMarkedClass resolved=true
  dep class annotations -> @DepInternalApi[target=null] declFqn=dep.DepInternalApi
      metaAnns=[RequiresOptIn] isMarker=true markerArgs=level=Level.ERROR, message=dep internal
```

**Verified (was Inferred): the same two-hop resolution holds for a marker read off a Kotlin/Native
`.klib`.** The spike used a JVM `.jar` on `libraries`, because the KSP2 programmatic entry point
available there was `KSPJvmConfig`, so this claim shipped un-verified against the real native
toolchain. It is now retired for real by fixture cell 8: `CatteryInternalApi` is declared one
Gradle module away, in `:test-models`
(`test-models/src/nativeMain/kotlin/.../test/models/CatteryInternalApi.kt`), applied to
`Cattery.crossModuleName` in `:test-library`
(`test-library/src/nativeMain/kotlin/.../test/issue113/Issue113Sample.kt`), and exercised through a
real `packNuget` run: the generated `Interop.cs` declares `Cattery` with `PlainName` and
`ConsumesMarked` only, no `CrossModuleName`, and `IntegrationTests/Issue113Tests.cs`'s
`Cell8_MarkerFromADependencyModule_IsResolvedAcrossTheKlib` pins the absence from compiled C#.
`Tier1OptInMarkerSkipTest`'s `a marker declared in a dependency module is resolved across the
boundary` runs the same assertion at Tier 1 speed over a jar, and says so in its own comment: this
klib-level fixture cell is what retires the claim for real, not the Tier 1 jar case.


**Verified (grep): the forward pipeline reads no annotations today.** `grep -rn "\.annotations"
nuget-processor/src/main/kotlin` returns nothing. This feature introduces the first annotation read
in the forward direction, so there is no existing helper to extend, and no existing evidence in this
repo that annotations survive the cross-module klib read the way the finding above shows they
survive a jar read. That is what keeps the klib claim Inferred rather than "obviously fine".

**Finding 5 (Verified): `RequiresOptIn.Level` is readable, and its argument value is a
`KSClassDeclaration` (`KSClassDeclarationEnumEntryImpl`), not a `String` or `KSType`.** Any code
keying on level must read `simpleName.asString()` (`"ERROR"` / `"WARNING"`) off that declaration,
not `toString()`.

```
markerArgs=level=Level.ERROR (valueClass=com.google.devtools.ksp.impl.symbol.kotlin.KSClassDeclarationEnumEntryImpl), message= (valueClass=kotlin.String)
```

**Finding 6 (Verified): `@SubclassOptInRequired` is NOT itself meta-annotated `@RequiresOptIn`.** It
is a distinct annotation (`kotlin.SubclassOptInRequired`) whose *argument* names a marker class, so
the two-hop test does not match it:

```
DECL KSClassDeclarationImpl fixture.OpenForSubclass
  @SubclassOptInRequired[target=null] declFqn=kotlin.SubclassOptInRequired
      metaAnns=[Target, Retention, MustBeDocumented, SinceKotlin, WasExperimental] isMarker=false
```

**Finding 7 (Verified): `@OptIn(InternalApi::class)` is not confused with a marker.** `kotlin.OptIn`
carries no `RequiresOptIn` meta-annotation, so the two-hop test answers `isMarker=false` for it. A
declaration annotated `@OptIn(...)` is a *consumer* of a marker and stays exported.

```
DECL KSFunctionDeclarationAAImpl fixture.Holder.consumesInternal
  decl.annotations -> @OptIn[target=null] declFqn=kotlin.OptIn metaAnns=[Target, Retention, SinceKotlin] isMarker=false
```

Spike command (real, run against a scratch project, `EXIT=OK`):

```
gradlew -p /tmp/optin-spike.XXXX :probe:run -q --console=plain
```

### Amendment: three of the spike's shapes do not compile (verified by execution)

The spike above drives KSP only, and KSP2 does not run the frontend's `OPT_IN_MARKER_ON_WRONG_TARGET`
checker. So its fixture was never shown to be *compilable* Kotlin, only to be readable by KSP.
Compiling the same shapes in this repository (Kotlin 2.4.10, real `macosArm64` / `mingwX64` targets,
`:test-library` and `:test-models`) rejects three of them outright:

| Position | Kotlin 2.4.10 |
| --- | --- |
| `@Marker` on a class / function / body property / top-level declaration | legal |
| `@property:Marker` on a constructor `val` (Finding 1) | legal |
| default-target `@Marker` on a constructor `val`, marker with **no** `@Target` (Finding 2) | **rejected**: `Opt-in requirement marker annotation cannot be used on parameter.` The default use-site target for a constructor `val` is `param` |
| default-target `@Marker` on a constructor `val`, marker whose `@Target` excludes `VALUE_PARAMETER` | legal; the target then resolves to `property` |
| `@get:Marker`, and the same annotation written directly on an explicit `get()` accessor (Finding 3) | **rejected**: `Opt-in requirement marker annotation cannot be used on getter.` Both spellings |
| `@set:Marker` on a `var` | legal |
| `@field:Marker` | **rejected**: `Opt-in requirement marker annotation cannot be used on field.` |

Consequences for the implementation:

- **Finding 2 is only reachable through a marker that declares a `@Target` list.** That is the
  common real-world shape, so the finding still matters, but a marker with no `@Target` cannot
  express it.
- **Finding 3 describes a shape that cannot exist.** There is no `@get:` marker to read, so the
  `getter` half of the "Where the marker is read" table below is dead code.
- **`setter?.annotations` is still live, and is the only accessor position that is.** `@set:Marker`
  compiles, and the marker is invisible on `KSPropertyDeclaration.annotations`, so the silent-leak
  risk Finding 3 existed to guard against is real. It just lives on the setter.
- **`@field:` is out of scope for a stronger reason than the one recorded**: not merely "a backing
  field has no forward projection", but "not writable Kotlin".

Open, and deliberately not decided here: `@set:Marker` means *reads are free, writes require
opt-in*. The Decision's read table skips the whole property, which is what the ADR-115 fixture
asserts. Exporting it get-only is the defensible alternative, and it was never weighed on its own
because it was bundled with `@get:`, which turns out not to exist.

## Prior art (both precedents point the other way, and why we deviate)

**`binary-compatibility-validator`** (the tool the issue names). `nonPublicMarkers` is a collection
of fully-qualified annotation names, appended in the DSL
(`nonPublicMarkers.add("my.package.MyInternalApiAnnotation")`), described as "Set of annotations
that exclude API from being public. Typically, it is all kinds of `@InternalApi` annotations that
mark effectively private API that cannot be actually private for technical reasons." It does **not**
auto-exclude `@RequiresOptIn` markers; the author lists them explicitly. There is no `publicMarkers`
counterpart. Verified by reading the project README
(<https://github.com/Kotlin/binary-compatibility-validator>).

**Kotlin/Native's ObjC/Swift export.** It does not hide opt-in-required declarations either. Hiding
is explicit and per-declaration, via `@HiddenFromObjC` ("use the `@HiddenFromObjC` annotation to hide
a Kotlin declaration from Objective-C and Swift. It disables the function or property export to
Objective-C"), which is itself experimental under `@ExperimentalObjCRefinement`
(<https://kotlinlang.org/docs/native-objc-interop.html>,
<https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.native/-hidden-from-obj-c/>). Note that a
Swift or ObjC consumer can no more honour a Kotlin opt-in marker than a C# consumer can, and Kotlin
still exports it. So the "the consumer cannot opt in, therefore drop it" argument on its own does
**not** match how Kotlin's own foreign export behaves.

**Why this ADR deviates from both anyway.** Neither precedent has failure (1). BCV produces a text
dump and ObjC export goes straight from the frontend to a framework header; neither generates an
intermediate *Kotlin source file that must itself compile*. This project does, and that generated
file currently fails to compile against the author's own marker. That failure has to be fixed by
default, and there are exactly three ways to fix it: drop the declaration (alternative 1), opt in on
the author's behalf (alternative 4, which fixes the compile and makes the leak strictly worse), or
leave the build broken until the author configures something (alternative 2). Alternative 1 is the
only one that fixes the reported build break by default without widening the leak. The leak half of
the decision follows the drop for free; it is not independently argued from the precedents, which
would have gone the other way.

Skipped, deliberately: Swift Export, JS/Wasm export, Java interop. The ObjC precedent and BCV
already settle the "is this automatic anywhere?" question with a consistent "no", and none of the
others has failure (1) either, so none of them can change the tie-break above.

## Alternatives Considered

### 1. Automatic: any declaration behind a `@RequiresOptIn` marker is out of scope (chosen)

Presence of a marker on the declaration (or its getter/setter) removes it from the exported surface,
regardless of `RequiresOptIn.Level`. A `SKIPPED_OPT_IN_MARKER` warning names the declaration and the
marker's fully-qualified name.

Level is deliberately **not** consulted. Failure (1) only bites at `ERROR`, but failure (2) leaks at
both levels, and the leak is the one a C# consumer cannot detect. Keying on level would also produce
the least defensible split: `WARNING`-level markers, the ones actually meaning "experimental public
API", would be the ones silently exported with no signal reaching C# at all.

Pros: no new config, no new plugin/processor plumbing, fires for the issue's exact reproducer, and
it uses a signal the author already wrote deliberately. Fires identically for a marker defined in a
dependency module (Finding 4).

Cons: `@RequiresOptIn` covers two different intents, *internal* and *experimental*, and this treats
them the same. A library whose entire public API sits behind one `@ExperimentalFooApi` marker (a
common kotlinx-style shape) gets an empty C# surface. That is the real cost, and the compensation is
that the drop is never silent: one `SKIPPED_OPT_IN_MARKER` warning per dropped declaration, each
naming the marker FQN, so an author who wanted it exported is told exactly which annotation caused
it. See "Open questions".

### 2. Config: `nuget { publish { nonPublicMarkers(...) } }`

Mirrors `binary-compatibility-validator`, which the issue names. BCV's `nonPublicMarkers` is a
collection of fully-qualified annotation names, appended in the DSL, and it does **not** auto-exclude
`@RequiresOptIn` markers: you list them explicitly
(<https://github.com/Kotlin/binary-compatibility-validator>, verified by reading its README).

Pros: the author states intent, so internal-vs-experimental is never guessed. A project that already
runs BCV has usually already written the list.

Cons: does not fix the reported bug by default. The failing build in issue #113 stays failing until
the author finds and configures this. It also does not have to be a `@RequiresOptIn` marker at all
under BCV's semantic, which widens the feature past what the issue asks for. New plumbing crosses
the plugin/processor boundary: `NugetPublishConfig` field + method, a `argMethod.invoke(ksp,
"nuget.nonPublicMarkers", ...)` line in `NugetPlugin`, an option read in `NugetProcessorProvider`,
and threading through to the plan.

Rejected as the v1 default because the restatement's contract is "opt-in-marked declarations stay
out of the exported surface", and this option only satisfies it after configuration.

### 3. Hybrid: automatic by default, with an escape list to force export

Option 1's behaviour, plus `nuget { publish { exportMarkers("com.foo.ExperimentalFooApi") } }`
naming markers to keep exporting. Note the list is the *inverse* of BCV's: BCV lists what to hide,
this would list what to keep.

Pros: fixes the reported bug by default and gives the experimental-public-API case a way out.

Cons: pays option 2's whole plumbing cost for a case nobody has reported yet, and introduces a
second, differently-polarised marker list in a project that may already have BCV's. Deferred, not
rejected: it is purely additive on top of option 1 and can land later without breaking anything
already working.

### 4. Add the author's markers to the generated file's `@OptIn` (rejected)

`NugetProcessor.kt:1125-1141` already builds the generated file's `@OptIn` list
(`ExperimentalNativeApi`, `ExperimentalForeignApi`, and conditionally `ExperimentalCoroutinesApi`).
Discovering every marker in the exported set and adding it there would make the generated Kotlin
compile.

Rejected: it fixes failure (1) and makes failure (2) strictly worse. The build stops complaining and
the library-internal API leaks to C# with the last remaining signal removed. It is the option that
looks cheapest and is most wrong.

### 5. Map the marker to C# `[Experimental("ID")]` (deferred, not v1)

.NET 8 / C# 12 has `System.Diagnostics.CodeAnalysis.ExperimentalAttribute`, which is a genuine
structural analogue of `@RequiresOptIn`: a compile-time diagnostic the consumer suppresses per
diagnostic ID. A `WARNING`-level marker could plausibly map to it rather than being dropped.

Deferred: it needs its own diagnostic-ID scheme, it only makes sense for the experimental intent
(not the internal one), and it cannot be told apart from the internal intent without option 2's or
3's config. Worth revisiting if the empty-surface case in option 1 turns out to bite.

## Decision

**Option 1.** A declaration carrying a `@RequiresOptIn`-meta-annotated marker is not part of the
forward-exported surface, at any `RequiresOptIn.Level`, with a named `SKIPPED_OPT_IN_MARKER`
diagnostic.

### Recognising a marker

Two hops (**Verified**, Findings 4 and 7):

```kotlin
internal fun KSAnnotation.isOptInMarker(): Boolean =
  annotationType.resolve().declaration.annotations.any {
    it.annotationType.resolve().declaration.qualifiedName?.asString() == "kotlin.RequiresOptIn"
  }
```

`kotlin.OptIn` answers `false` here, so an `@OptIn(Marker::class)` consumer stays exported
(Finding 7). `kotlin.SubclassOptInRequired` also answers `false` (Finding 6) and is **explicitly out
of scope**: its semantic is "you may use this, you may not subclass it", and the forward direction
never generates a C# subclass of an exported Kotlin class, so a `@SubclassOptInRequired` type is
still safe to export. Say so in the implementation with a comment, so a later reader does not
"fix" it.

### Where the marker is read

Three positions, all **Verified**, and corrected by the amendment above:

| Declaration form | Read | Finding |
| --- | --- | --- |
| `@InternalApi class`/`fun`/`val` | `declaration.annotations` | 2, and the class/function rows of the spike output |
| `@property:InternalApi val` (ctor `val` or otherwise) | `declaration.annotations`, target `PROPERTY` | 1 |
| ~~`@get:InternalApi val`~~ / `@set:InternalApi var` | `(declaration as KSPropertyDeclaration).setter?.annotations` | 3, amended: `@get:` does not compile, so only the setter position exists |

`@field:` is not read: a backing field has no forward projection, and marking only the field does not
mark the property the bridge actually calls. `@param:` on a constructor `val` is covered because the
default-target case also lands on the property (Finding 2); a `@param:`-only marker on a *function*
parameter is out of scope for v1 (see Scope).

### Where the skip lands (the structural split)

Two different places, because a marked class and a marked member fail differently:

- **A marked class, object, interface, enum or value class**: never declared. It is refused at the
  same place a package-scope refusal is (`NugetProcessor`'s `isExported` / root-bucket filters, and
  `ForwardReachabilityClosure` for a dependency-module type), so no C# type is emitted and nothing
  in `CNameExports.kt` names it.
- **A marked member of an exported class, or a marked top-level function/property**: a per-callable
  skip in `ForwardCallablePlanner`, via a new `ForwardPlanSkipReason.OPT_IN_MARKER`
  (`droppedFromCSharp = true`). This is the same *kind* of skip as `EXCLUDED_DEPENDENCY_TYPE`: the
  declaration is fully supported, it is out of scope by the author's own signal, not by a bridge
  limitation.
- **A member whose *type* is a marked class**: this must carry its own reason, not fall through to
  the existing unexported/undeclared machinery. `SKIPPED_UNEXPORTED_DEPENDENCY_TYPE`'s hint names
  `include(...)`, which is wrong here (no include can bring a marked type into scope), and
  `SKIPPED_UNDECLARED_*`'s hint names the nesting. Add `ForwardPlanSkipReason.OPT_IN_MARKER_TYPE`
  and render both reasons through one `ForwardDiagnosticKind.SKIPPED_OPT_IN_MARKER`, exactly as
  `UNEXPORTED_DEPENDENCY_TYPE` / `EXCLUDED_DEPENDENCY_TYPE` share one kind today.

#### Amendment (issue #121): the legacy routes need the gate spelled out

The split above says the member skip is "a per-callable skip in `ForwardCallablePlanner`", which is
true and was not sufficient. It locates the gate in the planner, and the lambda, suspend-lambda and
`Flow` property arms do not run through the planner: they run *after* it declines, on both sides
(`ClassExports`, `SealedClassExports`, and the two matching arms in `CirClassTranslator`).

Those arms read a null plan as "the planner has no shape for this type", which is what they exist
for. A refusal is also a null plan. So a marked lambda or `Flow` property was reported
`SKIPPED_OPT_IN_MARKER` by the planner and then emitted anyway, on both sides, and the generated
`CNameExports.kt` read a marked declaration without opting in. Nineteen members leaked in one real
project before this was caught.

The gate is therefore not "the planner refuses it" but **"no route emits a refused declaration"**.
Every arm that runs after a null plan asks `isOptInRefused()` before emitting. The property still
reaches the planner, so the diagnostic is unchanged; only the emitters are gated.

This generalises beyond opt-in: a declaration named in any `SKIPPED_*` diagnostic must be absent
from both generated artifacts, and that pairing is mechanically checkable over a Tier 1 result
(`kspWarnings` against `generated` and `generatedCSharp`). `Tier1OptInLambdaRouteTest` asserts it
over its own fixture; making it a global invariant is a broader change than this fix.

### Consumer-side API

The C# consumer sees the *absence* of output. For the issue's reproducer, `State` is still exported;
`State.Extra` simply is not there:

```csharp
var state = new State();
// state.Extra;  // does not exist: no property, no IntPtr fallback, no stub
```

And the generated `CNameExports.kt` no longer reads `State.extra`, so it compiles without any
module-wide opt-in.

The author's side is the KSP diagnostic, in `ForwardDiagnostic.format`'s existing shape
(`[nuget:KIND] <verb> <declaration>: <reason>. <hint><at location>`):

```
w: [nuget:SKIPPED_OPT_IN_MARKER] Skipping property `State.extra`: it is marked with the opt-in
marker `com.example.InternalApi`. A C# consumer has no way to opt in, so an opt-in-required
declaration is not exported; remove the marker, or move the declaration behind `exclude(...)` if
it was never meant to be public, at Fixture.kt:12
```

and for the member-typed-with-a-marked-class case:

```
w: [nuget:SKIPPED_OPT_IN_MARKER] Skipping function `Repo.load`: its return type `Session` is marked
with the opt-in marker `com.example.InternalApi`, so no C# type is declared for it, at Repo.kt:31
```

Exact wording is the implementer's; the load-bearing parts are that the message names **the marker's
fully-qualified name** (so the author can find it) and states **that C# cannot opt in** (so the
behaviour reads as a decision rather than a limitation).

### Interaction with the known `warnDroppedForwardCallables` bug

`NugetProcessor.warnDroppedForwardCallables` hardcodes the reason sentence as
`"its ${dropped.reason} type combination is not supported"` for every reason except
`REFERENCE_UNDERLYING_VALUE_CLASS_CONSTRUCTOR`, which gets an `if`. For `OPT_IN_MARKER` that
sentence would read "its OPT_IN_MARKER type combination is not supported", which is wrong on both
counts: it is not a type combination and it is not unsupported.

This feature can land without the recorded proper fix (a `reason.diagnosticReason()` sibling to
`diagnosticHint()`, a separate ROADMAP Phase 3 item) by adding a second branch to that existing
`if`, the same way `REFERENCE_UNDERLYING_VALUE_CLASS_CONSTRUCTOR` did. It does not *force* the fix,
but it is the second special case, which is the usual signal that the `if` should become the enum
method. Flagged here, not scoped here.

## Consequences

- Declarations behind an author's own `@RequiresOptIn` marker disappear from the C# surface. For a
  library that used markers to fence off internals, that is the fix. For a library whose public API
  is experimental-but-intended-for-export, that is a regression from "leaks" to "absent", and the
  only remedy in v1 is to remove the marker.
- Issue #113's note that this "hides real bugs too" cuts both ways: issue #111 was only reachable
  through a marked member. After this change such members are not exported, so bugs behind them stop
  being reachable *and* stop being tested. Tier 1 fixtures exercising a construct must not rely on a
  marked declaration to reach it.
- The `@OptIn` list the generated file emits (`NugetProcessor.kt:1125-1141`) is untouched. It keeps
  naming only the bridge's own markers (`ExperimentalNativeApi`, `ExperimentalForeignApi`,
  conditionally `ExperimentalCoroutinesApi`), never the author's. That is deliberate: see
  alternative 4.
- Deferred: the `exportMarkers(...)` escape list (alternative 3), the `[Experimental]` C# mapping
  (alternative 5), `@SubclassOptInRequired` (Finding 6, explicitly out of scope), `@field:`- and
  function-`@param:`-only markers, and any handling of the `-opt-in=` compiler flag (a module-wide
  opt-in does not change what should be exported, so it is ignored).

## Open questions for the gate

0. **Deviation from precedent.** Both prior-art precedents (BCV, ObjC export) are explicit-config,
   not automatic. This ADR deviates because only this project has failure (1). If the gate weighs
   precedent-consistency higher than fixing the build break by default, alternative 3 (hybrid) is
   the re-cut.
1. **The empty-surface case.** Is a library whose whole public API sits behind one experimental
   marker in scope for v1? If yes, this ADR should be re-cut as alternative 3 (hybrid). If no,
   option 1 stands and the diagnostic is the only compensation.
2. **Severity.** `SKIPPED_OPT_IN_MARKER` is proposed as `WARNING`, consistent with every other
   `SKIPPED_*` kind. A library that marks a lot of internals will produce a lot of warnings on every
   build. Should it be `INFO_` instead once the behaviour is documented? (Recommendation: keep
   `WARNING` for the first release, since it is a behaviour change people need to see.)

## Resolved at the gate

Two decisions shipped that this ADR's body never separately weighed, because both grew out of a
question this ADR left open rather than a question it asked.

**(a) `@set:Marker` skips the whole property, not a get-only projection.** The amendment above
already narrows "Where the marker is read" to say the setter is the only accessor position that
exists, and flags, but does not decide, that exporting the property get-only (reads are free,
writes require opt-in) is the defensible alternative it never got to weigh on its own, since it was
originally bundled with `@get:`, which turns out not to be writable Kotlin at all. The gate chose
skip-the-whole-property over get-only. Reasoning: get-only would need a second, narrower kind of
partial projection that does not exist anywhere else in the forward plan (a property either has a
getter-and-setter shape or it does not; there is no accessor-level opt-out today), for a case with
no reported motivating example, versus the "declaration behind a marker is out of scope" rule the
rest of the feature already establishes staying uniform across every read position. The fixture
(`Litter.viaSetter`) and `IntegrationTests/Issue113Tests.cs`'s `Cell3_AccessorTargetedMarker_IsAbsent`
pin the chosen behaviour: no `ViaSetter` property, get or set, in the generated C#.

**(b) A marked primary-constructor `val` never leaves the marked declaration reachable through a
constructor.** The Decision section fixes where a marked *member* or *class* is skipped, but does
not say what happens to a constructor parameter whose backing property is marked. The invariant the
gate chose: a marked declaration must never appear in a C# signature, constructors included, so the
outcome follows from the position the parameter occupies in the parameter list, not from a new rule
of its own:
- A **trailing** marked parameter that carries a **default** reuses ADR-096's own omitting-overload
  machinery: the shorter constructor overload that already omits it never named it in the first
  place, so that overload survives untouched and is the only one exported.

  **Amendment (2026-09-10, issue #128): that holds only when the marker sits on the parameter or
  its property while the parameter's *type* is unmarked** (verified against Kotlin 2.4.10:
  `PropMarked(5)` and `PropMarked()` both compile from a non-opting file). When the parameter's
  **type** is opt-in-marked, no arity is callable, defaults included and regardless of what the
  default expression reads (verified: `Mixed(a = 5)` is rejected exactly like
  `Mixed(1, Mode.Slow)`, while `DefaultReadsMarked(val n: Int = Mode.Fast.ordinal)` compiles, so
  the trigger is the declared parameter type and never the default expression). Kotlin propagates
  the requirement from the callee's signature, so a shorter call is no more legal than the declared
  one. Every arity is then skipped `OPT_IN_MARKER_TYPE`, one `SKIPPED_OPT_IN_MARKER` per arity, and
  `WARNING_NO_PUBLIC_CONSTRUCTOR` reports the class as factory-only. Before this, ADR-096
  synthesized `GroomingPlan(name)` for exactly this shape and the generated `CNameExports.kt` did
  not compile (`Cattery bookkeeping, not a public API`). The per-arity warnings are kept rather than
  collapsed: this ADR's invariant is that a declaration absent from both artifacts is named by a
  `SKIPPED_*`, and a suppressed arity would be absent unnamed.
- An **undefaulted or non-trailing** marked parameter has no shorter overload that omits it, so the
  constructor itself is dropped (and a data class's `copy` alongside it, for the same reason), named
  once with `OPT_IN_MARKER` each. The class stays reachable only through a Kotlin factory, and
  `WARNING_NO_PUBLIC_CONSTRUCTOR` fires the same way it does for every other constructor-skip
  reason, naming `OPT_IN_MARKER` among the skipped constructors.

Both branches are pinned by `Tier1OptInMarkerSkipTest`'s constructor cases and by
`IntegrationTests/Issue113Tests.cs`'s `Cell1_PropertyTargetedMarker_OnAConstructorVal_IsAbsent` /
`Cell2_DefaultTargetMarker_OnAConstructorVal_IsAbsent`. The amendment's marked-*type* branch is
pinned by `Tier1OptInMarkedParameterArityTest` and `IntegrationTests/Issue128Tests.cs`, whose
`GroomingLog` cell is the control that the unmarked-type branch above did not move.
