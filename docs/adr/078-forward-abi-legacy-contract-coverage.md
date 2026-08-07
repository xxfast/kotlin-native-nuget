# ADR-078: Forward ABI contract coverage for the specialized legacy protocols

## Status
Accepted

## Context

ADR-055's generation-time ABI contract check (`ForwardAbiContract.assertMatches`, called from
`NugetProcessor`) compares the C# `DllImport` declarations against the Kotlin `@CName` exports so
that a drifting emitter fails during KSP instead of SIGBUS-ing at runtime (the ADR-053/054 failure
class). Today it covers only ordinary callables: `ForwardAbiContract.csharp(file)` collects
`CirDllImport` nodes from `CirStaticClass` / `CirObject` / `CirClass` (via `ordinaryNativeImports()`
plus companion members) / `CirValueClass`, and every other declaration falls into
`else -> emptyList()`. The Kotlin side is then pre-filtered to the C# export-name set, so the
legacy helper exports are invisible to the check in both directions.

The legacy universe is large. In the real `test-library` fixture build (verified,
`./gradlew :test-library:packNuget`, 2026-08-07), the generated `Interop.cs` contains 729
`DllImport` declarations against 728 Kotlin `@CName` exports, and the ordinary check sees only a
subset. The uncovered routes are the ones enumerated by `ForwardAbiLegacyRoutes`: generic
functions/classes, sealed classes, suspend functions/methods, Flow properties/methods, lambda and
suspend-lambda properties, lambda-parameter methods, stored callbacks, interface bridges, and the
shared helper classes (`CirFuncHelper`, `CirSuspendFuncHelper`, `CirAsyncHelper`, `CirFlowHelper`,
`CirListHelper`, `CirMapHelper`, `CirSetHelper`, `CirMarshalHelper`, `CirErrorHelper`,
`CirScopeHelper`, `CirJobHelper`, `CirStateFlowHandleHelper`, `CirSubscriptionHelper`).

Two structural facts, both verified against the fixture build, constrain the design:

1. **The legacy `DllImport`s are not CIR nodes.** Every legacy helper import is emitted as raw
   text by a renderer (`CirFunctionRenderer`, `CirMarshalRenderer`, `CirFlowRenderer`,
   `CirConcurrencyRenderer`, `CirErrorRenderer`, `CirSealedRenderer`, `CirEnumRenderer`, and the
   raw-text paths of `CirClassRenderer`). There is no structured `CirDllImport` to feed the
   existing normalizer. This is exactly the "helper import shapes not fitting the current
   `ForwardAbiSignature` normalizer" named by the roadmap item.

2. **The two name universes are not 1:1.** Verified by diffing the fixture's `@CName` names
   against its `EntryPoint` names:
   - Duplicate C# imports for one entry point are normal: `nuget_dispose` is declared 6 times
     (list/map/set/func/suspend-func helper classes each declare their own), `nuget_wrap_bool`
     / `_int` / `_long` / `_float` / `_double` / `_string` 3 times each. All duplicates are
     shape-identical (verified by inspection of the emission sites).
   - Kotlin-only exports exist by design: `identity_{byte,short,ubyte,uint,ulong,ushort}` and
     `wrapInBox_{...}` (generic-function monomorphizations for widths the C# dispatcher never
     imports; C# imports only the bool/int/long/float/double/string/object widths),
     `observation_superposition_{equals,hashcode,tostring}` and `animal_dispose` (sealed-route
     Object-method exports the C# renderer does not import). So the "missing C# import" arm of
     `assertMatches` cannot be enabled for the legacy universe; it must stay import-driven.

The complete C#-side type-token catalog of the fixture's 729 extern declarations (verified by
tokenizing every `static extern` line) is: the primitives already in `csharpType`
(`bool sbyte byte short ushort char int uint long ulong float double void`), `IntPtr`, `string`
/ `string?`, `out IntPtr error`, `out bool|int|long valueOut` (with `[MarshalAs(UnmanagedType.I1)]`
on the `out bool` case, already stripped by the normalizer), and exactly one delegate type,
`NugetAsyncCallback`, which the normalizer's `else -> POINTER` branch classifies correctly (a
marshalled delegate is a function pointer at the C ABI; the Kotlin counterpart parameter is
`COpaquePointer`, also `POINTER`). Sampled legacy pairs (`observation_alive_tostring`,
`nuget_suspend_func1_invoke`, `nuget_stateflow_collect`, `nuget_list_get`, `box_create_object`)
all normalize to equal signatures under the existing rules (verified). Kotlin legacy wrappers
spell their out-parameters `errorOut` / `valueOut` consistently (verified: 536 `errorOut` + 10
`valueOut` Kotlin-side vs 524 `out IntPtr error` + 10 `out ... valueOut` C#-side; the 12-count
delta matches the 12 Kotlin-only `identity_*` / `wrapInBox_*` exports, inferred from the counts,
not pairwise-checked).

No generated API or runtime ABI changes. The legacy emitters are not migrated.

## Alternatives Considered

### 1. Collect the legacy C# side from the rendered `Interop.cs` text (chosen)

Add a text-based collector: render the `CirFile` (the same pure render that produces the shipped
`Interop.cs`) and scan it for `[DllImport(..., EntryPoint = "name")]` attribute lines followed by
their `static extern <return> <name>(<params>);` declaration, normalizing each with the existing
rules (`csharpType`, the `out `-prefix rule, the `[MarshalAs(...)] ` strip). Entry points already
covered by the CIR-based ordinary collector are excluded; the remainder is the legacy universe.

- Pros: the compared C# side is byte-for-byte what ships, so a renderer edit that changes an
  import shape is caught by construction; one collector covers all thirteen legacy routes and any
  future raw-text emission site with zero per-route code; no shape knowledge is duplicated.
- Cons: it is a text parser, so it depends on the renderer's fixed two-line format (attribute
  line, then extern line). That format is machine-generated by this same codebase and uniform
  across all emission sites (verified by grep over every `DllImport(\"` emission site); the
  collector must `error()` on an attribute line whose following extern line does not parse, so a
  future format change fails loudly rather than silently shrinking coverage.

### 2. Per-helper structured signature emitters

Extend `ForwardAbiContract.csharp`'s `when` with a hand-written `toSignatures()` per helper node
(`CirFuncHelper` -> the `nuget_funcN_invoke` + `nuget_dispose` + `nuget_wrap_*` shapes, etc.),
mirroring what each renderer prints.

- Pros: structured, no text parsing.
- Cons: it duplicates the renderer's shape knowledge by hand in ~20 places. The duplicate is the
  compared "C# truth", so if a renderer changes and the emitter is not updated, the check compares
  a stale expectation against Kotlin and can pass while the shipped C# text disagrees with both.
  That silent under-coverage is precisely the failure class this item exists to close.

### 3. Migrate the legacy renderers to emit `CirDllImport` nodes

The long-term direction (the ForwardCallablePlan migration, ADR-062), but explicitly out of scope
for this item: it changes every legacy emitter and risks the generated output, for a checking
feature.

## Decision

Extend the contract check with a rendered-text legacy collector, keep the universe import-driven,
and relax only the duplicate rule, as follows.

### C#-side collection

```kotlin
// ForwardAbiContract
fun csharpLegacy(renderedCsharp: String, ordinaryNames: Set<String>): List<ForwardAbiSignature>
```

- Scan `renderedCsharp` line-pairwise: a line containing `EntryPoint = "<name>"` must be
  immediately followed (next non-blank line) by a `static extern` declaration; `error()` with the
  offending line otherwise (fail fast, never skip). **Verified** that every current emission site
  produces this adjacent two-line shape; the hard failure is the guard for future sites.

  **Implementation deviation from this text, recorded on acceptance:** "immediately followed" is
  not literally the *next* non-blank line. 46 of the fixture's 729 `DllImport` declarations carry a
  `[return: MarshalAs(UnmanagedType.I1)]` attribute line between the `EntryPoint` attribute and the
  `static extern` declaration (every marshalled-`bool`-return legacy route). The implemented
  collector skips further `[`-prefixed attribute lines and only fails hard on a non-attribute line
  that still isn't a parsable `static extern` declaration. The fail-fast guard is unchanged, it
  just now tolerates the one attribute shape the fixture actually emits between the two lines.
  Pinned by `ForwardAbiLegacyImportTest`'s "accepts a return marshalling attribute between the
  attribute and the declaration".
- Parse the extern line into return type and parameter list; normalize with the same rules the
  `CirDllImport` path uses today: strip a leading `[MarshalAs(...)] ` from each parameter, an
  `out `-prefixed parameter is `(POINTER, OUT)`, otherwise `csharpType` on the type token
  (`string` at the return position is `POINTER`, per the existing `csharpReturnType` rule).
  Unknown type tokens (delegate names such as `NugetAsyncCallback`) fall to `POINTER`, which is
  **verified** correct for the only delegate in the current fixture and **inferred** correct for
  any future delegate parameter (a marshalled delegate is a function pointer at the C ABI).
- Drop entry points in `ordinaryNames` (those are already covered structurally), then collapse
  duplicates: `distinct()` plus a `require` that all declarations of one entry point normalized to
  the same signature (message naming the entry point and both shapes). **Verified** that duplicate
  declarations exist today (`nuget_dispose` x6, `nuget_wrap_*` x3) and are shape-identical.

### Kotlin-side universe and assertion

```kotlin
val ordinary: List<ForwardAbiSignature> = ForwardAbiContract.csharp(cirFile)
val legacy: List<ForwardAbiSignature> =
  ForwardAbiContract.csharpLegacy(cirFile.render(), ordinary.map { it.exportName }.toSet())
val csharp: List<ForwardAbiSignature> = ordinary + legacy
ForwardAbiContract.assertMatches(
  csharp = csharp,
  kotlin = ForwardAbiContract.kotlin(cNameExports, csharp.map { it.exportName }.toSet()),
)
```

- The Kotlin projection (`FunSpec.toSignature()`, `@CName` + `errorOut`/`valueOut` naming) is
  reused unchanged. **Verified** that the legacy Kotlin wrappers use the same out-parameter
  spellings and that sampled legacy pairs normalize to equal signatures.
- The universe stays import-driven (Kotlin side filtered to the C# name set) because the Kotlin
  export set is a **verified** strict superset: the per-width generic monomorphizations
  (`identity_*`, `wrapInBox_*`) and the sealed-route Object-method exports
  (`observation_superposition_equals`/`hashcode`/`tostring`, `animal_dispose`) have no C# import
  by design. The "missing Kotlin export" arm applies to every C# import, ordinary and legacy; the
  "missing C# import" arm remains effectively disabled outside the C# name set, unchanged from
  ADR-055.
- `assertMatchesPlan` (ADR-062's shadow check) is unchanged.

### What this catches that ADR-055 does not

A drift between any legacy renderer's `DllImport` text and its Kotlin `@CName` wrapper (a
parameter added on one side, a width change, an out-parameter dropped) now fails the KSP round
with the existing `Forward ABI mismatch for <name>` message instead of surfacing as a runtime
marshalling fault in the consumer.

### First-run findings, recorded on acceptance

The residual risk below predicted "possibly a handful" of findings on the first full-suite run.
It found exactly two, both pre-existing generator defects, neither caused by this feature, both
fixed on this same branch before merge:

1. **`NugetMarshal.CreateBox<T>` hardcoded `box_create_*` entry points.** The shared C# boxing
   helper dispatched every unconstrained generic class's constructor through one set of
   `box_create_string`/`_byte`/.../`_object` imports, regardless of which class was being
   constructed. That only resolved because `test-library` happens to declare a generic class
   literally named `Box`, whose own exports are named `box_create_*` by the ordinary
   class-prefix convention; any other library's unconstrained generic class would either throw
   `EntryPointNotFoundException` (no `box_create_*` symbols in its native library) or, worse, if
   the library *also* happened to export a class prefixed `box`, construct a `Box` instance and
   downcast the handle to the wrong type (memory corruption, not a clean failure). Fixed by
   per-class constructor dispatch: each unconstrained generic class's `${Name}Native` now
   declares its own 13 `${prefix}_create_*` imports (`CirClassRenderer.kt`), and `CreateBox<T>`
   is deleted from `CirMarshalRenderer.kt`. Regression-pinned by `Tier1GenericClassPrefixTest`, a
   `Crate<T>` fixture (deliberately *not* named `Box`) asserting the generated C# contains no
   `box_create_` and no `CreateBox`.
2. **`nuget_wrap_*`/`nuget_dispose` C# imports were not gated the same way as their Kotlin
   exports.** `NugetMarshal`'s C# rendering emits `nuget_dispose`/`nuget_wrap_*` whenever the
   helper class itself is emitted, but the matching Kotlin `@CName` exports were gated on
   generics/collections/lambdas only, a narrower condition. A module tripping the wider C#-side
   condition without tripping any of the narrower Kotlin-side ones would generate a C# import
   with no native symbol behind it. Fixed by a `needsCoreMarshal` flag in `NugetProcessor.kt`
   (true whenever the module declares a function, class, object, sealed class, suspend function,
   or property) that both halves now share.

Neither defect was reachable by the ADR-055 ordinary-callable check, since both live entirely in
the legacy universe this ADR adds coverage for. Their discovery on the first real run is the
feature's proof of value, not a scope overrun.

### Residual risk, stated plainly

The pairwise equality of all ~700 legacy signatures in the fixture has **not** been executed;
shapes were verified by a complete type-token catalog plus sampled pairs. If some individual pair
disagrees under normalization (a differently-named Kotlin out-parameter, say), the first
`scripts/verify.sh` run of the implementation will fail on it with the exact entry point named,
and the fix is either the wrapper's parameter name or a documented exclusion. That is the check
working, not a design flaw, and it is exactly what happened: see "First-run findings" above.

The Context section's "Kotlin exports are a verified strict superset" claim, and the Decision
section's identical claim justifying why the universe stays import-driven, held only because
verification ran against `test-library`, the one corpus this project has, which happens to be the
same corpus that masked the `CreateBox<T>` defect above (it is the one library that declares a
class named `Box`). A future corpus with a differently-shaped legacy export set is not guaranteed
to be a superset in the same way; the claim is verified for `test-library`, not proven in general.

## Consequences

- `ForwardAbiContract` gains `csharpLegacy` (text collector) and the duplicate-collapsing rule;
  `assertMatches` itself keeps its `size <= 1` invariant because the collector dedupes before it.
- `NugetProcessor`'s check site renders the CIR file (a pure, already-existing render) once for
  the collector; the rendered text it checks is identical to what is written to `Interop.cs`.
- New tests: unit tests for the text collector (one per shape class: primitive, `IntPtr`,
  `string` return vs parameter, `out IntPtr error`, `[MarshalAs] out bool valueOut`, delegate
  parameter, duplicate identical imports, conflicting duplicate imports failing, attribute line
  without a parsable extern failing), plus mismatch tests in the `ForwardAbiContractTest` style
  driving `assertMatches` with a legacy-shaped pair. The Tier 1 processor harness already runs
  the full check over every legacy route via the `test-library` sources, so route coverage is
  exercised end to end on every build.
- Deferred: migrating any legacy emitter to structured `CirDllImport` nodes (ADR-062's plan
  migration supersedes the text collector route by route as it lands; when a route migrates, its
  imports move from `csharpLegacy`'s universe into `csharp`'s automatically via `ordinaryNames`).
- Two pre-existing generator defects the check found on its first full-suite run are fixed
  alongside it: `CirClassRenderer.kt` gives each unconstrained generic class its own
  `${prefix}_create_*` constructor imports instead of sharing the hardcoded `box_create_*` ones
  (`CirMarshalRenderer.kt`'s `CreateBox<T>` is deleted), and `NugetProcessor.kt`'s
  `needsCoreMarshal` flag gates the Kotlin `nuget_wrap_*`/`nuget_dispose` exports under the same
  condition as their unconditional C# `NugetMarshal` imports. See "First-run findings" above.
