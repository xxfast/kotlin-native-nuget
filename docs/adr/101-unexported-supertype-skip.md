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
*dependency* base stays unreachable even after its package is included). The shipped hint hedges
accordingly rather than promising or denying the fix outright, naming the package but not asserting
it will work: "include(\"dep.outside\") admits a base declared in this module, but not one from a
dependency".

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
  returns only the first `CLASS` supertype; an overriding member whose defaults live on the dropped
  base loses its short C# omitting overloads (`ForwardCallablePlanner.kt`'s synthesis gate keys on
  the Kotlin `override` modifier, independent of the forward `isOverride` bit); a generic exported
  base renders by simple name (`CirClassRenderer.kt:194`); `X`'s own interfaces still disappear
  whenever an *exported* base exists (`CirClassTranslator.kt`), unrelated to this fix; an abstract
  `X` with a structurally-skipped concrete inherited member still renders it `public abstract`
  (`CirClassTranslator.kt`), which a further concrete subclass would fail to override (CS0534); the
  base-class hint hedges between the same-module and dependency cases rather than picking one,
  since nothing today distinguishes them cheaply at the hint site.
- Not changed: the ADR-066 closure, `isForwardMemberOf`/`isForwardPlannableMemberOf`, this ADR's
  original interface gate, the ABI.
