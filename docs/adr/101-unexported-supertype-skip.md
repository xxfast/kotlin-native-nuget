# ADR-101: Forward, unexported supertype skip: drop the dangling `: IFoo` with a named diagnostic, never a marker interface, never a wider closure

## Status
Accepted

## Context

GitHub [#42](https://github.com/xxfast/kotlin-native-nuget/issues/42) (ROADMAP.md, the `#42` item): an
exported class that implements an interface declared outside the export set, the Koin shape

```kotlin
class PeopleInSpaceApi(private val client: HttpClient) : KoinComponent
```

renders `public class PeopleInSpaceApi : IKoinComponent` and `Interop.cs` fails with CS0246, because no
`IKoinComponent` is ever generated. The class is exported only because it shares a package with DTOs the
export needs; `include`/`exclude` are package-level, so the author cannot drop the one class. The reporter's
workaround was an empty `public interface IKoinComponent;` in the consumer.

The mechanism, all **Verified** by source reading this session:

- `translateClass` (`nuget-processor/.../cir/CirClassTranslator.kt:58-67`) derives `interfaces` from
  `cls.superTypes`, keeps every `ClassKind.INTERFACE` declaration, and prefixes `I` to the simple name. There
  is no export-set check.
- The export set the translator already carries is `exportedTypes: Set<String>`
  (`CirClassTranslator.kt:43`), built once in `CirTranslator.kt:74-84` from `classes`, `enums`, `interfaces`,
  `sealedClasses` (plus their `getSealedSubclasses()`) and `objects`, **keyed by fully qualified name**
  (`qualifiedName?.asString()`, blanks removed). Every existing membership test in the translators is
  qualified (`CirClassTranslator.kt:981`, `:1803`; `CirFunctionTranslator.kt:585`; `CirTranslator.kt:337`,
  `:388`, `:631`, `:850`).
- The reachability closure ([ADR-066](066-forward-export-reachability-closure.md)) admits returns,
  parameters, property types, type arguments, sealed subclasses and primary-constructor parameters. It never
  walks `superTypes`, so a supertype can be reachable and outside the export set with nothing said.
- The only consumer of `CirClass.interfaces` is the renderer, `CirClassRenderer.kt:193-199`:
  `cls.interfaces.isNotEmpty() -> " : ${(cls.interfaces + "INugetHandle").joinToString(", ")}"`. The
  `helper.interfaces` loops in `CirBridgeRenderer.kt:28`, `:104` iterate `CirMarshalHelper.interfaces` (the
  reverse-direction bound-interface bridges, ADR-085), not `CirClass.interfaces`. The ADR-094 factory
  registry registers concrete classes, not their interface lists. So removing the entry from
  `CirClass.interfaces` changes exactly one output line.
- Member binding is independent of the interface list. `ForwardClassMembership.kt:44-66`: with no base
  class, a defaulted interface member the class does not override is bound *on the class* by all three
  consumers (`CirClassTranslator.kt:120`, `:340`; `ForwardCallablePlanner.kt:723`;
  `ForwardPropertyPlanner.kt:94`; `ClassExports.kt:70`, `:201`), and the Kotlin export reaches it by
  ordinary dispatch on the instance. So a class implementing an unexported interface still exports its own
  members *and* the interface's defaulted ones; nothing on the C# side references the interface except the
  base-list line.

### The base-class hole is the same hole

**Verified** (`ForwardClassMembership.kt:24-30`): `forwardSuperClass()` returns the first
`ClassKind.CLASS` supertype that is not `kotlin.Any`, with no export-set check either. `class X :
UnexportedBase()` therefore renders `public class X : UnexportedBase` (`CirClassRenderer.kt:194`) and fails
with the same CS0246. This ADR **names** that hole but does not close it in the same edit, for a reason
that is not cosmetic: `forwardSuperClass()` is the *shared* has-superclass predicate that four sites agree
on (`CirClassTranslator.kt:55`, `ForwardCallablePlanner.kt:706`, `ForwardPropertyPlanner.kt:91`,
`ClassExports.kt:52`), and the ADR-090 planner derives `isOverride = superClass != null && OVERRIDE`
(`ForwardCallablePlanner.kt:746-747`) and `isForwardPlannableMemberOf` skips inherited base-class
members. Gating only `superClassDeclaration` inside `translateClass` would desynchronise the predicate the
file-level comment at `ForwardClassMembership.kt:14-22` exists to prevent: the translator would treat the
class as base-less while the planner still emits `override` (CS0115, no base member) and still declines to
plan the inherited members the class now has to carry itself. See Consequences for the exact shape the
base-class fix must take.

## Alternatives Considered

### 1. Skip the unexported supertype with a named diagnostic (chosen)

Filter the `interfaces` list against `exportedTypes` by qualified name before the `"I$name"` prefixing;
for each dropped supertype emit `SKIPPED_UNEXPORTED_SUPERTYPE` at the class. The class's own members and
the interface's defaulted members bind exactly as today.

- Pro: the C# surface loses nothing a consumer could call. An interface outside the export set has no
  generated members, so `: IKoinComponent` carried no capability, only a compile error.
- Pro: one-line generator change plus one enum constant; zero ABI change, zero Kotlin-export change.
- Pro: consistent with ADR-066's product decision that out-of-scope dependency types are *skipped with an
  `include(...)` hint*, never silently admitted.
- Con: a C# consumer cannot write `IKoinComponent`-typed code. Nobody could have anyway; the interface has
  no members on the C# side.

### 2. Emit an empty marker interface for the unexported supertype

Generate `public interface IKoinComponent { }` in the class's namespace and keep the base-list entry.

- Pro: the reporter's manual workaround, automated.
- Con: mints a public C# type for a declaration the author explicitly left out of the export set, in a
  namespace derived from the *implementing* class's package (the interface's own package is out of scope,
  so it has no namespace mapping). Two exported classes in different packages implementing the same
  interface would produce two distinct `IKoinComponent`s or need a cross-namespace dedupe.
- Con: an empty interface is a lie about capability: it invites `is IKoinComponent` checks and
  `IKoinComponent`-typed parameters that can never be satisfied by anything but generated wrappers.
- Con: contradicts ADR-064/066's rule that a thing outside the export set is *absent and named*, not
  stubbed.

### 3. Widen the ADR-066 reachability closure to admit supertypes

Let the closure walk `superTypes` so `KoinComponent` is admitted and fully generated.

- Con: blast radius. `KoinComponent` pulls `getKoin()`, `Koin`, `Scope`, and the rest of the framework into
  the export set; ADR-066 rejected exactly this "just scan the dependency" shape (`066-…md:120`) and its
  `include("...")` hint is the deliberate escape hatch for the author who *wants* admission.
- Con: a supertype is not a value the consumer receives. Returns/parameters must be admitted or the member
  is uncallable; a supertype is inert on the C# side.

## Decision

Alternative 1. Interface supertypes only in this change; the base-class hole is recorded below with the
required shape of its fix.

### Generator change (Verified anchor, `CirClassTranslator.kt:58-67`)

```kotlin
val interfaces: List<String> = if (superClass != null) {
  emptyList()
} else {
  cls.superTypes
    .map { it.resolve().declaration }
    .filterIsInstance<KSClassDeclaration>()
    .filter { it.classKind == ClassKind.INTERFACE }
    .filter { iface ->
      val qualified: String? = iface.qualifiedName?.asString()
      val exported: Boolean = qualified != null && qualified in exportedTypes
      if (!exported) {
        ForwardDiagnosticSink.emit(
          listOf(
            ForwardDiagnostic(
              kind = ForwardDiagnosticKind.SKIPPED_UNEXPORTED_SUPERTYPE,
              symbol = cls,
              declaration = "$name : ${iface.simpleName.asString()}",
              reason = "supertype '${qualified ?: iface.simpleName.asString()}' is not in the " +
                  "export set, so it has no generated C# interface; the class is generated " +
                  "without it and its members still export",
              // Superseded as implemented: see the Verified measurement in Consequences — the
              // include(...) hint is wrong for a supertype-only type, so the shipped hint says
              // the supertype carries no callable members and that include(...) will not admit it.
              hint = ForwardPlanSkipReason.UNEXPORTED_DEPENDENCY_TYPE.diagnosticHint(qualified),
            ),
          ),
          logger,
        )
      }
      exported
    }
    .map { "I${it.simpleName.asString()}" }
    .toList()
}
```

- **Verified**: `exportedTypes` is qualified-name keyed (`CirTranslator.kt:74-84`), so the membership test
  above is the same test every sibling site uses.
- **Verified**: the diagnostic shape is the one `translateClass`'s own sealed-subclass path already uses
  from inside the translator (`CirClassTranslator.kt:981-996`: `ForwardDiagnosticSink.emit(listOf(
  ForwardDiagnostic(...)), logger)` with the `logger: KSPLogger` parameter). No `ForwardPlanSkipReason`
  is involved, because no callable plan is being dropped; this is the `SKIPPED_ACTUAL_TYPEALIAS_TARGET` /
  `SKIPPED_UNEXPORTED_DEPENDENCY_TYPE` *message* family attached to a kept declaration, the way `:981`
  attaches `SKIPPED_UNSUPPORTED_TYPE` to a kept sealed subclass.
- **Verified**: `ForwardPlanSkipReason.UNEXPORTED_DEPENDENCY_TYPE.diagnosticHint(qualified)`
  (`ForwardDiagnostic.kt:235-242`) renders `add include("dev.other.core") to nuget { publish { } }, or
  expose a type from an in-scope package instead`, the exact text `Tier1ReachabilityClosureTest.kt:62-65`
  asserts. Reusing it keeps the fix wording byte-identical across the two kinds. If a reviewer prefers not
  to borrow a `ForwardPlanSkipReason` member for a non-plan diagnostic, lift lines 237-241 into a
  file-level `unexportedPackageHint(qualifiedName)` and call it from both; the rendered text must not
  change.
- **Verified**: `ForwardDiagnosticSink.emit` records every WARNING into the ADR-100 `NugetDiagnostics.json`
  list (`ForwardDiagnostic.kt:139-159`), so delivery through `NugetReportDiagnosticsTask` needs no change.

### New kind (`forward/ForwardDiagnostic.kt`, append after `SKIPPED_UNIMPLEMENTABLE_BOUND_INTERFACE`)

```kotlin
/** ADR-101: an exported class declares a supertype (interface today; base class deferred) that is
 *  not in the export set. The supertype is dropped from the generated C# base list, the class and
 *  its members still export. Hint names the `include(...)` fix, as SKIPPED_UNEXPORTED_DEPENDENCY_TYPE
 *  does. */
SKIPPED_UNEXPORTED_SUPERTYPE(ForwardDiagnosticSeverity.WARNING),
```

`toDiagnosticKind()` (`ForwardDiagnostic.kt:180`) is untouched: no `ForwardPlanSkipReason` maps to it.

Rendered line (**Inferred** from `format()` at `ForwardDiagnostic.kt:111-122`; not executed):

```
[nuget:SKIPPED_UNEXPORTED_SUPERTYPE] Skipping Issue42Api : Issue42Component: supertype
'dev.other.core.Issue42Component' is not in the export set, so it has no generated C# interface; the class
is generated without it and its members still export. add include("dev.other.core") to nuget { publish { } },
or expose a type from an in-scope package instead
    at .../issue42/Issue42Api.kt:N
```

### Consumer surface

```csharp
// before: public class Issue42Api : IIssue42Component, INugetHandle   -> CS0246
public class Issue42Api : IDisposable, INugetHandle
{
    public Issue42Api(...) { ... }
    public string Label { get; }          // the class's own scalar val
    public string Ping() { ... }          // the class's own method
    public string ComponentTag() { ... }  // Issue42Component's defaulted method, bound on the class
}
```

The `IDisposable` arm follows from `CirClassRenderer.kt:196-198` once `interfaces` is empty
(**Verified** branch order; **Inferred** that `cls.disposable` is true for this fixture, as for every
ordinary handle class). The defaulted-member line is **Inferred**: `ForwardClassMembership.kt:63-66` admits
it and `classEntries` (`ForwardCallablePlanner.kt:712-723`) plans it, but no shipped fixture plans a
member whose declaration lives in a **klib** (`containingFile == null`) interface; the Tier 1 test below is
the first to exercise that path and is the red that proves it.

### Fixture (confirmed against wiring)

- `test-models/src/nativeMain/kotlin/dev/other/core/Issue42Component.kt`: `interface Issue42Component { fun
  componentTag(): String = "issue42" }`. **Verified**: `dev.other.core` is outside `:test-library`'s
  `rootPackage` and is not in its `include(...)` list (`test-library/build.gradle.kts:190-213` lists only
  `Test.*`/`MimeMapping` reverse packages), `:test-library` depends on it via
  `implementation(project(":test-models"))` (`test-library/build.gradle.kts:114`), and `:test-models`
  applies no nuget plugin (`test-models/build.gradle.kts`), so the interface resolves at KSP time exactly
  as `Advertisement` does for `Newsroom.sponsor()`.
- `test-library/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/test/issue42/Issue42Api.kt`:
  `class Issue42Api(val label: String) : Issue42Component { fun ping(): String = ... }`. No base class,
  overrides nothing.

### Tier 1 red (`nuget-processor/src/test/.../tier1/`)

Model on `Tier1ReachabilityClosureTest.kt:20-66` (same harness, same cross-module jar), not on
`Tier1CollectionElementSkipTest.kt`, which is same-round and cannot produce `containingFile == null`:

```kotlin
val dependencyJar = Tier1DependencyLibrary.compile(
  "package dep.outside\n\ninterface Component { fun tag(): String = \"dep\" }",
  fileName = "Component.kt",
)
val result = Tier1Harness.run(
  "package tier1.issue42\n\nimport dep.outside.Component\n\nclass Api(val label: String) : Component { fun ping(): String = \"pong\" }",
  processorOptions = mapOf("nuget.rootPackage" to "tier1.issue42"),
  libraries = listOf(dependencyJar),
)
assertTrue(result.compiledClean)
assertTrue("export_api_ping" in result.generated)
assertTrue("export_api_tag" in result.generated)   // defaulted interface member bound on the class
val line = result.kspWarnings.first { it.contains(ForwardDiagnosticKind.SKIPPED_UNEXPORTED_SUPERTYPE.name) }
assertTrue(line.contains("include(\"dep.outside\")"))
```

**Verified**: `Tier1Harness.run(source, processorOptions, libraries)` and
`Tier1DependencyLibrary.compile(source, fileName)` are the signatures used at
`Tier1ReachabilityClosureTest.kt:22-29`, `:43-47`; `result.kspWarnings` / `result.generated` /
`result.compiledClean` are the fields it reads. **Inferred**: whether the harness also exposes the generated
`Interop.cs` text for a direct `": IComponent"`-absent assertion; if not, that assertion lives in the
`test-library` fixture's `GeneratedBindingsCheck` compile, which is the real CS0246 gate.

## Consequences

- One rendered line changes for affected classes; every already-shipping class is byte-identical (an
  in-scope interface passes the qualified-name test).
- New diagnostic kind, delivered through ADR-100's file for free.
- **Deferred, named, with the required shape**: the base-class hole (`class X : UnexportedBase()`). The
  fix must gate inside `forwardSuperClass()` itself, taking the export set as a parameter and threaded to
  all four call sites (`CirClassTranslator.kt:55`, `ForwardCallablePlanner.kt:706`,
  `ForwardPropertyPlanner.kt:91`, `ClassExports.kt:52`), so that the translator, both planners and the
  Kotlin emitter agree the class is base-less and the planner binds the inherited base members on the
  class. The planners today see only `ForwardBridgeTypeContext.exportedObjectHandles`
  (`NugetProcessor.kt:487-489`); whether that set equals `CirTranslator.kt:74`'s `exportedTypes` is
  **not verified** and must be checked before reusing it. Gating `superClassDeclaration` only in
  `translateClass` is explicitly rejected (CS0115 on `override` members, unplanned inherited members).
- Not changed: the ADR-066 closure, the ADR-040 interface backing classes, `ForwardClassMembership`.
- **Resolved, Verified (measured by `Tier1UnexportedSupertypeSkipTest`'s second variant, "including
  the dependency package does not admit a supertype-only interface"): the `include(...)` hint is NOT
  actionable for the cross-module case #42 reports.** Running the same fixture with
  `nuget.includePackages = "tier1.issue42,dep.outside"` still leaves `dep.outside.OutsideThing` out of
  `exportedTypes`, so `SKIPPED_UNEXPORTED_SUPERTYPE` keeps firing and no `IOutsideThing` is generated —
  exactly the loop predicted below. The hint therefore does not reuse
  `ForwardPlanSkipReason.UNEXPORTED_DEPENDENCY_TYPE.diagnosticHint(...)`; it states that the supertype
  carries no callable members and that `include(...)` will not admit it, because the closure never
  walks supertypes. (Also **Verified** in the same run: the supertype's *defaulted* member does bind
  on the class — `export_api_tag` is generated from a klib-declared interface member, the path the ADR
  marked Inferred above.) The original reasoning, now confirmed:
  A dependency-module declaration enters `exportedTypes` only through the
  ADR-066 closure (module isolation: `getAllFiles()` never sees `:test-models`; `066-…md:120` rules out
  scanning the included package as roots), and the closure walks returns, parameters, property types,
  type arguments, sealed subclasses and primary-constructor parameters, never `superTypes`. An interface
  reachable *only* as a supertype therefore stays out of the set after `include("dev.other.core")`, and
  the skip keeps firing with a hint the author has already followed. For a **module-local** interface in
  an excluded package (the `Tier1CollectionElementSkipTest` `Secret` shape) the hint is correct. If the
  Tier 1 test's admitted-package variant (`nuget.includePackages` covering `dep.outside`) confirms the
  loop, the remedy is the ADR-074 fork: a distinct hint (or a supertype edge in the closure, which is
  the widening this ADR rejects), not a change to the skip itself.
- Open: a class that *overrides* a member of an unexported interface renders that member `virtual`
  (`ForwardCallablePlanner.kt:748-750`, `CirClassTranslator.kt:36-37`), which compiles; noted, not tested.

## Amendment (2026-09-05): base classes

The base-class hole this ADR named and deferred is closed, the same way, through the one shared
predicate.

`class X : UnexportedBase()` rendered `public class X : UnexportedBase` (`CirClassRenderer.kt:194`)
and failed the same CS0246. **Verified** (`ForwardClassMembership.kt`): `forwardSuperClass()` was
the *shared* has-superclass predicate four sites read in agreement (`CirClassTranslator.kt`,
`ForwardCallablePlanner.kt`, where `isOverride`/`isVirtual` derive from it, `ForwardPropertyPlanner.kt`,
`ClassExports.kt`), with no export-set check. The open question this ADR left ("whether the
planners' `ForwardBridgeTypeContext.exportedObjectHandles` is the same set as the translator's
`exportedTypes`") is now **Verified**: same five buckets (classes, enums, interfaces, sealed
classes with their subclasses, objects), same qualified-name key, merged before either is built
(`NugetProcessor.kt`). Either may be passed; both are.

### Shipped shape

`ForwardClassMembership.kt` splits the old ungated read into two functions:

```kotlin
internal fun KSClassDeclaration.declaredSuperClass(): KSClassDeclaration? = superTypes
  .map { type -> type.resolve().declaration }
  .filterIsInstance<KSClassDeclaration>()
  .firstOrNull { declaration ->
    declaration.classKind == ClassKind.CLASS &&
        declaration.qualifiedName?.asString() != "kotlin.Any"
  }

internal fun KSClassDeclaration.forwardSuperClass(
  exportedTypes: Set<String>,
): KSClassDeclaration? = declaredSuperClass()
  ?.takeIf { base -> base.qualifiedName?.asString() in exportedTypes }
```

`declaredSuperClass()` is read only by `translateClass`, to decide whether a diagnostic is owed for
a base `forwardSuperClass()` is about to drop; every other reader (both planners, `ClassExports`,
the renderer) reads only the gated `forwardSuperClass(exportedTypes)`. `isForwardMemberOf` /
`isForwardPlannableMemberOf` are unchanged: with `superClass == null` they already bind every
concrete inherited member on the class.

`CirClassTranslator.kt` generalizes the interface-skip helper this ADR shipped into one shared
`keepsSupertype`, gated by a private `SupertypeKind`:

```kotlin
private enum class SupertypeKind { INTERFACE, BASE_CLASS }

private fun keepsSupertype(
  cls: KSClassDeclaration,
  name: String,
  supertype: KSClassDeclaration,
  kind: SupertypeKind,
  exportedTypes: Set<String>,
  logger: KSPLogger,
): Boolean
```

`translateClass` calls it once per class, from the one site that holds a logger:

```kotlin
val declaredBase: KSClassDeclaration? = cls.declaredSuperClass()
val superClassDeclaration: KSClassDeclaration? = cls.forwardSuperClass(exportedTypes)
if (declaredBase != null && superClassDeclaration == null) {
  keepsSupertype(cls, name, declaredBase, SupertypeKind.BASE_CLASS, exportedTypes, logger)
}
```

The reason and hint differ by kind. The base-class hint does **not** reuse the interface hint's flat
"nothing is lost, `include(...)` does not help here": a dropped base carries real callable members,
and whether `include(...)` helps depends on whether the base is same-module or a dependency (the
ADR-066 closure admits a same-round source declaration directly but never walks `superTypes`, so a
*dependency* base stays unreachable even after its package is included). The hint shipped in this amendment hedges
accordingly rather than promising or denying the fix outright, naming the package but not asserting
it will work: "include(\"dep.outside\") admits a base declared in this module, but not one from a
dependency". **Superseded by the 2026-09-11 amendment below**, which picks the clause instead; the
line quoted next is the pre-2026-09-11 text.

Rendered line, **Verified** from the fixture's own `NugetDiagnostics.json` entry for
`Issue42Derived`:

```
[nuget:SKIPPED_UNEXPORTED_SUPERTYPE] Skipping Issue42Derived : UnexportedBase: base class
'dev.other.core.UnexportedBase' is not in the export set, so it has no generated C# class;
Issue42Derived is generated with no base at all and the base's public members are bound on
Issue42Derived directly. nothing callable is lost — UnexportedBase's public members export as
members of Issue42Derived — but C# sees no UnexportedBase type and no inheritance relation, so
`is`/`as` against it and any other subclass's shared base are gone; to keep the base itself it has
to enter the export set on its own: include("dev.other.core") admits a base declared in this
module, but not one from a dependency — the export reachability closure never walks supertypes
    at .../issue42/Issue42Derived.kt:16
```

### What the four readers now do

- Translator: `superClass = null` for `Issue42Derived`, so the base list falls to
  `IDisposable, INugetHandle`, `Issue42Derived` declares its own `_handle`, and its internal handle
  constructor does not chain a base call. `Issue42Derived`'s own interfaces would now render too
  (the interface list is only ever emptied when a *kept* base exists), filtered by this ADR's
  original interface gate.
- Planners: `UnexportedBase`'s concrete `greet`/`label` members pass `isForwardPlannableMemberOf`
  and are planned with `Issue42Derived` as owner, exported as `issue42derived_greet` /
  `issue42derived_get_label`. `isOverride` is false (no superclass); a non-final overriding member
  renders `public virtual`, never `override`.
- Kotlin emitter: `ClassExports.kt` keeps the same member set; the body reaches the base's
  implementation by ordinary dispatch on the instance behind the handle.

### Load-bearing claim, now Verified by execution

Whether KSP's `getAllFunctions()`/`getAllProperties()` on a module-local class surface the public
members of a base **class** declared in a klib dependency (`Origin.KOTLIN_LIB`,
`containingFile == null`) was unverified when this ADR shipped, measured only for a klib
*interface* member. It is now **Verified**: `Tier1UnexportedBaseClassSkipTest`'s cross-module cell
asserts `export_api_greet` / `export_api_get_label` are generated from a
`Tier1DependencyLibrary`-compiled base class, and the real klib fixture
(`test-models/.../UnexportedBase.kt` consumed by `test-library`) confirms it end to end: the full
`scripts/verify.sh` run is green (1231 passed, 0 skipped, 0 failed), and
`IntegrationTests/UnexportedBaseClassTests.cs` calls `d.Greet("Oreo")` and reads `d.Label` on a live
`Issue42Derived`.

### Fixture

- `test-models/src/nativeMain/kotlin/dev/other/core/UnexportedBase.kt`: `open class UnexportedBase`
  with a `label` property and a `greet(name)` method, outside `:test-library`'s `rootPackage`/
  `include` scope, consumed the way `Issue42Component.kt` already was for the interface case.
- `test-library/.../test/issue42/Issue42Derived.kt`: `class Issue42Derived : UnexportedBase()` with
  its own `own()` method, no-arg constructor.
- `IntegrationTests/UnexportedBaseClassTests.cs`: constructs `Issue42Derived`, calls `Own()` and
  `Greet(...)`, reads `Label`, asserts `typeof(Issue42Derived).BaseType == typeof(object)` and that
  no type named `UnexportedBase` exists in the assembly.
- `Tier1UnexportedBaseClassSkipTest.kt`: cross-module, same-module, and abstract-base cells,
  modelled on `Tier1UnexportedSupertypeSkipTest`.

### Consequences

- Affected classes lose one base-list entry and gain the base's public members on their own
  surface; a class whose base is exported is byte-identical.
- Four call sites now agree through one gated predicate; the diagnostic still fires exactly once,
  from `translateClass` alone.
- **Deferred, named** (none widened into this change, all pre-existing or newly exposed by it, not
  fixed here; tracked on `ROADMAP.md`): a transitive `X : UnexportedMid : ExportedBase` flattens
  `ExportedBase`'s members onto `X` and loses `X is ExportedBase` in C#, since `declaredSuperClass()`
  returns only the first `CLASS` supertype (**fixed by the 2026-09-11 chain amendment below**); an
  overriding member whose defaults live on the dropped
  base loses its short C# omitting overloads (`ForwardCallablePlanner.kt`'s synthesis gate keys on
  the Kotlin `override` modifier, independent of the forward `isOverride` bit), **fixed by
  [ADR-096](096-function-default-parameters.md)'s 2026-09-11 amendment, which re-keys that gate on
  this ADR's `overridesBaseClassMember` predicate**; a generic exported
  base renders by simple name (`CirClassRenderer.kt:194`); `X`'s own interfaces still disappear
  whenever an *exported* base exists (`CirClassTranslator.kt`), unrelated to this fix; an abstract
  `X` with an unplanned concrete inherited member used to render it `public abstract` (CS0534 on a
  further concrete subclass), **fixed by the 2026-09-11 abstract-method-walk amendment below**; the
  base-class hint hedges between the same-module and dependency cases rather than picking one
  (the "nothing today distinguishes them cheaply" reasoning behind this is wrong; corrected in the
  2026-09-11 amendment below, which picks the clause).
- Not changed: the ADR-066 closure, `isForwardMemberOf`/`isForwardPlannableMemberOf`, this ADR's
  original interface gate, the ABI.

## Amendment (2026-09-10): a declared `open` member renders `virtual`

The 2026-09-05 amendment above says "a non-final overriding member renders `public virtual`, never
`override`". That was the *only* route to `virtual`: the predicate read `OVERRIDE && !FINAL` and
never `Modifier.OPEN`, so an ordinary exported base class's own `open val` / `open var` rendered
with no modifier at all, and every subclass `override` of it was CS0506. `Animal.vibe` (an
`override` of an interface member) and `Issue42Derived` (an `override` of a dropped base's member)
both take the old arm, which is why no fixture ever hit it.

The rule is now `!override && (open || (override && !final))`, in one shared predicate,
`isOpenForOverride()` in `ForwardClassMembership.kt`, applied at the property projection site in
`CirClassTranslator.kt`. `abstract` is excluded: C# spells that `abstract`, never `virtual`
(CS0503 on the pair), and it keeps its own path.

The same change makes a concrete `open class` render `public virtual void Dispose()`. A derived
class always spells its inherited `Dispose` `override`, so the base has to be overridable; only an
abstract base was, because it renders `abstract void Dispose();`. `CirClass.isOpen` (a non-abstract
Kotlin `open class`, read from `Modifier.OPEN` in `translateClass`) carries it into `renderDispose`.
Abstract and final classes are byte-identical to before. `Bed` is the first concrete exported base
with an exported subclass, which is why this surfaced now and not in issue #42.

Fixture: `test/bed/Bed.kt`, `open class Bed` with `open val softness`, `open var occupant` and a
final `val brand`, overridden by `class Hammock : Bed()`. Pinned by
`IntegrationTests/OpenMemberOverrideTests.cs` (the compile is the CS0506 proof; reflection asserts
the virtual/final split on `Bed` and `GetBaseDefinition()` on `Hammock`) and
`Tier1OpenMemberOverrideTest.kt`, which adds the row no fixture covers: an `open val` declared on a
class that itself has an exported base.

**Not fixed here, split out:** an `open fun` has the identical gap, in the planner rather than the
translator (`ForwardCallablePlanner.kt`'s `entryFor` computes `isVirtual` the old way). **Verified**
against generated output: `open class Kennel { open fun describe() }` with `class Crate : Kennel()`
renders `public string Describe()` on `Kennel` and `public override string Describe()` on `Crate`.
Closed by the 2026-09-11 method amendment below.

## Amendment (2026-09-11): the method half, `open fun` renders `virtual`

The split-out clause above is closed. `ForwardCallablePlanner.kt`'s `classEntries.entryFor` computed
`isVirtual = omitted == 0 && superClass == null && OVERRIDE && !FINAL`: `Modifier.OPEN` was never
consulted, so a declared `open fun` on an ordinary exported base reached `virtual` on no path and a
Kotlin subclass's `override fun` rendered `public override` against a non-virtual member (CS0506 at
the consumer's compile).

It now reads `omitted == 0 && !isOverride && method.modifiers.isOpenForOverride()`, the same shared
predicate the property site already uses. One expression, one file; the renderer and the CIR
translator are unchanged, since `CirMethod.isVirtual` was already fed from `plan.publicSignature`.
Truth table against the old behaviour: `superClass == null && OVERRIDE && !FINAL` is unchanged
(`Animal.vibe`, `Issue42Derived`), `superClass != null && OVERRIDE` still renders `override` only,
and the new rows are `OPEN` without `OVERRIDE`, on a base with or without an exported base of its
own. ADR-096's synthesized omitting overloads keep `omitted > 0` and stay non-virtual.

Fixture: `Bed` gains `open fun fluff()`, overridden by `Hammock`. Its `describe()` stays final on
purpose: it is the non-open control, and it reads both open properties so Kotlin's own dispatch
through `Hammock` stays observable through either C# static type. Pinned by
`IntegrationTests/OpenMemberOverrideTests.cs` (`Bed_OpenFun_RendersVirtual`,
`Bed_FinalFun_StaysNonVirtual`, `Hammock_Fluff_OverridesRatherThanHides`, and the dispatch fact
through both static types) and by the two `Tier1OpenMemberOverrideTest` rows, which add
`open fun climb()` on a `Bunk` that itself has an exported base.

## Amendment (2026-09-11): the base-class hint picks its clause

The 2026-09-05 amendment shipped one hint string for both cases and its Consequences deferred the
split "since nothing today distinguishes them cheaply at the hint site". That reasoning is wrong.
`keepsSupertype` already receives the base as a `KSClassDeclaration`, and `containingFile == null`
is exactly the cross-module signal the rest of the forward pipeline keys on: the ADR-066 closure
uses it to decide a type is dependency-declared (`ForwardReachabilityClosure.kt:206-208`), and the
classifier repeats the same test. **Verified**, not inferred: the check is in the shipped closure,
and the two cells of `Tier1UnexportedBaseClassSkipTest` now assert opposite clauses off it.

So the `BASE_CLASS` arm branches on `supertype.containingFile == null` and states the one fix that
works for *that* base. The shared "what is lost" preamble is unchanged. The dependency clause also
names the only workaround that exists today, since Alternative 3 (walk `superTypes` in the closure)
stays deferred: have an exported member name the base as a return, parameter or property type.

Rendered dependency line, **Verified** from the fixture's own `NugetDiagnostics.json` entry for
`Issue42Derived`:

```
[nuget:SKIPPED_UNEXPORTED_SUPERTYPE] Skipping Issue42Derived : UnexportedBase: base class
'dev.other.core.UnexportedBase' is not in the export set, so it has no generated C# class;
Issue42Derived is generated with no base at all and the base's public members are bound on
Issue42Derived directly. nothing callable is lost (UnexportedBase's public members export as
members of Issue42Derived), but C# sees no UnexportedBase type and no inheritance relation, so
`is`/`as` against it and any other subclass's shared base are gone; UnexportedBase is declared in a
dependency, and include("dev.other.core") alone will not admit it: the export reachability closure
never walks supertypes, so it enters the export set only when an exported member also names it as a
return, parameter or property type and its package is included
    at .../issue42/Issue42Derived.kt:16
```

The same-module clause, from the Tier 1 cell (`class Api : LocalBase` with `LocalBase` in
`tier1outside.base`, outside `rootPackage`), replaces everything after the shared preamble:

```
LocalBase is declared in this module, so adding include("tier1outside.base") alongside your
existing rootPackage/include(...) admits it and renders it as the C# base
```

"alongside" is load-bearing and asserted: an explicit `include(...)` *replaces* the `rootPackage`
default rather than adding to it (`NugetProcessor.kt`, the same trap `SKIPPED_ALL_DECLARATIONS`
warns about), so an author who pastes the package on its own would trade the base for their own
module's exports.

Scope: diagnostic text only. No ABI, no `Interop.cs`, no closure change. The interface hint keeps
its flat "include(\"...\") does not help here" wording: a same-module interface outside the scope
*is* admitted by `include(...)`, so that string is imprecise for the same reason, but an interface
carries no members and the fix is not worth advertising. Tracked on `ROADMAP.md`.

## Amendment (2026-09-11): a generic exported base spells its type arguments

The 2026-09-05 Consequences deferred one line as cosmetic: "a generic exported base renders by
simple name (`CirClassRenderer.kt:194`)". It is not cosmetic, and the real site is the translator,
not the renderer. `class NamedParcel : Parcel<String>(name)` rendered `public class NamedParcel :
Parcel`, which never compiles: CS0305 in the ordinary case, and, **Verified** against the sample
package, `CS0118: 'Parcel' is a namespace but is used like a type` when the file's own namespace
carries the base's name, because the arity-free name binds to the namespace first.

Three things change, all forward, no ABI:

- `CirClassTranslator.translateClass` spells the base through `forwardBaseSpelling`. A generic base
  is matched against the class's own `superTypes` entry and each type argument is classified by the
  shared `ForwardBridgeTypeClassifier` and spelled with `forwardPublicCsharpType()`, the same pair
  every other public C# type on the forward side goes through, so `Parcel<String>` in Kotlin and
  `Parcel<string>` in C# cannot drift. A non-generic base keeps `nestedCsName()` exactly as it was.
- An argument with no public C# spelling (a nested generic, a lambda, a `Flow`), or a star
  projection, fails the build with a message naming the class, the base and the argument. That
  shape does not compile today either, so nothing regresses, and a silent skip would have to drop
  the base class itself or the inherited members vanish with no diagnostic at all.
- `CirGenericClass.isOpen`, read from Kotlin's `open` modifier, renders `public virtual void
  Dispose()` on the generic wrapper. A derived class always renders `public override void
  Dispose()` (the 2026-09-10 amendment above), so without this the pair is CS0506. A final generic
  class is byte-identical to what shipped.

### The membership predicate had to change too, and the memo said otherwise

Planning said an inherited member is never re-bound on the subclass, so `NamedParcel` would simply
inherit `Value` from `Parcel<string>`. The red test disagreed. **Verified** by running it: when the
base is generic, KSP's `getAllProperties()` / `getAllFunctions()` hand back the base's member
*substituted onto the subclass*, parented to the subclass and carrying `Modifier.OVERRIDE`. The raw
`parentDeclaration == cls` test in `isForwardMemberOf` / `isForwardPlannableMemberOf` therefore
called `Parcel<T>.value` a member of `NamedParcel`, minted `export_namedparcel_get_value`, and
rendered `public override string Value` against a base property that is not `virtual` (CS0506
again, on the member this time).

Both predicates now ask `isDeclaredBy(cls)`, which keeps the parent test and additionally requires
the member to appear in `cls.getDeclaredProperties()` / `getDeclaredFunctions()`. A non-generic base
performs no substitution, so nothing about the 2026-09-05 behaviour moves
(`Tier1InheritedMemberDiagnosticsTest` pins it), and a real `override val` in the subclass is a
declared member and keeps the 2026-09-10 virtual/override pair.

Deferred, named: a subclass that declares one overload of a name it also inherits *substituted*
from a generic base keeps both, since the declared-member match is by simple name. No fixture
reaches it, and a generic *subclass* (`class Derived<T> : Parcel<T>()`) still takes the generic
route, which ignores supertypes and renders base-less. Both stay on `ROADMAP.md` with the rest of
the generic-class work.

Evidence: `Tier1GenericBaseClassTest.kt` (base list, `virtual`/`override` `Dispose`, no re-bound
`stringcrate_get_value`) and `IntegrationTests/GenericBaseClassTests.cs` (`NamedParcel("Oreo").Value`
reaches the base's own export, `IsAssignableFrom<Parcel<string>>`, dispose through a base-typed
reference).

## Amendment (2026-09-11): a kept base keeps the interfaces beside it

The 2026-09-05 shipped shape emptied a class's interface list whenever a base class survived
(`interfaces = if (superClass != null) emptyList() else ...`), and the membership predicate matched
it: with a base, every inherited member was dropped. `class Ledge : Shelf(), Groomable` therefore
rendered `public class Ledge : Shelf`, and a C# consumer could not hold a `Ledge` as an
`IGroomable` at all (CS0266 at the assignment).

Dropping the short-circuit alone does not compile, which is why this is three coupled edits rather
than one. **Verified** by the red pair (`Tier1KeptBaseInterfaceListTest`, then
`IntegrationTests/InterfaceBesideBaseTests.cs`): fixing the base list first unmasks CS0115 on
`public override string Groom()`, because `override` was read off the Kotlin modifier and `Shelf`
declares no `Groom`.

- **Base list** (`CirClassTranslator.translateClass`, `CirClassRenderer.renderClass`): the
  interface walk now runs with a kept base too, and the renderer spells `: Base, IFoo`. An
  interface the base already implements is dropped before the export-set gate: the base carries it,
  re-listing it says nothing and re-binding its members would hide the base's (CS0108), so it owes
  no diagnostic either. The disposables stay off a derived class's list, unchanged: the base
  declares `_handle`, implements `INugetHandle` and carries `IDisposable` (ADR-094).
- **Membership** (`ForwardClassMembership.kt`): `isForwardMemberOf` /
  `isForwardPlannableMemberOf` gain a third arm, `isFromInterfaceBeside(superClass)`, admitting a
  member inherited from an interface the base does not implement. Without it, `Groomable.brushes()`
  (defaulted, never overridden) has no C# carrier and the interface the class just declared is
  CS0535. Base-*class* members are still not re-bound, and the arm is keyed on the same
  base-supertype closure the translator filters the list with, so the two halves cannot disagree
  about who binds a member.
- **`override` means a base *class* member** (`ForwardCallablePlanner.classEntries`,
  `CirClassTranslator`'s property and abstract-method walks): all three now ask
  `overridesBaseClassMember(superClass)`, and `isVirtual = !isOverride && isOpenForOverride()`
  follows. `Ledge.groom()` is a fresh virtual slot, exactly as it would be on a base-less
  implementer. The lookup is `ForwardPropertyPlanner`'s `readOnlyOverrideeOwner` walk lifted to
  `ForwardClassMembership.kt` and shared: trust `findOverridee()` only when it lands on a
  `ClassKind.CLASS`, else match the base class's own members by simple name. The property planner
  now calls the lifted helper, so the `val`-widened-to-`var` setter rule (CS0546) and the rendered
  modifier are keyed on one answer instead of two.

The `findOverridee()` half stays **Inferred** for functions (same KSP API as the property side,
which was probed). Nothing silently breaks if it answers the interface for a member overriding
both: the by-name fallback on the base class still says `override`.

Deferred, named: an interface the base already implements is skipped rather than re-listed (C#
accepts either), and `IAsyncDisposable` on a derived class with its own suspend members is a
separate hole, untouched here.

Evidence: `Tier1KeptBaseInterfaceListTest.kt` (`: Shelf, IGroomable`, bound `Brushes`, `public
virtual string Groom()`, no re-bound `ledge_height`) and
`IntegrationTests/InterfaceBesideBaseTests.cs` (`using IGroomable g = new Ledge()` with no cast,
`((Shelf)g).Height()`, `Groom().GetBaseDefinition().DeclaringType == typeof(Ledge)`).

## Amendment (2026-09-11): the base walk follows the chain to the nearest exported base

The 2026-09-05 amendment took exactly one hop. `class Dinghy : Skiff()` with `Skiff : Vessel` and
only `Skiff` outside the export set therefore rendered `public class Dinghy : IDisposable,
INugetHandle`: one unexported link cost the consumer an exported base it could have had, plus every
`is`/`as` against `Vessel` and every `Vessel`-typed API that would have accepted a `Dinghy`. The C#
compile proof is `CS0029: Cannot implicitly convert type 'Dinghy' to 'Vessel'`.

`forwardSuperClass` now walks `declaredBaseChain()`, a `generateSequence` over `declaredSuperClass()`,
and keeps the first link in the export set. When the direct base is exported the first element
answers, so every shipping class is byte-identical: the walk only ever looks past a base that has no
generated C# class anyway.

Walking up alone silently loses members, which is the second half of this amendment. With
`superClass = Vessel` non-null, `Skiff`'s own `oars`/`row` are parented to `Skiff`, so the old
membership predicate bound them on neither `Dinghy` nor `Vessel` and they would have vanished from
C# with no diagnostic. `isForwardMemberOf` / `isForwardPlannableMemberOf` gain an
`isFromDroppedBase(cls, superClass)` arm: a member declared on any link of `droppedBaseChain()` (the
chain prefix before the kept base, compared by qualified name) binds on the class itself, the same
re-homing rule the base-less case already applies to the whole chain. Members of the *kept* base are
still not re-bound; re-homing them would hide the base member (CS0108).

The diagnostic fires once per dropped link rather than once per class, from `translateClass` as
before, and its middle clause names the kept base:

```
[nuget:SKIPPED_UNEXPORTED_SUPERTYPE] Skipping Dinghy : Skiff: base class
'io.github.xxfast.kotlin.native.nuget.hidden.Skiff' is not in the export set, so it has no generated
C# class; Dinghy is generated extending Vessel, the nearest exported base, and Skiff's public
members are bound on Dinghy directly. <hint unchanged>
```

The single-drop text is unchanged to the byte ("generated with no base at all and the base's public
members are bound on ... directly"), which is what the quoted `Issue42Derived` line above and in
`docs/topics/forward-overview.md` still show. The hint is unchanged in both cases: what is lost is
the dropped link's own type and its `is`/`as` relation, whether or not a base above it survives.

Deferred, named: if the nearest exported base is a *sealed* class, `X : SealedBase` would render
against an abstract base whose `FromHandle` switch does not know `X` (reachable only through
`exclude()`, untouched here); and a re-homed member that overrides an interface declared on the
dropped link renders from the same `isOverride` rule as before, the pre-existing hole the
2026-09-11 interface amendment names.

Evidence: `Tier1UnexportedBaseClassSkipTest`'s chain cell (`public class Api : LocalExportedBase`,
`export_api_row` bound, no `export_api_anchor`, exactly one diagnostic naming `Api : LocalMid`) and
`IntegrationTests/TransitiveUnexportedBaseTests.cs` (`typeof(Dinghy).BaseType == typeof(Vessel)`,
`Vessel v = new Dinghy()`, `d.Row()`, `d.Oars`, no `Skiff` type in the assembly).

**2026-09-11 amendment: the abstract method walk decides `abstract` by the body, not by the declaring
class.** The CS0534 clause above closes. `CirClassTranslator.kt`'s abstract walk asked
`parentDeclaration == cls || Modifier.OVERRIDE` and got both directions wrong. An inherited member
*with* a body that no plan covers (a generic interface default, a refused parameter type, a base
dropped by this ADR's skip) looked unimplemented and rendered `public abstract`, so the generated file
itself could be CS0246 and any further C# subclass CS0534. A class's own `abstract fun` looked
implemented and was dropped from C# entirely, so a subclass's `public override` was CS0115. The walk
now keys on KSP's `KSFunctionDeclaration.isAbstract`, the same body-based predicate
`isForwardPlannableMemberOf` and the ADR-075 property route already use: bodiless renders `abstract`,
a body that reached no plan is dropped like it is on a concrete class. Nothing gains or loses an
export; an abstract C# method has no `DllImport` either way.

Evidence: `Tier1AbstractMethodTest` (`public abstract string Honk();` on `Vehicle` with `Truck`'s
`override` compiling; no line carrying both `abstract` and `Tally` for `Vault : Register`, with
`: IRegister` still in the base list) and the `test/garage/` fixtures behind
`IntegrationTests/AbstractMethodTests.cs`.
