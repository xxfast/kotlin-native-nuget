# ADR-064: Forward unsupported-declaration diagnostics, a named skip subset for Kotlin → C#, the mirror of ADR-043

## Status

Accepted

## Context

The reverse direction (C# → Kotlin) has a defined bridgeable subset and a diagnostic that names each
skipped member and why ([ADR-043](043-bridgeable-subset-boundary.md)). The forward direction
(Kotlin/Native → C#) has neither a written subset boundary nor a coherent skip diagnostic. When the
forward generator meets a construct it cannot express, one of three things happens, all of them bad
for the consumer:

1. it emits invalid **Kotlin** (a `@CName` export that does not compile), so
   `:test-library:compileKotlinMingwX64` fails and no `.nupkg` is produced;
2. it emits a **valid-but-lying C# API** (a signature whose contract the runtime does not honour); or
3. the member **silently vanishes** from the generated C# API with no signal at all.

In every case the library author running `packNuget` gets a compiler error pointing into generated
code they never wrote, or a silent gap, rather than a message at their own Kotlin source saying
"member X was skipped because Y." This is the exact inversion of the reverse direction, and it is
hard to defend: the newer, preview direction is currently the honest one, while
MVP.md's stated principle is an honest capability ceiling rather than a compatibility
promise. This item sits beside [ADR-055](055-forward-abi-contract-check.md)'s forward ABI contract
check as the other half of "the forward generator should fail loudly rather than emit something
wrong": ADR-055 catches drift between the two halves of a callable that *was* planned; this ADR
catches constructs that should never have been planned or emitted at all.

### Why now: the reachability-closure prerequisite

This is the sequencing prerequisite for the reachability-closure export item (ROADMAP line 14).
Today `getAllFiles()` module isolation *accidentally* hides unbridgeable constructs that live one
module away. When the export set becomes a reachability closure from roots, those constructs get
pulled back in and re-arm as build breaks. ROADMAP line 14 states this explicitly ("this must not
land before the forward diagnostics item ... or reachability will drag the exact constructs module
isolation currently hides ... back into the export set and re-arm them"). Two named constructs are
at stake:

- **A generic `suspend inline` extension returning `Result<T>`** (ROADMAP line 111 / NYTimes-KMP
  BUG-010): `suspend inline fun <reified T> HttpClient.get(...): Result<T>`. ROADMAP line 111
  explicitly assigns its end state ("a named skip and not invalid source") to *this* diagnostics
  item. It is [ADR-060](060-adversarial-forward-fixture.md) cell 23 and a permanent Tier 1
  diagnostic cell.
- **A `CharSequence`-delegating value class with inherited members** (ROADMAP line 77): the product
  decision left open after ADR-062 Phase 9 closed declared value-class-method parameters.

### What the forward direction already has (verified, repository source, this session)

The forward pipeline is not starting from nothing. It already skips some constructs; the problem is
that the skip is unstructured, inconsistent, incomplete, and in one case (cell 23) not reached at all.

- **Verified** (`NugetProcessor.kt:86-96`): `warnDroppedForwardCallables()` logs one generic
  `logger.warn` per ordinary callable the planner dropped: *"Forward bridge dropped {symbol}: its
  {reason} type combination is not supported, so it is omitted from the generated C# API."* No kind,
  no severity, no hint, no source location.
- **Verified** (`ForwardCallablePlanner.kt:25-49`): `ForwardPlanSkipReason` already carries the
  load-bearing `droppedFromCSharp: Boolean`. A reason is a *drop* (warn) only when no named legacy
  route re-emits the callable; a reason that defers to a legacy route
  (`SUSPEND`, `FLOW_PROTOCOL`, `GENERIC`, `CALLBACK_PROTOCOL`, `SEALED_PROTOCOL`, `TYPE_PARAMETER`,
  `ABSTRACT`, `SUSPEND_CALLBACK_PROTOCOL`) is `droppedFromCSharp = false` and stays silent, on the
  assumption the legacy route emits it correctly.
- **Verified** (`ForwardMarshallingModel.kt:48`, `ForwardBridgeTypeClassifier.kt:42/53/92/98/112`):
  `BridgeType.Unsupported(rendered, reason)` already carries a human reason string, produced for
  type parameters, local/anonymous declarations, non-class/object kinds, out-of-export-set handles,
  and malformed value classes.
- **Verified** (`ForwardCallablePlanner.kt:971-1009`): `skipReason()` / `inputSkipReason()` map every
  `BridgeType` to a `ForwardPlanSkipReason`, so the classification-to-reason mapping exists.
- **Verified**: scattered, inconsistent ad-hoc diagnostics already live in the CIR translators, in
  three different message styles and two severities:
  - `CirClassTranslator.kt:92`, `logger.error` for a C# constructor signature collision (ADR-034,
    ADR-060 cell 21). **Fatal.**
  - `CirFunctionTranslator.kt:89`, `logger.error`, the same collision for functions.
  - `CirClassTranslator.kt:561`, `logger.warn` "Variance '…' … will be dropped" (an INFO-shaped
    note: the member still binds).
  - `CirClassTranslator.kt:675`, `logger.warn("Skipping property '…': unsupported type '…'")`
    (ADR-060 cell 24, sealed-subclass property).
  - `CirFunctionTranslator.kt:585`, `logger.warn("Skipping function '…': unsupported return type
    '…'")`.
- **Verified (fresh Tier 1 probe, this session, supersedes ADR-060's older spike)**: the cell-23 gap
  is a **silent drop**, not invalid Kotlin. In `ForwardCallablePlanner.extensionEntry`,
  `Modifier.SUSPEND` matches first in the `when`, so the callable is `Skipped(symbol,
  ForwardPlanSkipReason.SUSPEND)` and `SUSPEND.droppedFromCSharp = false`, so it never reaches
  `droppedCallables` / `warnDroppedForwardCallables` (no warning). The legacy emitter
  `ExtensionFunctionExports.addExtensionFunctionExports` only emits `callableCatalog.planFor(symbol)`-
  backed plans and no-ops when the plan is null, a Phase 10 guard that postdates ADR-060's spike, so it
  **no longer renders raw `Function1`/`Result`**. Net result today: `export_..._chartEntry` is absent
  from `CNameExports.kt`, `kspExitCode = OK`, `kspWarnings = []`, `kspErrors = []`, completely silent.
  So the cell-23 fix is narrower than a legacy-emitter guard: **reclassify** the extension + generic/
  reified + suspend + `Result<T>` combination out of the structural `SUSPEND`/`GENERIC` silent-skip
  buckets into a *genuine drop* carrying `SKIPPED_UNSUPPORTED_COMBINATION`, so a silent drop becomes a
  named skip. ADR-060's Verification section (raw `Function1`/`Result`, *cannot infer type*, *suspend
  … can only be called from a coroutine*) recorded the pre-guard state and is stale; the honesty gain
  here is silent → named, not invalid → named.

### What the reverse direction's diagnostic model looks like (verified, `nuget-plugin` source)

The model this ADR mirrors:

- **Verified** (`RirModel.kt:207-276`): `RirDiagnostic(kind, typeName, memberName, memberSignature,
  reason, hint)` plus a `RirDiagnosticKind` enum whose entries encode **severity by name prefix**:
  `SKIPPED_*` (member absent), `ERROR_*` (fatal), `INFO_*` (member still binds, under an assumed
  policy). Kinds include `SKIPPED_OVERLOAD_SET`, `SKIPPED_REF_STRUCT`, `SKIPPED_OPEN_GENERIC`,
  `SKIPPED_DYNAMIC`, `SKIPPED_DEFAULT_INTERFACE_METHOD`, `SKIPPED_ABI_ARITY_LIMIT`,
  `ERROR_KOTLIN_SIGNATURE_COLLISION`, `INFO_ASYNC_NOT_YET_MAPPED`, `INFO_OBLIVIOUS_NULLABILITY`.
- **Verified** (`NugetGenerateBindingsTask.kt:2873-2885`): `validateDiagnostics()` `require`s that no
  `ERROR_*` diagnostic is present, so an `ERROR_*` kind is fatal to generation
  ([ADR-057](057-csharp-overload-sets-in-kotlin.md) made `ERROR_KOTLIN_SIGNATURE_COLLISION` fatal
  while keeping `SKIPPED`/`INFO` non-fatal).
- **Verified** (`NugetGenerateBindingsTask.kt:2941-2949`, `:2977`): `formatDiagnostic()` renders
  `w: [nuget:{pkg}] {Skipping|Note}{location}: {reason}. {hint}` and `diagnosticWarnings(rir)` is fed
  to `logger.warn`.
- **Verified** (ROADMAP line 162): reverse diagnostics are **Gradle-log warnings only, not a
  structured or queryable report**, and there is no test asserting every kind reaches the log.

### External prior art

- **Kotlin ObjC / Swift Export**, *Inferred* (Kotlin docs, not executed):
  [native-objc-interop](https://kotlinlang.org/docs/native-objc-interop.html) lists unsupported
  Kotlin features (inline classes, custom collection implementations, Kotlin subclasses of ObjC
  classes) but does not document a per-declaration skip diagnostic. Visibility is controlled by
  **opt-out** annotations (`@HiddenFromObjC`, `@ShouldRefineInSwift`), and name conflicts are resolved
  by author-side renaming or `@ObjCName`. So the precedent is "all public leaves export, opt out
  explicitly," and the ceiling is communicated by documentation, not build-time diagnostics. This is
  the same picture ADR-043 and ADR-063 recorded. It tells us the *mechanism* (skip + diagnose) is our
  own convention (CLAUDE.md fail-fast), not something inherited from JetBrains' exporters.
- **Xamarin / .NET binding generator**, *Inferred* (Microsoft docs, per ADR-043): skips unsupported
  Java members and surfaces them as MSBuild diagnostic warnings, with `Metadata.xml` as the un-skip
  escape hatch. This is the closest analogue to our chosen model.
- **KSP diagnostics API**, *Verified* (KSP source,
  [`KSPLogger.kt`](https://github.com/google/ksp/blob/main/api/src/main/kotlin/com/google/devtools/ksp/processing/KSPLogger.kt),
  read this session): `logging`, `info`, `warn`, `error` each have signature
  `fun (message: String, symbol: KSNode? = null)`. The optional `symbol` is the seam that lets a
  forward diagnostic point at the **user's Kotlin declaration** rather than at generated code,
  something the reverse direction structurally cannot do, because it works from ECMA-335 metadata and
  has no KSNode. That the IDE/Gradle then renders the message at that source position is *Inferred*
  (KSP documented behaviour, not spiked here); the signature itself is verified.

## Alternatives Considered

### 1. A forward diagnostic model mirroring `RirDiagnosticKind`, one sink, KSP-native severity + source location (chosen)

Introduce a `ForwardDiagnosticKind` enum in `nuget-processor` that mirrors `RirDiagnosticKind`'s
`SKIPPED_* / ERROR_* / INFO_*` prefix-encodes-severity convention, and a `ForwardDiagnostic` record
carrying the originating `KSNode` (for source location), the reason, and an actionable hint. Every
forward skip/fail decision, the planner's `droppedCallables`, the classifier's `Unsupported`, the
legacy-route guards, and the today-scattered CIR-translator `logger.warn`/`logger.error` calls,
routes through one sink that emits via `logger.warn(message, symbol)` / `logger.error(message,
symbol)`. `SKIPPED_*` warns and the build continues with the member absent; `ERROR_*` fails
generation; `INFO_*` notes a member that still binds under an assumed policy.

**Pros:** reuses a shape the team already maintains and tests-by-eye on the reverse side, so the two
directions read the same in a build log; `droppedFromCSharp` already encodes the "genuine drop vs
deferred-to-legacy-route" distinction the model needs; the KSNode source location is a strict
improvement over reverse (the message lands on the author's own `fun get(...)`, not on generated
`CNameExports.kt`); the fatal/warn/info policy is the ADR-057 precedent, unchanged. **Cons:** the
forward reason space is not identical to the reverse one (forward has no overload sets or `ref
struct`; it has generic-suspend-extension combinations and value-class inheritance), so the kind
enum is a sibling, not a shared type, two enums to keep roughly parallel.

### 2. Keep `warnDroppedForwardCallables`'s single generic string, extend it to the legacy routes (rejected)

Leave the message unstructured ("its {reason} type combination is not supported") and just call it
from more places.

**Rejected:** it cannot express severity (cell 21 must be fatal, cell 23 must warn), it has no hint,
and, the load-bearing failure, it cannot be asserted precisely in ADR-060's Tier 1 diagnostic
mode, so a test can only substring-match a vague sentence. It also does not carry a `KSNode`, so the
message still cannot point at the author's source.

### 3. Hard-error on every unsupported construct (rejected)

Fail `packNuget` whenever any declaration falls outside the subset.

**Rejected** for the same reason ADR-043 Alternative 3 was rejected in reverse: real Kotlin modules
routinely contain a construct or two outside the subset (a `Sequence` property, a `Map` parameter),
and blocking the whole package on one of them makes the plugin unusable. The default is warn; only a
genuinely ambiguous, silently-corrupting collision is fatal (below).

### 4. A structured, queryable diagnostics report file (deferred)

Emit a machine-readable report (JSON) of every forward skip, queryable by tooling, rather than only
Gradle-log lines. ROADMAP line 162 notes the reverse side lacks this too.

**Deferred, and flagged as its own roadmap item.** Building a forward-only report format now would
diverge from reverse, which has the identical gap. The right move is one shared structured-report
item covering both directions, sequenced after both directions emit through their respective
in-memory diagnostic models. v1 forward stays KSP-diagnostic-only, which already beats reverse by
carrying source location.

## Decision

Adopt **Alternative 1**. Give the forward direction a defined bridgeable subset and a single named
diagnostic model, mirroring ADR-043 / `RirDiagnostic`.

### The forward bridgeable subset (v1)

The catalogue below is assembled from the ADR-060 cell matrix, the ADR-062 legacy-route table, the
classifier's `Unsupported` sites, and the planner's silent-skip sites. Every "cannot express" row is
a **skip + named diagnostic**; the one collision row is **fatal**.

| Construct | Can the forward direction express it? | Disposition | Where classified |
|---|---|---|---|
| Primitives, `Char`, `String`, `Unit`/void, enums | Yes | bridged | classifier `Primitive`/`Char`/`String`/`Enum` |
| Object handles (classes/objects in the export set) | Yes | bridged | classifier `ObjectHandle` |
| Value classes, incl. methods **with parameters** (ADR-062 Ph.9) | Yes | bridged | classifier `ValueClass` |
| `List`/`MutableList` returns and inputs; `Map`/`Set`/`MutableMap`/`MutableSet` **returns** (ADR-062 Ph.8) | Yes | bridged | classifier `Collection` |
| Nullable of the above (per ADR-002/061 position rules) | Yes | bridged | classifier `Nullable` |
| Sealed classes, interfaces, data classes, generics, suspend, `Flow`, lambdas | Yes, via **named legacy routes** (ADR-009/010/012/019/026/037/039) | bridged (not via plan) | `SpecializedProtocol`, `droppedFromCSharp = false` |
| **Generic + `suspend` + `inline` + `reified` extension returning `Result<T>`** (cell 23, BUG-010) | **No**; the *combination* has no working legacy route. Today it is a **silent drop** (the Phase 10 plan-null guard already suppresses the raw emit), just unnamed | **reclassify → skip + `SKIPPED_UNSUPPORTED_COMBINATION`** | planner `extensionEntry`, below |
| **Value-class inherited members** (`CharSequence by value`; ROADMAP line 77) | **No** (v1 product decision). **Currently exported** (`getAllFunctions()`/`getAllProperties()` don't filter by `parentDeclaration`), so `length`/`get`/`isEmpty` bind clean today, a behavioral change, not a rename | **filter out + skip + `SKIPPED_INHERITED_MEMBER`** | value-class path |
| **`Map`/`Set` (and mutable) as method *parameters*** (ROADMAP line 78, no `CreateMap`/`CreateSet` helper) | **No** (v1) | **skip + `SKIPPED_UNSUPPORTED_INPUT`** | planner `inputSkipReason()` = `COLLECTION` |
| **Nullable `Boolean` method return** (ROADMAP line 79, ADR-061 deferred width) | **No** (v1) | **skip + `SKIPPED_UNSUPPORTED_RETURN`** | planner `NULLABLE` |
| **`Char` at positions ADR-062 did not close**, and other `Unsupported` types (`Sequence`, local/anonymous, non-exported handle, bare type parameter) | **No** | **skip + `SKIPPED_UNSUPPORTED_TYPE`** | classifier `Unsupported` |
| **A property whose type `ForwardPropertyPlanner.isPlannable` rejects** (added later, no ADR: this completes the position naming below) | **No** | **skip + `SKIPPED_UNSUPPORTED_PROPERTY`** | `ForwardPropertyPlanner.recordDropped`, excluding lambda/suspend-lambda/`Flow`/`StateFlow`, which still bind via a legacy route |
| **An extension property whose *receiver* type is unsupported** (added later, no ADR: closes this ADR's position coverage) | **No** | **skip + `SKIPPED_UNSUPPORTED_PROPERTY`** | `ForwardPropertyPlanner.extensionProperty`, naming the receiver's classified type; no legacy route re-emits by receiver, so no exclusion is needed |
| **Variance (`out`/`in`) on a class type parameter** | Partially, dropped, member still binds | **`INFO_DROPPED_VARIANCE`** (note, not skip) | `CirClassTranslator.kt:561` |
| **Two constructors that collapse to one C# signature** (cell 21, ADR-034) | **No, and ambiguous** | **`ERROR_CSHARP_SIGNATURE_COLLISION`, fatal** | `CirClassTranslator.kt:92` / `CirFunctionTranslator.kt:89` |

The kind names above are proposals; the load-bearing decisions are (a) the prefix convention encodes
severity exactly as reverse, and (b) the collision stays the single fatal case.

### The diagnostic model

A record and a kind enum in `nuget-processor`, deliberately parallel to `RirDiagnostic` /
`RirDiagnosticKind`:

```kotlin
internal data class ForwardDiagnostic(
  val kind: ForwardDiagnosticKind,
  val symbol: KSNode?,          // the originating declaration, reverse cannot carry this
  val declaration: String,      // e.g. "HttpClient.get" (for the log line and for tests)
  val signature: String,        // rendered parameter/return shape
  val reason: String,
  val hint: String,             // the actionable escape ("expose a non-suspend adapter", etc.)
)

internal enum class ForwardDiagnosticKind(val severity: Severity) {
  SKIPPED_UNSUPPORTED_TYPE(Severity.WARNING),
  SKIPPED_UNSUPPORTED_INPUT(Severity.WARNING),
  SKIPPED_UNSUPPORTED_RETURN(Severity.WARNING),
  SKIPPED_UNSUPPORTED_COMBINATION(Severity.WARNING),   // cell 23
  SKIPPED_INHERITED_MEMBER(Severity.WARNING),          // ROADMAP L77
  SKIPPED_UNSUPPORTED_PROPERTY(Severity.WARNING),      // added later, completes the position naming
  INFO_DROPPED_VARIANCE(Severity.INFO),
  ERROR_CSHARP_SIGNATURE_COLLISION(Severity.ERROR),    // cell 21, ADR-034
}
```

**Later addition, same sink:** `SKIPPED_UNSUPPORTED_PROPERTY` closed the one position this ADR's
original table left unnamed. `SKIPPED_UNSUPPORTED_INPUT` covers a parameter and
`SKIPPED_UNSUPPORTED_RETURN` a return, but a property `ForwardPropertyPlanner.isPlannable` rejected
still vanished with no diagnostic at all (tracked on `ROADMAP.md`). `ForwardPropertyPlanner` now
records every property it declines to plan and routes it through the same `ForwardDiagnosticSink`,
except a lambda/suspend-lambda/`Flow`/`StateFlow`-typed property (nullable or not), which is
unplannable by design and still bound by `CirClassTranslator`'s legacy adapters, so naming it would
be a false positive.

**Later addition, same sink:** the position table above still had one gap after
`SKIPPED_UNSUPPORTED_PROPERTY` landed: an extension property whose *receiver* type is unsupported
(`ForwardPropertyPlanner.extensionProperty`'s bare `return null`) is a distinct drop site from the
type-based one, since the property's own declared type is usually fine and naming it would send the
reader after the wrong declaration. A new `ForwardDroppedExtensionReceiver` record and
`warnDroppedForwardExtensionReceivers` function feed the same `SKIPPED_UNSUPPORTED_PROPERTY` kind,
this time naming the receiver's classified type instead of the property's own. This closes the
position coverage this ADR set out to have; no new diagnostic kind was needed.

Severity is encoded both by the `SKIPPED_/INFO_/ERROR_` name prefix (so it reads like reverse in a
log) and by an explicit field (so the sink does not string-match its own enum). The message format
matches reverse's `formatDiagnostic()` house style, differing only in that the forward line can carry
a source location from `symbol`:

```
w: [nuget] Skipping HttpClient.get(reified T, block): generic suspend inline extension returning
   Result<T> has no bridge, inline+reified erases at the C ABI and suspend needs a concrete
   continuation type. Expose a non-inline, non-generic wrapper (e.g. suspend fun getString(...):
   String) and export that instead.
     at NicknameSample.kt:18
```

**Message-format contract:** the rendered line embeds the kind's `.name()` (e.g. bracketed as
`[SKIPPED_UNSUPPORTED_COMBINATION]`) so a test can assert the exact kind fired without a structured
capture seam on top of `KSPLogger` (which carries only `String` + optional `KSNode`). The Tier 1
diagnostic-mode tests assert `kspWarnings.any { it.contains(ForwardDiagnosticKind.X.name) }` against
the real enum, so a rename is caught at compile time. Keep the kind name in the text.

**Severity policy (mirrors ADR-057):**

- `SKIPPED_*` → `logger.warn(message, symbol)`; generation continues, the member is **absent** from
  the C# API (never emitted as an `IntPtr`/`"0"` fallback). This is the default for every "cannot
  express" construct.
- `INFO_*` → `logger.warn(message, symbol)` phrased as a "Note"; the member **still binds** under a
  documented assumption (e.g. variance dropped). Kept at `warn` (not `info`) so it is visible by
  default, matching reverse's treatment of `INFO_OBLIVIOUS_NULLABILITY`.
- `ERROR_*` → `logger.error(message, symbol)`; generation **fails**. The only v1 member is the
  ADR-034 C# constructor-signature collision, which is already fatal today. It is fatal because the
  two constructors are genuinely ambiguous: silently dropping one would change the API contract
  unpredictably.

### Where the decision lives

Two producers, one sink.

1. **Ordinary callables**, the `ForwardCallablePlanner` already emits
   `ForwardCallableCatalogEntry.Skipped(symbol, reason)` and exposes `droppedCallables` (the
   `droppedFromCSharp = true` subset). Replace the generic string in `warnDroppedForwardCallables`
   with a translation from `ForwardPlanSkipReason` to a `ForwardDiagnostic` (mapping
   `COLLECTION`-input → `SKIPPED_UNSUPPORTED_INPUT`, `NULLABLE`-boolean-return →
   `SKIPPED_UNSUPPORTED_RETURN`, `UNSUPPORTED`/`CHAR`/`HANDLE`/… → `SKIPPED_UNSUPPORTED_TYPE`) and
   attach the originating `KSNode`. This closes ROADMAP lines 78-79's silent skips.

2. **Cell-23 reclassification** (planner `extensionEntry`). The verified probe (Context, above) shows
   the emitter is **already** guarded: `addExtensionFunctionExports` only emits plan-backed callables
   and no-ops on a null plan (Phase 10), so no raw `Function1`/`Result` is written, and **no
   legacy-emitter guard is needed**. The one gap is that the combination is silently classified
   `Skipped(SUSPEND)` with `droppedFromCSharp = false`, so it never surfaces a diagnostic. The change:
   recognize the unbridgeable combination (extension **and** generic/reified **and** suspend **and**
   `Result<T>` return) in `extensionEntry`, ahead of the structural `SUSPEND`/`GENERIC` buckets, as a
   genuine drop carrying `SKIPPED_UNSUPPORTED_COMBINATION`. **Value-class inherited members** are the
   sibling case: `valueClassMethodEntries` / `valueClassPropertyEntries` call `getAllFunctions()` /
   `getAllProperties()` with no inheritance filter, so inherited members bind clean today. Filter them
   out and emit `SKIPPED_INHERITED_MEMBER` per excluded member. Note `parentDeclaration != cls` alone
   is insufficient: a `CharSequence by value` delegation forwards the delegate's abstract members
   (`get` / `subSequence` / `length`) as `Origin.SYNTHETIC` with `parentDeclaration == cls`, so the
   filter must also exclude `origin != Origin.KOTLIN` (JDK default methods like `isEmpty` / `chars` are
   `Origin.JAVA_LIB`; only genuinely `Origin.KOTLIN` members declared on the value class itself stay). The today-scattered
   CIR-translator `logger.warn` / `logger.error` calls (variance, unsupported property/function,
   constructor collision) are rewritten to construct `ForwardDiagnostic`s and route through the same
   sink, so message style and severity are decided in one place.

The sink lives at the `NugetProcessor` level (it already owns `warnDroppedForwardCallables` and both
generation passes), collecting `ForwardDiagnostic`s from both producers and emitting them before
`cNameExports.writeTo(...)`. The `ERROR_*` case must abort **before** the invalid Kotlin is written,
which the existing `logger.error` at `CirClassTranslator.kt:92` already does for cell 21.

**Ordering note:** the ADR-063 export-scoping filter runs *before* the planner, so an out-of-scope
declaration is silently not bridged and never reaches this diagnostic, which is correct: a package
the author excluded is not "unsupported," it is "not asked for." Only in-scope declarations produce
forward diagnostics.

### Testing seam

No new harness. [ADR-060](060-adversarial-forward-fixture.md)'s Tier 1 harness already has a
**diagnostic assertion mode**: it supplies the `KSPLogger`, so `logger.warn`/`logger.error` are
captured directly (ADR-060 Decision, "Three assertion modes fall out of one harness"). Cell 23 is
already a permanent Tier 1 diagnostic cell whose assertion is "skipped with a diagnostic naming it";
this ADR supplies the concrete kind and message to assert on. Cell 21 is the fatal-diagnostic cell.
Each new named skip (value-class inheritance, `Map`/`Set` input, nullable-boolean return) gets a Tier
1 diagnostic cell asserting the kind fires **and** that no broken source is emitted, the two halves
that together define "honest skip" rather than "silent drop" or "invalid Kotlin." This also begins to
pay down ROADMAP line 264 (the forward processor's thin unit-test seam), for the diagnostic slice.

## Consequences

- The forward direction gains a written bridgeable-subset boundary and a `ForwardDiagnostic` /
  `ForwardDiagnosticKind` model that reads like the reverse `RirDiagnostic` in a build log, with the
  added source location KSP affords.
- `warnDroppedForwardCallables`'s single generic string is replaced by structured, per-kind messages;
  the scattered CIR-translator `logger.warn`/`logger.error` calls converge on one sink and one format.
- **The cell-23 combination becomes a named skip instead of a silent drop** (the Phase 10 plan-null
  guard already stopped the invalid-Kotlin emit ADR-060's spike recorded; the remaining gap was
  silence). Naming it is what lets the reachability-closure export item (ROADMAP line 14) treat it as a
  known skip rather than an unexplained gap when the closure drags it back in.
- **Value-class inherited members go from silently exported to a named skip** (a behavioral change: a
  `parentDeclaration` filter is added to the value-class paths).
- ROADMAP lines 78-79's silent planner skips (`Map`/`Set` inputs, nullable-boolean returns) become
  named diagnostics.
- No public C# API change, no native ABI change, no generated-consumer change: this is generator
  tooling only, like ADR-055.

### Scope

**v1 (this ADR):**
- The subset table above, as named skips (WARNING) with `KSNode` source location.
- The one fatal case (`ERROR_CSHARP_SIGNATURE_COLLISION`), unchanged from ADR-034/ADR-057 policy.
- One sink; both the plan path and the legacy-route path route through it.
- Tier 1 diagnostic-mode assertions for cells 21 and 23 and each newly-named skip.

**Deferred, and each its own roadmap item:**
- A **structured/queryable diagnostics report** (JSON) covering *both* directions (Alternative 4);
  reverse has the same gap (ROADMAP line 162), so this should be one shared item, not a forward-only
  format.
- The **value-class inherited-members** product decision itself (ROADMAP line 77), whether these are
  ever in scope, or permanently skipped. This ADR only guarantees they skip with a name rather than
  break the build; it does not decide their long-term disposition.
- **Un-skip / remap escape hatch** (the Xamarin `Metadata.xml` analogue, or a `@HiddenFromObjC`-style
  opt-out), out of scope; the hint text names the hand-written-adapter workaround instead, as ADR-043
  does for reverse.
- Raising the specialized-protocol legacy routes to produce their own diagnostics uniformly (rather
  than the targeted cell-23 guard), folds into the ADR-062 per-protocol plan-adapter follow-ups.

## Inferred vs Verified claims in this ADR

**Verified (repository source, read this session):**
- `warnDroppedForwardCallables` logs one generic warning per dropped ordinary callable
  (`NugetProcessor.kt:86-96`).
- `ForwardPlanSkipReason` carries `droppedFromCSharp`, distinguishing genuine drops (warn) from
  legacy-route deferrals (silent) (`ForwardCallablePlanner.kt:25-49`).
- `BridgeType.Unsupported(rendered, reason)` exists and is produced at five classifier sites
  (`ForwardMarshallingModel.kt:48`; `ForwardBridgeTypeClassifier.kt:42/53/92/98/112`).
- `skipReason()` / `inputSkipReason()` map every `BridgeType` to a reason
  (`ForwardCallablePlanner.kt:971-1009`).
- The five scattered forward diagnostic sites and their two severities (`CirClassTranslator.kt:92`
  error, `:561` warn, `:675` warn; `CirFunctionTranslator.kt:89` error, `:585` warn).
- `extensionFunctions` carries no suspend/generic filter and is passed to the legacy emitters
  (`NugetProcessor.kt:153-158`, `:280-289`), the cell-23 gap.
- Reverse `RirDiagnostic` / `RirDiagnosticKind` shape, the `SKIPPED_/ERROR_/INFO_` prefix convention,
  `validateDiagnostics()` making `ERROR_*` fatal via `require`, and `diagnosticWarnings` →
  `logger.warn` (`RirModel.kt:207-276`; `NugetGenerateBindingsTask.kt:2842-2977`).
- `KSPLogger.warn`/`error`/`info`/`logging` each have signature `(message: String, symbol: KSNode? =
  null)` (KSP `KSPLogger.kt` source, read this session).
- ADR-060's Tier 1 harness has a diagnostic assertion mode supplying the `KSPLogger`, and cell 23 is a
  permanent Tier 1 diagnostic cell (ADR-060, read this session).

**Verified by a fresh Tier 1 probe this session (corrects an earlier claim):**
- The cell-23 shape is a **silent drop today**, not invalid Kotlin. `extensionEntry` matches
  `Modifier.SUSPEND` first → `Skipped(SUSPEND)`, `droppedFromCSharp = false` → no warning; and
  `addExtensionFunctionExports` no-ops on a null plan (Phase 10) → no raw `Function1`/`Result`. The
  probe (`suspend inline fun <reified T> Patient.chartEntry(entry: T): Result<T>`) returned
  `kspExitCode = OK`, `kspWarnings = []`, `kspErrors = []`, and the export absent from
  `CNameExports.kt`. ADR-060's Verification section (invalid Kotlin, four failure modes) recorded the
  **pre-guard** state and is now stale. So no legacy-emitter guard is needed; the fix is a planner
  reclassification into `SKIPPED_UNSUPPORTED_COMBINATION`.
- Value-class inherited members (`CharSequence by value`) are **not filtered today**:
  `valueClassMethodEntries` / `valueClassPropertyEntries` call `getAllFunctions()` /
  `getAllProperties()` without a `parentDeclaration` filter, so `length` / `get` / `isEmpty` bind
  clean. Skipping them is a behavioral change, not a rename.

**Inferred (documentation / spec, not executed):**
- Kotlin ObjC/Swift Export does not emit per-declaration skip diagnostics and uses opt-out annotations
  (`@HiddenFromObjC`, `@ShouldRefineInSwift`) instead (Kotlin docs). Load-bearing only for the "our
  skip-diagnose convention is our own, not inherited" framing.
- Xamarin surfaces skipped Java members as MSBuild warnings with a `Metadata.xml` escape hatch
  (Microsoft docs, via ADR-043).
- That attaching a `KSNode` to `logger.warn`/`error` causes KSP/Gradle/IDE to render the message at
  the author's Kotlin source position. The signature is verified; the **rendering behaviour** is not
  spiked here. If it does not render as expected, the diagnostics still fire and still name the member
  in text, only the clickable-source-location nicety is lost, so nothing silently breaks.
- (Resolved) The cell-23 routing question this section originally flagged is now **verified** above:
  `extensionEntry` takes the silent `Skipped(SUSPEND)` branch and the emitter is already plan-guarded,
  so neither hypothesized fix (upgrade an existing warning, or add a legacy-emitter guard) applies. The
  actual fix is a planner reclassification. Kept here as a record that the claim was checked before
  implementation, per the process that flagged it.

## Amendment (2026-09-07): a public `annotation class` skips with a name

Judgement: an **amendment**, not a new ADR. This closes the ROADMAP Phase 3 item
"A public `annotation class` produces no diagnostic at all" (`docs/backlog/public-annotation-class-produces-no-diagnostic.md`).
It adds one `ForwardDiagnosticKind` member and one root bucket; it introduces no mechanism this ADR
does not already describe (one sink, `SKIPPED_*` = absent from C#, `KSNode` source location,
ADR-100's `NugetDiagnostics.json` delivery). Status stays Accepted.

Mechanism claims are labelled **Verified** (read in this repository's source on 2026-09-07; no
build was run, another agent held the Gradle lock) or **Inferred**.

### The gap

`ClassKind.ANNOTATION_CLASS` has no route in the forward direction: every root bucket in
`NugetProcessor.kt:396-440` keys on `CLASS`, `OBJECT`, `ENUM_CLASS` or `INTERFACE`, and `grep
ANNOTATION_CLASS nuget-processor/src/main` returns zero hits (**Verified**). A public annotation
class therefore passes `isExported` (`:265-277`), lands in `allDeclarations` (`:296`), and is then
matched by nothing, so it is absent from the generated C# with no diagnostic: exactly the silence
this ADR exists to remove. An `expect annotation class` is dropped one line earlier by the
`isExpect` filter (`:293`) and its `actual` then meets the same fate.

### Decision

**One new kind, `SKIPPED_ANNOTATION_CLASS`, severity `WARNING`, no `verb` override.**

- `WARNING`, not `INFO`: this ADR's severity policy reserves `INFO_*` for a member that **still
  binds** under a documented assumption. An annotation class binds nothing, so it is a `SKIPPED_*`
  like every other "cannot express" construct.
- No `verb` override (ADR-109): the type genuinely is skipped, so the severity-keyed "Skipping" is
  the truth, unlike `WARNING_DUPLICATED_DEPENDENCY_TYPE`.
- Once per public annotation class, `symbol` = the declaration, so the rendered line carries the
  author's own `file:line`.

Shipped enum entry (`forward/ForwardDiagnostic.kt`, beside `SKIPPED_ALL_DECLARATIONS`):

```kotlin
/** A public `annotation class`. Annotations are metadata for the Kotlin compiler and reflection;
 *  there is no C# projection of one worth generating (a .NET attribute would never be applied to
 *  anything, since the Kotlin usages do not cross the bridge). Usages of the annotation on exported
 *  declarations are unaffected: the forward pipeline reads no annotation but `kotlin.native.CName`
 *  (`NugetProcessor.kt:211-215`, `ForwardAbiContract.kt:262`). Top-level declarations only; nested
 *  declarations of any kind are outside the forward funnel. */
SKIPPED_ANNOTATION_CLASS(ForwardDiagnosticSeverity.WARNING),
```

Shipped bucket + emission (`NugetProcessor.kt`, directly after `rootInterfaces` at `:440`, so it
sits before the `hasNothingToProcess` early return at `:557`; that return already writes
`NugetDiagnostics.json` (`:560`), so a module whose only public declaration is an annotation class
still gets the diagnostic into the file `nugetReportDiagnostics` re-emits). The bucket also filters
`parentDeclaration == null`: this both narrows the top-level-only claim below from an inferred
`KSFile.declarations` property to a structural filter on the bucket itself, and, together with the
public-visibility filter, means a nested annotation class (of any visibility) is never matched here:

```kotlin
val annotationClasses: List<KSClassDeclaration> = allDeclarations
  .filterIsInstance<KSClassDeclaration>()
  .filter { it.getVisibility() == Visibility.PUBLIC }
  .filter { it.classKind == ClassKind.ANNOTATION_CLASS }
  .filter { it.parentDeclaration == null }
ForwardDiagnosticSink.emit(
  annotationClasses.map { annotation ->
    ForwardDiagnostic(
      kind = ForwardDiagnosticKind.SKIPPED_ANNOTATION_CLASS,
      symbol = annotation,
      declaration = annotation.qualifiedName?.asString() ?: annotation.simpleName.asString(),
      reason = "annotation classes are not bridged; there is no C# projection of a Kotlin " +
          "annotation, so nothing is generated for it",
      hint = "usages of it on exported declarations are unaffected. Make it internal, or " +
          "exclude(...) its package, if the warning is unwanted",
    )
  },
  logger,
)
```

Rendered (per `format()`, `:160-171`):
`[nuget:SKIPPED_ANNOTATION_CLASS] Skipping io.github.xxfast.kotlin.native.nuget.test.cat.Tagged: annotation classes are not bridged; there is no C# projection of a Kotlin annotation, so nothing is generated for it. usages of it on exported declarations are unaffected. Make it internal, or exclude(...) its package, if the warning is unwanted\n    at .../Tagged.kt:N`

### What it does and does not cover

- **`expect annotation class`: the diagnostic fires, once, on the `actual`.** ADR-074's amendment
  item 6 called it "not applicable" because no route existed; a diagnostic is now the route. The
  `isExpect` filter (`:293`) drops the expect half, the `actual annotation class` enters
  `allDeclarations`, and the new bucket names it, with `symbol` pointing at the actual's file. That
  is the same "the `actual` is the export root" rule every other ADR-074 kind follows, so no
  special case. ADR-074's "not applicable" verdict on the *mapping* stands; only its silence ends.
- **Annotation usages are unaffected.** `@Tagged class Toy` keeps exporting exactly as before. The
  forward pipeline reads annotations at exactly two sites, both matching `kotlin.native.CName` by
  qualified name (`NugetProcessor.kt:211-215`; `ForwardAbiContract.kt:262`) (**Verified**), so a
  user annotation is inert and produces no second diagnostic.
- **Top-level only, structurally.** The bucket filters `parentDeclaration == null`, so this is no
  longer resting on the `KSFile.declarations`-yields-only-top-level-declarations inference the
  original proposal made: even if `allDeclarations` (`resolver.getAllFiles().flatMap {
  it.declarations }`, `:283-284`) ever did carry a nested declaration, the filter excludes it
  explicitly. A nested `annotation class` inside an exported class is therefore never matched here.
  That is the same silence every nested class kind has today (no forward route for nested
  declarations at all) and is out of scope here.
- **`internal`/`private` annotation classes: silent**, like every other bucket's visibility gate.
- **An annotation *instance* at a bridged position** (Kotlin 1.6+ annotation instantiation, e.g.
  `fun tag(): Tagged`) is not touched: the classifier already treats it as an unexported handle
  type and names that skip on the member. Not exercised; edge case, deferred.
- **No plugin change.** `NugetReportDiagnosticsTask` parses `kind` as a plain `String`
  (`NugetReportDiagnosticsTask.kt:71,115`) and `ForwardDiagnosticsFile.kt:31` writes `kind.name`,
  and no `when` in `nuget-processor/src/main` is exhaustive over `ForwardDiagnosticKind`
  (**Verified**, grep), so a new member is additive.

### Files touched

1. `nuget-processor/.../forward/ForwardDiagnostic.kt`: the enum member.
2. `nuget-processor/.../NugetProcessor.kt`: the bucket and emission after `:440`.
3. `nuget-processor/src/test/.../tier1/Tier1AnnotationClassSkipTest.kt` (new), modelled on
   `Tier1UnexportedSupertypeSkipTest.kt:46-90` (`kspWarnings.firstOrNull { it.contains(kind.name) }`)
   and the `expect` cell shape of `Tier1ExpectActualDeclarationsTest.kt:412-440` (assert on
   `kspWarnings`, never `compiledClean`, per the ADR-074 amendment's harness caveat):
   - a public `annotation class Tagged(val tag: String)` yields exactly one
     `SKIPPED_ANNOTATION_CLASS` naming it, and no `Tagged` in `generatedCSharp`;
   - `@Tagged("x") class Toy(val name: String)` in the same fixture still yields `export_toy_get_name`
     and exactly one diagnostic (the class, not the usage);
   - `internal annotation class` yields none;
   - `expect annotation class` + `actual annotation class` yields exactly one, and its `at` line
     points at the actual's file.
4. `test-library/src/nativeMain/.../test/cat/Tagged.kt` (new): `public annotation class Tagged(val
   tag: String)`; `Toy.kt:3` gains `@Tagged("plaything")`.
5. `IntegrationTests/AnnotationClassTests.cs` (new): xunit can only pin absence and non-regression,
   `typeof(Toy).Assembly.GetTypes()` has no `Tagged` (precedent `Issue42Tests.cs:59`), and `new
   Toy(...)` still round-trips. The diagnostic itself is asserted at Tier 1 only.
6. Docs: ROADMAP line 21 and the backlog note go; FEATURES.md gains the row.

### Consequences of the amendment

- Every public annotation class in a forward-published module now costs one build warning per
  `packNuget`. That is the intended trade: this ADR's whole premise is that silence is worse than a
  warning the user can act on (`internal`, or `exclude(...)`).
- `test-library`'s build log permanently carries one `SKIPPED_ANNOTATION_CLASS` line for `Tagged`,
  alongside the named skips it already carries (`Issue54Tests.cs`, `Issue56Tests.cs`).
- Nothing changes in generated C#, Kotlin exports, or the ABI.

## Amendment (2026-09-07): an absent declaration leaves no husk

Judgement: an **amendment**, not a new ADR. This closes the ROADMAP Phase 3 item "A file whose only
top-level declaration is skipped still emits an empty, pointless `public static partial class X { }`
stub into `Interop.cs`" (`docs/backlog/file-whose-only-top-level-declaration-skipped.md`). It adds no
new diagnostic kind; the named skips this ADR already produces (`SKIPPED_UNSUPPORTED_INPUT` and its
siblings) are what leaves a file's static class empty in the first place. This amendment is about
what the emitter does with that emptiness, not about naming it. Status stays Accepted.

`CirTranslator` builds one `CirStaticClass` per (namespace, file) group across five separate
contribution loops (sync functions, suspend functions, generic functions, properties, and the
extension loops), each of which merges into that class by name. The extension loops already guarded
their own two emission sites with an `isNotEmpty()` check, so an extension receiver with nothing
left never got a class. The other loops did not, so a file whose every top-level declaration was
named-skipped (a `Map`/`Set` parameter, a nullable-`Boolean` return, `SKIPPED_UNSUPPORTED_INPUT`, or
any other `SKIPPED_*`) still emitted `public static partial class X { }`: compiles, carries no
`DllImport`, but is a scar left by a skip that a consumer reading IntelliSense cannot tell apart from
a class whose members are merely still to come.

The fix is a single sweep at `CirFile` assembly, after every loop has merged and after the
suspend/lambda helpers have been folded into the root namespace (it cannot be a per-loop guard: a
file with a skipped sync function and a surviving `suspend fun` contributes an empty member list in
one loop and the survivor in another loop, so only the fully merged set can say "empty"):

```kotlin
private fun List<CirNamespace>.withoutEmptyStaticClasses(): List<CirNamespace> = this
  .map { namespace ->
    namespace.copy(
      declarations = namespace.declarations.filterNot { it is CirStaticClass && it.members.isEmpty() },
    )
  }
  .filter { it.declarations.isNotEmpty() }
```

A `CirStaticClass` with no members is dropped, and a namespace left with no declarations at all is
dropped with it. Contract-neutral: an empty static class carried no `DllImport`s, so nothing an
existing consumer could be calling disappears.

Three fixtures pin the three outcomes in one pack: `test-library/.../test/husk/HuskOnly.kt` (its
only declaration, `fun scan(items: List<List<String>?>)`, is named-skipped, so `HuskOnly` no longer
appears anywhere in the generated C#), `husk/HuskMixed.kt` (one skipped function plus a surviving
`fun ping(): Int = 1`, so the class stays with only `ping` on it, the control that stops "elide when
empty" degrading into "elide when anything was skipped"), and `chaff/ChaffOnly.kt` (the only file in
its package, so the whole `TestLibrary.Chaff` namespace goes with it). `IntegrationTests/EmptyStaticClassTests.cs`
asserts the compiled absence/presence from the C# side; `Tier1EmptyStaticClassElisionTest` pins the
same three shapes, plus a fourth cross-loop case (a skipped sync function and a surviving `suspend
fun` in the same file) that rules out a per-loop guard.

## Amendment (2026-09-07): a class with no reachable constructor stays, and says so

Judgement: an **amendment**, not a new ADR. This closes the ROADMAP Phase 3 item "A class whose only
constructor is skipped ships as a dead public type"
(`docs/backlog/class-whose-only-constructor-skipped-ships-dead.md`). It adds one
`ForwardDiagnosticKind` member and one call site; no new mechanism beyond what this ADR already
describes. Status stays Accepted.

Mechanism claims are labelled **Verified** (read in this repository's source on 2026-09-07) or
**Inferred**.

### The gap

A class whose every public Kotlin constructor is skipped (any reason: an unsupported parameter
type, a legacy-route deferral like `SEALED_PROTOCOL`, a value-class parameter) still generates a C#
type, but one carrying only its `internal Foo(IntPtr handle)` constructor. `Issue54Drawing`
(`docs/adr/105-sealed-property-position.md`) is exactly this shape: all four of its constructor
parameters are sealed-typed, so the primary constructor never reached a plan. Before this
amendment, that outcome had no diagnostic anywhere: the per-constructor `SKIPPED_*` warning fires
only for a `droppedFromCSharp = true` skip, and a legacy-route deferral (`droppedFromCSharp =
false`, the `SEALED_PROTOCOL` case) never reaches `droppedCallables` at all, since no legacy route
re-emits a constructor. A consumer saw a public type with no way to construct it and no explanation
anywhere in the build log.

### Decision

**Keep the type. Do not drop it.** `exportedTypes` and the `ObjectHandle` classifier admit a class
by declaration, not by constructor outcome, so a class in this state can still reach C# through a
Kotlin factory that returns it, `Issue54Drawing`'s own `sleepingCats()`/`curledCats()` prove exactly
that. Dropping the class would need a "does anything reference this type" reachability check this
ADR has no closure for (ADR-066's closure walks return/parameter/property types outward from
already-admitted declarations; it does not compute the inverse, "is this admitted type used").
Building that just to decide whether to hide a class would be a large, separately-scoped feature for
a small win, and a false verdict (a factory this specific check missed) would silently change the
public surface. Say so instead.

**One new kind, `WARNING_NO_PUBLIC_CONSTRUCTOR`, severity `WARNING`, `declaredVerb = "Keeping"`.**
Not `SKIPPED_*`: the type is not skipped, it is kept, and this ADR's severity policy already has the
precedent for a kind that fires at `WARNING` but overrides its verb because nothing in the output
changes (`WARNING_DUPLICATED_DEPENDENCY_TYPE`, ADR-109). `WARNING_NO_PUBLIC_CONSTRUCTOR` follows the
same shape: `WARNING`, not `SKIPPED_*` or `INFO_*`, because neither existing prefix's meaning fits
(nothing is skipped, and the class does not "still bind" the way an `INFO_*` kind's subject does),
and `declaredVerb = "Keeping"` says plainly that the class stays.

Fired from `CirClassTranslator`'s `translateClass`, once per class, when the class is not abstract,
its Kotlin declaration has at least one public constructor, and the callable catalog produced zero
C# constructors for it:

```
if (!isAbstract && cirConstructors.isEmpty() && cls.hasPublicConstructor()) {
  warnNoPublicConstructor(cls, name, callableCatalog, logger)
}
```

The message lists every skipped constructor by name and reason, read off
`ForwardCallablePlanCatalog.skippedConstructors(owner)`, a catalog query added alongside this kind
and **deliberately not filtered by `droppedFromCSharp`**, unlike the sibling `droppedCallables`
query the per-constructor warning uses: that flag distinguishes a genuine drop from a method still
reachable through a legacy route, and no legacy route re-emits a constructor, so a
`SEALED_PROTOCOL`/`GENERIC` constructor skip is exactly as absent from the C# surface as an
`UNDECLARED_ENUM` one. Shipped, verified against the fixture's `NugetDiagnostics.json`:

```
[nuget:WARNING_NO_PUBLIC_CONSTRUCTOR] Keeping Issue54Drawing: every public constructor is skipped
    (<init>: SEALED_PROTOCOL), so the generated C# class has only its internal handle constructor
    and C# cannot construct one. the type is kept because instances can still come from Kotlin
    factories that return it (a top-level function, or a companion factory); expose one, or change
    the constructor parameters to types the bridge can express
    at Issue54Sample.kt:52
```

`Issue56Failure` (`docs/adr/107-throwable-property-mapping.md`) fires the same kind for an
unrelated reason (`NULLABLE`, not `SEALED_PROTOCOL`), which is what the "whatever the reason" wording
in the diagnostic covers rather than naming one skip family:

```
[nuget:WARNING_NO_PUBLIC_CONSTRUCTOR] Keeping Issue56Failure: every public constructor is skipped
    (<init>: NULLABLE), so the generated C# class has only its internal handle constructor and C#
    cannot construct one. the type is kept because instances can still come from Kotlin factories
    that return it (a top-level function, or a companion factory); expose one, or change the
    constructor parameters to types the bridge can express
    at Issue56Sample.kt:41
```

Not fired for an abstract class (uninstantiable by design, and never expected to have a public
constructor) or for the ADR-040 interface-return backing wrapper (`translateInterfaceBackingClass`,
which is never handle-less by accident).

### Alternatives considered

- **Drop the type from the generated C# entirely when unreferenced.** Rejected: there is no
  "is this type referenced" reachability set today (see above), and a false verdict would silently
  remove part of the public API rather than merely warn about it. The backlog item's own "two
  candidate fixes, not decided" left this open; this amendment decides against it.
- **An XML `<remarks>` doc comment on the generated C# class itself**, so the signal reaches a
  consumer's IDE tooltip, not just the library author's build log. **Deferred.** The forward
  generator emits no `///` doc comments anywhere today (`CirClassRenderer` has no XML-doc rendering
  path at all), so this would be new renderer machinery, not a small addition to an existing one.
  Tracked as its own ROADMAP Phase 3 item.

### Testing seam

No new harness; the existing Tier 1 diagnostic-assertion mode covers it.
`Tier1NoPublicConstructorWarningTest.kt` pins: a `droppedFromCSharp = true` skip warning once and
keeping the type (`Dial`, an unsupported nested-enum parameter); a `droppedFromCSharp = false`
legacy-route deferral warning too (`Drawing`, a sealed parameter); a class with one surviving
constructor never warning (`Meter`); an abstract class never warning (`Gauge`); and a factory
returning the unconstructible class still binding (`make(): Drawing`). No `IntegrationTests` change:
the shape was already exercised by the existing `Issue54Tests.cs`/`Issue56Tests.cs` fixtures, and
xunit can only assert on the generated C# surface, not on a Gradle-log diagnostic.

### Consequences of the amendment

- A class whose every public constructor is skipped, for any reason, now costs one build warning
  per `packNuget`, naming the class and every skipped constructor's reason, instead of shipping
  silently as a public type nothing can construct.
- No generated C#, Kotlin export, or ABI change: `Issue54Drawing` and `Issue56Failure` keep the exact
  shape they already had, only their diagnostics change.
- The "drop vs keep" product decision for an unreferenced dead type stays open, deferred behind a
  reachability-of-admitted-types check this ADR does not build.
