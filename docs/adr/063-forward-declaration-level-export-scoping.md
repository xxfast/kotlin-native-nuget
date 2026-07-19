# ADR-063: Forward declaration-level export scoping: an explicit package include/exclude filter in `publish {}`

## Status

Accepted

## Context

`NugetProcessor.process()` takes `resolver.getAllFiles()` with no scoping filter
(`NugetProcessor.kt:118-121`) and bridges **every** public declaration in the module. There is no
notion of an *export set*. Everything downstream (functions, classes, objects, properties, the
`ForwardCallablePlanner` catalog) derives from the single `allDeclarations` list built there, whose
only filter today is "drop the generated `...nuget.generated` package".

`rootPackage` in the `nuget { publish { } }` DSL (`NugetPublishConfig.kt`) *looks* like the scoping
knob but is not one. It only feeds `mapPackageToNamespace` (`CirTypeMapping.kt:101`), which renames
the C# namespace while the declaration set stays global, and that function has an explicit `else`
branch (`CirTypeMapping.kt:110-112`) that maps a package *outside* `rootPackage` anyway. So a
package outside `rootPackage` is still bridged, just under its own name.

**Consequence.** Applying the plugin to a module that also holds unrelated public API tries to
bridge that API too. Any construct the forward direction cannot express there becomes a build break
in generated code the user never wrote, a flat contradiction of GOALS.md #1 ("plug and play").
The first real consumer to hit this was NYTimes-KMP (its BUG-003), which works around it with a
dedicated `:app:windows` export module whose `build.gradle.kts` carries a `TODO` explaining why the
module exists at all.

### What this ADR is and is not

MVP.md deliberately **demotes MF-001 from a crash-avoider to ergonomics**: "once value-class
parameters are fixed and unsupported declarations are skipped rather than mis-generated, module
isolation is ergonomics rather than a workaround for a build break." So the scoping mechanism does
**not** have to solve crash-avoidance. It solves: *let the user apply the plugin to a module that
holds more than the export surface, and bridge only the intended subset.*

MVP.md also frames MF-001 and MF-003 (transitive model export) as "the same missing idea seen from
opposite sides: there is no notion of an export set... Both resolve to defining the export set as a
reachability closure from explicit roots." This ADR weighs that framing (see Alternative 3) and
**deliberately does not build the closure now**, because MF-003 is cut from MVP and sequenced
*after* forward diagnostics and the value-class-parameter fix. A closure that pulls types across
module boundaries today would re-arm the exact constructs module isolation currently hides.

### The plumbing that already exists

Forward config already flows DSL → KSP option → processor:

- `NugetPlugin` reads `pub.rootPackage` / `pub.packageId` and passes them as KSP args
  `nuget.rootPackage` / `nuget.namespace` (`NugetPlugin.kt:245-248`).
- `NugetProcessorProvider.create()` reads `environment.options[...]` into `NugetContext`
  (`NugetProcessorProvider.kt:10-15`).
- `NugetProcessor` holds `context: NugetContext` and can read a new field with no new wiring.

A package include/exclude list flows the same way. KSP options are `String → String`, so a list is
joined with a delimiter on the plugin side and split on the processor side (package names contain no
comma, so `,` is a safe delimiter). **Verified in source:** all three sites above were read.

### The reverse direction already answered "which declarations cross"

The reverse bridge has a per-package namespace filter: `bind { include(...); exclude(...) }`
(`NugetBindConfig.kt`), threaded per-package (ADR-044) and applied at the reader CLI (ADR-047). Its
predicate, `IsNamespaceIncluded` (`NugetMetadataReader/Program.cs:1591-1601`), is:

```
exclude wins:      any exclude prefix matches → excluded
empty include:     included (all)
non-empty include: included iff some include prefix matches
prefix match:      ns == p || ns.StartsWith(p + ".")
```

**Verified in source.** The forward side should mirror this predicate exactly, over Kotlin package
names instead of C# namespaces.

## Alternatives Considered

### 1. Explicit package include/exclude filter in `publish {}` (chosen)

Add `include(vararg String)` / `exclude(vararg String)` to `NugetPublishConfig`, mirroring
`NugetBindConfig` verb-for-verb, taking Kotlin package prefixes. Apply the same
`IsNamespaceIncluded` predicate to each declaration's package at the `allDeclarations` choke point.

```kotlin
nuget {
  publish {
    packageId = "Contoso.Api"
    rootPackage = "com.contoso.api"
    include("com.contoso.api")            // only bridge this subtree
    exclude("com.contoso.api.internal")   // ...minus internals
  }
}
```

**Default (chosen):** when no explicit `include` is given, the effective include set is derived
from `rootPackage` (see the Decision section). This makes `rootPackage` the scoping knob users
already assume it is. When neither `include` nor `rootPackage` is set, the set is empty and
everything is bridged (today's behaviour). This is a deliberate departure from the additive-only
default the reverse side uses; see Consequences for the accepted behaviour-change risk.

**Pros:**
- Exact mirror of the reverse `bind { include/exclude }` the repo already ships (ADR-044/047), same
  verbs, same prefix semantics, same "empty include = all" default. One mental model for both
  directions.
- Threads through the existing `rootPackage` plumbing with no new task, no new file, no new
  contract. The filter is a `List<String>` on `NugetContext`.
- Single choke point: applied once at `allDeclarations`, so every downstream category (classes,
  objects, properties, the planner catalog) is scoped for free.
- Package granularity matches how an export module is actually organised (a package or package
  subtree is the natural unit), and matches how ObjC/Swift/framework export scope (module/DSL
  granularity, not per-declaration). See prior art below.
- Forward-compatible with MF-003: the include set is exactly the "explicit roots" a future
  reachability closure would seed from, so this seeds that design rather than pre-empting it.

**Cons:**
- The user must configure it to get isolation; nothing is inferred. (Mitigated: it is one line, and
  the alternative, inferring from `rootPackage`, is a silent behaviour change, see Alt 3.)
- Prefix filtering is coarser than per-declaration control. Acceptable: the goal is module
  isolation, not fine-grained curation, and `@HiddenFromObjC`-style per-declaration opt-out can be
  layered on later without conflict.

### 2. A per-declaration annotation (`@NugetExport` opt-in, or `@NugetExclude` opt-out)

Gate export on an annotation, either opt-in (nothing leaves unless annotated, like `@JsExport`) or
opt-out (everything public leaves unless hidden, like `@HiddenFromObjC`).

**Pros:**
- Per-declaration precision.
- Precedented: JS export is opt-in per-declaration; ObjC/Swift export is opt-out per-declaration.

**Cons:**
- Opt-in contradicts GOALS.md #1 ("just add the plugin"): the user must annotate every exported
  declaration, which is ceremony. MVP.md already rejected an `@ExperimentalNugetApi` opt-in on the
  same "ceremony for a 0.x badged-experimental plugin" grounds.
- Opt-out does not solve the stated problem: unrelated public API in the same module still gets
  bridged unless the user finds and annotates every piece of it, the same manual burden as today,
  just spread across declarations instead of one build-script block.
- An annotation is a source dependency the export module must take on
  (`io.github.xxfast...:annotations`), which the package-filter approach avoids entirely.
- Asymmetric with the reverse direction, which uses a package filter, not an annotation.

**Rejected** for v1. A per-declaration opt-out annotation is a reasonable *future* refinement
layered *on top of* the package filter (mirroring `@HiddenFromObjC` sitting on top of module
visibility), and is noted as deferred.

### 3. An "effective package filter" derived from `rootPackage`

Overload `rootPackage`: when set, bridge only packages under it; when unset, bridge everything.

**Pros:**
- Zero new DSL surface. Most users already set `rootPackage`, so isolation would come "for free".
- Aligns `rootPackage` with the intuition MVP.md notes users already have ("looks like the scoping
  knob").

**Cons:**
- **Silent behaviour change.** `mapPackageToNamespace` today explicitly maps packages *outside*
  `rootPackage` (`CirTypeMapping.kt:110-112`); making `rootPackage` also scope would silently stop
  bridging them. Any existing consumer relying on that else-branch breaks with no diagnostic.
- Conflates two orthogonal concerns: namespace *renaming* (what `rootPackage` does) and export
  *scoping* (what this ADR adds). A user who wants to bridge two unrelated package trees under one
  C# namespace root can no longer do so.
- No way to express "bridge this subtree but exclude one internal package under it": the exclude
  half has no home.

**Rejected** as the *mechanism*, but **adopted as the default** layered under Alternative 1: "if no
explicit `include`, default the include set to `rootPackage`". This is a deliberate product call
(decided with the human) that `rootPackage` should be the scoping knob users already assume it is,
accepting the silent-behaviour-change risk on a 0.x experimental plugin. See the Decision section
for the effective-include logic and Consequences for the accepted risk. What stays rejected is
letting `rootPackage` be the *only* mechanism: `include`/`exclude` remain the explicit override, so
"bridge two unrelated trees" and "bridge this subtree minus one internal package" both stay
expressible.

### 4. Reachability closure from explicit roots now (the MF-003-unified framing)

Define the export set as the transitive reachability closure from explicit root declarations,
solving MF-001 and MF-003 together.

**Pros:**
- One mechanism for both "too broad" (unrelated API) and "too narrow" (a referenced type one module
  away is never exported).
- The end-state MVP.md gestures at.

**Cons:**
- MF-003 is **cut from MVP** and explicitly sequenced *after* forward diagnostics and the
  value-class-parameter fix (MVP.md "Cut from MVP", the sequencing trap). Building the closure now
  would drag types across module boundaries and re-arm the exact constructs module isolation hides
  today (a `CharSequence`-delegating value class, a generic suspend extension).
- Much larger surface (cross-module symbol resolution, root declaration syntax, closure walk) for a
  problem MVP.md classifies as *ergonomics*.

**Rejected for now, but not contradicted.** Alternative 1's include set is the seed-roots half of
this closure. When MF-003 lands, reachability expands *from* the include set rather than replacing
it. This ADR is the "choose that framing" step MVP.md asks for: the roots are packages, declared in
`publish {}`, and MF-003 generalises "the packages named here" to "everything reachable from the
declarations in the packages named here".

## Prior art (all inferred from documentation, not verified against a toolchain)

- **Kotlin/Native Apple framework export (`export()` / `transitiveExport`).** The scoping unit is
  the *dependency module*, not the declaration: a framework exports the module's own public API plus
  explicitly `export()`-ed `api` dependencies; `transitiveExport = true` pulls the full transitive
  closure. Inferred from
  [Build final native binaries](https://kotlinlang.org/docs/multiplatform/multiplatform-build-native-binaries.html).
  This is the **MF-003 (transitive)** analogue, and it confirms the split: "which of *my*
  declarations leave" and "do I pull in *dependencies*" are separate knobs, the latter opt-in.
- **ObjC/Swift export.** Everything public in the exported module leaves; the per-declaration knobs
  are `internal` visibility and the opt-out `@HiddenFromObjC` annotation. There is no per-declaration
  *opt-in* for the module-scoping question. Inferred from
  [Interop with Swift/Objective-C](https://kotlinlang.org/docs/native-objc-interop.html) and
  [HiddenFromObjC](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.native/-hidden-from-obj-c/).
- **JS export.** The inverse: `@JsExport` is opt-in per-declaration, nothing leaves unless
  annotated. Inferred from
  [JsExport](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.js/-js-export/). This is
  Alternative 2's opt-in variant in the wild.
- **Reverse direction of this project (ADR-044, ADR-047).** Package/namespace include/exclude with
  prefix matching, "empty include = all" default. **Verified in source** (`NugetBindConfig.kt`,
  `Program.cs:1591-1601`). This is the precedent Alternative 1 mirrors.

Takeaway: the ecosystem answers "which declarations leave the module" at **module/DSL granularity**
with an optional per-declaration opt-out, and answers "pull in dependencies" as a **separate opt-in**
knob. Alternative 1 matches the first; MF-003 is the second.

## Decision

Adopt **Alternative 1: an explicit package include/exclude filter in `publish {}`**, mirroring the
reverse `bind { include/exclude }` semantics exactly.

### DSL

```kotlin
nuget {
  publish {
    packageId = "Contoso.Api"
    rootPackage = "com.contoso.api"
    include("com.contoso.api")            // whitelist of package prefixes; empty = all
    exclude("com.contoso.api.internal")   // applied after include; exclude wins
  }
}
```

`NugetPublishConfig` gains, copied verb-for-verb from `NugetBindConfig`:

```kotlin
private val _include = mutableListOf<String>()
private val _exclude = mutableListOf<String>()
val include: List<String> get() = _include.toList()
val exclude: List<String> get() = _exclude.toList()
fun include(vararg packages: String) { _include.addAll(packages) }
fun exclude(vararg packages: String) { _exclude.addAll(packages) }
```

`include`/`exclude` as flat functions on `publish {}` (not a nested `export {}` block), because
`publish {}` is already the single forward-config unit, the forward analogue of one `bind {}`, so
no extra grouping is warranted. (A nested `export {}` block was considered for closer visual symmetry
with reverse but adds a level of nesting for no grouping benefit.)

### Bridge mechanism / plumbing

1. **Plugin → KSP options.** In `NugetPlugin` alongside the existing `nuget.rootPackage` arg
   (`NugetPlugin.kt:245-248`), join the lists and pass:
   ```kotlin
   argMethod.invoke(ksp, "nuget.includePackages", (pub?.include ?: emptyList()).joinToString(","))
   argMethod.invoke(ksp, "nuget.excludePackages", (pub?.exclude ?: emptyList()).joinToString(","))
   ```
   Comma is safe: a Kotlin package identifier contains no comma.
   **Inferred (not spiked): KSP passes an empty-string option through to
   `environment.options` as `""` rather than dropping the key.** The processor guards this by
   treating blank as empty (`.split(",").filter { it.isNotBlank() }`), so the exact behaviour is not
   load-bearing.

2. **Provider → context.** In `NugetProcessorProvider.create()`:
   ```kotlin
   includePackages = environment.options["nuget.includePackages"]
     ?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
   excludePackages = environment.options["nuget.excludePackages"]
     ?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
   ```
   Add `includePackages: List<String>` / `excludePackages: List<String>` to `NugetContext`
   (`CirTranslator.kt:24`).

3. **Single choke point.** In `NugetProcessor.process()`, extend the existing `allDeclarations`
   filter (`NugetProcessor.kt:118-121`) with one predicate, mirroring `IsNamespaceIncluded`. The
   effective include set is the explicit `includePackages` when non-empty, else `[rootPackage]` when
   `rootPackage` is set, else empty (= all):
   ```kotlin
   val effectiveInclude: List<String> = when {
     context.includePackages.isNotEmpty() -> context.includePackages
     context.rootPackage.isNotBlank() -> listOf(context.rootPackage)
     else -> emptyList()
   }

   fun isPackageExported(pkg: String): Boolean {
     fun matches(p: String) = pkg == p || pkg.startsWith("$p.")
     if (context.boundPackages.any(::matches)) return true   // reverse-bound: always in scope
     if (context.excludePackages.any(::matches)) return false
     if (effectiveInclude.isEmpty()) return true
     return effectiveInclude.any(::matches)
   }

   val allDeclarations = resolver.getAllFiles()
     .flatMap { it.declarations }
     .filter { it.packageName.asString() != "io.github.xxfast.kotlin.native.nuget.generated" }
     .filter { isPackageExported(it.packageName.asString()) }
     .toList()
   ```
   Explicit `include` overrides the `rootPackage` default entirely (so a user can bridge packages
   outside `rootPackage`); `exclude` always applies on top of whichever include set is effective.

### Reverse-bound packages are always in scope (correctness, not ergonomics)

A module that both **publishes** forward and **consumes** a NuGet dependency via `bind {}` receives
the reverse-generated Kotlin stubs (ADR-048) as ordinary sources in `getAllFiles()`, in the *bound*
Kotlin packages (the `bind` aliases / `packageName`), which sit **outside** the forward
`rootPackage`. The module's own forward code routinely returns those bound types (e.g.
`test-library`'s `fun catMoodRoundTrip(): CatMood`, where `CatMood` is bound from `TestDependency`
into package `test.enums`). The forward direction re-projects such a return into a new C# type
(`TestLibrary.Test.Enums.CatMood`), so the bound stub **must** stay in the export set or the
generated C# references a namespace that was never declared and the consumer build breaks.

Therefore an include-based filter, the `rootPackage`-derived default *or* an explicit
`include(...)`, must never drop a reverse-bound package. The predicate checks bound membership
**first**, before `exclude` and before the include test: a bound package is unconditionally
exported. This is discovered, not hypothetical: it is exactly the `test-library` round-trip and it
broke `scripts/verify.sh` on the first cut of this feature (12 × `CS0234` on
`TestLibrary.Test.Enums`).

The bound packages are known from config alone, no RIR extraction needed. For each `bind {}` the
processor is told the superset of packages its stubs can land in, mirroring `kotlinPackage()`'s
resolution order (`NugetGenerateBindingsTask.kt:66-73`): the `alias` values, the `packageName`, and
the sanitised `packageId` fallback (`id.lowercase().replace('-', '_')`). The plugin joins that set
into a third KSP option, `nuget.boundPackages`, threaded exactly like the include/exclude options.

Everything downstream derives from `allDeclarations`, so classes, objects, properties, extension
functions, the `ForwardCallablePlanner` catalog (`NugetProcessor.kt:243`), the `@CName` exports and
the `Interop.cs` translation are all scoped by this one filter with no per-category changes.

**Mechanism claims labelled:** the choke-point location, the downstream derivation, and the existing
`rootPackage` threading are **verified in source** (read directly). The reverse predicate copied is
**verified in source**. The KSP empty-option passthrough is **inferred** and made non-load-bearing
by the blank-filter guard.

### Interaction with forward unsupported-declaration diagnostics (P1)

The forward diagnostics item (MVP.md P1, the mirror of ADR-043) will warn about **in-scope**
declarations the planner cannot express. An **out-of-scope** declaration must **not** trigger such a
warning: the user did not ask to export it, so telling them it is "unsupported" is noise.

This falls out for free from the choke-point placement. The filter runs at `allDeclarations`
(`NugetProcessor.kt:118`), **before** the `ForwardCallablePlanner` runs (`NugetProcessor.kt:243`)
and before `warnDroppedForwardCallables` (`NugetProcessor.kt:255`). An out-of-scope declaration
never enters the planner, so it can never appear in `catalog.droppedCallables` and can never be
warned about. The two features compose correctly by ordering alone, with no cross-coupling.
**Verified in source:** the planner and `warnDroppedForwardCallables` both consume the catalog built
from the already-filtered `allDeclarations`.

## Consequences

- **New DSL surface:** `include`/`exclude` on `NugetPublishConfig`.
- **`rootPackage` now scopes, not just renames.** When set with no explicit `include`, only packages
  under `rootPackage` are bridged. This is a **behaviour change**: an existing consumer that sets
  `rootPackage` and relies on `mapPackageToNamespace`'s else-branch to bridge out-of-root packages
  will silently stop bridging them. Accepted deliberately (decided with the human) on a 0.x
  experimental plugin, where `rootPackage`-as-scope matches user intuition and the explicit
  `include` override recovers the old reach for anyone who needs it. A consumer with **no**
  `rootPackage` and no `include`/`exclude` is unaffected (still bridges everything).
- **Three new KSP options** (`nuget.includePackages`, `nuget.excludePackages`, `nuget.boundPackages`)
  and **three new `NugetContext` fields**. Threaded through the exact path `rootPackage` already uses.
- **Reverse-bound packages are always exported**, checked before `exclude`/`include`, so a module
  that both publishes forward and consumes NuGet keeps compiling under scoping. See the dedicated
  section above.
- **One new predicate** at the `allDeclarations` choke point. No per-category export code changes.
- **Out-of-scope declarations are silently not bridged** and, by ordering, never trigger the
  forward "unsupported" diagnostic. This is the intended interaction.
- **NYTimes-KMP's `:app:windows` workaround becomes optional:** it can apply the plugin to a module
  holding unrelated API and scope with `include(...)`, though keeping a dedicated module remains
  valid.
- **Does not solve MF-003.** A type in a *dependency* module is still not exported. This filter
  operates only within the module's own `getAllFiles()` domain. Deferred by design.
- **Does not pre-empt MF-003's reachability closure.** The include set is the seed-roots half of
  that future design; reachability will expand from it rather than replace it. This ADR chooses the
  roots-are-packages framing so MF-003 can generalise it, per MVP.md's "choose that framing".
- **Deferred:** a per-declaration opt-out annotation (`@HiddenFromObjC` analogue); a `rootPackage`-
  derived default for `include` (silent-behaviour-change risk); the full reachability closure
  (MF-003, and only after forward diagnostics + the value-class-parameter fix land).

### Scope

**In v1:**
- `include`/`exclude` package-prefix filter on `publish {}`, prefix semantics identical to the
  reverse side (exclude wins; `pkg == p || pkg.startsWith("$p.")`).
- `rootPackage`-derived default include set: when no explicit `include`, scope to `rootPackage`.
  Empty `include` **and** empty `rootPackage` = all (today's behaviour).
- Applied to the module's own declarations only, at the single `allDeclarations` choke point.

**Deferred:**
- Per-declaration opt-out annotation.
- Cross-module reachability closure (MF-003), sequenced after forward diagnostics and the
  value-class-parameter fix.
