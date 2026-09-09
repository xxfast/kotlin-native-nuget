# ADR-117: Forward ABI entry-point collision names the owning Kotlin declarations

## Status

Accepted

> **Amended by [ADR-118](118-suspend-route-sealed-arm-owners-and-overload-numbering.md) (2026-09-09).**
> ADR-118 numbers a `suspend` overload pair, so the Tests section's `Radio` cell below
> (`play(Player)` / `play(Track)`, deliberately guard-agnostic) no longer collides and was reshaped
> in `Tier1EntryPointCollisionTest.kt` into a class method against a top-level function of the same
> mangled name (`Radio.play(Player)` vs a top-level `suspend fun radio_play()`). That cell hits
> `DUPLICATE_CSHARP_IMPORT`, not `CONFLICTING_LEGACY_IMPORTS`: this ADR's recorded residual, that no
> Tier 1 cell reaches `CONFLICTING_LEGACY_IMPORTS` through a real KSP round, is **still open**.

## Context

Issue [#106](https://github.com/xxfast/kotlin-native-nuget/issues/106), split out of the closed
[#97](https://github.com/xxfast/kotlin-native-nuget/issues/97). When two Kotlin declarations
derive the same C entry point, `ForwardAbiContract.assertMatches` (`ForwardAbiContract.kt:79`)
throws:

```
Forward ABI duplicate C# import for radio_play_collect: [radio_play_collect(in pointer, ...) -> pointer, radio_play_collect(in pointer, ...) -> pointer]
```

Two identical signatures and a mangled symbol. The contract is: **the failure names both owning
declarations** (e.g. `sample.Radio.play(Player)` and `sample.Radio.play(Track)`), not only the
symbol.

The same message is the surface for a whole family of collisions, all
[ADR-055](055-forward-abi-contract-check.md) / [ADR-078](078-forward-abi-legacy-contract-coverage.md)
guards firing on user-writable Kotlin:

| Shape | Colliding entry point | Universe of the C# import | Where the Kotlin `@CName` export is composed |
| --- | --- | --- | --- |
| Two `class Kitten` in different packages ([backlog](../backlog/two-exported-types-same-simple-name-different.md)) | `kitten_create` | structural (`CirDllImport`, planned constructor) | `addForwardKotlinPlanExport` (plan, `node = constructor`, **Verified** `ForwardCallablePlanner.kt:1083/1097`) |
| Two same-named top-level functions in different packages | `<name>` | structural (planned, [ADR-095](095-static-route-overloads.md)) | `addForwardKotlinPlanExport` (plan, `node = function`, **Verified** `:1428`) |
| Two `sealed class LoadState` in different packages | `loadstate_get_type` | legacy text (`CirSealedRenderer.kt:32`, **Verified**) | `SealedClassExports.kt` (6 `cNameAnnotation` sites, sealed class known, no per-export owner) |
| `fun dispose()` on an exported class ([backlog](../backlog/fun-dispose-crashes-ksp-raw-stack-trace.md)) | `closer_dispose` | user method structural; generated `Dispose` legacy text (`CirClassRenderer.kt:57`, **Verified**) | user method via plan; generated one at `ClassExports.kt:74` |
| Two `suspend` overloads (ROADMAP line 54) | `radio_play_async` | legacy text (`CirClassTranslator.kt:711/766`) | `SuspendFunctionExports.kt:56/101` |
| Two enum-extension properties on cross-package `enum class Mood` (backlog, fourth shape) | `mood_get_*` | structural (planned extension) | `addForwardKotlinPlanExport` |

Constraints found by reading (all **Verified** unless marked):

- `ForwardAbiSignature(exportName, result, parameters)` carries no owner, and `assertMatches`
  compares signatures with `==` (`:87`), so an owner field on the signature would break the
  mismatch check unless excluded from equality.
- There are 55 `CirDllImport(` composers across `cir/` and `forward/` (grep), and the legacy
  routes never build a `CirDllImport` at all: their imports are raw renderer text scraped back by
  `csharpLegacy` (`:115`). **The C# side cannot name a legacy owner, whatever is threaded.**
- Every export in both universes has exactly one Kotlin `@CName` `FunSpec` in `cNameExports`, built
  by `generateCNameWrappers` (`NugetProcessor.kt:1055`) which iterates per top-level declaration
  (`functions.forEach`, `classes.forEach { addClassExports }`, `sealedClasses.forEach`, ...), and
  `cNameExports` is produced (`:906`) before the contract check (`:927`).
- All *planned* exports flow through one Kotlin emitter: `addForwardKotlinPlanExport(plan)`
  (`ForwardKotlinPlanEmitter.kt:16`, three `FunSpec.builder` sites) and
  `addForwardPropertyPlanExports(plan)` (`ForwardPropertyKotlinEmitter.kt:13`, one site). A plan
  carries `invocation.symbol` (`sample.Radio.play_2`, qualified, overload-numbered) and the catalog
  entry carries the originating `node: KSNode?`.
- KotlinPoet 2.2.0 (the repo's version, `gradle/libs.versions.toml:4`): `FileSpec.Builder`
  exposes `getMembers(): List<Object>` and `addFunction` reads/writes the `members` field
  (**Verified** by `javap -c -p` on `kotlinpoet-jvm-2.2.0.jar`: `getfield members` inside
  `addFunction(FunSpec)`). `FunSpec` implements `Taggable` with `tag(Class)`/`tag(KClass)`
  (**Verified** by `javap` on 2.3.0; 2.2.0 exposes the same `Taggable` API, **Inferred** for that
  exact version, not re-run).
- Fail-safe today: the round fails and `cNameExports.kt` is never written, so `packNuget` never
  runs. **`Interop.cs` is already written** by `generateCSharpBindings` (`:1044-1052`) before the
  contract check runs. The two backlog docs' "no invalid `Interop.cs` is written" is wrong as
  stated; the guarantee that matters is that the KSP round fails before the Kotlin export file
  exists.
- `ForwardDiagnosticSink.emit` never records `ERROR_*` into `NugetDiagnostics.json`
  (`ForwardDiagnostic.kt:338-340`, by ADR-100 design), and `NugetProcessor.kt:922` returns on
  `hasFatalDiagnostic` before `writeForwardDiagnostics`. An ERROR kind reaches the KSP/Gradle log
  (`e: [ksp] path:line: [nuget:ERROR_*] ...`) and `nugetReportDiagnostics` only through that log,
  never through the JSON.
- `csharpLegacy` applies `.distinct()` (`:132`) before its own `require`, so two *identical*
  legacy imports collapse to one on the C# side and the collision surfaces at the **Kotlin-side**
  `require` (`:80`, "duplicate Kotlin export"); two legacy imports with *different* signatures
  fire the "conflicting C# legacy imports" `require` (`:134`). Which of the three guards a shape
  hits therefore depends on its universe and signature equality, and all three are user-hittable.

## Alternatives Considered

### 1. Owner index built from the Kotlin `FileSpec`, two granularities (chosen)

Build one `ForwardExportOwners` index, `entryPoint -> List<ForwardExportOwner>`, from the
`cNameExports` `FileSpec` the processor already has in hand, and pass it to `assertMatches` and
`csharpLegacy` as a separate parameter (never on `ForwardAbiSignature`).

- **Fine** (planned universe): `addForwardKotlinPlanExport` / `addForwardPropertyPlanExports` tag
  every `FunSpec` they build with the plan symbol (`FunSpec.Builder.tag(ForwardExportOwnerTag::class,
  ForwardExportOwnerTag(plan.invocation.symbol))`). At check time the symbol resolves through
  `ForwardCallablePlanCatalog.entries` (`entry.symbol == symbol`) to `node`, rendered as
  `pkg.Owner.member(ParamType, ...)` plus `file:line` from `node.location` when it is a
  `FileLocation`. A constructor renders as `pkg.Owner(ParamType, ...)` via
  `parentDeclaration.qualifiedName`, never via the constructor's own `qualifiedName` (what KSP puts
  there is unspiked). A property plan renders its `symbol` (properties cannot overload).
- **Fine, suspend route**: the issue's own Expected (`sample.Radio.play(Player)` and
  `sample.Radio.play(Track)`) is a same-class overload pair on a legacy route, which a class-level
  owner cannot satisfy. The one live legacy route with that shape is `suspend`
  (`addSuspendClassMethodExports`, `SuspendFunctionExports.kt:67-102`, and
  `addSuspendFunctionExports`, `:33-57`); both hold the `KSFunctionDeclaration` when they build the
  `FunSpec`, so they tag it with the declaration node directly (no catalog lookup: a `SUSPEND`
  entry is `Skipped`, and its number is not in the `_async` name). Price: **one file, two sites**.
  The renderer then reads `sample.Radio.play(Player)` and `sample.Radio.play(Track)` with each
  method's own `file:line`. The Flow route was numbered by #97/ADR-090 and no longer collides; the
  sealed route needs no tag, because a sealed collision is inherently class-level (every
  `loadstate_*` export derives from the sealed class's own prefix), so the class name is the
  right owner.
- **Coarse** (every other legacy route, and the generated `Dispose`): range attribution in
  `generateCNameWrappers`: around each per-declaration loop body, record `members.size` before and
  after and attribute every `FunSpec` added in between to that declaration's qualified name plus
  `file:line`. Tag wins over range. A `FunSpec` outside every range (`nuget_dispose`, scope/job
  helpers, lambda helpers) gets the explicit owner text `generated helper (no Kotlin declaration)`.

Pros: covers both universes from one place, no change to the 55 C# composers or any renderer,
matches [ADR-078](078-forward-abi-legacy-contract-coverage.md)'s principle that a route migrating to
a plan moves between universes automatically (its owner goes from coarse to fine with no edit).
Cons: a future same-class collision on a legacy route *other than* suspend would name the class
twice rather than the two members; no such route produces one today (Flow is numbered, sealed
and `Dispose` are class-prefix collisions), and the tag mechanism is a one-line addition at any
site that starts to.

### 2. Thread `owner` through `CirDllImport`

Add `owner: String?` to `CirDllImport` and populate it at the 55 composers.
Rejected: touches ~10 files, and by construction cannot name a legacy owner (raw text has no
node), which is exactly the sealed, `Dispose` and `suspend` shapes.

### 3. Tag every Kotlin `cNameAnnotation` site

~96 `cNameAnnotation(...)` call sites in 16 `exports/` files (grep count). Full fine-grained
coverage of legacy routes, but sixteen files for a diagnostic, and every future legacy site must
remember the tag. Rejected for v1; the coarse range is the cheap 80 %.

### 4. Owner on `ForwardAbiSignature`

The issue text's suggestion. Rejected: `assertMatches` compares signatures with `==` (`:87`), so
an owner field must be excluded from equality, and the legacy scraper has no owner to put there.

## Decision

Alternative 1, with fail mode **B** (a named `ForwardDiagnostic` ERROR) rather than an enriched
`IllegalArgumentException`.

### Owner source per universe

| Universe | Source | Granularity |
| --- | --- | --- |
| Planned (`addForwardKotlinPlanExport`, `addForwardPropertyPlanExports`) | `FunSpec` tag carrying `plan.invocation.symbol` / `plan.symbol`, resolved to the catalog entry's `node` | declaration + parameter types + `file:line` |
| Suspend legacy route (`addSuspendClassMethodExports`, `addSuspendFunctionExports`) | `FunSpec` tag carrying the `KSFunctionDeclaration` itself | declaration + parameter types + `file:line` (`sample.Radio.play(Player)`) |
| Other legacy routes, generated `Dispose`, sealed discriminator, `_collect` | `members` range in `generateCNameWrappers` | the top-level declaration the exporter ran for (class, sealed class, object, function, property, value class, enum, interface) + `file:line` |
| Generator helpers outside every range | constant | `generated helper (no Kotlin declaration)` |

### Message format

The first line keeps the existing phrase (`ForwardAbiContractTest.kt:238` asserts
`contains("duplicate C# import for")`), then one bullet per Kotlin export that carries the entry
point, then the signatures that were being compared:

```
[nuget:ERROR_C_ENTRY_POINT_COLLISION] Error tier1.abicollision.a.Kitten(String): Forward ABI duplicate C# import for kitten_create; 2 Kotlin declarations export the same C entry point:
  - tier1.abicollision.a.Kitten(String)
    at C:\...\src\A.kt:3
  - tier1.abicollision.b.Kitten(String)
    at C:\...\src\B.kt:3
The C entry point is derived from the unqualified simple name; rename one declaration. [kitten_create(in string) -> pointer, kitten_create(in string) -> pointer]
```

The `dispose` shape renders one fine owner and one coarse owner:

```
  - sample.Closer.dispose()
    at .../Closer.kt:2
  - sample.Closer (route-owned export: the generated Dispose, a suspend/Flow/sealed export, or another legacy route)
    at .../Closer.kt:1
```

The same three-part body is used for all three guards: `duplicate C# import for` (`:79`),
`duplicate Kotlin export for` (`:80`) and `conflicting C# legacy imports for` (`:134`); only the
leading phrase differs, so the unit tests' substrings stay valid.

### Fail mode

`ForwardAbiContract.assertMatches` and `csharpLegacy` stop throwing for the *duplicate* cases and
instead return them as `List<ForwardAbiCollision>(exportName, guard, owners, signatures)`; the
remaining guards (`missing`, `mismatch`, and every `assertMatchesPlan` `require`) stay as
generator-bug `require`s. `NugetProcessor` turns each collision into a
`ForwardDiagnostic(kind = ERROR_C_ENTRY_POINT_COLLISION, symbol = firstOwnerNode, declaration =
firstOwnerText, reason = ..., hint = ..., signature = "")` through `ForwardDiagnosticSink.emit`, then
returns `emptyList()` before `cNameExports.writeTo`. The `ERROR_` prefix derives verb `Error` and
severity `ERROR` (`ForwardDiagnosticKindTest` pins the prefix/severity/verb invariant; the new kind
needs no test edit there).

Why B over A (keep the `require`, enrich its message):

- A satisfies the restatement on its own and is one fewer file (no new kind, no sink call). Its
  fixture assertion, however, rests on an **Inferred** claim: that KSP2 catches a processor's
  uncaught exception and routes it to `KSPLogger.exception`, which `Tier1Recorders.kt:33` appends
  to `errors`. The spike (`javap -c -p` over every `KotlinSymbolProcessing*` class in
  `symbol-processing-aa-embeddable-2.3.10.jar`) found the only `KSPLogger.exception` call inside the
  delegating `execute$logger$1` logger, not a processor `catch`; it did not settle where the
  catch is. Not verified.
- B's seam is **Verified**: `Tier1ClassMethodOverloadTest.kt:118` already asserts an `ERROR_*` kind
  via `result.kspErrors` from `logger.error`, `format()` (`ForwardDiagnostic.kt:311-317`) appends
  `at path:line` from the `KSNode`, and the `hasFatalDiagnostic` return keeps the fail-safe.
- B is what the dispose backlog asks for, and what ROADMAP line 54 will cite.
- B does **not** buy JSON visibility: `ERROR_*` is never recorded (`:338-340`), by ADR-100.

Fail-safe (**Verified** by reading): unchanged. `Interop.cs` is written before the check either
way; the KSP round fails and `cNameExports.kt` is never written, so `packNuget` cannot run.

### Files

Implementation:

1. `nuget-processor/src/main/kotlin/.../forward/ForwardExportOwners.kt` (new): `ForwardExportOwnerTag`,
   `ForwardExportOwner(text, node)`, `ForwardExportOwners.build(file: FileSpec, ranges, catalog)`,
   the `KSDeclaration` renderer (`qualifiedName(ParamTypes)`, ctor via `parentDeclaration`,
   `file:line` from `FileLocation`).
2. `forward/ForwardKotlinPlanEmitter.kt` (3 `FunSpec.builder` sites) and
   `forward/ForwardPropertyKotlinEmitter.kt` (1 site): `.tag(...)` with the plan symbol;
   `exports/SuspendFunctionExports.kt` (2 sites, `:56` and `:101`): `.tag(...)` with the
   `KSFunctionDeclaration` in hand.
3. `NugetProcessor.kt`: `generateCNameWrappers` wraps each per-declaration loop body in an
   `attributing(declaration) { ... }` that records the `members` range and returns the ranges with
   the `FileSpec`; the call site at `:927-935` builds the index, passes it to `csharpLegacy` and
   `assertMatches`, and emits the ERROR diagnostics.
4. `ForwardAbiContract.kt`: `assertMatches(csharp, kotlin, owners)` and
   `csharpLegacy(rendered, ordinaryNames, owners)` return collisions for the three duplicate guards
   and render the message above.
5. `forward/ForwardDiagnostic.kt`: `ERROR_C_ENTRY_POINT_COLLISION`.

Tests:

- `ForwardAbiContractTest.kt`: extend `reports duplicate C# import` and `reports duplicate Kotlin
  export` (and the legacy-conflict cell) to pass an owner index and assert both owner texts appear;
  a cell with an empty index asserts the `generated helper (no Kotlin declaration)` fallback.
- `tier1/Tier1EntryPointCollisionTest.kt` (new, in-process only, must never go into
  `test-library/` because it breaks `packNuget`):
  - `two classes with one simple name in different packages name both constructors`:
    `Tier1Harness.run(mapOf("A.kt" to "package tier1.abicollision.a\nclass Kitten(val name: String)",
    "B.kt" to "package tier1.abicollision.b\nclass Kitten(val name: String)"))`; assert
    `kspErrors.any { it.contains("ERROR_C_ENTRY_POINT_COLLISION") && it.contains("kitten_create")
    && it.contains("tier1.abicollision.a.Kitten") && it.contains("tier1.abicollision.b.Kitten") }`
    and `generatedFiles.keys.none { it.endsWith("CNameExports.kt") }`. Exercises the fine path
    (constructors are planned with `node = constructor`). Multi-file, multi-package `run(Map)` is
    the shape `Tier1ValueClassCrossNamespaceUnderlyingTest.kt:33/41` already uses (**Verified**);
    `rootPackage` is irrelevant since the collision is on the C symbol.
  - `fun dispose() names the method and the generated Dispose`: `class Closer { fun dispose() {} }`;
    assert `closer_dispose`, `Closer.dispose()` and the route-owned text appear. Exercises fine +
    coarse together and is the dispose backlog's exact reproducer.
  - `two suspend overloads name both methods by parameter type` (the issue's own shape on the
    live route, and the spike of ROADMAP line 54):

    ```kotlin
    package tier1.abicollision.suspend

    class Player(val name: String)
    class Track(val title: String)

    class Radio {
      suspend fun play(p: Player): Int = 1
      suspend fun play(t: Track): Int = 2
    }
    ```

    Assert `kspErrors.any { it.contains("ERROR_C_ENTRY_POINT_COLLISION") &&
    it.contains("radio_play_async") && it.contains("tier1.abicollision.suspend.Radio.play(Player)")
    && it.contains("tier1.abicollision.suspend.Radio.play(Track)") }` and no `CNameExports.kt`.
    Reading says this cell collides today (**Inferred, not run**): `SuspendFunctionExports.kt:86-102`
    composes `${prefix}_${cname}_async` from `toCName(methodName)` and the file has no
    `overloadSuffix` reference; the C# import is legacy text (`CirClassTranslator.kt:711`, same
    composition; `ordinaryNativeImports` filters `isAsync`, `CirNativeImports.kt:14`). Both
    imports are byte-identical (each parameter is a pointer), so `csharpLegacy`'s `.distinct()`
    collapses them and the Kotlin-side guard (`:80`, "duplicate Kotlin export") is the one that
    fires; the assertion above deliberately does not depend on which guard it is. If the cell
    does not fail, ROADMAP line 54 is wrong and the cell should be replaced by one whose two
    overloads differ in a primitive parameter (`play(id: Int)` / `play(id: Long)`), which makes
    the legacy signatures differ and moves the collision to the `:134` guard.

### Mechanism claims, labelled

- **Verified**: `ForwardAbiSignature` has no owner and `assertMatches` uses `==` (`:41-52`, `:87`).
- **Verified**: 55 `CirDllImport(` composers; `_dispose` and `_get_type` imports are raw text
  (`CirClassRenderer.kt:57`, `CirSealedRenderer.kt:32`).
- **Verified**: planned constructor/top-level/class entries carry `node` (`ForwardCallablePlanner.kt:1083,
  1097, 1428, 1665`).
- **Verified**: `cNameExports` is generated before `bindings` and before the contract check
  (`NugetProcessor.kt:906, 912, 927`).
- **Verified**: KotlinPoet 2.2.0 `FileSpec.Builder.getMembers()` exists and `addFunction` touches
  `members` (`javap -c -p -cp kotlinpoet-jvm-2.2.0.jar 'com.squareup.kotlinpoet.FileSpec$Builder'`).
- **Verified**: `FunSpec` is `Taggable` (`javap` on 2.3.0); **Inferred** that 2.2.0's
  `FunSpec.Builder` exposes `tag(KClass, Any)` (KotlinPoet has shipped `Taggable.Builder` since
  1.x; not re-run against 2.2.0).
- **Verified**: `ERROR_*` is logged, never recorded, and the processor returns before writing the
  Kotlin export file and before `writeForwardDiagnostics` (`ForwardDiagnostic.kt:338-340`,
  `NugetProcessor.kt:922`).
- **Verified**: `Tier1Recorders.kt:29-34` appends both `error(...)` messages and `exception(e).message`
  to `errors`; `Tier1ClassMethodOverloadTest.kt:118-136` is the `ERROR_*` assertion precedent.
- **Inferred, not verified**: KSP2 routes an uncaught processor exception to `KSPLogger.exception`
  (would only matter for fail mode A; B does not depend on it).
- **Inferred, not verified**: a `KSFunctionDeclaration` constructor's `qualifiedName` spelling;
  the renderer is specified to avoid it.
- **Inferred, not spiked, and moot**: which of the three guards the cross-package sealed shape
  hits today. Reading says `csharpLegacy`'s `.distinct()` collapses its two identical `_get_type`
  imports and the Kotlin-side `:80` guard fires; the backlog records it as `duplicate C# import`,
  which may predate ADR-078's collector. All three guards share one body and one index, so the
  answer changes no output; not an open question.
- **Verified by reading**: ROADMAP line 54's premise. `SuspendFunctionExports.kt` contains no
  `overloadSuffix` reference and composes `${prefix}_${cname}_async` at `:101-102` from the bare
  method name; the Tier 1 suspend cell is the runtime spike.

## Consequences

- A collision now fails with `e: [ksp] <file>:<line>: [nuget:ERROR_C_ENTRY_POINT_COLLISION] ...`
  pointing at the first owner's source, naming every owner, instead of a JVM stack trace; still
  fails safe (no `cNameExports.kt`).
- The three duplicate guards become diagnostics; `missing`/`mismatch` and `assertMatchesPlan` stay
  generator-bug `require`s.
- Owners on the suspend route and every planned route are method-granular
  (`sample.Radio.play(Player)`); owners on the remaining legacy routes (sealed, Flow `_collect`,
  generated `Dispose`, generic class, interface bridge) are class-granular, which is the right
  owner for every collision those routes can produce today (all class-prefix collisions). Any
  route migrating to a plan gets fine owners for free. ROADMAP line 54's numbering fix removes the
  suspend collision itself; until then this ADR names it.
- The message names but does not fix the unqualified-prefix scheme
  ([backlog](../backlog/two-exported-types-same-simple-name-different.md)); that remains its own
  item, and this ADR's hint ("rename one declaration") is the interim remedy it points at.
- Deferred: per-site tags on the legacy routes (Alternative 3); recording `ERROR_*` into
  `NugetDiagnostics.json` (ADR-100's deferred item, unchanged); a structural `role` field
  (ROADMAP line 24, unchanged, since the owner is carried beside the signature, not on it).
- Doc correction to land with the implementation: both family backlog docs' "no invalid
  `Interop.cs` is written" becomes "the KSP round fails before `cNameExports.kt` is written".

## Implementation notes (2026-09-08)

Shipped as designed, with four corrections to claims this ADR made or left open:

1. The suspend `_async` imports both survive into the structural contract list uncollapsed, so the
   **C#-side** guard (`assertMatches`, `DUPLICATE_CSHARP_IMPORT`) fires for the `Radio` shape, not
   the Kotlin-side `require` this ADR predicted. Output is identical either way, since all three
   duplicate guards share one message body and one owner index.
2. The generated `_dispose` import is emitted **structurally**, via `ordinaryNativeImports()`, not as
   raw renderer text as the Context table's "Where the Kotlin `@CName` export is composed" column
   implied. That changes which guard `fun dispose()` is positioned to hit: both `closer_dispose`
   imports are now structural, so `assertMatches`'s `DUPLICATE_CSHARP_IMPORT` arm
   (`expected.size > 1`) is the one that would fire, not the Kotlin-side `DUPLICATE_KOTLIN_EXPORT` arm
   this ADR's table implied (**Inferred** from reading `assertMatches:141-146`; no test names the
   guard by which arm fired). The fine + coarse owner pairing in the "Message format" section is
   unaffected either way, and `Tier1EntryPointCollisionTest`'s dispose cell deliberately asserts
   neither guard by name, only the two owner texts and `closer_dispose`.
3. The "Inferred, not verified" claim that KSP2 routes an uncaught processor exception to
   `KSPLogger.exception` is false in the Tier 1 harness: an uncaught exception propagates straight to
   JUnit, never through `RecordingKSPLogger`. Fail mode A (enrich the `require`) would therefore have
   had no assertable seam in-process. This is further confirmation for choosing fail mode B, not a
   correction to the decision itself.
4. `FunSpec.Builder.tag` and `FileSpec.Builder.members` are now **Verified** (not Inferred) against
   the repo's actual KotlinPoet 2.2.0 jar, matching the "Mechanism claims, labelled" section's
   `javap` spikes.

Also recorded, both **Verified by execution**: ROADMAP line 54's premise (the suspend `_async`
collision) is reproduced by `Tier1EntryPointCollisionTest`'s `Radio` cell, which is now green against
the shipped code, not a spike. No `else ->` swallow site exists for `ERROR_C_ENTRY_POINT_COLLISION`.
The legacy-import-conflict guard (`csharpLegacy`'s `CONFLICTING_LEGACY_IMPORTS`) is covered at the
unit level (`ForwardAbiContractTest`) but no Tier 1 cell reaches it through a real KSP round; the
three Tier 1 cells hit the other two guards.
