# ADR-179: `@CSharpName` on a member: an author-declared C# name that resolves a CS0102 collision without renaming the Kotlin API

## Status

Accepted

## Context

Issue [#366](https://github.com/xxfast/kotlin-native-nuget/issues/366). Since
[ADR-151](151-bytearray-mapping.md) made `ByteArray` bridge, this ordinary KMP interface

```kotlin
interface Record {
  val payload: Payload?
  fun payload(code: Int): ByteArray?
}
```

fails the build with `ERROR_CSHARP_NAME_COLLISION` (`ForwardDiagnostic.kt:381`, emitted from
`CirMemberNameCollisions.kt:151-189` and `:225-288`, `CirClassTranslator.kt:3741-3790` and
`:3916-3945`; verified by reading): both members render `Payload`, and C# cannot declare a property
and a method with one name on one type (CS0102). The error is right per
[ADR-110](110-top-level-function-pascal-case.md) and [ADR-113](113-interface-declaration-on-the-forward-plan.md)
("a rename is a silently different API", `CirMemberNameCollisions.kt:32`), and its only hint is
"rename one of them" (`CirMemberNameCollisions.kt:199`). For a KMP library that rename lands on
Android and iOS too, to satisfy a C#-only rule. `Issue112Sample.kt:89` hid the case by returning
`Sequence<Int>` (refused by name) so the colliding method is dropped before the guard runs.

What is wanted: one annotation on one member that sets its C# name. ADR-110/113 reject only a name
the generator picks on its own; a name the author writes is not silent. Not wanted: automatic
renaming or suffixing, skipping one side (ADR-055's both-halves contract), or a warning.

Constraints found in source:

- There is no annotations module. `nuget-runtime` is native-only (`nuget-runtime/build.gradle.kts:11-14`,
  four native targets) and the plugin wires it to `${target}MainApi` plus `export()`
  (`NugetPlugin.kt:230-268`); [ADR-130](130-reverse-error-envelope-on-runtime.md) variant 3 rejected a
  `commonMain` `api` on it because a JVM/JS consumer would fail at configuration. Verified by reading.
- [ADR-063](063-forward-declaration-level-export-scoping.md):130-141 rejected a per-declaration *export* annotation
  for v1, one objection being "a source dependency the export module must take on". That objection was
  about ceremony on every declaration; here the annotation is written once, on the one member that
  collides, so the dependency is the whole feature, not overhead on it.
- ADR-037:274 and ADR-021:371 mention a planned `nuget-annotations` module that was never built.
- The processor already reads annotations by qualified name through the inline idiom
  (`ForwardOptInMarkers.kt:36-77`, `exports/Helpers.kt:288-334`), including an annotation declared one
  klib boundary away (`CatteryInternalApi` in `:test-models`, read for `Issue113Sample.kt:205`), and
  already reads an *argument* off an annotation (`isHiddenByDeprecation`, `Helpers.kt:288-305`, three
  value shapes reduced to a simple name). Verified by reading.
- There is no chokepoint for a member's C# name: `replaceFirstChar { it.uppercase() }` is recomputed at
  55 sites across 15 files (`grep -rc`, verified: `CirClassTranslator.kt` 19, `ForwardCallablePlanner.kt`
  11, `ForwardCirPlanProjection.kt` 4, `CirTranslator.kt` 4, `CirFunctionTranslator.kt` 3, two each in
  `ForwardInterfaceBridgePlanner.kt`, `ForwardCirPropertyProjection.kt`, `ForwardBridgeTypeClassifier.kt`,
  `CirMemberNameCollisions.kt`, one each in `ForwardPropertyPlanner.kt`, `ForwardDiagnostic.kt`,
  `ForwardCirCollectionComponents.kt`, `CirTypeMapping.kt`, `CirNativeImports.kt`, `ForwardSymbolTable.kt`).
  Not all are member names (some build type, file-class or native-stem names). The suspend `Async`
  suffix is appended at `CirFunctionTranslator.kt:792`, `CirClassTranslator.kt:2041`, `:2150`, and
  mirrored by the collision checker at `CirMemberNameCollisions.kt:101`.
- `@CName` export symbols ([ADR-163](163-export-symbol-package-qualification.md)) and ADR-095 overload
  numbering derive from the Kotlin name, so a C#-only rename never touches the native ABI. Verified by
  reading `ForwardSymbolTable.kt` and ADR-095.

## Alternatives Considered

### 1. A new common `nuget-annotations` module with `@CSharpName(name)`, wired by the plugin to `commonMainApi` (chosen)

A pure-`commonMain` KMP module publishing one annotation class, no code. The plugin adds it as
`commonMainApi` beside the existing `${target}MainApi` runtime wiring, so an author writes
`@CSharpName("PayloadBytes")` in `commonMain` with nothing else to add. The processor reads it by
qualified name through the existing idiom.

Pros: the author's declaration is visible where the member is declared, the same shape as
`@JvmName`, `@JsName`, `@ObjCName`; C#-only, no side effect on another platform; the new dependency
is tiny and precedented (kotlinx.serialization's annotations ride in a published KMP artifact).

Cons: a new published artifact (release workflow, version pin, ADR-129-style version drift risk);
it must publish a variant for every target a consumer declares or configuration fails (ADR-130's
objection, which a native-only module could not meet and an all-targets module can).

### 2. Reuse the stdlib's `kotlin.native.ObjCName` (rejected)

No new module, and `@ObjCName` is the precedent ADR-064:123 cites. Verified by reading
`kotlin-stdlib-2.4.10-common-sources.jar` (`commonMain/kotlin/annotations/NativeAnnotations.kt:62-73`):

```kotlin
@Target(CLASS, PROPERTY, VALUE_PARAMETER, FUNCTION)
@Retention(BINARY) @MustBeDocumented @OptionalExpectation @ExperimentalObjCName @SinceKotlin("1.8")
public expect annotation class ObjCName(val name: String = "", val swiftName: String = "", val exact: Boolean = false)
```

Rejected on semantics and on mechanism:

- It renames the member for ObjC and Swift too. #366's author has an iOS target; `fun payload(code)`
  does not collide in ObjC (`payload` vs `payloadCode:`), so the annotation would change the Apple API
  to fix a .NET one, which is exactly the cross-platform rename the issue refuses.
- `@ExperimentalObjCName` is `@RequiresOptIn` (`commonMain/kotlin/experimental/ExperimentalObjCName.kt`,
  verified by reading), so every use needs `@OptIn(ExperimentalObjCName::class)` or a compiler flag;
  the stdlib can withdraw or reshape it.
- `@OptionalExpectation` with no `actual` on `mingwX64`/`linuxX64` (inferred, not spiked): the
  compiler drops the annotation on targets without an actual, and whether KSP's `annotationType.resolve()`
  still yields the qualified name on those targets is unverified. This plugin's primary targets are
  the ones without an actual.

### 3. A rename map in the build script, `nuget { publish { rename("...Record.payload(Int)", "PayloadBytes") } }` (rejected)

The Xamarin `Metadata.xml` / SWIG `%rename` shape, and the shape ADR-063 chose for export filtering
(a package filter in the build script, no source dependency). The plugin already passes string
options to KSP (`NugetPlugin.kt:311-315`, `nuget.libraryName`, `nuget.namespace`).

Rejected: the key must spell a Kotlin overload unambiguously (a string signature grammar to design,
document and parse), it lives a file away from the member it renames, and nothing in the IDE links the
two. Xamarin needs an external file because it does not own the Java source; a Kotlin author owns
theirs. Kotlin's own precedent for a per-platform name is an annotation on the declaration, every time.

### 4. Author adds the dependency by hand, no plugin wiring (rejected variant of 1)

Avoids the all-targets publishing question by making it the author's problem. Rejected: GOALS #1
("just add the plugin"), and the plugin already wires the runtime; one more `api` line in the same
block is the right place.

## Decision

Alternative 1. The three parts:

### The annotation

New module `nuget-annotations` (`settings.gradle.kts` `include`, `mavenPublish` like `nuget-runtime`),
one file in `commonMain`:

```kotlin
package io.github.xxfast.kotlin.native.nuget.annotations

/**
 * The exact C# name of this member, in place of the generated PascalCase one. Written on the one
 * member whose generated name collides with another's (CS0102); never changes the Kotlin name, the
 * native export symbol, or any other platform's name.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class CSharpName(val name: String)
```

`BINARY`, not `SOURCE`, because a dependency owner admitted through the ADR-066 closure is read from
a klib, and KSP sees only what the klib metadata kept (inferred from the `CatteryInternalApi`
cross-module read, which is `BINARY`-retained; not spiked with a `SOURCE` one). The `jvm` target compiles to JVM 1.8 bytecode (verified: left at the build JDK's default, the
class file was version 65 and the processor's JVM 17 test run failed with
`UnsupportedClassVersionError`, which any JVM 17 consumer would hit too). Targets: the standard
KMP set the Kotlin team publishes libraries for (jvm, js, wasmJs, every `ios*`/`macos*`/`tvos*`/`watchos*`,
`linuxX64`, `linuxArm64`, `mingwX64`, `androidNativeArm64` etc.). Inferred: an `androidTarget()`
consumer resolves the `jvm` variant through KGP's `KotlinPlatformType` compatibility rule, so no AGP
in this module; a ProjectBuilder test with a `jvm()` + `macosArm64()` consumer must prove the wiring
configures (this is the one claim that breaks loudly, not silently, if wrong).

Plugin wiring (`NugetPlugin.kt`, beside `:230-268`): `project.dependencies.add("commonMainApi", annotationsDep)`
where `annotationsDep` is `project.findProject(":nuget-annotations") ?: "io.github.xxfast:nuget-annotations:$PLUGIN_VERSION"`,
the same local-vs-published fallback the runtime uses. No `export()`: nothing in it is a `@CName`.

### The chokepoint

New `forward/ForwardCSharpName.kt`:

```kotlin
internal fun KSDeclaration.declaredCSharpName(): String?   // the validated annotation argument, or null
internal fun KSDeclaration.csharpMemberName(): String      // declared, else PascalCase(simpleName), unescaped
```

Amended during implementation (verified): `csharpMemberName()` returns the name **unescaped**. A
forward plan refuses a C#-escaped public name at plan time (`Forward plan ... public signature name
@event must not be C#-escaped at plan time`), and the renderer escapes (amended again 2026-10-03,
see the end: every CIR member type escapes through an `identifier` accessor). Validation (rules 2 and 3 below) is one
pass over `getSymbolsWithAnnotation` at the start of the round, not inside the helper.

Semantics, each a cell in the Tier 1 test:

1. **Verbatim.** The declared name is the C# name, not PascalCased, not suffixed. A `suspend fun`
   with `@CSharpName("Fetch")` renders `Fetch`, not `FetchAsync`; the author who wants the suffix
   writes it. `@JvmName` and `@ObjCName` are both verbatim, and a name the author spelled out is the
   one place a convention should not be layered back on top. The three `Async` sites and
   `CirMemberNameCollisions.kt:101` therefore branch on `declaredCSharpName() != null`.
2. **Validated, fatal.** `^[A-Za-z_][A-Za-z0-9_]*$` or new `ERROR_CSHARP_NAME_INVALID` (ERROR) naming
   the declaration and the string. A C# keyword passes validation and is escaped by `toCSharpName`
   (`Reserved.kt:55`) like every generated name, so `@CSharpName("event")` renders `@event`.
   `@JvmName` rejects an invalid JVM name at compile time (inferred, Kotlin docs); same posture.
3. **Overrides inherit; a disagreeing override is fatal.** `csharpMemberName()` walks
   `findOverridee()` to the root (the walk `ForwardCallablePlanner.kt:1855-1866` already does for
   another purpose) and takes the root's declared name, so `override fun payload(code)` on the class
   renders `PayloadBytes` and implements `IRecord.PayloadBytes` without repeating the annotation. An
   override that declares a *different* name is `ERROR_CSHARP_NAME_OVERRIDE_MISMATCH` (ERROR), the
   posture of the Kotlin compiler's `INCOMPATIBLE_OBJC_NAME_OVERRIDE` (inferred, not verified against
   the compiler source). A declared name on an override whose root is unannotated renames the C#
   member on the subclass only, which breaks the interface implementation, so that is the same
   mismatch error; the annotation goes on the root declaration.
4. **A declared name that still collides is the same error.** `KotlinSpellings.record` keeps the
   Kotlin spelling and gains an `declared: String?` so the message reads
   `` `fun payload()` (`@CSharpName("Payload")`) `` and the hint no longer says only "rename": "rename
   one of them, or give one a different `@CSharpName`".
5. **Routing.** Every site that renders a *member* name (function, property, companion member,
   interface projection, bridge planner, collision registry) calls `csharpMemberName()`. Sites that
   build a *type*, file-class or native stem name do not (a class name is out of scope, and the native
   stem must keep the Kotlin spelling or the `@CName` and `DllImport` drift apart). The site list by
   file is in the research memo; the implementing agent classifies each of the 55 while routing.

### Scope

v1: `fun` and `val`/`var` members of a class, object, companion, interface and top-level file class,
on the planner and legacy routes alike (both read the same helper). The ABI is untouched: `@CName`
symbols (ADR-163) and ADR-095 numbering stay on the Kotlin name, so an author can add the annotation
in a patch release of their library and only the C# member name changes.

Deferred: `@CSharpName` on a class/object/interface (a type-name collision has its own ADR-007 `Kt`
answer and no issue asks), on a parameter (C# named arguments would be the consumer, nothing collides),
on an enum entry, on a constructor.

## Consequences

- New published artifact `nuget-annotations`: `settings.gradle.kts`, `release.yml:53-55` publication
  list (the memory note "0.7.0 went out without nuget-runtime" is the hazard), `verify.sh --plugin`.
- `NugetPlugin.kt` gains one `commonMainApi` wiring and the plugin's ProjectBuilder test gains a
  mixed jvm+native consumer cell.
- `Issue112Sample.kt` drops its `Sequence<Int>` stand-in for the real `ByteArray?` with the annotation
  on the interface method, and `Tier1Issue112InterfaceProjectionTest.kt:55-67,107-111` flip from
  "the method is absent" to "the method is `CollarTagBytes`". A `commonMain` source set is added to
  `test-library` for that file so the fixture also proves the `commonMain` claim.
- Two new fatal diagnostics on `ForwardDiagnostic.kt`.
- `docs/topics/instance-members.md` and `interfaces-abstract-sealed.md` document the annotation;
  `supported-features.md` gains a row.

## Inferred claims, listed

1. A `commonMain` declaration's annotation is visible to the native-target KSP run through the idiom
   (the repo proves `nativeMain` and cross-klib; `commonMain` is compiled into the same native
   compilation per KGP's source-set model, so this is expected, and the flipped fixture proves it
   on the first `verify.sh`). Fails loudly if wrong: the collision error keeps firing.
2. KGP's `androidJvm` to `jvm` platform-type compatibility lets an Android consumer resolve the
   annotations module without an Android variant. Fails loudly at configuration if wrong.
3. `SOURCE` retention would not survive into a klib for ADR-066 dependency owners; `BINARY` chosen
   without spiking `SOURCE`.
4. `@OptionalExpectation` ObjCName resolution on actual-less targets under KSP (only matters for
   the rejected alternative).
5. The Kotlin compiler's `INCOMPATIBLE_OBJC_NAME_OVERRIDE` posture (precedent for rule 3 only).

## Amendment 2026-10-03: a keyword name is escaped on every member route

Rules 2 and 5 already decided this (a keyword is escaped like every generated name; every member
site goes through `csharpMemberName()`), but the code did not meet them. The `ROADMAP` item that
found it said only the legacy routes were affected and that the planned route escaped correctly.
That was wrong: every property route (planned class, `object`, companion, top-level, extension,
enum member, abstract, interface) emitted a bare `public string event`, and the planned route's
`ForwardPropertyPlan.publicName` had no escaped twin.

Rule, as now implemented:

- CIR public member names stay unescaped in the model. The CIR member types refuse a name starting
  with `@` in an `init` check, and every renderer prints their `identifier` accessor, which applies
  `toCSharpName`. A new member route cannot forget the escape without printing a bare name on
  purpose.
- Native extern stems are built from the Kotlin name (`nativeStem`), never from the public name, so
  a declared or escaped name cannot make an extern declaration and its call site disagree.
- Collision keys are unescaped on every route (see behaviour change c).

Two defects on the same sites, fixed with it:

1. `@CSharpName` was silently ignored on `Flow`/`StateFlow`-returning methods, the stored-callback
   add (ADR-037) and the interface-bridge add (ADR-039); the sites recomputed PascalCase from the
   Kotlin name. It is honoured now, and the interface-bridge thunk calls the listener member by
   its declared name.
2. Any `@CSharpName` on a class `Flow` property emitted a collect extern declared under the declared
   name (`Native_GetStreamCollect`) and called under the Kotlin name (`Native_GetUpdatesCollect`).

Pinned: `@CSharpName` on the `removeX` half of an add/remove pair names no C# member and is inert,
with no warning.

Behaviour changes a reviewer should know:

- (a) Verified by the implementer, no fixture covers it: an unannotated extension `fun X.lock()` on
  the legacy extension route (`CirTranslator.kt`) used to render `@lock(this X)` and now renders
  `Lock(this X)`.
- (b) The legacy callback-method extern now uses the Kotlin name.
- (c) Collision keys are all unescaped.

Evidence (verified): `Tier1CSharpNameKeywordTest` has 17 cells, 15 red before the change and all
green after; the full `:nuget-processor:test` run passed 1562 tests with 0 failures; the full
`scripts/verify.sh` run is green, where `KeywordMemberNamesTests` compiles and calls
`lantern.@event`, `@lock`, `@namespace`, `@operator`, `@object`, `@fixed` and `@checked` against
the `Lantern` fixture. No `LeakTests` row: the change adds no handle kind or marshalling path.

Two claims were left unverified here: whether `renderLegacyMethodNativeImport`
(`CirClassRenderer.kt`) could still build `Native_<public name>` for an async or `Flow` method in
`cls.methods`, and whether a keyword-named method beside a same-named property is pinned now that
collision keys are unescaped. Both are settled by the 2026-10-04 amendment below.

## Amendment 2026-10-04: the two unverified claims are settled

Both claims are now pinned by cells in `Tier1KeywordMemberCollisionTest` and
`CirOrdinaryRendererTest`, and one of them exposed a defect.

**A keyword-named method beside a same-named property is never wrong.** Five cells pin it. A declared
`@CSharpName("lock")` on both a property and a method, a plain `val lock` beside `fun lock()`, and
the declared `suspend` and `Flow` method forms each fail with `ERROR_CSHARP_NAME_COLLISION` naming
both members and the annotation. A `@CSharpName("lock")` method beside a `val lock` (which renders
`Lock`) compiles, as `Lock` and `@lock()`. Verified.

**A method beside a nested type was wrong three ways.** `nestedOwnerScopeCollision`
(`NugetProcessor.kt`, ADR-133 surface 6) compared the Kotlin simple name with its first letter
uppercased, so it ignored both a declared name and the `Async` suffix:

- `@CSharpName("Lock") fun take()` beside `class Lock` produced no error, then CS0102 in the
  consumer;
- `@CSharpName("lock") fun lock()` beside `class Lock` raised `ERROR_CSHARP_SIGNATURE_COLLISION`
  although C# declares `@lock()` and `Lock` as two names;
- `suspend fun lock()` beside `class Lock` raised it too, although it renders `LockAsync`.

The check now compares the rendered C# member name of each owner and companion member:
`csharpMemberName()`, `csharpAsyncMemberName()` for a `suspend fun`, and the PascalCased Kotlin name
for a `const val`. Keys stay unescaped, like every collision key. Behaviour change a reviewer should
know: a `@CSharpName` that takes a nested type's name now fails the build, and a rename away from
it no longer fails falsely. Verified by four cells: the missed collision, the plain `lock` against
`Lock` collision, and the two false positives, which now compile and declare `class Lock`.

**`renderLegacyMethodNativeImport` was dead.** Not reproducible: an async or `Flow` method never
reaches `CirClass.methods`. It rides `companionMembers`, beside a `[DllImport]` its legacy route
builds from the Kotlin `@CName` (`suspendMembers`, `flowMembers`), and `CirClass.methods` holds only
ordinary and abstract methods. Two cells (a renamed or keyword `suspend` method and its `Flow` twin,
over keyword, declared and plain names, `StateFlow` and `MutableStateFlow` included) assert that
every `EntryPoint` in the C# is a Kotlin `@CName` and that the C# compiles. The function is deleted.
`renderClassDeclaration` now has a `check` that fails, naming the class and method, if an async or
`Flow` method ever reaches `CirClass.methods`; a `CirOrdinaryRendererTest` cell pins it. This
closes a second `ROADMAP` line as well, the one noting that the function derived
`Native_${method.name}` (ADR-090's 2026-09-10 amendment), together with its `docs/backlog` file: the
function is gone and every suspend and `Flow` `EntryPoint` is a Kotlin `@CName`.

Evidence, verified: full `:nuget-processor:test` run, 1698 tests, every cell of this item green. The
processor cells compile both generated halves. No `LeakTests` row for this part: no handle kind or
marshalling path changed (the member-less marker crossing added in the same change has its own,
see the ADR-084 amendment of this date).

Not verified: whether `renderLegacyMethodNativeImport` (`CirClassRenderer.kt`) can still build
`Native_<public name>` for an async or `Flow` method in `cls.methods`; and no test pins a
keyword-named method beside a same-named property now that collision keys are unescaped. Both are
recorded in the `ROADMAP`.

## Amendment 2026-10-04: a keyword binds, a name that is not an identifier needs `@CSharpName`

Rules 2 and 5 assumed every Kotlin member name is an identifier. A name written in backticks is not
always one, and the ROADMAP item that asked ("an extension whose name needs backticks would produce
an invalid import alias") was real and wider than extensions. Two shapes, two outcomes.

**A hard keyword (`in`, `object`, `when`, `class`)** binds with no author action. C# keeps the
existing escape (`In()`, `@object`), and the generated Kotlin now backticks every call and every
parameter reference (`obj.`in`()`, `pull(`in`)`). Before, the plan route and every legacy route
(`suspend`, `Flow`, lambda parameter, stored callback, interface bridge, generic function) emitted
the bare keyword, which does not parse. The C entry point is the keyword unchanged (`lamp_in`), so no
symbol moves.

**A name that is not an identifier (a space or a symbol: `tug hard`, `a+b`)** has no C# spelling and,
under rule 5, no native stem. It is a named skip, reason `NON_IDENTIFIER_NAME`, kind
`SKIPPED_UNSUPPORTED_COMBINATION` (`SKIPPED_UNSUPPORTED_PROPERTY` for a property), on every route:
``its name `tug hard` is not an identifier, so neither a C# member nor a C entry point can be spelled
from it``, with the hint to add `@CSharpName` or rename. One central pass in `NugetProcessor` emits
the record once per declaration (top-level declarations and members of classes, objects and sealed
hierarchies); the enum, value-class and companion planners name the same refusal themselves.

**With `@CSharpName("TugHard")` it binds.** The C# name is the declared one, as rule 1 says, and the
Kotlin side keeps the backticked name. The C# name is never derived from a non-identifier
automatically: every C# name stays author-written or the PascalCased identifier. Three spellings
derive from the Kotlin name, because the author did not write them:

- The entry point: every `@CName` and `EntryPoint` goes through `asCSymbol()`, which replaces each
  run of characters that are not letters, digits or `_` with one `_` (`leash_tug_hard`,
  `leash_tug_hard_async`, `leash_get_trail_end_collect`). The identity for every name spelled from
  identifiers, so no shipped symbol moves.
- A parameter named with a space becomes `step_count` in the C# and in the native slot.
- The extension import alias (ADR-132) encodes each other character as `_x` plus four hex digits
  (`nuget_ext_..._tug_x0020hard`), so the call needs no backticks. A literal `_` is already `_u`,
  so the alias stays injective.

**Uniqueness is best effort.** `tug hard` and `tug_hard` on one owner clean to the same symbol and
fail with `ERROR_C_ENTRY_POINT_COLLISION` naming both owners (ADR-117), the backstop ADR-163's
2026-10-04 amendment records. Rule 4's `ERROR_CSHARP_NAME_COLLISION` does not apply: the C# names
differ.

Also fixed: the legacy `Flow` property route was the one class-member route that ignored
`@CSharpName` for its extern stem. It now builds the stem from the cleaned Kotlin name, like the
others.

**Routes added later in the same batch.** Keyword names also bind on a generic function's checked
bounded read, a lambda-typed property getter, a listener interface's members and `val` slots (on the
add/remove pair and on the bridge factory, whose slot names are built from the cleaned name, such as
`tug_hardPtr`), an abstract member overridden by a backing wrapper, a `Result` Try twin (an authored
`value` or `failure` parameter shifts to `value_` or `failure_`), and a nested type on a generic
owner's holder.

A listener interface with a space-named member and no `@CSharpName` is refused: the bridge factory
is not generated, and an add/remove pair is skipped with `its listener interface `BarkListener`
declares `slack line`, whose name is not an identifier` and a hint to annotate the member or rename
it (`ForwardLegacyRouteCollections.kt`). Inferred, not tested natively: such an interface still
binds as a plain parameter through the handle route, but a C# implementation of it cannot be passed
in, as for any interface the factory refuses.

Also fixed on the assembled stack: the class-property extern matching compared the raw Kotlin name
with the cleaned export, a C# compile error for a `@CSharpName`d space-named property on the plain
class route.

**Verified.** `Tier1BacktickedNameTest` (plan route, extension, top level) and
`Tier1BacktickedNameRoutesTest` (legacy `suspend`, `Flow` method, property and sealed arm, lambda
parameter, stored callback, interface bridge, generic function, companion, value class, enum and
object members) pin the keyword, the named skip, the `@CSharpName` rescue and the collision; the
routes cells compile both the generated Kotlin and the generated C#. `:nuget-processor:test` passes
1667, 0 failed (1725, 0 on the assembled stack). Natively, `BacktickedNameTests` calls a keyword
plan member with a keyword parameter (`lamp.In(@object: 3)`) and a keyword `suspend` member
(`await lamp.IsAsync(@fun: "Oreo")`) through the real pipeline; the full `IntegrationTests` (3052;
3091 on the assembled stack), `LeakTests` (165; 175) and the 7 AOT shapes pass. No `LeakTests` row:
no handle kind or marshalling path is new.

**Known limits.**

1. Kotlin/Native cannot link a shared library with a **public member whose name contains a space**:
   its own generated C API header declares `const char* (*burn down)(...)`. Verified by the fixture
   attempt. The `@CSharpName` rescue for a space-named public member is therefore pinned in Tier 1
   only and cannot run natively.
2. Inferred from reading, not tested: a space-named member inherited from an unexported base class is
   refused but not named, because the central pass names declared members only.

Dead code noticed and left alone: `translateCompanionProperty` and `translateCompanionFunction` in
`cir/CirClassTranslator.kt` have no callers.

## Amendment 2026-10-05: an annotations-only plugin for dependency modules (issue #464)

**Problem.** A `@CSharpName` collision often sits in a dependency module that the cross-module
export closure walks and that does not apply the main plugin. Alternative 4 (the author adds the
dependency by hand) was the only route there: `api("io.github.xxfast:nuget-annotations:<version>")`,
with the version kept in step with the plugin manually.

**Decision.** A second plugin id in the existing `nuget-plugin` artifact,
`io.github.xxfast.kotlin.native.nuget.annotations` (`NugetAnnotationsPlugin`). On a Kotlin
Multiplatform module it adds `nuget-annotations` to `commonMainApi` at the plugin's own version and
does nothing else: no `nuget {}` extension, no KSP, no tasks. The main plugin applies it internally,
so a module that applies the main plugin behaves as before. Verified: `NugetPluginAnnotationsWiringTest`
covers the plugin alone and both together, and `test-models` applies it for a real `@CSharpName`
that `DependencyCSharpNameTests` calls from C#.

**Why the plugin reuses `PLUGIN_VERSION`.** It takes the generated `PLUGIN_VERSION` the main plugin
already uses, so the annotation and the generator cannot skew. Verified: the smoke test resolves `nuget-annotations` by coordinate at that version.

**Rejected: publishing a version catalog.** One more artifact to release, and the consumer still has
to wire it in `settings.gradle.kts`. The plugin id needs no catalog and no hardcoded version line.

**Collision hint.** The `ERROR_CSHARP_NAME_COLLISION` hint for an owner declared in a dependency
module now names the annotations plugin as the fix, and says the main plugin adds `nuget-annotations`
only to the module that applies it.
