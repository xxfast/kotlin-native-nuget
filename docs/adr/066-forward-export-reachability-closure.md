# ADR-066: Forward export set as a reachability closure from module roots (MF-003)

## Status

Accepted

## Context

`NugetProcessor.process()` builds its declaration set from `resolver.getAllFiles()`
(`NugetProcessor.kt:159`), filtered by ADR-063's package predicate. `getAllFiles()` means *the files
in this compilation*, so a type declared one Gradle module away is never translated, while an
unrelated type in the same module is. The export set is defined by **file provenance**, not by what
the exported API actually references.

The consequence is not a missing type; it is two different broken outputs, depending on the position
the dependency type appears in.

**Failure mode 1: the callable disappears.** A plain return type routes through
`ForwardBridgeTypeClassifier.classifyNonNullable`, which ends at:

```kotlin
if (qualifiedName !in context.exportedObjectHandles) {
  return BridgeType.Unsupported(qualifiedName, "declaration is not in the exported object-handle set")
}
```

**Verified in source** (`ForwardBridgeTypeClassifier.kt:97-102`). The planner drops the callable and,
post-ADR-064, warns `SKIPPED_UNSUPPORTED_TYPE`. The member is absent from C#.

**Failure mode 2: the generated C# references a type that does not exist.** The `Flow<T>` route does
not consult `exportedObjectHandles` at all. It maps the element type by **simple name**:

```kotlin
val elementType = propTypeResolved.arguments.firstOrNull()?.type?.resolve()
val elementTypeName: String = elementType?.declaration?.simpleName?.asString() ?: "Any"
KOTLIN_TO_CSHARP_PARAM[elementTypeName] ?: elementTypeName
```

**Verified in source** (`CirClassTranslator.kt:147-150` and `:454-458`; the identical three-line
shape also appears at `:724-726`, `:742-744`, `:1062-1064`, but those three are `List`/`Set`
element-type mapping on a sealed-subclass property and a companion-function return, not `Flow` at
all, so they share the shape and not the bug). So `Flow<TopStoriesState>` emits
`KotlinFlow<TopStoriesState>` into `Interop.cs`, naming a C# type that was never generated: `CS0246`
inside generated code the consumer never wrote, in *every* consumer of the package. This is exactly
NYTimes-KMP BUG-004, and it explains why that consumer hand-writes a module-local projection DTO plus
mappers (its MF-003) rather than returning the shared model.

> **Correction (post-implementation).** This ADR originally counted all five line ranges above as
> the same `Flow` element-type-by-simple-name bug and said so throughout. `kotlin-dev` found only
> **two** of the five are: the class-property route and the class-method-return route. The other
> three are a separate, still-open `List`/`Set` element-type-by-simple-name gap, out of scope for
> this feature. Every "five sites" claim below is corrected to two.

### Prerequisites (confirmed)

ROADMAP line 14 sequences this item behind two others; both have landed.

- **ADR-064** (forward unsupported-declaration diagnostics) is Accepted and names both constructs
  reachability was expected to re-arm: `SKIPPED_INHERITED_MEMBER` for the `CharSequence`-delegating
  value class, `SKIPPED_UNSUPPORTED_COMBINATION` for the generic `suspend inline` extension returning
  `Result<T>`. **Verified**: read in `docs/adr/064-…md`, Decision table rows 7-8.
- **The value-class-parameter item** closed under ADR-062 Phase 9. **Verified**: ROADMAP line 76 is
  checked, and its "Still open" remainder is the *product* question about inherited members, not the
  parameter export.

ADR-064's coverage is necessary but, as verified below, **not sufficient as written**: one of its two
filter rules is expressed in terms of `Origin`, and `Origin` does not mean the same thing on the far
side of a module boundary. See "Two ADR-064 amendments this forces".

### Composing with ADR-063, not contradicting it

ADR-063 deliberately deferred this closure and said what shape it expected: *"the include set is
exactly the 'explicit roots' a future reachability closure would seed from, so this seeds that design
rather than pre-empting it"*, and *"MF-003 generalises 'the packages named here' to 'everything
reachable from the declarations in the packages named here'"* (ADR-063, Alternative 1 pros and
Alternative 4). This ADR takes that framing literally: ADR-063's predicate becomes the **admission
boundary of the closure**, and no new DSL verb is introduced.

## Verification: what KSP can actually see across a module boundary

This is the load-bearing question for the whole feature, so it was spiked rather than inferred.
A scratch Gradle project (`mktemp`-style scratch dir, never the repo tree) with the repo's own
versions (Kotlin 2.4.0, KSP 2.3.9), a `:dep` KMP module, a `:lib` KMP module depending on it with
plain `implementation(project(":dep"))`, and a probe `SymbolProcessor` on `:lib`, run as
`./gradlew :lib:kspKotlinMacosArm64`, the **same task shape the plugin wires up**
(`NugetPlugin.kt:199`, `kspKotlin{Target}`), against a real Kotlin/Native target, so the dependency
is a **klib**, not a jar.

### V1: `getAllFiles()` really is module-local, and by-name resolution really does cross

```
PROBE| === getAllFiles() ===
PROBE| file Api.kt pkg=lib decls=[Facade]
PROBE| [byName] dep.TopStoriesState kind=CLASS origin=KOTLIN_LIB vis=PUBLIC modifiers=[FINAL, PUBLIC, DATA] file=null location=NonExistLocation
PROBE| [byName]   primaryCtor=[title: kotlin.String, count: kotlin.Int, tags: kotlin.collections.List, nested: dep.Nested?]
PROBE| [byName]   declaredProps=[title:kotlin.String[vis=PUBLIC], count:kotlin.Int[vis=PUBLIC], tags:kotlin.collections.List[vis=PUBLIC], nested:dep.Nested?[vis=PUBLIC], computed:kotlin.Int[vis=PUBLIC]]
PROBE| [byName]   declaredFuns=[describe([String])->kotlin.String, component1…, copy…, <init>([String, Int, List, Nested])->dep.TopStoriesState]
PROBE| [byName]   sealedSubclasses=[]
PROBE| method stream -> kotlinx.coroutines.flow.Flow args=[dep.TopStoriesState]
PROBE| [typearg:stream] dep.TopStoriesState … primaryCtor=[title: kotlin.String, …]   (full model, identical to byName)
PROBE| [ret:outcome] dep.Outcome kind=CLASS modifiers=[SEALED, PUBLIC] sealedSubclasses=[dep.Outcome.Failed, dep.Outcome.Ok]
PROBE| [dep.Mood] kind=ENUM_CLASS … enumEntries=[Calm, Excited]
PROBE| getDeclarationsFromPackage(dep) -> []
```

**Verified**, and this is the green light for the feature:

- `getAllFiles()` returns only the module's own file. The roadmap's premise is exact.
- `Resolver.getClassDeclarationByName` resolves a dependency-klib type, and so does
  `KSType.declaration` reached through a **return type** *and* through a **type argument**
  (`Flow<TopStoriesState>`). All three yield the same full declaration.
- The model is complete enough to build a C# type: primary-constructor parameter names, types and
  **nullability** (`nested: dep.Nested?`), declared properties with visibility, declared functions
  with parameters and return types, `getSealedSubclasses()` (returned both subclasses), enum entries,
  type parameters with bounds, supertypes.
- `containingFile` is `null` and `location` is `NonExistLocation`. A cross-module declaration cannot
  be added to KSP `Dependencies`, and cannot carry a source location in a diagnostic.
- **`getDeclarationsFromPackage("dep")` returns an empty list for a klib dependency.** A dependency
  package **cannot be enumerated**. Reachability is not merely the nicest design; it is the *only*
  way in. This single line rules out "just add the dependency's package to `include()` and scan it".

### V2/V3: two divergences that would silently produce wrong output

```
PROBE| [v3] lib.LocalUri    origin=KOTLIN     modifiers=[VALUE]                isValueVIA_VALUE=true  isValueVIA_INLINE=false
PROBE| [v3] dep.ArticleUri  origin=KOTLIN_LIB modifiers=[FINAL, PUBLIC, INLINE] isValueVIA_VALUE=false isValueVIA_INLINE=true
PROBE| [v3] lib.LocalState  origin=KOTLIN     modifiers=[DATA]
PROBE| [v3]    fun copy       origin=SYNTHETIC  parent=LocalState
PROBE| [v3] dep.TopStoriesState origin=KOTLIN_LIB modifiers=[FINAL, PUBLIC, DATA]
PROBE| [v3]    fun copy       origin=KOTLIN_LIB parent=TopStoriesState
PROBE| [v3]    fun describe   origin=KOTLIN_LIB parent=TopStoriesState
PROBE| [v2] dep.HiddenInternal classOrigin=KOTLIN_LIB vis=INTERNAL isPublic=false
PROBE| [v2] dep.Suspender  fun load origin=KOTLIN_LIB mods=[PUBLIC, FINAL, SUSPEND]
PROBE| [v2] lib.Facade     fun state origin=KOTLIN mods=[]
```

**Verified, and both are traps:**

- **`Modifier.VALUE` is absent cross-module; the klib reports `Modifier.INLINE`.** `NugetProcessor`
  partitions value classes with `it.modifiers.contains(Modifier.VALUE)`
  (`NugetProcessor.kt:210`, `:217`) and so does the classifier
  (`ForwardBridgeTypeClassifier.kt:78`). A cross-module value class would fall into the **ordinary
  class** bucket and be exported as an opaque handle with an `IDisposable` wrapper instead of an
  unwrapped value, silently, with valid-compiling output. Every `Modifier.VALUE` test site must
  become `VALUE || INLINE`.
- **Every member of a cross-module declaration has `origin = KOTLIN_LIB`**, never `KOTLIN` and never
  `SYNTHETIC`. In-module, a `data class`'s `copy`/`componentN` and a `by value` delegation's
  forwarded members are `SYNTHETIC`; cross-module they are indistinguishable from author-declared
  members.
- `internal` declarations in a dependency **are** resolvable and correctly report `vis=INTERNAL`, so
  the closure must filter on visibility (it does not come for free).
- Modifier sets are *sparse* in-module (`fun state mods=[]`) and *complete* cross-module
  (`mods=[PUBLIC, FINAL, SUSPEND]`). Any check for the **absence** of a modifier diverges.

### V4: supertype members vs delegated members

```
PROBE| [v4] dep.DelegatingUri (value class … : CharSequence by value)
PROBE| [v4]    fun get         origin=KOTLIN_LIB parent=dep.DelegatingUri mods=[PUBLIC, OPEN, OPERATOR, OVERRIDE]
PROBE| [v4]    fun subSequence origin=KOTLIN_LIB parent=dep.DelegatingUri mods=[PUBLIC, OPEN]
PROBE| [v4]    prop length     origin=KOTLIN_LIB parent=dep.DelegatingUri
PROBE| [v4] dep.Derived : Base(), Named
PROBE| [v4]    fun own      origin=KOTLIN_LIB parent=dep.Derived
PROBE| [v4]    fun baseFun  origin=KOTLIN_LIB parent=dep.Base
PROBE| [v4]    fun equals   origin=KOTLIN_LIB parent=kotlin.Any
PROBE| [v4] dep.Generic typeParams=[T:[kotlin.Any]] supertypes=[kotlin.Any]
```

**Verified.** Genuine supertype inheritance is still visible cross-module (`baseFun` reports
`parent = dep.Base`), so a `parentDeclaration == cls` filter works. **Interface delegation is not**:
`CharSequence by value` forwards `get` / `subSequence` / `length` with `parent = the value class` and
`origin = KOTLIN_LIB`, identical to a hand-written member on both axes.

### V5: the dependency's code links into the shared library

A `@CName` export in `:lib` that constructs and calls a `:dep` type, with only
`implementation(project(":dep"))` and **no** `export()` / `api`:

```
$ ./gradlew :lib:linkDebugSharedMacosArm64        → BUILD SUCCESSFUL
$ nm -gU lib/build/bin/macosArm64/debugShared/libprobe.dylib | grep export_dep
0000000000004a2c T _export_dep_topstoriesstate_get_count
```

**Verified.** The generated `@CName` wrappers may freely reference dependency-module types; the
Kotlin/Native linker pulls the klib into the `sharedLib` binary and the symbol is exported. This
matters because Kotlin's framework `export()` DSL is about the *generated ObjC header*, and we
generate our own surface, so no `export()`/`api` requirement is inherited.

## Alternatives Considered

### 1. Reachability closure from module roots, bounded by ADR-063's package predicate (chosen)

Roots are the module's own declarations that survive ADR-063's filter (unchanged). Walk the type
graph from those roots. A discovered dependency-module declaration is **admitted** iff its package
passes the *same* `isPackageExported` predicate ADR-063 already computes.

**Pros:**
- **Zero new configuration in the common case.** A dependency module under the same `rootPackage`
  (`com.acme.presentation` under root `com.acme`) is admitted automatically. That is the NYTimes-KMP
  shape, and it satisfies GOALS.md #1 (plug and play) with no new DSL.
- **Blast radius bounded by construction.** Third-party libraries (`io.ktor.*`, `kotlinx.*`,
  `kotlin.*`) are outside `rootPackage`, are never admitted, and behave exactly as today. The closure
  cannot silently swallow the classpath.
- Exactly the generalisation ADR-063 said it was seeding. One predicate, one mental model, both
  directions of the "too broad / too narrow" problem solved by the same knob.
- The escape hatch already exists and is one line: `include("dev.other.core")` widens admission for a
  dependency whose package tree is unrelated to `rootPackage`. The diagnostic (below) names that
  exact line.

**Cons:**
- Admission is inferred from package naming, which is a convention, not a declaration of intent. A
  dependency module that happens to share the root package is pulled in whether or not the author
  meant it to be part of the public surface. Mitigated by `exclude(...)` and by the closure being
  *reachability*-gated: an unreferenced type is never exported no matter what its package is.
- Asymmetry to document: an **empty** effective include set means "all" for the module's own files
  (ADR-063) but must mean "**no cross-module admission**" here. The module's own files are a bounded,
  author-owned set; the compile classpath is not. Without this rule, a consumer who sets neither
  `rootPackage` nor `include` would have the closure walk into `kotlinx-coroutines`.

### 2. Explicit per-dependency opt-in, mirroring `export(project(":dep"))`

Add a `publish { export(project(":presentation")) }` verb, and admit types only from explicitly
exported modules. This is what Kotlin/Native framework export and Swift Export both do.

**Pros:**
- Exactly the ecosystem precedent (see Prior art). Intent is declared, not inferred from package
  names. Per-module namespace control is a natural extension (Swift Export's `moduleName` /
  `flattenPackage`).
- Immune to the "shares a package prefix by accident" objection.

**Cons:**
- Costs the plug-and-play default: every consumer with a models module must find and add the verb
  before anything works, and the failure before they do is the *current* broken behaviour.
- Duplicates ADR-063's scoping concept with a second, differently-shaped knob (Gradle `Project`
  handles vs package prefixes), which then have to interact.
- The Gradle-project handle is not available on the KSP side; it would have to be lowered to a set of
  packages or klib paths to reach the processor anyway, which is Alternative 1 with more ceremony.

**Rejected. Not part of this feature at all, and deferred to its own item** (decided with the human).
The admission boundary for this feature is the ADR-063 package predicate and nothing else: no
`export(project(...))` verb is added, and no Gradle `Project` handle reaches the processor. If
package-prefix admission later proves too blunt, `export(project(...))` layers cleanly on top by
contributing additional admitted package prefixes to the same predicate, so nothing here forecloses
it, but an implementing agent should not build any part of it now. See Scope / Deferred.

### 3. Unbounded closure over the whole compile classpath

Admit any reachable type from anywhere.

**Rejected.** It is the maximal re-arming of ADR-064's ceiling: a single `Instant`, `HttpClient` or
`Json` in a signature drags an entire third-party library into the export set. It also makes the
generated assembly's contents a function of transitive dependency upgrades. Kotlin's own
`transitiveExport` exists and its documentation calls it *not recommended* for exactly this reason.

### 4. Status quo: document the hand-written projection DTO

**Rejected.** It is the workaround NYTimes-KMP already pays for (a DTO plus mappers per shared
model), and it contradicts GOALS.md #2 and #3.

### 5. Export unbridged dependency types as bare opaque handles

Give any unexportable reachable type an `IntPtr`-backed C# wrapper with no members, so signatures
keep compiling.

**Rejected.** It is a valid-but-lying API: the consumer gets `TopStoriesState` with no `Title`, and
no diagnostic. ADR-064 chose "absent + named" over "present + useless", and ADR-060's whole point is
that a valid-compiling wrong API is the most expensive failure class here. It is also the disposition
this ADR is asked to rule on, and the answer is: **named diagnostic + skip, never an opaque handle,
never a hard error.**

## Prior art

*All inferred from documentation, not spiked.*

- **Kotlin/Native framework export** ([Build final native binaries](https://kotlinlang.org/docs/multiplatform/multiplatform-build-native-binaries.html)):
  `export(project(":dependency"))` inside `binaries { framework { } }`, opt-in **per dependency
  module**, and *"You can only export `api` dependencies of the corresponding source set."*
  Non-transitive by default; `transitiveExport = true` exists and the docs warn it is *"not
  recommended"* because it adds **all** transitive dependencies and disables dead-code elimination.
  The docs do not state what happens to a type from a non-exported dependency that appears in public
  API.
- **Swift Export** ([Swift export](https://kotlinlang.org/docs/native-swift-export.html)):
  `swiftExport { export(project(":subproject")) { moduleName = "Subproject"; flattenPackage =
  "com.subproject.library" } }`. Same per-module opt-in, plus **per-exported-module namespace
  control**. Kotlin packages become nested Swift enums; `flattenPackage` strips a prefix. The docs do
  not address non-exported dependencies.
- **ObjC export** ([Interop with Swift/Objective-C](https://kotlinlang.org/docs/native-objc-interop.html)):
  all public declarations of the exported module leave; opt out with `internal` or `@HiddenFromObjC`.
  No per-declaration opt-in, no documented per-declaration skip diagnostic.
- **JS export**: `@JsExport` is per-declaration opt-in, the inverse.

**Takeaway.** The ecosystem answers "do I pull in a dependency?" as an **explicit per-module opt-in**
(Alternative 2), not as automatic reachability. This ADR deviates deliberately, and the deviation is
narrow: admission is still gated (by the ADR-063 predicate the user already configures), and the
gate's default is a package prefix rather than a project handle, because the alternative costs the
plug-and-play default that the framework exporters do not have to care about (an ObjC framework has
no equivalent of "the package just works if you named it consistently"). Swift Export's
`moduleName`/`flattenPackage` is noted as the model for future namespace control.

## Decision

Define the forward export set as a **reachability closure from module roots**, admitted by ADR-063's
existing package predicate.

### 1. Roots

Unchanged from ADR-063: the public declarations in `resolver.getAllFiles()` that pass
`isPackageExported`. No new root syntax. **Roots are the only thing enumerated**; everything else is
discovered. (Forced, not chosen: `getDeclarationsFromPackage` on a klib returns empty, **verified**.)

### 2. Edges followed

From each root declaration, and transitively from each admitted declaration:

| Edge | Followed in v1 | Note |
|---|---|---|
| Callable return type | yes | methods, top-level functions, extension functions, companion methods |
| Callable parameter types | yes | |
| Property type (declared, public) | yes | class properties, top-level properties, extension properties |
| Type arguments of an admitted carrier | yes | `Flow<T>`, `StateFlow<T>`, `List/Set/Map<…>`, `Nullable` wrapper |
| Sealed subclasses of an admitted sealed class | yes | `getSealedSubclasses()` **verified** to work cross-module |
| Primary-constructor parameter types | yes | needed to generate the C# constructor |
| Supertypes of an admitted class | **no** | deferred; see Scope |
| Type-parameter bounds | **no** | deferred with generic cross-module classes |
| Enum entries | n/a | intrinsic to the enum, not an edge |

Only **public** declarations are followed and admitted (`internal` is resolvable cross-module and
correctly reported (**verified**), so this filter is real work, not a no-op).

### 3. Admission predicate

A discovered declaration is admitted iff **all** hold:

1. It is not already a root (roots are admitted by definition).
2. `getVisibility() == PUBLIC`.
3. It has no known intrinsic mapping (primitives, `Char`, `String`, `Unit`, collections, `Flow`,
   `StateFlow`, function types). These terminate the walk; they are already bridged.
4. `context.effectiveInclude.isNotEmpty()`, the cross-module asymmetry from Alternative 1. With no
   `rootPackage` and no `include`, the closure does not cross the module boundary and behaviour is
   byte-identical to today.
5. `isPackageExported(pkg)`, ADR-063's predicate, unchanged, including its "reverse-bound packages
   are always in scope" first clause.

Termination is a visited-set keyed on `qualifiedName`; cyclic type graphs (`A.b: B`, `B.a: A`)
terminate on the second visit. The frontier is finite because the classpath is.

### 4. Disposition of a reachable-but-unsupported type

**Named diagnostic + skip. Not a hard error, not an opaque handle.** This preserves ADR-064's
severity policy exactly (Alternative 3 there was rejected on the same grounds). Two kinds are added
to `ForwardDiagnosticKind`:

```kotlin
SKIPPED_UNEXPORTED_DEPENDENCY_TYPE(Severity.WARNING),  // reachable, bridgeable, but out of scope
INFO_EXPORTED_FROM_DEPENDENCY(Severity.INFO),          // the closure manifest, emitted once
```

`SKIPPED_UNEXPORTED_DEPENDENCY_TYPE` replaces the misleading current message for this case. Today a
dependency type reaches the classifier's *"declaration is not in the exported object-handle set"*
branch and, post-ADR-064, warns `SKIPPED_UNSUPPORTED_TYPE`, which tells the author their type is
unsupported when it is merely out of scope. The new kind's hint names the fix verbatim:

```
w: [nuget] [SKIPPED_UNEXPORTED_DEPENDENCY_TYPE] Skipping Newsroom.latest(): its return type
   dev.other.core.TopStory is declared in a dependency module whose package is outside the export
   scope, so it has no C# model. Add include("dev.other.core") to nuget { publish { } }, or expose a
   type from an in-scope package instead.
     at Newsroom.kt:14
```

A reachable type that is admitted but that the forward direction genuinely cannot express (a
cross-module `CharSequence`-delegating value class, a cross-module generic class) keeps its existing
ADR-064 kind. The unit of skipping is the **member**, not the type: if `TopStory.uri: StoryUri` is
unexportable, `StoryUri` is skipped and `TopStory` is still exported without that property. Only if
the *root* callable's own return/parameter type is unexportable does the callable itself drop.

### 5. Namespacing: no new rule

An admitted dependency type keeps its **Kotlin package** and goes through the existing
`mapPackageToNamespace(pkg, rootPackage, rootNamespace)` unchanged. **Verified in source**
(`CirTypeMapping.kt:111-133`):

- package under `rootPackage` → relative path, PascalCased:
  `…nuget.test.models` under root `…nuget.test` with `packageId = TestLibrary` → `TestLibrary.Models`.
- package outside `rootPackage` → the **full** package PascalCased under the root namespace:
  `dev.other.core` → `TestLibrary.Dev.Other.Core`.

This follows the framework naming convention (the exported dependency keeps its own identity as a
sub-namespace, the way Swift Export gives it its own module name) rather than folding everything into
the exporting module's short name, which would collide the moment two dependency modules declare a
`State`. Everything still lands under the assembly's root namespace because it is **one** assembly.
A Swift-Export-style `flattenPackage` alias is noted as future work, not v1.

### 6. Blast-radius reporting

The closure can enlarge the exported API substantially and invisibly. It must be reported, but one
warning per admitted type would be noise at the scale this operates. So, a **deliberate deviation
from ADR-064's one-diagnostic-per-member shape**: `INFO_EXPORTED_FROM_DEPENDENCY` is emitted **once
per KSP run**, aggregating the admitted set into a single line, plus per-type detail at
`logger.info`:

```
w: [nuget] [INFO_EXPORTED_FROM_DEPENDENCY] Note: the export closure admitted 3 type(s) from
   dependency modules: dev.acme.models.TopStory, dev.acme.models.Byline, dev.acme.models.Section.
```

Cross-module declarations have `containingFile == null` and `location == NonExistLocation`
(**verified**), so these diagnostics carry **no source location**: the `KSNode` attached is the
in-module *reference site* (the callable that reached the type), not the type itself. A file-based
manifest is deferred to ADR-064's already-deferred shared structured-report item.

### 7. Bridge mechanism

Nothing new on the wire. An admitted dependency type is generated exactly like an in-module type: a
C# class over an opaque `IntPtr` handle with `IDisposable` (ADR-003/ADR-005), and `@CName` exports in
the **publishing** module's generated `CNameExports.kt` that call into the dependency type's members.
**Verified** (V5 above) that such an export links and is present in the built `.dylib` with only
`implementation(project(":dep"))`; no `export()` or `api` dependency is required, because we generate
our own surface rather than Kotlin's ObjC header.

The `Flow<T>` element-type sites must stop mapping by simple name and start emitting the qualified
`global::{namespace}.{Type}`, matching the enum branch's existing shape
(`ForwardBridgeTypeClassifier.kt:63-76`). Two sites, **verified in source**:
`CirClassTranslator.kt:149` (class property) and `:458` (class-method return). (Originally counted
as five; `:725`, `:743`, `:1063` are the unrelated `List`/`Set` element-type gap, see the correction
above.) Until the two `Flow` sites are fixed, a `Flow<T>` of an admitted dependency type still emits
an unqualified name and only compiles by accident when the namespaces happen to coincide.

### Two ADR-064 amendments this forces (both IN SCOPE for this feature)

> **Implementing agent: read this section before writing any code.** Both amendments are **verified
> against a real klib**, both are **part of this feature and not a follow-up**, and both produce
> **valid-compiling, silently wrong output** if you follow ADR-064 literally once types cross a
> module boundary. Neither fails the build. Neither emits a diagnostic. You will not notice either
> one unless the fixture cells below exist.

Both are **verified**, and both silently produce wrong output if an implementer follows ADR-064
literally once types cross a module boundary.

1. **`SKIPPED_INHERITED_MEMBER`'s filter rule is wrong cross-module.** ADR-064 specifies *"the filter
   must also exclude `origin != Origin.KOTLIN`"*, and it is **already implemented that way in
   source**: `if (prop.origin != Origin.KOTLIN || prop.parentDeclaration != cls)`
   (`ForwardCallablePlanner.kt:261`) and the same test for methods (`:306`). **Verified in source**,
   including that these are the *only* two `Origin.`-keyed sites in `nuget-processor` (so no
   data-class path shares the bug). Every member of a `KOTLIN_LIB` declaration has
   `origin == KOTLIN_LIB` (verified, V2/V3), so applied to an admitted dependency type that rule
   excludes **every member, including author-declared ones**, and the type exports empty. The rule
   must be restated origin-independently:
   - genuine supertype inheritance → `parentDeclaration != cls` (verified to work cross-module:
     `baseFun` reports `parent = dep.Base`);
   - **interface delegation** (`CharSequence by value`) → **not detectable by `origin` or
     `parentDeclaration` cross-module** (verified, V4: `get`/`subSequence`/`length` report
     `parent = the value class`, `origin = KOTLIN_LIB`, identical to hand-written members). The only
     origin-independent discriminator available is *"a supertype declares a member with this simple
     name"*, walking `getAllSuperTypes()`.

   **The correction lands as part of this feature** (decided with the human), not as a split-out
   follow-up: reachability is what makes the bug reachable at all, so the closure and the corrected
   filter must land together or the closure ships broken. Concretely, the two shipped tests at
   `ForwardCallablePlanner.kt:261` and `:306` are replaced by an origin-independent rule:

   ```kotlin
   // Was: prop.origin != Origin.KOTLIN || prop.parentDeclaration != cls
   // Origin is KOTLIN in-module but KOTLIN_LIB for EVERY member of a dependency declaration,
   // so the origin test must go. Supertype simple-name match replaces it, and covers the
   // `CharSequence by value` delegation that neither origin nor parentDeclaration can see
   // cross-module.
   val inheritedNames: Set<String> = cls.getAllSuperTypes()
     .mapNotNull { it.declaration as? KSClassDeclaration }
     .flatMap { it.getAllFunctions().map { fn -> fn.simpleName.asString() } +
                it.getAllProperties().map { p -> p.simpleName.asString() } }
     .toSet()
   if (prop.parentDeclaration != cls || prop.simpleName.asString() in inheritedNames) { /* skip */ }
   ```

   **It carries its own Tier 1 cell**, asserting `SKIPPED_INHERITED_MEMBER` fires for a
   *cross-module* `CharSequence by value` value class, because that is the one place a cross-module
   fixture behaves differently from the in-module fixture ADR-064 was validated against, and the
   in-module cell will keep passing whether or not the correction landed.

2. **`Modifier.VALUE` must become `Modifier.VALUE || Modifier.INLINE`.** Verified: a klib reports
   `INLINE` and **never** `VALUE`; an in-module `value class` reports `VALUE`. Without this, a
   cross-module value class falls into the ordinary-class bucket and is exported as an
   `IDisposable` opaque handle instead of an unwrapped value. It compiles, it runs, the C# API is
   wrong, and nothing warns. **This is the single most likely way to implement this feature and ship
   a silent defect.**

   **The three sites, all verified in source, all of which must change:**

   | Site | Current code | Effect if missed |
   |---|---|---|
   | `NugetProcessor.kt:210` | `.filter { !it.modifiers.contains(Modifier.VALUE) }` (the `allClasses` bucket) | cross-module value class is treated as an ordinary class |
   | `NugetProcessor.kt:217` | `.filter { it.modifiers.contains(Modifier.VALUE) }` (the `valueClasses` bucket) | cross-module value class never enters the value-class path |
   | `ForwardBridgeTypeClassifier.kt:78` | `if (classDeclaration.modifiers.contains(Modifier.VALUE)) return valueClass(...)` | classifier returns `ObjectHandle` instead of `ValueClass`, so every signature using it marshals a handle |

   The recommended implementation is a single shared helper (e.g. `KSClassDeclaration.isValueClass()`
   testing `VALUE || INLINE`) used at all three, so a fourth site cannot be added later with the
   in-module-only test. The fixture guard is the cross-module value-class cell in the fixture surface
   below, which must assert it binds as an **unwrapped value and not a handle**.

### Expected consumer-side C# API

Kotlin, publishing module `:test-library` (`rootPackage = io.github.xxfast.kotlin.native.nuget.test`,
`packageId = TestLibrary`):

```kotlin
package io.github.xxfast.kotlin.native.nuget.test

class Newsroom {
  fun latest(): TopStory                 // reachable via a plain return type
  fun stream(): Flow<TopStory>           // reachable ONLY via a type argument
  fun archive(): List<TopStory>          // reachable via a collection type argument
}
```

Kotlin, dependency module `:test-models`
(package `io.github.xxfast.kotlin.native.nuget.test.models`, plugin **not** applied):

```kotlin
data class TopStory(val title: String, val rank: Int, val byline: Byline?)
class Byline(val name: String)                                  // reachable only transitively
value class StoryUri(val value: String) : CharSequence by value // reachable + unsupported
```

Generated C#:

```csharp
namespace TestLibrary.Models {
  public sealed class TopStory : IDisposable {
    public string Title { get; }
    public int Rank { get; }
    public Byline? Byline { get; }
    public TopStory Copy(string? title = null, int? rank = null, Byline? byline = null);
    public void Dispose();
  }
  public sealed class Byline : IDisposable { public string Name { get; } public void Dispose(); }
}

namespace TestLibrary {
  public sealed class Newsroom : IDisposable {
    public TestLibrary.Models.TopStory Latest();
    public KotlinFlow<TestLibrary.Models.TopStory> Stream();
    public IReadOnlyList<TestLibrary.Models.TopStory> Archive();
    public void Dispose();
  }
}
```

`StoryUri` is absent, with a `SKIPPED_INHERITED_MEMBER` / `SKIPPED_UNSUPPORTED_TYPE` diagnostic
naming it, not a member-less handle, and not a build break.

### Fixture surface required in `test-library`

The fixture must **cross every mechanism**, not minimise types. A single-module fixture cannot
express any of this, so:

- **A real second Gradle module**, `:test-models`, added to `settings.gradle.kts`, KMP with the same
  `mingwX64` + `macosArm64` targets, consumed by `:test-library` via
  `implementation(project(":test-models"))`. The nuget plugin is **not** applied to it. Without a real
  module the klib boundary (where `Modifier.VALUE` becomes `INLINE` and every `Origin` becomes
  `KOTLIN_LIB`) is never crossed, and the fixture proves nothing.
- **A type reachable only via a type argument**: `fun stream(): Flow<TopStory>` and
  `fun archive(): List<TopStory>`. This is the NYTimes-KMP shape and the one that today emits
  dangling C# rather than dropping the member; it must assert the **qualified** `KotlinFlow<…>`
  element name, since the unqualified name compiles by accident when namespaces coincide.
- **A type reachable via a plain return type**: `fun latest(): TopStory`.
- **A type reachable only transitively**, through an admitted type's member: `TopStory.byline: Byline?`
  proves the closure iterates rather than doing one hop, and covers cross-module nullability.
- **A cross-module value class**: `value class StoryUri(val value: String)` used at a bridgeable
  position, asserting it binds as an unwrapped value and **not** as a handle. This is the
  `VALUE`/`INLINE` regression guard, and it is the fixture most likely to be omitted.
- **A reachable-but-unsupported construct** that must hit an ADR-064 diagnostic rather than crashing:
  `value class StoryUri(...) : CharSequence by value` with its delegated members, asserting
  `SKIPPED_INHERITED_MEMBER` fires (the amended, origin-independent rule) and that no invalid Kotlin
  is emitted.
- **An out-of-scope dependency type**: a type in a package deliberately outside `rootPackage`,
  asserting `SKIPPED_UNEXPORTED_DEPENDENCY_TYPE` fires with the `include(...)` hint, and that adding
  the `include` admits it. This is the only test of the admission predicate itself.
- **A cyclic pair** (`A.b: B`, `B.a: A`) across the module boundary, asserting termination.

ADR-060's Tier 1 harness runs KSP2 in-process with `libraries = listOf(...)`
(`Tier1Harness.kt:81`), so the closure's *unit-level* behaviour can be tested by adding a
pre-compiled fixture klib/jar to that list, but the `Modifier.VALUE`/`INLINE` and `Origin` findings
above were only reproducible against a **real Kotlin/Native klib**, so the value-class and
inherited-member cells must also exist at the `scripts/verify.sh` integration level.

## Consequences

- **The export set is no longer "the files in this module".** It is the reachability closure from
  those files, bounded by the ADR-063 predicate. `getAllFiles()` remains the root enumeration.
- **A consumer's generated API can grow without a source change in their module**, if a dependency
  module adds a public member to an already-admitted type. This is the price of the closure and the
  reason for the `INFO_EXPORTED_FROM_DEPENDENCY` manifest line.
- **Duplicate-type hazard across two published packages: an accepted known limitation, not solved
  here** (decided with the human). If two modules both publish and both admit the same dependency
  type, each assembly declares its own copy: `TestLibrary.Models.TopStory` *and*
  `OtherLib.Models.TopStory`. A consumer referencing both packages gets two unrelated C# types for
  one Kotlin type, with no conversion between them and no diagnostic, because neither KSP run can see
  the other package.

  This is accepted because it is the **same hazard Kotlin/Native framework export already has**: when
  two frameworks each `export()` a shared dependency, the dependency's classes are duplicated into
  both, which is why the Kotlin docs recommend exporting only what you directly need and warn against
  `transitiveExport` ([Build final native binaries](https://kotlinlang.org/docs/multiplatform/multiplatform-build-native-binaries.html),
  inferred from documentation). The blast radius here is also narrower than the framework case: a
  type is duplicated only if it is *reachable from the exported API of two different published
  modules*, which is a deliberate act, not an accident of the dependency graph.

  It is **not** in scope for this feature and gets **its own ROADMAP item**, which should link to
  this bullet. The likely shapes for that item, none decided: a shared "models" NuGet package that
  both publishers depend on rather than each inlining the type; a diagnostic when an admitted type
  originates from a module that is itself published; or an opt-out that forces such a type back to
  the `SKIPPED_UNEXPORTED_DEPENDENCY_TYPE` path so the author must choose explicitly.
- **Two ADR-064 rules change, and both changes land in this feature** (`SKIPPED_INHERITED_MEMBER`'s
  filter, `Modifier.VALUE` classification). They are not split into a follow-up: reachability is what
  makes both fire, and shipping the closure without them produces silently wrong output rather than a
  visible failure. ADR-064 should be amended in place rather than contradicted silently.
- **Two `Flow` element-type sites change** from simple-name to qualified-name emission (corrected
  from an original count of five; see the correction note in Context). Pre-existing latent bug; this
  feature is what makes it fire.
- Cross-module declarations carry **no source location** in diagnostics; the attached `KSNode` is the
  in-module reference site.
- **NYTimes-KMP's projection DTOs and mappers (BUG-004 / MF-003) become deletable**, provided its
  models module sits under the configured `rootPackage` or is named in `include(...)`.
- Behaviour is **unchanged** for a consumer with neither `rootPackage` nor `include` set (admission
  rule 4), and unchanged for any type not referenced from an exported declaration.

### Scope

**In v1:**
- Closure over return types, parameter types, property types, type arguments of admitted carriers,
  sealed subclasses, and primary-constructor parameter types.
- Admission via the ADR-063 predicate, plus the non-empty-include requirement for crossing the module
  boundary.
- Cross-module data classes, ordinary classes, enums, sealed classes, value classes.
- `SKIPPED_UNEXPORTED_DEPENDENCY_TYPE` and the aggregate `INFO_EXPORTED_FROM_DEPENDENCY` manifest line.
- **The `SKIPPED_INHERITED_MEMBER` filter correction** at `ForwardCallablePlanner.kt:261` / `:306`:
  the shipped `origin != Origin.KOTLIN` test is wrong for cross-module types and is replaced by the
  supertype simple-name match. Lands in this feature, with its own Tier 1 cell asserting the kind
  fires for a *cross-module* `CharSequence by value` value class.
- **The `Modifier.VALUE || Modifier.INLINE` correction** at all three classification sites
  (`NugetProcessor.kt:210`, `:217`, `ForwardBridgeTypeClassifier.kt:78`), preferably behind one
  shared `isValueClass()` helper, guarded by the cross-module value-class fixture cell.
- The two `Flow` element-type sites, simple-name to qualified-name emission (see correction note in
  Context; the ADR originally miscounted these as five).
- Existing `mapPackageToNamespace` namespacing, unchanged.

**Deferred, each needing its own decision:**
- **Supertype edges.** An admitted class implementing an admitted interface does not gain `: IFoo` in
  C#; interfaces are only exported when independently reachable. Interacts with ADR-040 (interface
  return types), which is still open.
- **Cross-module generic classes.** `dep.Generic<T : Any>` resolves fully (verified) but routes to
  the `SpecializedProtocol` generic legacy route, which was never exercised across a module boundary.
  Skipped with the existing diagnostic in v1.
- **Cross-module `suspend` members and `Flow`-returning members on an admitted type.** The `SUSPEND`
  modifier survives (verified), but the suspend/Flow legacy routes generate scope helpers assuming
  in-module ownership. Roots may return `Flow<DepType>`; an admitted dep type's own suspend members
  are skipped in v1.
- **`export(project(":dep"))` DSL** (Alternative 2) as an explicit admission source alongside package
  prefixes. **Explicitly out of scope for this feature** (decided with the human): the admission
  boundary is the ADR-063 package predicate and nothing else. Build no part of this now.
- **`flattenPackage`-style namespace aliasing** for an admitted dependency package (Swift Export
  precedent).
- **A file-based closure manifest**, folded into ADR-064's deferred shared structured-report item.
- **The duplicate-type-across-two-packages problem**, accepted as a known limitation here (see
  Consequences for the accepted trade-off and the Kotlin/Native framework-export precedent). Gets its
  own ROADMAP item, which should link to that bullet.
- **Java/JVM-origin declarations.** Not applicable to the Kotlin/Native forward target today; if a
  dependency ever surfaces `Origin.JAVA_LIB` declarations (verified to occur even on a native target
  for `Enum.compareTo`), they are out of scope.

## Inferred vs Verified claims in this ADR

**Verified by spike this session** (scratch Gradle project, Kotlin 2.4.0 / KSP 2.3.9,
`:lib:kspKotlinMacosArm64` against a real klib dependency; outputs quoted in the Verification
section):
- `getAllFiles()` returns only the module's own files.
- `getClassDeclarationByName`, return-type resolution and **type-argument** resolution all reach a
  dependency-klib declaration, yielding primary-constructor parameters (names, types, nullability),
  declared properties with visibility, declared functions with parameters and returns, type
  parameters with bounds, supertypes, `getSealedSubclasses()`, and enum entries.
- `getDeclarationsFromPackage("dep")` returns **empty** for a klib dependency.
- `containingFile == null`, `location == NonExistLocation` for cross-module declarations.
- A cross-module `value class` reports `Modifier.INLINE`, **not** `Modifier.VALUE`; an in-module one
  reports `VALUE`.
- Every member of a cross-module declaration has `origin == KOTLIN_LIB`; in-module synthetic members
  are `SYNTHETIC`.
- Cross-module interface **delegation** (`CharSequence by value`) is indistinguishable from a declared
  member by both `origin` and `parentDeclaration`; genuine supertype inheritance is distinguishable by
  `parentDeclaration`.
- `internal` dependency declarations are resolvable and report `vis == INTERNAL`.
- Modifier sets are complete cross-module and sparse in-module.
- A `@CName` export referencing a dependency-module type links into the `sharedLib` and appears in
  `nm -gU` output, with only `implementation(project(":dep"))`.

**Verified in repository source, read this session:**
- `NugetProcessor.kt:159` (`getAllFiles()` choke point) and the ADR-063 predicate above it.
- `ForwardBridgeTypeClassifier.kt:97-102` (out-of-export-set → `Unsupported`), `:78`
  (`Modifier.VALUE` test), `:63-76` (the enum branch's qualified `global::` emission).
- `NugetProcessor.kt:210`, `:217` (`Modifier.VALUE` partitioning).
- The two simple-name `Flow` element-type sites (`CirClassTranslator.kt:149`, `:458`); `:725`,
  `:743`, `:1063` share the same three-line shape but are `List`/`Set` element-type mapping, not
  `Flow` (see correction note in Context).
- `mapPackageToNamespace` in-root and out-of-root behaviour (`CirTypeMapping.kt:111-133`).
- `Tier1Harness.kt:81` accepts a `libraries` list.
- ADR-063's stated forward-compatibility framing and ADR-064's subset table and filter rule.

**Inferred (documentation or reasoning, not executed). An implementing agent should check these
first:**
- **A change in a dependency module's klib invalidates the `kspKotlin{Target}` task and re-runs the
  processor.** The processor already uses `Dependencies(aggregating = true)`
  (`NugetProcessor.kt:269`, verified), and cross-module declarations cannot be added to `Dependencies`
  because `containingFile` is null (verified). Whether Gradle's task-level classpath input alone
  guarantees re-generation on a dependency change is **not spiked**. If it does not, a stale
  `Interop.cs` survives a dependency edit and the symptom looks exactly like the stale-state class of
  bug CLAUDE.md warns about. **Check this first with a two-build edit test.**
- Kotlin framework `export()` / `transitiveExport` semantics and the `api`-dependency requirement
  (Kotlin docs).
- Swift Export's `export(project) { moduleName; flattenPackage }` and package→nested-enum mapping
  (Kotlin docs).
- ObjC export's all-public-leaves + `@HiddenFromObjC` opt-out (Kotlin docs).
- That the generic, suspend and `Flow` legacy routes behave identically for an admitted cross-module
  declaration as for an in-module one. **Not spiked**, and the reason all three are deferred in Scope
  rather than claimed to work.
- That the data-class path handles a cross-module `data class`'s `copy` / `componentN` correctly.
  The *origin* half of this concern is now **resolved and verified**: `ForwardCallablePlanner.kt:261`
  and `:306` are the only two `Origin.`-keyed sites in `nuget-processor`, and both are value-class
  paths, so no data-class handling keys on `SYNTHETIC`. What remains inferred is that the data-class
  path's *other* assumptions (e.g. recovering component order from the primary constructor) hold for
  a klib-sourced declaration; the primary constructor is verified to be fully readable, so this is
  low-risk but unproven end-to-end.
