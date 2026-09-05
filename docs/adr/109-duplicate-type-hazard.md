# ADR-109: Forward, duplicate-type hazard across two published packages: a build-time warning keyed on every other publisher's export scope, delivered as a lazy KSP option Provider, never a shared models package, never a new DSL verb

## Status
Accepted

## Context

[ADR-066](../../docs/adr/066-forward-export-reachability-closure.md) made the forward export set a
reachability closure: a dependency-module type reachable from a publishing module's API is admitted
and generated *into that module's package*, as its own C# class over its own opaque handle. Its
Consequences accepted, with the human, that two publishing modules admitting the same dependency type
each declare a copy (`TestLibrary.Models.TopStory` and `OtherLib.Models.TopStory`) with no conversion
and no diagnostic, because neither KSP run can see the other. The ROADMAP item
(`docs/backlog/duplicate-type-hazard-across-two-published-packages.md`) carries three undecided
shapes: (1) a shared models NuGet both publishers depend on, (2) a diagnostic when an admitted type's
origin module is itself published, (3) a DSL opt-out forcing the type back to
`SKIPPED_UNEXPORTED_DEPENDENCY_TYPE`. The phase is being closed; one shape must ship.

### Shape (1) is not a remedy, and the hint must not name it

Every claim labelled.

- **Verified** (`NugetPlugin.kt:292-356`): `packNuget` packs the module's *own* `sharedLib` output
  per RID. Two published modules are two native libraries in the consumer's process.
- **Inferred** (Kotlin/Native runtime model, not spiked): each native library carries its own Kotlin
  runtime and heap; an ADR-003 `StableRef` handle minted inside `TestLibrary`'s runtime is
  meaningless to `TestModels`'s exports. So `TestLibrary` can never hand a consumer a
  `TestModels.Models.TopStory`: the C# wrapper would P/Invoke the wrong library with a foreign
  handle. The Kotlin docs state the same limit for frameworks, verbatim: *"Usage of several
  Kotlin/Native frameworks in a Swift application is limited, but you can create an umbrella
  framework and export all these modules to it"*
  ([Build final native binaries](https://kotlinlang.org/docs/multiplatform/multiplatform-build-native-binaries.html),
  Inferred from documentation).
- Therefore the only structural remedies are: **exactly one publisher declares the type**, either an
  umbrella module that depends on both and is the sole `publish {}` (the docs' remedy), or
  `exclude("<pkg>")` in this module so it stops admitting it and accepts
  `SKIPPED_UNEXPORTED_DEPENDENCY_TYPE` on the callables that reach it. ADR-066's Consequences bullet
  is amended to retract shape (1) when this ADR is accepted.

### Two scenarios, one mechanism

| Case | Situation | What A's build can know | Certainty |
|---|---|---|---|
| **D** | A publishes, depends on M, **M also publishes**, A admits `M.Foo` | M is a publisher in the build with `publish { rootPackage / include / exclude }` | **Certain** modulo M's own ADR-064 skips of `Foo`: M exports every public in-scope file by provenance (ADR-063), so `Foo` is in M's package iff M's predicate covers it and M could bridge it |
| **S** | A and B both publish, both depend on **non-publishing** M, both admit `M.Foo` (the ROADMAP headline) | B is another publisher in the build with its own predicate | **Heuristic**: whether B *reaches* `Foo` is only known to B's KSP run; a warning means "B will declare its own copy if its API reaches it" |

Both reduce to the same question the processor can answer: *is there another publisher in this build
whose export scope covers the package of a type my closure admitted?* So both ship under one
mechanism, with one message that is exact for D and hedged for S.

### What the processor can see (all Verified)

- Cross-module declarations: `containingFile == null`, `origin == KOTLIN_LIB`, no API naming the
  klib (ADR-066 spike; `ForwardReachabilityClosure.kt:164-182`). Admission is
  `PUBLIC && crossModuleAdmissionAllowed && isExported(decl)`: a **package-prefix** test and nothing
  else. So a publisher can only be matched to an admitted type **by package**, using that
  publisher's own predicate.
- The manifest line `INFO_EXPORTED_FROM_DEPENDENCY` is emitted once, `symbol = null`, right after
  `reachability.admitted` is built (`NugetProcessor.kt:463-479`). That is the insertion point.
- `isExported` (`NugetProcessor.kt:258-277`) is the ADR-063 predicate: `boundPackages` first,
  `exclude` wins (package prefix or qualified declaration, #53), empty include = all, else prefix
  match on the effective include (`includePackages` if non-empty, else `[rootPackage]`, `:252-256`).
- `ForwardDiagnostic.format()` picks the verb from severity: WARNING -> "Skipping"
  (`ForwardDiagnostic.kt`). This warning skips nothing.
- `NugetReportDiagnosticsTask` carries `kind` as an untyped string
  (`NugetReportDiagnosticsTask.kt:70-71, 114-115`): a new kind reaches `NugetDiagnostics.json` and
  the Gradle re-emit (ADR-100) with no plugin change.
- `NugetContext.rootNamespace` is `nuget.namespace`, which the plugin sets to `pub.packageId`
  (`NugetProcessorProvider.kt`, `NugetPlugin.kt:272`). So the processor knows its own NuGet id.

### What the plugin can see

- **Verified**: KSP args are set in `project.afterEvaluate` via reflection on
  `KspExtension.arg(String, String)`, as flat comma lists (`NugetPlugin.kt:232-289`);
  `nuget.boundPackages` (`:259-277`) is the precedent for lowering a Gradle-side concept to a package
  list the ADR-063 predicate understands. The plugin already reads across projects
  (`project.findProject(":nuget-processor")`, `:222`). `NugetExtension.publish` and
  `NugetPublishConfig.{packageId, rootPackage, include, exclude}` are plain readable properties.
- **Verified** (javap on the cached `symbol-processing-gradle-plugin-2.3.10.jar`, `KspExtension`,
  by the coordinator): `public void arg(String, Provider<String>)` exists alongside
  `arg(String, String)`; `apOptions` is a `MapProperty<String, String>`;
  `KspGradleSubplugin.applyToCompilation` returns `Provider<List<SubpluginOption>>`. A KSP option
  can therefore be a **lazy Provider** whose body runs when KSP resolves its options, not at
  `afterEvaluate`.
- **Verified**: `gradle.properties` enables configuration cache and does **not** enable project
  isolation. Gradle is 9.1.0 (wrapper). The plugin compiles against `kotlin("gradle-plugin")`.
- **Inferred**: cross-project extension reads at configuration time are legal under configuration
  cache (project isolation is the stricter, unset, opt-in).
- **Inferred, the residual claim this design rests on**: KGP/KSP resolve the option Provider only
  **after every project in the build is evaluated** (at task configuration or execution). If it is
  resolved earlier, a publisher evaluated later than the reader is missing from the value and its
  duplicates go unwarned (silent no-op, not wrong output). See Decision §6 for what proves it.
- **Verified by reasoning, and the reason the eager design is rejected**: an eager cross-project
  read inside `afterEvaluate` needs `project.evaluationDependsOn(other)` to see a not-yet-evaluated
  publisher (Gradle evaluates siblings alphabetically by default, Inferred, so `:test-library`
  precedes `:test-models`); two publishers each doing that to the other is
  `A.afterEvaluate -> evaluationDependsOn(B) -> B.afterEvaluate -> evaluationDependsOn(A)` while A
  is still configuring, the classic `CircularReferenceException` (Inferred). The Provider design
  never calls `evaluationDependsOn`, so the circularity does not apply.

## Alternatives Considered

### 1. Lazy `Provider<String>` KSP option listing every publisher's export scope; processor warns by package (chosen)

The plugin registers `nuget.publishedScopes` as a `Provider<String>` (reflection on
`arg(String, Provider)`). When resolved, the body walks `project.rootProject.allprojects`, keeps
those with this plugin applied and `publish != null`, and encodes each one's ADR-063 predicate,
**including the current project itself**. The processor skips the entry whose `packageId` equals its
own `nuget.namespace` and warns for every admitted type whose package another publisher's scope
covers. Nothing is skipped; generated output is unchanged.

Pros: one mechanism for D and S; no new DSL; no `afterEvaluate` ordering or circularity, because the
walk runs after configuration; the only thing crossing to the processor is a package predicate it
already understands; ADR-100 delivery is free; including self means the existing single-publisher
real build (`scripts/verify.sh --plugin`, `test-library`) exercises the plumbing end to end.
Cons: S is a heuristic ("may duplicate") with no off-switch beyond `exclude(...)`; the Provider
resolution timing is Inferred (Decision §6); reflection on a second `arg` overload.

### 2. Eager cross-project read in `afterEvaluate` with `evaluationDependsOn`

Rejected: acyclic for D alone, but circular the moment two publishers read each other (Context), and
S is the ROADMAP headline. Recorded so nobody re-attempts it.

### 3. Post-KSP intersection task (precise for S)

A Gradle task per publisher depending on every other publisher's `kspKotlin{Target}`, reading both
`NugetDiagnostics.json` manifests plus a new processor-written root-export manifest, intersecting,
and warning precisely. Rejected for size: a new task type, cross-project task dependencies, a new
manifest, and build order decides which publisher warns. Named as the follow-up if S's heuristic
proves noisy.

### 4. A DSL opt-out (`publish { dontInline("...") }`, ADR-066 shape 3)

Rejected: `exclude("<pkg>")` already forces the type back to `SKIPPED_UNEXPORTED_DEPENDENCY_TYPE`
(**Verified**: `isExported` returns false on exclude, the closure then refuses admission,
`ForwardReachabilityClosure.kt:177`). A second verb for the same effect is a second thing to document.

### 5. A shared models NuGet (ADR-066 shape 1)

Rejected as not implementable: handles do not cross native libraries (Context).

### 6. Status quo

Rejected: the human closed the option of deferring.

## Decision

### 1. Plugin: register `nuget.publishedScopes` as a lazy Provider (`NugetPlugin.kt`, KSP-args block)

```kotlin
// ADR-109: the ADR-063 predicate of EVERY forward publisher in the build, this project included,
// lowered to packages because the processor can only match an admitted klib type by package.
// A Provider, not a String: its body runs when KSP resolves its options, after every project is
// evaluated, so no cross-project read happens inside afterEvaluate and no evaluationDependsOn
// (which would be circular between two publishers) is needed. The processor drops the entry whose
// packageId is its own nuget.namespace; self is listed so the single-publisher real build still
// exercises the plumbing.
fun NugetPublishConfig.encodeScope(): String {
  val include: List<String> = include.ifEmpty { listOfNotNull(rootPackage) }
  return listOf(packageId ?: "", include.joinToString("|"), exclude.joinToString("|")).joinToString(":")
}

val publishedScopes: Provider<String> = project.provider {
  project.rootProject.allprojects
    .mapNotNull { other -> other.extensions.findByType(NugetExtension::class.java)?.publish }
    .map { it.encodeScope() }
    .sorted()
    .joinToString(";")
}
val providerArg: Method = kspClass.getMethod("arg", String::class.java, Provider::class.java)  // Verified overload
providerArg.invoke(ksp, "nuget.publishedScopes", publishedScopes)
```

`findByType` on a project without the plugin returns null, so non-publishing modules (`:test-models`,
`:nuget-processor`) contribute nothing. Sorted for a stable configuration-cache input.

### 2. Encoding

Entries `;`, fields `:`, lists `|`: `<packageId>:<include1|include2>:<exclude1|exclude2>`.

- `include` is the publisher's *effective* include, exactly what `effectiveInclude` computes
  (`NugetProcessor.kt:252`, Verified): the explicit `include(...)` list when non-empty, else
  `[rootPackage]`.
- No relation field: the processor does not need to know whether the other publisher is a
  dependency or a sibling; the message is written to be true for both.
- Empty include (publisher with neither `rootPackage` nor `include`) means "all its own files",
  which cannot be turned into packages: encode empty; the processor treats it as unknown and stays
  silent. Documented gap.
- Delimiters are safe: Kotlin package names are `[A-Za-z0-9_.]`, NuGet IDs `[A-Za-z0-9._-]`
  (**Inferred** for the NuGet grammar). The processor rejects an entry with the wrong field count
  with a named error rather than guessing.
- `test-library` alone yields `TestLibrary:io.github.xxfast.kotlin.native.nuget.test:`.

### 3. Processor: parse, drop self, match, warn

- `NugetProcessorProvider.kt`: parse `nuget.publishedScopes` into
  `NugetContext.publishedScopes: List<PublishedScope>` (`CirTranslator.kt`), where
  `PublishedScope(packageId, include, exclude)` exposes `covers(decl: KSClassDeclaration)` built on
  the same `matches`/`matchesDeclaration` rules `isExported` uses (extract a shared predicate; do
  not copy). Entries with `packageId == context.rootNamespace` are dropped at parse time.
- `NugetProcessor.kt`, immediately after the `INFO_EXPORTED_FROM_DEPENDENCY` emission (`:479`):

```kotlin
reachability.admitted.values.forEach { cls ->
  context.publishedScopes.filter { it.covers(cls) }.forEach { scope ->
    emit WARNING_DUPLICATED_DEPENDENCY_TYPE(symbol = null,
      declaration = cls.qualifiedName!!.asString(), reason = ..., hint = ...)
  }
}
```

One line per (admitted type, covering publisher). `symbol = null` because a klib declaration has no
location (ADR-066, Verified). The type stays exported; nothing in the generated output changes.

> **Correction (post-implementation).** `PublishedScope.covers` takes `(packageName: String,
> qualifiedName: String?)`, not `(decl: KSClassDeclaration)` as drafted above: the caller
> (`NugetProcessor.kt`) already has both strings in hand from the admitted `KSClassDeclaration`, and
> passing the declaration itself would make `PublishedScope`/`PackageScope` depend on the KSP symbol
> API for no benefit. `PackageScope.covers` carries the same signature and is the one place the
> matching rule (`exclude` wins, by prefix or qualified name; empty include means "everything") is
> written, extracted rather than copied from `isExported` (`forward/ForwardPublishedScope.kt`).

### 4. New kind (`forward/ForwardDiagnostic.kt`, append after `SKIPPED_ALL_DECLARATIONS`)

```kotlin
/** ADR-109: an admitted dependency type another forward publisher's export scope also covers, so a
 *  consumer referencing both NuGets sees two unrelated C# types for one Kotlin type. Exported
 *  anyway; the remedy is structural (one publisher), not a skip. `verb` overrides the
 *  severity-keyed "Skipping", which would be false here. */
WARNING_DUPLICATED_DEPENDENCY_TYPE(ForwardDiagnosticSeverity.WARNING, verb = "Duplicating"),
```

`ForwardDiagnosticKind` gains `val verb: String? = null`; `format()` uses `kind.verb ?: <by severity>`.
Every existing kind is byte-identical.

### 5. Text (true for D and S)

```
w: [nuget:WARNING_DUPLICATED_DEPENDENCY_TYPE] Duplicating io.github.xxfast.kotlin.native.nuget.test.models.TopStory:
   the export closure admitted it from a dependency module, and the TestModels NuGet package's export
   scope (rootPackage/include "io.github.xxfast.kotlin.native.nuget.test.models") also covers it, so
   TestModels declares its own copy (certainly if it is one of TestModels' own types, otherwise
   whenever TestModels' API reaches it) and a consumer referencing both packages sees two unrelated C#
   types for one Kotlin type, with no conversion between them. Kotlin objects cannot cross between two
   native libraries, so export it from exactly one package: publish a single umbrella module that
   depends on both, or add exclude("io.github.xxfast.kotlin.native.nuget.test.models") to
   nuget { publish { } } here so only TestModels declares it (callables reaching it are then skipped
   with SKIPPED_UNEXPORTED_DEPENDENCY_TYPE)
```

### 6. Tests (the verification story, stated plainly)

There is **no two-publisher fixture** in the root build (Verified: `settings.gradle.kts` includes
`:nuget-processor`, `:test-models`, `:test-library` only; `:test-models` does not apply the plugin).
Proof is the sum of three things:

- **Plugin, `NugetPluginPublishedScopesWiringTest.kt`** (ProjectBuilder; mirrors
  `NugetPluginKspArgsWiringTest.kt:24-66`): a root, child `models` (KMP, `mingwX64`, plugin NOT
  applied: the ADR-066 dependency shape), children `lib-a` and `lib-b` (plugin applied, `mingwX64 {
  binaries { sharedLib {} } }`, `publish { packageId = "LibA"/"LibB"; rootPackage = "com.acme";
  include("com.acme", "com.acme.models") }`, `implementation(project(":models"))`). Evaluate all
  four; read `lib-a`'s `KspExtension.getArguments()`; assert `nuget.publishedScopes` lists **both**
  `LibA:com.acme|com.acme.models:` and `LibB:com.acme|com.acme.models:` and nothing for `models`.
  Second cell: a lone publisher yields exactly its own entry. **Inferred until this runs**: that
  `getArguments()` exposes a Provider-registered option resolved (via `apOptions.get()` or
  equivalent). If it does not, assert through `apOptions` reflectively instead.
- **Processor, `Tier1DuplicatedDependencyTypeTest.kt`** (`Tier1Harness.run` with a
  `Tier1DependencyLibrary` jar declaring `dep.models.TopStory`, and
  `processorOptions = mapOf("nuget.namespace" to "Lib", "nuget.includePackages" to
  "tier1.dup,dep.models", "nuget.publishedScopes" to "Lib:tier1.dup|dep.models:;OtherLib:dep.models:")`):
  assert exactly one warning containing `WARNING_DUPLICATED_DEPENDENCY_TYPE`, `dep.models.TopStory`,
  `OtherLib`, `exclude("dep.models")`, that no warning names `Lib` (self dropped), and that
  `export_newsroom_latest` is still generated. Negative cells: `OtherLib:dep.other:` is silent;
  `OtherLib:dep:dep.models` (excluded by the publisher) is silent.
- **Processor, `ForwardPublishedScopeTest.kt`** (6 cells, unit-level, no KSP round at all): parses
  the wire format itself, the self-entry drop, the empty-include documented gap, a blank-`packageId`
  drop, and the `exclude`-wins/qualified-name-exclude matching rules. **Correction (post-
  implementation)**: the malformed-entry cell (`"OtherLib:dep.models"`, 2 fields, not 4) lives here,
  not in `Tier1DuplicatedDependencyTypeTest.kt`, since a parse failure is unit-testable directly
  against `parsePublishedScopes` and does not need a full KSP round to observe.
- **Real build, `scripts/verify.sh --plugin`**: `test-library` is the only publisher, so the value is
  its own entry and the processor drops it; the build stays warning-free. That proves **delivery**
  of a Provider-backed option through KGP/KSP into `environment.options` (the residual Inferred
  claim), not the warning. The ProjectBuilder cell proves the multi-publisher **value**; the Tier 1
  cell proves the **warning**. No single test proves all three at once, and this ADR says so.

## Inferred claims (nobody has verified these; what breaks if wrong)

1. Handles do not cross two Kotlin/Native libraries in one process. If wrong, shape (1) becomes
   viable and the hint text is needlessly restrictive; the warning itself stays correct.
2. **KGP/KSP resolve the option Provider only after all projects are evaluated.** If wrong, a
   publisher evaluated after the reader is missing from the value and its duplicates go unwarned:
   a silent no-op, not wrong output. The ProjectBuilder cell evaluates root + two publishers *before*
   reading, so it proves the value but not the timing; the real build proves delivery for the
   single-publisher case only. A two-publisher real fixture would close it; not required for this cut.
3. `KspExtension.getArguments()` exposes Provider-registered options resolved. If wrong, the
   ProjectBuilder cell reads `apOptions` reflectively; test-only impact.
4. Gradle's default sibling evaluation order is alphabetical, and mutual `evaluationDependsOn` from
   `afterEvaluate` throws `CircularReferenceException`. Only the rejection of Alternative 2 rests on
   this; the chosen design does not call it.
5. NuGet ID grammar excludes `; : |`. If wrong, an entry splits incorrectly; the parser fails with a
   named error rather than guessing.
6. Kotlin docs framework precedent (no diagnostic, umbrella remedy): documentation only.

## Consequences

- One new diagnostic kind, one new Provider-backed KSP arg, a second reflective `arg` overload,
  no DSL change, no generated-output change.
- Both D and S are covered by one mechanism. **S remains a heuristic** ("declares its own copy
  whenever its API reaches it"), one line per (type, publisher), silenced only by `exclude(...)`. If
  it proves noisy, Alternative 3 is the precise follow-up.
- **Hint interplay, pre-existing, not fixed**: after the author follows the `exclude(...)` remedy,
  each callable reaching the type fires `SKIPPED_UNEXPORTED_DEPENDENCY_TYPE`, whose hint says to
  `include(...)` it back. The two hints contradict for this scenario; a later change may make the
  unexported-dependency hint aware of `publishedScopes`.
- ADR-066's Consequences bullet is amended: shape (1) retracted, pointer to this ADR.
- Publishers with no `rootPackage` and no `include` cannot be matched (documented gap).
- The Provider body reads other projects' extensions; legal today, and the first thing to break if
  project isolation is ever enabled. Note it in the code comment.

> **Correction (post-implementation), Inferred claims #2 and #3.** #3
> (`KspExtension.getArguments()` exposes a Provider-registered option resolved) is now **Verified**:
> javap/decompilation of the cached `symbol-processing-gradle-plugin-2.3.10.jar` (KSP 2.3.10, not
> 2.3.9 as elsewhere in this ADR) shows `arguments get() = apOptions.get()`, so the `ProjectBuilder`
> cell in `NugetPluginPublishedScopesWiringTest.kt` reads it directly rather than falling back to
> reflection on `apOptions`. #2's **delivery** half (a Provider-backed option reaches
> `environment.options`) is Verified by a temporary probe added to the real build (option value
> observed as `TestLibrary:io.github.xxfast.kotlin.native.nuget.test:`, then removed). Its **timing**
> half (the Provider body runs only after every project in the build is evaluated) stays **Inferred**:
> the single-publisher real build cannot distinguish "resolved after all projects evaluated" from
> "resolved after this project alone", and no two-publisher real fixture exists to force the
> distinction. The stated failure mode is unchanged if wrong: a publisher evaluated after the reader
> silently drops out of the value, a missing warning rather than wrong output. See ROADMAP.md.
