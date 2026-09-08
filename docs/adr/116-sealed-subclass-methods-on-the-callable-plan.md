# ADR-116: Sealed-subclass methods move onto the ADR-062 callable plan

## Status
Accepted

> **Amended by [ADR-118](118-suspend-route-sealed-arm-owners-and-overload-numbering.md) (2026-09-09).** This ADR priced binding a sealed arm's `suspend fun` as its option (b) and deferred it, leaving the member absent and named `SEALED_SUBCLASS_UNROUTED` (detail `SUSPEND`). ADR-118 re-keyed the legacy suspend route to sealed arms, so a `suspend fun` an arm declares now binds; `SEALED_SUBCLASS_UNROUTED` still covers `Flow`-returning, lambda-parameter, generic and callback-protocol arm members.

## Implementation notes (2026-09-08)

Shipped as designed, with four corrections found during implementation:

1. The file list in "Suspend methods (item 4)" omitted `ForwardDiagnostic.diagnosticHint()`
   (`forward/ForwardDiagnostic.kt:~474`); its `else ->` arm would have produced the wrong "expose a
   bridgeable adapter" hint for `SEALED_SUBCLASS_UNROUTED`. An explicit arm was added.
2. The renderer snippet's trailing `appendLine()` (in "C# rendering") double-spaced the output,
   because `renderMethod` already ends with one (`CirClassRenderer.kt:575`); dropped from the shipped
   code.
3. The claim under "Inferred, must be checked by the implementer" that "a wrong indent is a C#
   compile error" is false: C# is whitespace-insensitive. The indent was checked by reading the
   generated `Interop.cs` output instead.
4. The suspected collection-support ripple, also under "Inferred", is refuted: `plannedCollectionKinds()`
   iterates `callableCatalog.plans`, which already includes the new sealed-arm method plans, so no
   change was needed there. Verified by reading.

The only Tier 1 ripple was `ForwardSkippedCallableWarningTest`'s `droppedFromCSharp` enum-set
assertion, which needed the new `SEALED_SUBCLASS_UNROUTED` reason added; `ForwardAbiLegacyRoutesTest`
needed no change.

## Context

GitHub [#115](https://github.com/xxfast/kotlin-native-nuget/issues/115), ROADMAP Phase 3 line 39.
A public member function declared on a sealed subclass is never exported, and nothing says so:

```kotlin
sealed class Job {
  data class Running(val progress: Int, val onCancel: () -> Unit) : Job() {
    fun cancel() = onCancel()
    suspend fun pause() {}
  }
}
```

`Running` exports `job_running_get_progress`, `job_running_get_onCancel`, the data methods and
`_dispose`, and neither `cancel()` nor `pause()`. The generated C# class looks populated and is
inert. Since [ADR-115](115-opt-in-marker-declarations.md) an arm whose only operation delegates to an
opt-in-marked lambda property has nothing callable at all.

The chain, **verified by reading** (the line numbers in #115 are stale; these are current):

1. `NugetProcessor.kt:546-550` collects `rootSealedClasses`; the ordinary `classes` list excludes
   sealed subclasses (`isSealedSubclass()`, ADR-009/issue #54), correctly, so the sealed route owns
   the declaration.
2. `classes` drives `addClassExports` (`NugetProcessor.kt:1104`) and the suspend route
   (`:1266-1268`). Neither sees a sealed subclass.
3. The sealed route, `addSealedClassExports` (`SealedClassExports.kt`), only calls
   `getAllProperties()`; `CirSealedSubclass` (`CirModel.kt:111-123`) has `properties` and no
   `methods`; `translateSealedClass` (`CirClassTranslator.kt:1102-1200`) builds only properties;
   `CirSealedRenderer.sealedSubclassBlock` renders only properties, data methods and `Dispose`.
4. No diagnostic fires: the planner never plans the member (`catalog()` receives `sealedClasses`
   only for the property planner, `ForwardCallablePlanner.kt:493-544`), so there is no
   `Skipped` entry for `warnDroppedForwardCallables` (`NugetProcessor.kt:140`) to report.

[ADR-111](111-sealed-subclass-properties-on-the-property-plan.md) moved sealed-subclass
*properties* onto the ADR-062 property plan and deferred methods to this line. ADR-009 line 14 has
always said a sealed subclass carries methods. This ADR is the method half of ADR-111, and ADR-111
is its template.

## Prior art (to the depth that changes the decision)

- **Swift Export / ObjC export**: a sealed subclass is an ordinary class in the exported
  hierarchy; its members go through the same member translation as any class member, with no
  sealed-specific route. Inferred from the Kotlin docs
  ([ObjC interop, classes and objects](https://kotlinlang.org/docs/native-objc-interop.html#classes-and-objects)),
  no spike; ADR-111 records the same. It settles the direction: one member route, a discriminator
  on top.
- Skipped: JVM (a sealed subclass is a plain class there), JS/Wasm (no handle seam).

## Alternatives Considered

### 1. Plan sealed-subclass methods with `ForwardCallablePlanner` and project them with the shared Kotlin emitter and C# projection (chosen)

Add `sealedSubclassEntries(sealed, subclass)` to the planner next to `classEntries`, feed
`sealedClasses` into the *callable* half of `catalog()` (today only the property half reads it),
give `CirSealedSubclass` a `methods` list, and have the three sealed files read
`callableCatalog.classMethods(subQualifiedName)` the way `ClassExports.kt:268` and
`CirClassTranslator.kt:609-618` do for an ordinary class.

Pros: every parameter/return shape the plan binds on an ordinary class (String, primitives, enums,
handles, nullable handles, collections, `Instant`/`Duration`/`Uuid`, `Result<T>` unwrap, value
classes, bound interfaces, sealed bases via ADR-105's `sealedAsHandle`) arrives at once, by the
same code; overload numbering (ADR-090), default-argument omitting overloads (ADR-096), the error
slot, and the ABI contract check come for free; a method the plan refuses gets the ADR-064
diagnostic every other class method gets.
Cons: a sealed subclass has *no* legacy route, so the planner's "silent because a legacy route
re-emits it" skips must be turned into named drops here (Decision, Diagnostics); suspend methods
stay absent from C# in v1 (named, not silent).

### 2. Re-key the legacy routes (`addClassExports` and `addSuspendClassMethodExports`) to include sealed subclasses (rejected)

Feed `sealedClasses.flatMap { it.getSealedSubclasses() }` into `classes` for method purposes only.

Rejected: `addClassExports` spells its prefix `cls.simpleName.lowercase()` (`ClassExports.kt`),
so `Running.cancel` would export as `running_cancel` while every other `Running` export is
`job_running_*` (`docs/backlog/two-exported-types-same-simple-name-different.md` already records
the bare-simple-name prefix hazard); the C# half, `translateClass`, builds a `CirClass`, not a
`CirSealedSubclass`, so the sealed renderer could not consume it; and it re-opens exactly the
one-type-two-classes duplicate issue #54 closed by excluding sealed subclasses from `classes`.

### 3. Hand-roll a method loop in `SealedClassExports` / `translateSealedClass` (rejected)

A fourth spelling of the class-method export. ADR-111 rejected the same for properties for the same
reason: the hand-rolled sealed property loop had drifted from the planner four times.

## Decision

Adopt alternative 1. The mechanism follows ADR-111 step for step; the places where a *method*
diverges from a property are called out.

### Planner

`ForwardCallablePlanner.sealedSubclassEntries(sealed: KSClassDeclaration, subclass: KSClassDeclaration): List<ForwardCallableCatalogEntry>`
is `classEntries` (`ForwardCallablePlanner.kt:830-920`, **verified**) with these substitutions:

| `classEntries` | `sealedSubclassEntries` | Why |
|---|---|---|
| `prefix = className.lowercase()` | `prefix = "${sealed.simpleName.lowercase()}_${subclass.simpleName.lowercase()}"` | Must equal the `subPrefix` `SealedClassExports.kt` and `translateSealedClass` (`CirClassTranslator.kt:1120`) already use for the property getters, so `Job.Running.cancel` exports as `job_running_cancel` beside `job_running_get_progress`. **Verified** both sites spell it this way. `ForwardCirPlanProjection.classMethod` `require`s the export begins with `${nativePrefix}_` (`:359-363`, **verified**), so a mismatch fails the build rather than silently drifting. |
| `.filter { it.isForwardPlannableMemberOf(cls, superClass) }` | `.filter { it.parentDeclaration == subclass }` | **Declared-only (accepted at the gate).** v1 exports only the functions the arm itself declares, including an `override fun` it declares. An inherited base `open fun` body is *not* flattened onto the arm: the sealed C# base declares no methods, so the base-declared function has no C# carrier until the base-type item (deferred below, paired with ROADMAP line 47) gives it one. `parentDeclaration == subclass` is the first disjunct of `isForwardPlannableMemberOf` (`ForwardClassMembership.kt`, **verified** text) with the inherited-member disjunct removed; it is also what the verbatim `isForwardPlannableMemberOf(sub, base)` would compute, since the sealed base *is* in `exportedObjectHandles` (`NugetProcessor.kt:815-818`, **verified**), but spelling it directly keeps the intent readable and independent of `forwardSuperClass`. Note this diverges from ADR-111, which used `superClass = null` for properties and so *does* flatten an inherited base `val` onto each arm; a property has a C# carrier on the arm regardless, a method's `override` semantics do not, so the two routes deliberately differ until the base-type item lands. |
| `isOverride = omitted == 0 && superClass != null && OVERRIDE` | `isOverride = false` | An arm's declared `override fun` must render plain `public`: `public override` against a C# base with no such member is CS0115. |
| `isVirtual = omitted == 0 && superClass == null && OVERRIDE && !FINAL` | `isVirtual = false` | A `virtual` member on a `public sealed class` is CS0549. Both flags are plain `planOrSkip` parameters (`:1510-1511`, **verified**). |
| receiver `ObjectHandle(cls.qualifiedName)` | `ObjectHandle(subclass.qualifiedName)` | A sealed subclass crosses as a `StableRef` handle of its own type (`SealedClassExports.kt` `asStableRef<$subQualifiedName>()`, **verified**). |

Everything else transfers verbatim, **verified** at `:839-849` and `:860-905`: the
`getAllFunctions()` public filter; the exclusion of `equals`/`hashCode`/`toString`/`<init>` and,
for a `data` arm, `copy`/`component*`; the per-name `occurrences` overload counter and `_$n`
symbol/export suffix; `origin = ForwardCallableOrigin.CLASS`; `member = name`; the structural
`ABSTRACT`/`SUSPEND`/`GENERIC`/`CALLBACK_PROTOCOL` skips; the interface-bridge and
stored-callback pair detection; and the ADR-096 omitting-overload synthesis.

**Which functions (coordinator item A).** `getAllFunctions()` on a concrete sealed arm returns:
its own declared functions (including its `override`s of the base's `abstract`/`open` funs, whose
`parentDeclaration == sub`); the base's `open fun`s it does not override (`parentDeclaration ==
base`); and `Any`'s three. The name filter drops the third group and the data synthetics, so the
sealed route's own `_equals`/`_hashcode`/`_tostring` exports never collide; the
`parentDeclaration == subclass` filter drops the second group. A base `abstract fun` the arm
overrides appears once, as the arm's declared override, and binds as a plain `public` method on the
arm. A base `open fun` the arm does *not* override is absent from that arm in C# (no diagnostic:
it is not dropped, it is a base member the base-type item will carry), and present on an arm that
overrides it. The fixture's `describe()` proves both halves: absent on `Running`, present on
`Idle`. There is no inferred membership claim left: the filter is an equality test on
`parentDeclaration`, the same test `classEntries` already applies through the first disjunct of
`isForwardPlannableMemberOf`.

**Base-declared `abstract fun` and `open fun` on the C# base: deferred.** `CirSealedClass` has no
`methods` field (**verified**, `CirModel.kt`), exactly as it has no `properties` field (ROADMAP
line 47's abstract-`val` gap). A consumer holding a `Job` must pattern-match to an arm before
calling, and an arm inherits nothing from the base in C#. This ADR does not add members to the C#
base: doing so means `CirSealedClass.methods` (abstract for a base `abstract fun`, concrete with a
`job_describe` export for a base `open fun`), rendering them in `renderSealedClass`, and flipping
the arm's planned override to `isOverride = true` (then correct, because the base would declare
it), about 40-60 lines across three files, and it belongs with the abstract-`val` item as one
change. Record as a paired ROADMAP line next to 47.

**Overloads (item 3).** Automatic: the `occurrences` counter numbers the second `step` as
`Job.Running.step_2` / `job_running_step_2`, `ForwardCirPlanProjection.classMethod` derives
the extern name from the numbered symbol (`:372-373`, **verified**), and the C# public name stays
`Step`. `ForwardCallablePlanCatalog.overloadSuffix` (`:458`) is only read by the legacy Flow and
suspend routes, which do not run for a sealed arm in v1, so it is not involved.

**Owner key.** `classMethods(owner)` matches `plan.invocation.symbol.substringBeforeLast('.') ==
owner` (`:359-363`, **verified**), and the Kotlin emitter spells the receiver as
`handle.asStableRef<$owner>().get().$member(...)` with the same substring
(`ForwardKotlinPlanEmitter.kt:878-880`, **verified**). For `pkg.Job.Running.cancel` both give
`pkg.Job.Running`, a valid Kotlin qualified name for a nested class; for a sibling arm
`pkg.Label.describe` they give `pkg.Label`. An overload symbol `pkg.Job.Running.step_2` still
splits at the last dot. **Verified** by reading, not by a build.

### Catalog

`catalog()` (`:485-544`) adds, after `classes.forEach { cls -> addAll(classEntries(cls)) }`:

```kotlin
sealedClasses.forEach { sealed ->
  sealed.getSealedSubclasses().forEach { sub -> addAll(sealedSubclassEntries(sealed, sub)) }
}
```

`sealedClasses` is already a parameter (ADR-111) and `NugetProcessor` already threads the full
`sealedClasses` list (root + dependency-module, `:714-715`) into it. Nothing double-plans: the
ordinary `classes` list excludes sealed subclasses (**verified**, ADR-111 Claims).

An `object`/`data object` arm is a `KSClassDeclaration` in `getSealedSubclasses()` like any other,
so its methods plan through the same call with `ObjectHandle` receiver and `asStableRef` lowering.
That matches how the sealed route already treats a `data object` arm's data methods and properties
(ADR-009 2026-09-07/08 amendments, ADR-111 amendment), not how `objectEntries` treats a top-level
object (a static C# class). **Verified** that the sealed route mints a handle for an object arm
(`FromHandle` returns `new Empty(handle)`, `CirSealedRenderer.kt`); so an object arm method takes
the handle receiver without special casing.

### Kotlin exports

`SealedClassExports.kt`, inside the per-subclass loop after the property loop:

```kotlin
callableCatalog.classMethods(subQualifiedName).forEach { plan -> addForwardKotlinPlanExport(plan) }
```

the same one-liner `ClassExports.kt:268` uses (**verified**). `addForwardKotlinPlanExport` needs
no new inputs: the plan carries the export name, receiver and body.

### C# projection

`CirSealedSubclass` gains `val methods: List<CirMethod> = emptyList()` (`CirModel.kt:111`).

`translateSealedClass` (`CirClassTranslator.kt:1102`) builds, per subclass:

```kotlin
val methodPlans = subQualifiedName?.let { callableCatalog.classMethods(it) } ?: emptyList()
val methods: List<CirMethod> = methodPlans.map { plan ->
  tracker.trackPlan(plan)
  ForwardCirPlanProjection.classMethod(plan, nativePrefix = subPrefix, isOverride = false, isVirtual = false)
}
emitCsharpSignatureCollisions(methods, "$name.$subName", subclass, logger)
```

`classMethod` (`ForwardCirPlanProjection.kt:346-400`, **verified**) requires a leading `handle`
POINTER receiver (every CLASS plan has one) and spells the receiver argument `_handle`, which the
sealed subclass block declares (`internal IntPtr _handle` on the base). The ADR-034 collision
guard `emitCsharpSignatureCollisions` (`CirClassTranslator.kt:1210-1240`) is today called only for
ordinary classes (`:983`) and objects (`:1267`) (**verified** call sites), so it must be added here;
a sealed arm with `fun set(x: Foo)` and `fun set(x: Foo?)` is otherwise CS0111.

### C# rendering

`CirSealedRenderer.sealedSubclassBlock` renders, after the property loop and before the data
methods:

```kotlin
for (method in subclass.methods) {
  append(
    buildString {
      renderDllImport(methodNativeImport(sealed.libraryName, subclass.nativePrefix, method))
      renderMethod(method, subclass.name)
    }.indentNestedBody(),
  )
  appendLine()
}
```

`CirClass.methodNativeImport` (`CirNativeImports.kt:106-125`, **verified**) reads only
`libraryName` and `nativePrefix` off the receiver, so it lifts to a three-argument function the way
ADR-111 lifted `propertyNativeImports` (`:41`, **verified** it already takes
`(libraryName, nativePrefix, prop)`). `renderMethod` (`CirClassRenderer.kt:497`) is already
`internal` and takes `(method, className)`; it renders at ordinary-class depth, so the block uses
the same `indentNestedBody()` (+4) the property arm uses. **Inferred**: that `renderMethod`'s
output re-indents cleanly with the same helper (the property bodies do, and both are built at the
ordinary-class depth).

### Name collisions with always-emitted members

The subclass block always declares `Dispose()`, and for a data arm `Equals`/`GetHashCode`/
`ToString`; `FromHandle` is on the base. `equals`/`hashCode`/`toString` are excluded by the
planner filter. `dispose` is **not**: a Kotlin `fun dispose()` on an arm renders `public void
Dispose()` next to `public override void Dispose()`, CS0111. **Verified** no guard exists anywhere
(`grep '"dispose"'` over the planner, translator and renderer is empty), and the same hazard is
pre-existing on every ordinary class (`CirClass` also renders `Dispose()`). Not fixed here; recorded
in the report as a pre-existing bug for a separate line item. `FromHandle` is `internal static` on
the base, so an arm method `fromHandle` (`FromHandle`, instance) does not collide.

### Diagnostics (item 5): the one place a method is not a property

`ForwardCallablePlanCatalog.droppedCallables` (`:329-331`, **verified**) reports only skips whose
`reason.droppedFromCSharp == true`. `SUSPEND`, `GENERIC`, `FLOW_PROTOCOL`, `CALLBACK_PROTOCOL`,
`SUSPEND_CALLBACK_PROTOCOL` and `ABSTRACT` are `false` (ADR-064 Context, **verified**) *because a
legacy route re-emits them for an ordinary class*. No legacy route runs for a sealed arm, so a
verbatim transfer would leave `suspend fun pause()` exactly as silent as it is today, which is the
defect #115 reports.

Decision: `sealedSubclassEntries` post-processes its result. Any `Skipped` whose
`reason.droppedFromCSharp == false` (except `ABSTRACT`, which cannot occur on a concrete arm's
declared member after the `parentDeclaration == subclass` filter, and whose base-declared form is
deferred above) becomes

```kotlin
ForwardCallableCatalogEntry.Skipped(symbol, ForwardPlanSkipReason.SEALED_SUBCLASS_UNROUTED, node, detail = reason.name)
```

with a new `SEALED_SUBCLASS_UNROUTED(droppedFromCSharp = true)` reason, mapped in
`toDiagnosticKind()` (`ForwardDiagnostic.kt:375`) to `SKIPPED_UNSUPPORTED_COMBINATION`, whose
documented meaning, "the combination has no working legacy route" (`:84-87`, **verified**), is
literally the case. `warnDroppedForwardCallables` gains a wording branch:
"it is a `${detail}` member of a sealed subclass, which has no route yet (ADR-116; suspend members
follow ROADMAP line 54)". This is the third special case in that `if` chain; ADR-115 already
flagged that chain for the `diagnosticReason()` refactor, and this ADR does not do it either.

Consequence for each shape on a sealed arm owner:

| Method shape | Ordinary class today | Sealed arm after this ADR |
|---|---|---|
| plannable (Int, String, handle, nullable handle, enum, collection, Instant/Duration/Uuid, Result, value class, bound interface) | plan | plan, same export shape |
| `suspend` | legacy `_async` route, silent | `SKIPPED_UNSUPPORTED_COMBINATION`, named |
| `Flow`/`StateFlow` return | legacy `_collect` route, silent | `SKIPPED_UNSUPPORTED_COMBINATION`, named |
| lambda parameter / interface-bridge / stored-callback pair | legacy route, silent | `SKIPPED_UNSUPPORTED_COMBINATION`, named |
| generic | `GENERIC`, legacy generic route | `SKIPPED_UNSUPPORTED_COMBINATION`, named |
| unsupported type (nested interface, undeclared class, opt-in marker, sealed-position) | named drop (`SKIPPED_UNSUPPORTED_TYPE` / `_OPT_IN_MARKER` / `_SEALED_POSITION` ...) | identical, untouched by the post-process |

The last row is how ROADMAP line 39's literal example closes: `Shape.Circle.pick(): Owner.Listener?`
returns a *nested* interface, which the classifier refuses as `isUndeclaredNested`
(`ForwardBridgeTypeClassifier.kt:242-252`, **verified**) and which an ordinary class already drops
with a named `SKIPPED_UNSUPPORTED_TYPE` (`NestedInterfaceGateTests.cs:53` asserts
`NestedListenerOwner.Current()` is absent, **verified**; the kind is **verified** at
`ForwardDiagnostic.kt:430-434`, where `UNDECLARED_INTERFACE` and `UNDECLARED_CLASS` both map to
`SKIPPED_UNSUPPORTED_TYPE`). On a sealed arm it now produces the same
named skip instead of nothing. It does *not* bind; a top-level `interface Listener` return would
(ADR-040), and the fixture carries both so the test can tell them apart.

### Suspend methods (item 4): two options, priced

**(a) Chosen: non-suspend only; suspend methods emit the named skip above** and follow the suspend
route migration (ROADMAP line 54). Files touched by the whole feature:

1. `forward/ForwardCallablePlanner.kt`: `sealedSubclassEntries`, the `catalog()` loop, the
   `SEALED_SUBCLASS_UNROUTED` reason.
2. `forward/ForwardDiagnostic.kt`: `toDiagnosticKind()` arm.
3. `NugetProcessor.kt`: `warnDroppedForwardCallables` wording branch.
4. `exports/SealedClassExports.kt`: the `classMethods(...).forEach` loop.
5. `cir/CirModel.kt`: `CirSealedSubclass.methods`.
6. `cir/CirClassTranslator.kt`: `translateSealedClass` method projection + collision guard.
7. `cir/CirSealedRenderer.kt`: the method loop.
8. `cir/CirNativeImports.kt`: lift `methodNativeImport`.
9. Fixture + one C# test file (below).

**(b) Also re-key the legacy suspend route to sealed subclasses.** Everything in (a) plus:

10. `NugetProcessor.kt:1266-1268`: a second loop over `sealedClasses.flatMap { getSealedSubclasses() }`.
11. `exports/SuspendFunctionExports.kt:67-73`: `addSuspendClassMethodExports` derives its prefix from
    `cls.simpleName.lowercase()` (**verified**), so it would export `running_pause_async`, not
    `job_running_pause_async`; it needs a prefix parameter, and its `qualifiedName` receiver
    spelling must be checked for a nested arm.
12. `cir/CirClassTranslator.kt:660-720`: the `asyncMembers` projection lives inside
    `translateClass` and produces `CirMember`s for a `CirClass`; it would need extracting into a
    function `translateSealedClass` can call, plus `tracker.needsAsync` and `hasSuspendMethods`
    (`:1017`) equivalents on the sealed node.
13. `cir/CirSealedRenderer.kt` + `cir/CirClassRenderer.kt:351`: `renderLegacyMethodNativeImport(cls: CirClass, ...)`
    lifted the same way, and `renderAsyncMethod` reached from the sealed block.
14. `ForwardAbiLegacyRoutes.kt`: `SUSPEND_METHOD` route recognition for a sealed arm's `_async` import.
15. It inherits ROADMAP line 54's overload-collision hazard (two `suspend` overloads on one arm share
    one `_async` symbol) on day one.

(b) is roughly double the surface and lands a second copy of the suspend projection shortly before
that projection is scheduled to move onto the plan. (a) is narrower, and the skip it emits is
named. **Accepted at the gate: (a).** Plainly: (a) closes ROADMAP line 39 and **leaves #115
open**, since #115's text says "suspend or not makes no difference" and lists `pause()`; after (a)
`pause()` is still absent from C#, but the build says `SKIPPED_UNSUPPORTED_COMBINATION
Job.Running.pause` instead of nothing. The implementing PR carries **no `Closes #115` line**, and
adds a follow-up ROADMAP Phase 3 line that links #115 to line 54 (the suspend-route migration):
when sealed-arm suspend methods bind, that line and #115 close together.

### Consumer API

```csharp
public abstract class Job : IDisposable, INugetHandle
{
    public sealed class Running : Job
    {
        public int Progress { get; }
        public int Cancel();                         // fun cancel(): Int
        public string Label(string prefix);          // fun label(prefix: String): String
        public int Step(int by);                     // fun step(by: Int): Int
        public int Step(int by, int times);          // fun step(by: Int, times: Int): Int (ADR-090 overload)
        public Job Next();                           // fun next(): Job  (ADR-105 sealedAsHandle, FromHandle)
        public Job.Done Finish();                    // fun finish(): Done (ObjectHandle of a nested arm)
        public JobListener? Pick();                  // fun pick(): JobListener? (top-level interface, ADR-040)
        // base `open fun describe()`: NOT rendered here (declared-only; base-type item deferred)
        // suspend fun pause(): absent, SKIPPED_UNSUPPORTED_COMBINATION in the build
        // fun pickNested(): NestedListenerOwner.Listener?: absent, SKIPPED_UNSUPPORTED_TYPE (nested interface)
        public override void Dispose();
    }
    public sealed class Done : Job { ... }
    public sealed class Idle : Job
    {
        public string Poke();                        // fun poke() on a data object arm
        public string Describe();                    // Idle's own `override fun describe()`: declared, plain public
        ...
    }
}
```

### Fixture (`test-library/src/nativeMain/kotlin/.../issue115/JobSample.kt`)

```kotlin
package io.github.xxfast.kotlin.native.nuget.test.issue115

import io.github.xxfast.kotlin.native.nuget.test.issue54.NestedListenerOwner

interface JobListener { fun onEvent(): String }     // top-level: binds (ADR-040); named so it does not
                                                    // shadow issue54's nested `Listener`

// ADR-040 fixture precedent (`menagerie/MenagerieSample.kt:107`, `private class Goat : IFeedable`):
// the implementing type stays private, the consumer only ever sees the interface.
private class ProgressListener(private val progress: Int) : JobListener {
  override fun onEvent(): String = "progress $progress"
}

sealed class Job {
  open fun describe(): String = "job"               // base body: NOT rendered on Running (declared-only)
  data class Running(val progress: Int) : Job() {
    fun cancel(): Int = progress                    // no conversion (Int)
    fun label(prefix: String): String = "$prefix$progress"   // String in and out
    fun step(by: Int): Int = progress + by          // overload pair, same public name
    fun step(by: Int, times: Int): Int = progress + by * times
    fun next(): Job = Done(progress)                // sealed base return, FromHandle
    fun finish(): Done = Done(progress)             // nested arm return, ObjectHandle
    fun pick(): JobListener? =                      // nullable top-level interface return, binds
      if (progress > 0) ProgressListener(progress) else null
    fun pickNested(): NestedListenerOwner.Listener? = null   // ROADMAP line 39 literal: named skip
    suspend fun pause(): Int = progress             // deferral path: SKIPPED_UNSUPPORTED_COMBINATION
  }
  data class Done(val code: Int) : Job()
  data object Idle : Job() {
    fun poke(): String = "idle"                     // method on a data object arm
    override fun describe(): String = "idle"        // Idle's own declared override: plain public in C#
  }
}
```

The lambda-parameter cell (`fun watch(onTick: (Int) -> Unit)`) was trimmed at the gate; its
diagnostic row in the table above is design, not fixture-covered in v1.

C# test `IntegrationTests/SealedSubclassMethodTests.cs`: `Cancel`, `Label`, both `Step`
overloads, `Next() is Job.Done`, `Finish()`, `Idle.Poke()`, `Idle.Describe() == "idle"`;
`Pick()` returns a `JobListener` whose `OnEvent()` is `"progress 3"` for `Running(3)` and null for
`Running(0)`; reflection asserts `Describe` is absent on `Running` (declared-only) and present on
`Idle`, and `PickNested` and `Pause` are absent on `Running`; a diagnostics-file assertion that
`SKIPPED_UNSUPPORTED_COMBINATION` names `Job.Running.pause` and `SKIPPED_UNSUPPORTED_TYPE` names
`Job.Running.pickNested`.

### ABI contract

`ForwardAbiLegacyRoutes` keeps `SEALED_CLASS` for the discriminator, dispose and data-method
exports. `ForwardAbiContract.csharp` still returns `emptyList()` for a `CirSealedClass`
(`ForwardAbiContract.kt:103` `else -> emptyList()`, **verified**), so the new method imports are
collected by the `csharpLegacy` text scraper, exactly as ADR-111's property imports are (ROADMAP
line 44 stays open, unchanged by this ADR). The `require(signatures.size == 1)` in `csharpLegacy`
fails the build if the Kotlin and C# halves disagree, so a prefix mistake is loud.

## Consequences

- Every plannable public method on a sealed subclass (nested, sibling, `data class`, `object`,
  `data object`) is exported as `${sealed}_${sub}_${name}[_n]` and rendered as a public C# method
  on the nested `sealed class`, with the error slot, overloads and omitting overloads an ordinary
  class gets.
- A `suspend`, `Flow`-returning, lambda-parameter or generic method on a sealed subclass is
  **absent from C# and named** (`SKIPPED_UNSUPPORTED_COMBINATION`), where today it is absent and
  silent. New skip reason `SEALED_SUBCLASS_UNROUTED`; no new `ForwardDiagnosticKind`.
- `emitCsharpSignatureCollisions` now runs for sealed arms.
- No existing export is renamed; no Tier 1 test changes spelling (only new members appear).
- Closes ROADMAP line 39. **#115 stays open** (its suspend half); the PR carries no `Closes`
  line and adds a ROADMAP Phase 3 line linking #115 to line 54. `docs/backlog/two-exported-types-...`
  is unaffected (the sealed prefix is unchanged).
- Deferred, each to a ROADMAP line: suspend methods on sealed arms (line 54's migration, linked to
  #115); Flow/lambda/generic methods on sealed arms (join their respective plan migrations);
  base-declared `abstract fun` **and `open fun`** members on the C# **base** type, and with them an
  arm's inherited base body (pair with line 47); the `dispose` name collision (pre-existing on
  ordinary classes too); `ForwardAbiContract.csharp` structural walk of `CirSealedSubclass` (line 44).

## Scope

- v1: **declared-only**: the non-suspend, non-generic, non-Flow, non-lambda-parameter public
  member functions a sealed subclass itself declares (its own `override fun`s included), on any
  sealed-subclass kind, with every parameter/return shape `planOrSkip` binds for an ordinary class
  method, overloads, and default-argument omitting overloads.
- Not in v1: a base-declared `open fun` the arm does not override (absent from that arm, no
  diagnostic, carried by the deferred base-type item); everything listed under Deferred above.

## Claims list

Verified by reading the cited lines (no build, no fixture; no Gradle spike was needed because every
prefix/receiver mistake fails at a `require` or the ABI contract, loudly): the four-step silent
chain; `classEntries`' filter, counter, receiver, flags and `planOrSkip` parameters; the sealed
base's membership in `exportedObjectHandles`; `isForwardPlannableMemberOf`'s text;
`classMethods`/`invocationExpression` owner splitting; `classMethod`'s prefix `require`;
`methodNativeImport`'s inputs; `emitCsharpSignatureCollisions` call sites; `droppedCallables`'
filter and the `droppedFromCSharp` flags; `SKIPPED_UNSUPPORTED_COMBINATION`'s doc;
`NestedListenerOwner.Current()` skipped on an ordinary class; `addSuspendClassMethodExports`'
prefix derivation; `ForwardAbiContract.csharp`'s `else -> emptyList()`; no `dispose` guard.

Inferred, must be checked by the implementer:

- The membership claim that was load-bearing in the Proposed draft (an inherited base `open fun`
  flattening onto every arm) is gone with the declared-only decision: the filter is
  `parentDeclaration == subclass`, an equality test with no inheritance reasoning behind it. What
  replaces it is a *negative* fixture check, not an inference: `Running` must render no
  `Describe()` and `Idle` must render its own. If `Describe()` appears on `Running`, the filter
  was not applied (a wrong-output bug the reflection assertion catches, not a silent one).
- `renderMethod` output re-indents correctly with `indentNestedBody()` for every body shape
  (sync-error-check, custom body, has-value fan-out). A wrong indent is a C# compile error, not
  silent.
- `tracker.trackPlan` on a sealed arm plan pulls in the same collection/lambda helpers it does for
  an ordinary class (it reads only the plan).
- The Swift/ObjC prior-art sentence, from docs only.
