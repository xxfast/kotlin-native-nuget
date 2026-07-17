# ADR-060: The adversarial forward fixture — one red seam, two green guards, a corpus split by observability

## Status

Accepted. Steps 1–3 shipped: the Tier 1 harness, the strict-`@XFail` red assertions for all 19 red cells, and the `clinic` corpus's non-quarantined half (green in `test-library`). The 12 generator fixes are **not** in this delivery — each is a future commit deleting one `@XFail` and adding its Tier 2/Tier 3 regression surface. Inferred-claim #2 (the `kotlinx.cinterop` stub fidelity risk) remains **live and unguarded** until the `clinic` corpus's real-Kotlin/Native backstop catches a lying stub; it is not discharged.

## Context

[ROADMAP.md](../../ROADMAP.md) "Tooling & Test Integrity" asks for an adversarial forward fixture: "the
exact mirror of `Test.Household`". [MVP.md](../../MVP.md)'s launch order asks for it **first**, with the
forward P0 fixes landing green against it afterwards: "each fix lands green against a test that was red,
which is the only evidence worth having here."

Those two sentences describe two different artefacts, and this ADR exists because that only becomes
visible once you try to build the thing.

### The forward direction has three gates; the reverse has one

`Test.Household` is a **pre-compiled C# assembly**. It is *input* to the reverse generator. The reverse
round trip has one observation point: the xunit test.

The forward "fixture" is **Kotlin source**, and it is input to *two independently generated outputs*
that are then compiled by *different compilers* (verified, `NugetProcessor.kt:201-217`):

1. `generateCNameWrappers()` → `CNameExports.kt` (Kotlin), compiled by `:test-library:compileKotlinMingwX64`.
2. `generateCSharpBindings()` → `Interop.cs` (C#), shipped as `contentFiles` with
   `buildAction="Compile"` (verified, `PackNugetTask.kt:149`) and therefore compiled **inside every
   consumer**, including `IntegrationTests`.
3. `ForwardAbiContract.assertMatches()` (verified, `NugetProcessor.kt:212-216`) fails KSP generation if
   the two disagree at the wire level.

The two generators **re-derive the type independently** and can disagree about what is correct. So a
forward fixture shape does not have one outcome; it has a pair of them, plus an observability class.

### Why the fixture cannot simply be added to `test-library`

**Every defect cell enumerated below breaks a build rather than failing an assertion**, if its fixture and
its assertion are simply added to `test-library` + `IntegrationTests` today. Only the failure mode
differs:

- **twelve** produce invalid **Kotlin** → `:test-library:compileKotlinMingwX64` fails → no `.nupkg` is
  produced → `IntegrationTests` and `GeneratedBindingsCheck` cannot even restore. Nothing else in the
  repo can be tested.
- **one** produces invalid **C#** *inside `Interop.cs`* (`CS0111`) → the package builds, but every
  consumer that compiles its `contentFiles` fails → all xunit tests become unrunnable.
- **five** produce a **valid-but-lying C# API** (four a type/nullability lie; the fifth an object-method
  casing mismatch, cell 25). The generated code compiles clean — but the natural C#
  test for a lying API is *itself* a compile-time act, so asserting them in `IntegrationTests` breaks
  that project too (verified: `CS0029`/`CS1503` are hard errors regardless of warning settings; the
  casing cell is `CS1061`).
- **one** is a member that silently vanishes, so even asserting its existence is `CS1061`.

An earlier draft of this ADR held that one defect (`Char`) could be red in `IntegrationTests` today
without bricking it, on the theory that nothing but a runtime round trip could see it. **The spike
falsified that** (see Verification): `Char` leaks `IntPtr` into the *public* C# signature, so it is a
call-site defect like the rest. There is no cell whose red is free.

So "put the adversarial fixture in `test-library`, watch it go red, then fix" is not available for a
single cell. The question this ADR answers is **where the red lives instead**.

### `Test.Household` was never red

ROADMAP line 248 records that `Test.Household` "found no new bug, which is the point: the previously
latent defects stay fixed under a harsher shape than the one that first exposed them." It landed
**after** ADR-053's fixes, green, as regression hardening. It was never a TDD driver.

MVP.md's launch order asks the forward fixture to be a TDD driver. Both jobs are worth doing; they are
not the same artefact, and only one of them is a mirror of `Test.Household`.

## The seam matrix, and why "declaration kind × type" is the wrong axis

The starting hypothesis was that the axis is **declaration kind × type-at-the-seam**. Grounded in the
generator, that framing is wrong in three ways and missing a fourth axis entirely.

### The generative fact: `.declaration` is the lossy step

Both pipelines flatten `KSType` → `String` before deciding anything:

- export side: `resolved.declaration.qualifiedName.asString()` → `ClassName.bestGuess(...)`
- CIR side: `resolved.declaration.simpleName.asString()` → `mapParamType`/`mapReturnType`

`.declaration` drops **nullability**, **type arguments** and **variance**. After that point the generator
knows only a name. Anything it needs back must be re-read from the `KSType` explicitly — and (all
verified by grep over `nuget-processor/src/main`):

- `isMarkedNullable` is re-read at **four** sites in the CIR translators — `CirClassTranslator.kt:110`
  (class property), `CirClassTranslator.kt:929` (sealed subclass property), `CirTranslator.kt:547`
  (top-level property), `CirFunctionTranslator.kt:30` (top-level function return) — mirrored by four more
  in the export generators (`PropertyExports.kt:23`, `ClassExports.kt:122`, `SealedClassExports.kt:67`,
  `FunctionExports.kt:25`). **Every one is a property or a top-level-return position; no class method
  return re-reads it, so that position drops nullability.**
- `parameterizedBy` appears **zero** times, so no exported type argument survives anywhere.
- `Char` is absent from both mapping tables, so it takes the `?: "IntPtr"` fallthrough
  (`CirTypeMapping.kt:71`, `:74`) — or, at a class property, is misclassified as a reference type
  (`CirClassTranslator.kt:179`, because `Char !in KOTLIN_TO_CSHARP_RETURN`) and **silently skipped**
  (`CirClassTranslator.kt:184-188`).

So the predictive axis is not "which Kotlin feature" but **"which facet does this position drop, and does
anything downstream re-read it"**.

### Three corrections to the hypothesis

**1. It under-resolves on _position_.** Position, not declaration kind, selects the code path. Inside
`translateClass`, a property re-reads `isMarkedNullable` (`:110`) but a method return (`:628`) and a
method parameter (`:636`) do not. So "class × `String?`" is not one cell, it is three cells with three
different outcomes:

| carrier | position | `String?` outcome |
|---|---|---|
| class | property | correct (`string?`, two-call) — covered by `Cat.owner` |
| class | method return | **invalid Kotlin** (verified below) |
| class | method param | **lying C#** (`string`) |

The hypothesis marks this covered, because it *is* covered — at the one position that works.

**2. It over-resolves on _declaration kind_.** `class`, `data class` and `abstract class` all route
through `translateClass` + `addClassExports`; `isDataClass`/`isAbstract` are flags
(`CirClassTranslator.kt:27-28`), not paths. The fixture needs one carrier, not three — except where a
flag actually branches (data-class `copy()` is its own site, `ClassExports.kt:687`).

**3. It omits the _pipeline_.** The same cell can be right in one generator and wrong in the other. The
object leak is *correct Kotlin* and *wrong C#*. The nullable class-method return is *invalid Kotlin* and
*lying C#*. You cannot predict the defect from (kind, type).

**4. It omits _observability_ — the axis that decides where the red lives**, and therefore the whole of
this ADR. Observability is a property of the cell, not of the defect.

### The corrected axes

> **cell = (carrier code path) × (position) × (type facet)**
> **value = (Kotlin-pipeline outcome, C#-pipeline outcome, observability class)**

Observability classes, in order of how early they fire:

| class | gate | bricks? |
|---|---|---|
| **K** | `:test-library:compileKotlinMingwX64` — generated Kotlin is invalid | yes: no `.nupkg`, nothing downstream runs |
| **B** | `GeneratedBindingsCheck` / any consumer — generated C# is invalid | yes: `Interop.cs` compiles inside consumers |
| **C** | consumer **call site** only — generated C# is valid but lies | yes, if asserted in `IntegrationTests` |
| **N** | nothing observes it — the member is silently absent | **no** |

**There is no runtime-only class.** An earlier draft of this ADR posited one (`R`) on the assumption that a
`Char` reaches C# as a `char` with a mismatched extern underneath. It does not: `mapParamType`/
`mapReturnType` leak `IntPtr` into the **public** signature too, so `Patient.tag(initial: Char)` renders
`public string Tag(IntPtr initial)` (verified). A consumer cannot even pass a `char`, so the defect is
caught at the call site, not at runtime. This is why **Tier 3 has no red cells** (see Decision).

### The matrix

Status legend: **✓** covered · **✗** uncovered · **~** covered-but-trivially (a fixture exists but picked
the type/arity that needs no work at this seam — the `CatRegistry` disease).

| # | carrier | position | facet | Kotlin path | C# path | outcome | obs | status | fixture today |
|---|---|---|---|---|---|---|---|---|---|
| 1 | object | method return | `String` | `ObjectExports:69` ok | `CirObjectRenderer:24` renders **native** type | C# leaks `IntPtr` | **C** | **~** | `CatRegistry` returns only `Int`/`void` |
| 2 | object | method return | object | `ObjectExports:69` non-null + `:79` `defaultValueFor`→`null` | `mapReturnType`→`IntPtr` | **invalid Kotlin** | **K** | **✗** | — |
| 3 | object | method return | `Int`/`Unit` | ok | ok | correct | — | ✓ | `CatRegistry.count/clear` |
| 4 | class | method return | `String?` | `ClassExports:563` non-null | `:663` `string` + `!` | **invalid Kotlin** | **K** | **✗** | — |
| 5 | class | method return | `Int?` | `ClassExports:563` | `:665` `int` | **invalid Kotlin** | **K** | **✗** | — |
| 6 | class | method return | object | `ClassExports:563` (no StableRef branch) | `:665` `IntPtr` | **invalid Kotlin** | **K** | **✗** | — |
| 7 | class | property | `String?`/`Int?`/object? | `ClassExports:198/361` | `:110`,`:261` | correct | — | ✓ | `Cat.owner/age/brother` |
| 8 | class | method param | `String?` | `ClassExports:544` | `:636` `mapParamType` | lying C# (`string`) | **C** | **✗** | — |
| 9 | top-level fn | param | `String?` | `Helpers:39` | `CirFunctionTranslator:42` | lying C# (`string`) | **C** | **~** | `greetNickname` exists; **no gate observes it** |
| 10 | top-level fn | return | plain class | `FunctionExports:178` fall-through | `mapReturnType` → `IntPtr` | **invalid Kotlin** | **K** | **✗** | — |
| 11 | top-level fn | return | sealed / generic | `FunctionExports:105` StableRef | ok | correct | — | ✓ | `peekBox(): Observation` |
| 12 | class | method param | `Char` | `ClassExports:544` `Char` (compiles; ABI `KChar`, 2 bytes) | `:641` → **public** `Tag(IntPtr)` | consumer cannot pass a `char` | **C** | **✗** | — |
| 13 | class | method return | `Char` | `ClassExports:563` `Char` + `:573` `defaultValueFor("kotlin.Char")`=`"0"` | `:665` → `IntPtr` | **invalid Kotlin** (`Int` vs `Char`) | **K** | **✗** | — |
| 14 | class | property | `Char` | — | `:179` misclassified → `:186` **skipped** | member vanishes | **N** | **✗** | — |
| 15 | value class (prim) | method param | any | `ValueClassExports:161-168` drops params | `CirObjectRenderer:145` drops params | **invalid Kotlin** | **K** | **~** | `CatId.isValid()` zero-arg |
| 16 | value class (ref) | method param | any | `ValueClassExports:145-159` drops params | same | **invalid Kotlin** | **K** | **~** | `CatResult.describe()` zero-arg |
| 17 | data class | ctor param | `List<T>` | `bestGuess` → raw `List` | ok (handle) | **invalid Kotlin** | **K** | **✗** | — |
| 18 | data class | `copy()` | `List<T>` | `ClassExports:687` | ok | **invalid Kotlin** | **K** | **✗** | — |
| 19 | class | property | `List<T>` (non-null) | routes via handle path | ok | correct | — | ✓ | `Cat.nicknames` |
| 20 | class | property | `List<T>?` | `bestGuess` → raw `List` | — | **invalid Kotlin** | **K** | **✗** | — |
| 21 | class | secondary ctor | object param | ok | `:89` set excludes handle ctor; `CirClassRenderer:62` always emits `(IntPtr)` | **`CS0111`** | **B** | **✗** | — |
| 22 | class | secondary ctor | `(String, Int)` | ok | ok | correct | — | ✓ | `CatId(name, number)` |
| 23 | ext fn | generic+inline+reified+suspend | `Result<T>` | `ExtensionFunctionExports` — no suspend/generic filter (`NugetProcessor:102-107`) | — | **invalid Kotlin** ×4 | **K** | **✗** | — |
| 24 | class | property | unsupported type | — | `:184-188` warn + skip | named skip | — | ✓ | `Cat.unsupported: Sequence` |
| 25 | object | method name | casing | `ObjectExports` ok | `CirClassTranslator:1143` sets raw `name`; `CirObjectRenderer:24` renders `method.name` verbatim | C# `greet` not `Greet` | **C** | **~** | `Clinic.greet` lands; only casing asserted |

Cells 4/5/6, 12/13, 15/16 and 17/18/20 each route through a code path of their own that **nothing
exercises**. Cells 1, 9, 15 and 16 are the `CatRegistry` disease proper: a fixture exists and picked the
one shape that needs no work.

Cell 9 is the subtlest and worth stating separately: **the fixture is fine and the gate is missing.**
`greetNickname(name: String?)` already exists (`NicknameSample.kt:18`). It is not caught because
`GeneratedBindingsCheck` has `<EnableDefaultCompileItems>false</EnableDefaultCompileItems>` and **no
sources of its own** — it only proves the bindings *compile*, never that they are *usable* — while
`IntegrationTests` sets `<Nullable>enable</Nullable>` but **no `TreatWarningsAsErrors`**, so `CS8625` is
a warning there and the suite stays green (all verified in the two `.csproj` files).

### Not fixture-expressible

- **MF-001 (no export set)** — confirmed **not expressible**. `test-library` *is* the export module, and
  the defect is "everything public is bridged". There is no assertion available: you cannot assert "X was
  not exported" when the specification is that everything is. It becomes expressible only once the
  export-set mechanism exists, at which point the fixture is "included → exported; excluded → not".
- **MF-003 (transitive model export)** — confirmed **not expressible today**, and the reason is exactly
  the one suspected: `settings.gradle.kts` includes only `:nuget-processor` and `:test-library`, and
  `test-library` has no Kotlin project dependency (verified). It *could* be expressed by adding a second
  Kotlin module and having `test-library` return one of its types — that is a real fixture, just a
  module-shaped one. It should not be built now: ROADMAP line 14 sequences transitive export *after* the
  diagnostics and value-class-parameter items precisely because reachability re-arms the constructs
  module isolation currently hides.

## Alternatives Considered

### 1. Three seams by observability class, plus a green corpus that lands with the fixes (chosen)

Route each cell's red to the cheapest gate that can see it, and keep the `Test.Household` mirror as a
separate, green, regression corpus.

- **Tier 1 — `nuget-processor` unit tests (new seam).** Kotlin source in → the real `NugetProcessor` via
  KSP2's programmatic entry point → generated `CNameExports.kt` → **compiled**. Carries every **K** cell
  (4, 5, 6, 10, 15, 16, 17, 18, 20, 23). Red in seconds; bricks nothing, because the compile happens
  inside the test, not in the build graph.
- **Tier 2 — `GeneratedBindingsCheck` + a consumer-surface source file.** The permanent compile guard for
  the **C** cells (1, 8, 9, 12). The project already is "compile as a consumer does, net8.0,
  `TreatWarningsAsErrors`"; it just has no consumer. Lands **green, with each fix** (see Decision).
- **Tier 3 — `IntegrationTests` runtime assertions.** The permanent behavioural guard. Lands **green,
  with each fix**.
- **The `clinic` corpus** in `test-library` — the actual `Test.Household` mirror — splits by
  observability class: its non-quarantined half lands **now**, and the **K** cells migrate in as their
  fixes land. It is the permanent adversarial guard.

**Pros:** every cell has a home that can actually observe it. MVP.md's "red first, then green" is
satisfied by Tiers 1–3, which land first and are red. ROADMAP's "exact mirror of `Test.Household`" is
satisfied by the `clinic` corpus, which — like `Test.Household` — lands green and hardens. The repo keeps
working the whole time. Tier 1 also closes the slice of ROADMAP line 251 that the four upcoming codegen
fixes will each need anyway, **and — because Tier 1 compiles rather than substring-matches — the forward
half of ROADMAP line 247 as well** (see "Tier 1 compiles" below).
**Cons:** three artefacts instead of one. Tier 1 needs two first-party KSP test dependencies plus
`kotlin-compiler-embeddable`, and its compile step needs a small `kotlinx.cinterop` stub file (see
Consequences: both are verified to work, and the stubs are a standing fidelity risk to guard).

### 2. A known-failing corpus gated out of the main build

A module whose `compileKotlinMingwX64` is expected to fail, with a check asserting the expected failures
and flipping to green as fixes land.

**Pros:** real compilation, not text proxies. One artefact.
**Cons:** Gradle cannot natively express "this task must fail"; it needs TestKit or a script wrapper, and
a Kotlin/Native compile per iteration is minutes, not seconds. Asserting on compiler **error message
text** is brittle across Kotlin versions. It covers only the **K** cells — the **C** cells need a C#
consumer, which needs a package, which needs the Kotlin to compile. And the assertion is a proxy: it
proves "the generator emits text that does not compile", never "the generator emits the right text".

### 3. Split by failure mode: lying-API cells as red xunit tests now

**Rejected as stated, and the reason is load-bearing.** The premise — "the object leak compiles clean, so
it can be a red xunit test" — does not survive contact with C#. Asserting the *shape* of an API in C# is
a compile-time act. `string s = Clinic.Greet("Oreo")` where `Greet` returns `IntPtr` is `CS0029`, a hard
error (verified below), so it breaks the `IntegrationTests` build and takes all ~60 test files with it.
The premise only holds if the assertions are written reflectively (`GetMethod(...).ReturnType`), which is
an unpleasant way to state an API contract. Alternative 1 keeps the idea but moves it to a project where
a compile error is the *expected* signal and nothing else depends on it.

### 4. Fixture shapes land paired with their fix, one commit each

**Pros:** simplest; no new infrastructure.
**Cons:** contradicts MVP.md's launch order, and does so on the one point ADR-053's phantom-bug history
paid for: without a red test you cannot distinguish a real defect from stale build state. Two of the
eight NYTimes reports arrived as unverified prose, and this repo's rule is that a finding which cannot
survive `scripts/verify.sh` from a purged state is not a finding. This alternative is what Alternative 1
degrades to for the **K** cells if the Tier-1 harness proves unworkable.

## Decision

**Adopt Alternative 1.** The adversarial forward fixture is **not one artefact**, because the forward
direction has three gates where the reverse has one.

Verifying the observability classes (rather than assuming them) then simplified the shape further than
the Alternative describes: **all red lives in Tier 1**, as strict-xfail JUnit tests. Tier 2
(`GeneratedBindingsCheck`'s consumer surface) and Tier 3 (the `clinic` corpus + `ClinicTests`) are
*regression* tiers — each lands green, with the fix that makes it pass. So: **one red seam, two green
guards, one corpus that splits by observability class.**

### Scope of the first delivery

This ADR's steps **1–3 only** are one unit of work: the Tier 1 harness and the red assertions. **The twelve
fixes are not in it.** Each fix is its own commit against a named red assertion, per MVP.md's launch
order, and each carries its own Tier 2/Tier 3 regression surface (below). Read this ADR as specifying a
seam and a list, not one large change.

### All red lives in Tier 1

The per-cell routing collapsed once the observability classes were verified rather than assumed:

| obs | cells | red lives in | assertion mode |
|---|---|---|---|
| **K** | 2, 4, 5, 6, 10, 13, 15, 16, 17, 18, 20, 23 | **Tier 1** | **compile** the generated `CNameExports.kt` |
| **B** | 21 | **Tier 1** | **diagnostic** — see below, its correct end state is a named error |
| **C** | 1, 8, 9, 12, 25 | **Tier 1** | **structural** — assert the generated `Interop.cs` declares `string Greet(...)`, `string? to`, `string Tag(char)`, and `Greet` (not `greet`) for the casing cell |
| **N** | 14 | **Tier 1** | **structural** — the member is absent; assert its presence |

**Tiers 2 and 3 hold no red.** They are *regression* tiers: each lands **green, with the fix that makes it
pass**, and stays as the permanent guard. That is the same role `Test.Household` plays for the reverse
direction, and it is why this ADR stopped calling them red seams.

Three findings forced this, all verified:

- **Cell 21's correct end state is a diagnostic, not working code.** ADR-034 already decided that a C#
  constructor-signature collision is a fail-fast (`CirClassTranslator.kt:93-98` calls `logger.error`).
  The fix is to add the handle constructor to the set that check already computes — after which
  `class Referral { constructor(from: Patient) }` **fails generation by design**. So `Referral` can never
  live in `test-library`, before or after the fix. It is a permanent Tier 1 *diagnostic* cell, exactly
  like cell 23. (Its Kotlin does compile — verified with real konanc — so the `CS0111` genuinely would
  reach `Interop.cs` and brick every consumer, which is the other reason it stays out.)
- **There are no runtime-only cells**, so Tier 3 has nothing to be red about (see the matrix).
- **The one remaining case for a Tier 2 red — an expected-errors manifest — is rejected**, next.

### Rejected: an expected-errors manifest for `GeneratedBindingsCheck`

The obvious way to give a whole-project C# compile strict-xfail semantics is to declare that
`GeneratedBindingsCheck` is *expected* to fail with exactly this diagnostic set (`CS0029` here, `CS8625`
there), have `verify.sh` diff actual against expected, and fail on either an unexpected error or a
missing expected one. It is rejected, for one reason that outweighs its elegance:

**it blinds the gate for the entire xfail period.** Inverting that project's exit code means every
*unrelated* new C# error — the kind the gate exists to catch, and the kind it caught on its very first
run (`CS8604` on `Cat.Owner`) — must be told apart from the expected set by matching diagnostic strings
and source positions. For however long the twelve fixes take, a contributor's genuine regression either
hides inside the expected set or has to be distinguished from it by brittle text matching. That trades a
red CI for a *lying* CI, which is strictly worse: a red CI is ignored, a lying one is believed.

It is also unnecessary. Every **C** cell's information — "does `Greet` return `string` or `IntPtr`?" — is
fully present in the generated `Interop.cs` **as text**, which Tier 1 already has in hand (the harness's
`codeGenerator` writes it). A structural assertion there is red first, strict, and costs nothing. The
compile-based proof still happens, at the right gate, in the commit that fixes the cell.

### Rejected: compiling the generated C# in Tier 1

The alternative to the manifest is to let Tier 1 compile `Interop.cs` itself, which would make the **C**
cells compile-asserted rather than structural. Rejected on a property the repo states publicly:
**the forward direction requires no .NET SDK at all.** MVP.md line 62 is explicit — `packNuget` writes
the `.nupkg` with `java.util.zip`, and only `nugetRestore`/`nugetExtractApi` shell out to `dotnet`.
Making `nuget-processor`'s *unit tests* need `dotnet` would erode that for a gain we do not need: the
structural assertion already fails for the right reason, and `GeneratedBindingsCheck` — which is allowed
to need the SDK, and already does — provides the compile proof at the moment the fix lands.

### Strict xfail: how red assertions coexist with a green `verify.sh`

Steps 3 and 4 are separate, so `main` carries red assertions while fixes land one at a time. Those
assertions therefore run **as known-failing**, and known-failing means **CI green**. The load-bearing half
is *strict*: when a fix makes an assertion pass, the **unexpected pass fails the build**, forcing whoever
landed the fix to remove the marker deliberately. Nothing maintains the list; the build does.

Because all red lives in Tier 1, this needs exactly one mechanism, in JUnit:

```kotlin
/**
 * The cell is known-failing: [reason] must name its ROADMAP item. The body states the CORRECT
 * expected behaviour, never the buggy one, so the assertion needs no edit when the fix lands —
 * only the annotation is removed.
 *
 * Strict: an unexpected PASS fails, so a fix cannot land while leaving its marker behind.
 */
@Test
@XFail("ROADMAP Phase 4 - value-class methods drop their parameters")
fun `value class method keeps its parameter`() {
  val result = generate(kotlin("ChartId.kt", """..."""))
  assertEquals(emptyList(), result.compileErrors)   // correct behaviour, stated positively
}
```

`@XFail` is a JUnit 5 extension of roughly ten lines: it intercepts the test invocation, swallows an
`AssertionError` (→ the test reports as *aborted*, i.e. green CI, listed as skipped-with-reason), and
**throws when the body does not fail**, with a message naming the marker to remove. `Assumptions` alone
is not enough — it gives the leniency without the strictness, which is the half that matters.

Two consequences worth stating, because they are the point:

- The assertion always spells out the **correct** contract. It never encodes the bug. So the fix's
  commit deletes one annotation and changes nothing else — the diff *is* the evidence.
- The `@XFail` list is a live, build-enforced inventory of the forward direction's real capability
  ceiling. When it is empty, the twelve defects are gone, and that is checkable rather than believed.

### Tier 1 compiles; it does not substring-match

This was an open question when this ADR was first written, and the spike settled it: **Tier 1 asserts by
compiling the generated Kotlin, not by matching its text.**

A text-only forward seam would have deliberately repeated a mistake this repo has already written down.
ROADMAP line 247 exists because the ADR-054 walking skeleton emitted literal backslashes into
`NugetRegistry.kt` and *every reverse unit test passed*, since they all substring-match. Building the
forward seam the same way would buy the same blindness. Compiling is strictly stronger, and it lands the
forward half of line 247 for free.

The mechanism (all verified, evidence in Verification):

- The **real** `NugetProcessor` runs under KSP 2.3.9's programmatic entry point,
  `com.google.devtools.ksp.impl.KotlinSymbolProcessing(config, providers, logger).execute()`. **No
  kotlin-compile-testing / kctfork is needed**, and no third-party test harness: `KSPJvmConfig.Builder`
  and the entry point ship in KSP's own `symbol-processing-aa-embeddable` and
  `symbol-processing-common-deps`, which are already in the build's dependency graph.
- The generated `CNameExports.kt` is then compiled **for JVM**, in-process, via
  `K2JVMCompiler().exec(...)`, and the reported errors are the assertion.

Three costs, each verified and each a deliberate trade:

1. **The generated file imports Kotlin/Native-only API** (`kotlin.native.CName`, `kotlinx.cinterop.*`,
   `kotlin.experimental.ExperimentalNativeApi`), so the harness ships a ~30-line stub file for them.
   Declaring into the `kotlin` package requires `-Xallow-kotlin-package` (verified: without it the
   compiler refuses with *"only the Kotlin standard library is allowed to use the 'kotlin' package"*).
   **This is the standing fidelity risk of Tier 1**: the stubs are not Kotlin/Native's real declarations,
   so a wrong stub could produce a false green. It is bounded — every **K** cell's error is in the
   *user-code* half of the export (`String` vs `String?`, a missing argument, a raw `List`), not in the
   cinterop plumbing — but the stub file must be treated as load-bearing and reviewed as such.
2. **Tier 1's fixture source lives in the harness, not in `test-library`.** This is forced: `value class`
   without `@JvmInline` does not compile for JVM (verified), and `test-library` has **zero** `@JvmInline`
   because Kotlin/Native does not require it. So the harness's copy of a value-class cell must carry the
   annotation. Verified harmless: `@JvmInline` is invisible to the processor (`addValueClassExports`
   branches on `Modifier.VALUE`, never on annotations) and the generated output is identical with and
   without it. This is also the *right* shape independently — one cell per test, isolated — and it
   matches the reverse precedent exactly, where `NugetGenerateBindingsTaskTest` feeds a local
   `reverse-ir.json` fixture rather than reading `TestDependency`.
3. **KSP2's standalone session leaves non-daemon threads alive**, so a harness `main` never exits;
   `exitProcess`/Gradle's forked test worker handles it. Cosmetic, but it is what made the first spike
   run look like a five-minute hang when `execute()` had in fact returned in 2.4 seconds.

**Three assertion modes fall out of one harness**, which is worth more than the compile alone:

| mode | for | how |
|---|---|---|
| **compile** | every **K** cell | assert the compiler's error list — red with the exact message, green when empty |
| **structural** | cell 14 (`Char` property silently skipped) | the generated file *compiles fine*; the member is simply absent, so assert its presence |
| **diagnostic** | cell 23, and the whole forward-diagnostics item (MVP P1) | the harness supplies the `KSPLogger`, so `logger.error`/`warn` are captured directly |

The third is a free by-product: Tier 1 is also the seam the forward unsupported-declaration diagnostics
item needs, so that item no longer has to build its own.

### Sequencing

1. **Tier 1 harness** (the slice of ROADMAP line 251 this item depends on). This is a **hard dependency
   and must land first**: it is the only place the twelve K cells can be red. Scope it to exactly "Kotlin
   source in → real processor → generated `CNameExports.kt` compiled" — not a unit-test suite for every
   translator, which remains ROADMAP line 251's own item. Verified feasible end to end (below); the
   remaining work is packaging it as JUnit, not discovery.
2. **The `clinic` corpus's non-quarantined half** lands in `test-library` **now**, green (see the split
   below). It must, because Tiers 2 and 3 need real types to bind against later.
3. **Tier 1 `@XFail` assertions** for all nineteen red cells (all red lives in Tier 1). This is the end of this ADR's delivery.
4. **The fixes** — *not in this delivery*. Each is its own commit: delete one `@XFail`, fix the
   generator, and add that cell's Tier 2 / Tier 3 regression surface in the same commit.
5. **Migration**: when a **K**-cell fix lands, its Kotlin shape moves from the Tier 1 harness into the
   `clinic` corpus, where it compiles for real on Kotlin/Native.

### What lands in `test-library` now, and what is quarantined

The corpus does **not** split "one shape per fix" — it splits **by observability class**, because that is
what decides whether a shape stops the build. Every classification below is verified (real konanc for the
Kotlin, real generated `Interop.cs` for the C#):

| cell | shape | obs | Kotlin lands in `test-library`… | why |
|---|---|---|---|---|
| 1 | `object Clinic { fun greet(name: String): String }` | C | **now** | generated Kotlin + C# both compile; only the C# *contract* lies |
| 8 | `Patient.rename(to: String?): String` | C | **now** | renders non-null `string to`; valid C# |
| 9 | `greetNickname(name: String?)` | C | **already there** | `NicknameSample.kt:18`; only the gate was missing |
| 12 | `Patient.tag(initial: Char): String` | C | **now** | verified: exports `KChar` (2 bytes), renders `Tag(IntPtr)` |
| 14 | `Patient.grade: Char` | N | **now** | verified: Kotlin exports `patient_get_grade`, C# skips it with a warning |
| — | `Clinic.capacity()/reset()`, `Patient.nickname/describe()` | — | **now** | controls: the paths that work and must stay working |
| 2 | `Clinic.intake(name: String): Patient` | **K** | **later** | verified invalid Kotlin — `defaultValueFor` → `null` from a non-null `Patient` |
| 13 | `Patient.initial(): Char` | **K** | **later** | verified invalid Kotlin — `defaultValueFor("kotlin.Char")` = `"0"` |
| 4, 5, 6 | `Patient.alias()/ageInMonths()/companion()` | **K** | **later** | invalid Kotlin (nullable / object returns) |
| 10 | `fun admit(name: String): Patient` | **K** | **later** | invalid Kotlin |
| 15, 16 | `ChartId.matches(other)`, `ChartRef.label(suffix)` | **K** | **later** | invalid Kotlin (dropped parameters) |
| 17, 18, 20 | `data class Visit(... List<String> ...)` | **K** | **later** | invalid Kotlin (raw `List`) |
| 21 | `class Referral { constructor(from: Patient) }` | **B** | **never** | its correct end state is a fail-fast diagnostic; permanent Tier 1 cell |
| 23 | `suspend inline fun <reified T> Patient.chartEntry(...): Result<T>` | **K** | **never** | correct end state is a named skip (forward-diagnostics item) |

So the `clinic` corpus starts as `object Clinic` (minus `intake`) plus a `Patient` carrying the `Char`
members, the nullable-parameter method and the controls — and grows a member per fix. Cells 21 and 23
never join it: both are cells whose right answer is a *diagnostic*, so a fixture that "passes" would mean
the diagnostic stopped firing.

### The `clinic` corpus

Namespace `io.github.xxfast.kotlin.native.nuget.test.clinic` → C# `TestLibrary.Clinic` (verified against
`mapPackageToNamespace` and `rootPackage = "io.github.xxfast.kotlin.native.nuget.test"`). It is a
veterinary clinic: where Oreo and Mylo get examined. It is a designed corpus, not the `cat` package's
overflow — every member exists to cross one seam, and says which, in the `Test.Household` house style.

```kotlin
package io.github.xxfast.kotlin.native.nuget.test.clinic

/**
 * The clinic facade — a Kotlin `object`, which is the natural shape for an export module and so the
 * first thing a new consumer meets (GOALS.md #2). `CatRegistry` is the only other `object` fixture and
 * returns only `Int` and `void`, which need no marshalling, so the object path — which has no
 * marshalling at all (`CirObjectRenderer.kt:24` renders the *native* return type as the public one) —
 * passed anyway. Cells 1-3.
 */
object Clinic {
  /** Cell 1 · LANDS NOW · obs C. Object × String return — the shape `CatRegistry` never had.
   *  Generated Kotlin and C# both compile; the C# just says `IntPtr Greet(string)`. */
  fun greet(name: String): String = "Welcome to the clinic, $name"

  /** Cell 2 · QUARANTINED (Tier 1) until its fix · obs **K**. Object × object return. Verified with
   *  real konanc: `defaultValueFor` yields `null` for a non-`kotlin.` type, so the export is
   *  `: Patient` returning `Patient?` — *"return type mismatch: expected 'Patient', actual 'Patient?'"*.
   *  Same root cause as cells 6 and 10; the object carrier has no `StableRef` branch either. */
  // fun intake(name: String): Patient = Patient(name)

  /** Cell 3, control: the exact `CatRegistry` shape. Must stay green — it is the path that works. */
  fun capacity(): Int = 12

  /** Cell 3, control: `void`. Needs no marshalling, which is why it never proved anything. */
  fun reset() { }
}

/**
 * A patient. The class path (`translateClass` / `addClassExports`), which is where *position* decides
 * everything: the property loop re-reads `isMarkedNullable` (`CirClassTranslator.kt:110`), the method
 * loop does not (`:628`, `:636`). Same carrier, same type, three different outcomes.
 */
class Patient(val name: String) {
  /** Cell 7, control: nullable property positions already work (`Cat.owner`/`age`/`brother`). */
  var nickname: String? = null
  var weight: Int? = null
  var buddy: Patient? = null

  /** Cell 14 · LANDS NOW · obs N. A `Char` property is misclassified as a reference type (`:179`,
   *  since `Char` is absent from `KOTLIN_TO_CSHARP_RETURN`) and silently skipped (`:186`). Verified:
   *  Kotlin *does* export `patient_get_grade` (boxed through `StableRef`) and the C# side drops it,
   *  which `ForwardAbiContract` permits by design (ADR-055: a Kotlin export with no C# import is not
   *  an error). So the member simply vanishes from the C# API. */
  val grade: Char = 'A'

  /** Control · LANDS NOW: a non-null String method return works. */
  fun describe(): String = "$name, $weight kg"

  /** Cell 8 · LANDS NOW · obs C. Class method × `String?` parameter. Renders non-null `string` — the
   *  API lies, and a consumer passing `null` gets `CS8625` (an error only under TWAE). */
  fun rename(to: String?): String = to ?: name

  /** Cell 12 · LANDS NOW · obs C. `Char` parameter. Verified in the real generated C header:
   *  Kotlin exports `patient_tag(void* handle, KChar initial, void* errorOut)` — `KChar` is
   *  `unsigned short`, 2 bytes — while C# renders **public** `string Tag(IntPtr initial)`. The
   *  consumer cannot pass a `char` at all, so this is a call-site defect, not a runtime one. */
  fun tag(initial: Char): String = "$initial-$name"

  // ---- QUARANTINED in the Tier 1 harness until each fix lands (all obs K, all verified) ----

  /** Cell 4 · obs K. Class method × `String?` return. The export declares non-null `String` and the
   *  body returns `String?`. The C# half compounds it with a null-forgiving `!` (`:658`). */
  // fun alias(): String? = nickname

  /** Cell 5 · obs K. Class method × `Int?` return. Same mechanism, primitive facet. */
  // fun ageInMonths(): Int? = weight?.times(2)

  /** Cell 6 · obs K. Class method × object return. The method loop has no `StableRef` branch, unlike
   *  the property loop (`:361`/`:404`), so ADR-005 holds for properties and not for methods. */
  // fun companion(): Patient = buddy ?: this

  /** Cell 13 · obs K. `Char` return. Not a wire mismatch — it never gets that far:
   *  `defaultValueFor("kotlin.Char")` is `"0"` (Helpers.kt:27 — it starts with `kotlin.`), so the
   *  catch branch yields `Int` from a `Char` function. Verified with real konanc:
   *  *"return type mismatch: expected 'Char', actual 'Comparable<*>'"*. */
  // fun initial(): Char = name.first()
}

/**
 * Cell 21: a secondary constructor taking an object-typed parameter. `mapParamType`'s `?: "IntPtr"`
 * fallthrough renders it `(IntPtr)`, colliding with the `internal Referral(IntPtr handle)` the renderer
 * always emits (`CirClassRenderer.kt:62`). ADR-034's fail-fast cannot see it: it builds its signature
 * set from the Kotlin constructors alone (`CirClassTranslator.kt:89-92`). `CatId(name, number)` is the
 * only secondary-ctor fixture and its `(String, Int)` cannot collide.
 */
// NEVER LANDS in test-library. Its Kotlin compiles (verified with real konanc: `@CName` accepts a
// Kotlin-class parameter and links a real .dll), so the CS0111 genuinely reaches Interop.cs and would
// brick every consumer including IntegrationTests. And its correct end state is a *fail-fast
// diagnostic* (ADR-034), so a version of this that "passes" would mean the check stopped firing.
// Permanent Tier 1 diagnostic cell.
// class Referral(val note: String) {
//   constructor(from: Patient) : this("referred by ${from.name}")
// }

/**
 * Cells 15/16: value-class methods. `ValueClassExports.kt:136-172` never reads `method.parameters` —
 * both branches emit `.method()` with literal empty parens. `CatId.isValid()` and `CatResult.describe()`
 * are the entire value-class method corpus and both are zero-arg, which is why ADR-014 stayed green.
 * Two value classes, because the primitive-underlying (`:160-169`) and reference-underlying
 * (`:145-159`) branches are separate code paths that drop parameters separately.
 */
value class ChartId(val value: String) {
  /** Cell 15 · QUARANTINED · obs K. Primitive-underlying × method with a parameter. Verified end to
   *  end through the real processor: the export is `export_chartid_matches(value: String)` calling
   *  `.matches()` — *"no value passed for parameter 'other'"*. */
  // fun matches(other: String): Boolean = value == other

  /** Control · LANDS NOW: the zero-arg shape that passed, and that is the entire corpus today. */
  fun isValid(): Boolean = value.isNotBlank()
}

value class ChartRef(val patient: Patient) {
  /** Cell 16 · QUARANTINED · obs K. Reference-underlying × method with a parameter — the *other*
   *  `ValueClassExports` branch (`:145-159`), which drops parameters separately. */
  // fun label(suffix: String): String = "${patient.name}$suffix"
}

/**
 * Cells 17/18/20: generic type arguments. `parameterizedBy` appears zero times in the processor, so
 * every `bestGuess` site renders a raw `List`. The three failing sites are the data-class constructor,
 * the generated `copy()` (`ClassExports.kt:687`) and the nullable list property getter; the non-null
 * list property (`Cat.nicknames`) escapes only by routing through the handle path, which is why
 * collections look complete from the C# side.
 */
// QUARANTINED · obs K (cells 17/18/20) until the type-argument fix lands.
// data class Visit(
//   val patient: String,
//   val symptoms: List<String>,
//   val notes: List<String>? = null,
// )

/** Cell 10 · QUARANTINED · obs K. A top-level factory returning a plain class. `FunctionExports.kt`
 *  handles nullable (`:53`), sealed-or-generic (`:105`, StableRef), enum (`:132`) and `Unit` (`:156`)
 *  returns — a plain class falls to `:178`, which returns the class itself and defaults to `null` on
 *  the catch path. Verified: *"return type mismatch: expected 'Patient', actual 'Patient?'"*. */
// fun admit(name: String): Patient = Patient(name)
```

**Deliberately not in the corpus:** the cell-23 shape
(`suspend inline fun <reified T> Patient.chartEntry(...): Result<T>`). Per ROADMAP line 101 its correct
end state is a **named skip, not working code**, so it belongs to the forward-diagnostics item
([MVP.md](../../MVP.md) P1) and lives in Tier 1 asserting "skipped with a diagnostic naming it". Putting
it in `test-library` before that item lands would stop the build with no fix available.

### The C# test surface

Neither tier lands in this delivery. **Each line below ships in the commit that fixes its cell**, green,
and stays as that cell's permanent guard. They are recorded here so each fix knows what it owes.

**Tier 2** — `GeneratedBindingsCheck/ConsumerSurface.cs`, a new file in the project that today has none.
It is not a test; it is the lines a consumer writes, compiled under `TreatWarningsAsErrors`. Every line
is a hard compile error **today**, which is exactly why it cannot land before its fix:

```csharp
// Cell 1: object × String return. `CS0029` (nint → string) until fixed.
static string Greet() => Clinic.Greet("Oreo");
// Cell 12: Char parameter. `CS1503` (char → nint) until fixed.
static string Tag(Patient p) => p.Tag('O');
// Cells 8/9: nullable parameters. `CS8625`, an error only because of TWAE.
static string Rename(Patient p) => p.Rename(null);
static string GreetNobody() => Nicknames.GreetNickname(null);
```

**Tier 3** — `IntegrationTests/ClinicTests.cs`, behavioural assertions in the `Test.Household` house
style. Note these are *not* red today: cells 12/13 do not compile at the call site, so there is no
runtime assertion to fail — which is why Tier 3 holds no red:

```csharp
[Fact] public void Greet_ReturnsAString()          // cell 1
    => Assert.Equal("Welcome to the clinic, Oreo", Clinic.Greet("Oreo"));
[Fact] public void Tag_Oreo_RoundTripsTheChar()    // cell 12 — the 2-byte KChar actually arrives
    => Assert.Equal("O-Oreo", new Patient("Oreo").Tag('O'));
[Fact] public void Grade_IsPresentOnTheApi()       // cell 14 — the member must exist at all
    => Assert.Equal('A', new Patient("Oreo").Grade);
[Fact] public void Matches_ComparesTheArgument()   // cell 15 — proves the parameter arrived
    => Assert.True(new ChartId("c-1").Matches("c-1"));
```

## Consequences

### What changes

- A new test seam in `nuget-processor/src/test` (Tier 1). This is the dependency to schedule first. It
  adds three `testImplementation` dependencies, all already in the build's dependency graph and none of
  them a third-party test harness:
  `com.google.devtools.ksp:symbol-processing-aa-embeddable:2.3.9` (the KSP2 entry point),
  `com.google.devtools.ksp:symbol-processing-common-deps:2.3.9` (`KSPJvmConfig`), and
  `org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.0` (the compile step), plus a harness-local
  `kotlinx.cinterop` stub file compiled with `-Xallow-kotlin-package`.
- **Cost, measured rather than estimated** (all in one JVM, since both KSP2 and the compiler warm up):

  | step | first call | subsequent |
  |---|---|---|
  | KSP2 `execute()` | 2398 ms | **178 ms**, then **126 ms** |
  | in-process `K2JVMCompiler().exec()` | 3032 ms | **483 ms**, then **287 ms** |

  So a ~20-cell Tier 1 suite is on the order of **~15–20s in one test JVM** — against a full `packNuget`
  round trip for every iteration today. That is the fast inner loop ROADMAP line 251 says the forward
  direction has never had, and it is the loop the next four codegen fixes each run through.
- `GeneratedBindingsCheck` gains its first source file and stops being compile-only, which is a small
  widening of its charter: from "the bindings compile" to "the bindings are usable". That is what MVP.md
  line 39 already claims for it ("nullability and invalid-construct regressions now fail our build rather
  than the consumer's") and what it cannot currently deliver. **Not in this delivery** — it lands a line
  at a time, with each fix.
- `test-library` gains the `clinic` package **now**, in its non-quarantined half (green), and grows a
  member per fix.
- `scripts/verify.sh` is **green throughout**, on a tree where all twelve defects are still present, and
  goes red the moment a fix lands while its `@XFail` marker is stale. Nothing about verify.sh changes:
  no manifest, no inverted exit code, no MSBuild diagnostic parsing.

### Defects the fixture pins

**Twelve ROADMAP-level defects, spanning nineteen cells** — not six. ROADMAP's six confirmed, plus the
two it carries as unverified (**both now verified**, below), plus **four** this analysis found that no
report mentioned: cells 6, 10, 14 and 25.

The nineteen cells distribute as **12 K + 1 B + 5 C + 1 N**, which is the count the Context uses and the
reason all red fits in one tier. One ROADMAP item often spans several cells because *position* — not
type — selects the code path: "Char → IntPtr" alone is three cells with three different outcomes
(param → **C**, return → **K**, property → **N**), and only one of them is the defect the item describes.

Cell 10 is worth naming out loud: **the forward direction cannot export a top-level factory function
returning a class.** `fun admit(name: String): Patient` generates invalid Kotlin today. Nobody noticed
because every factory in the corpus is a companion (`Cat.fromName`), which routes through a different
path that does use `StableRef` (`ClassExports.kt:760`).

Cell 25 was found after this ADR was first written and is the object leak's twin: **an `object`'s methods
are not PascalCased.** `translateObject` sets `name = method.simpleName.asString()` raw
(`CirClassTranslator.kt:1143`) where every class-method path PascalCases (`:631`), and `renderObjectMethod`
emits `method.name` verbatim (`CirObjectRenderer.kt:24`), so `Clinic.greet` reaches C# as `greet`, not
`Greet`. Same member as cell 1, two facets: cell 1 is the *type* leak on `Clinic.greet`, cell 25 is its
*casing*. Obs **C** — the C# compiles, only the idiom is wrong — asserted structurally on the generated
`Interop.cs`; it does not enlarge the `clinic` corpus, since `greet` already lands there for cell 1.

### What is deferred

- MF-001 and MF-003 stay out; neither is fixture-expressible now (see Context).
- Cell 23 stays in Tier 1 until forward diagnostics land.
- ROADMAP line 251's broader "fast inner loop for all forward codegen" stays its own item; this ADR takes
  only the slice it needs.

### Risk

The risk this ADR originally carried — "Tier 1 rests on one claim this ADR could not verify: that a KSP2
harness runs against KSP 2.3.9 in this repo" — **has been spiked and is discharged**. It runs; the real
processor executes; cells 4 and 15 were driven red through it for the real reason. See Verification.

The residual risk moved, and is now narrower and named: **the `kotlinx.cinterop` stub file**. Tier 1's
compile is only as truthful as those ~30 lines. A stub whose signature drifts from Kotlin/Native's could
turn a real defect green. Three things bound it: the stubs are plumbing-only; every **K** cell's error is
in the user-code half of the export, not the plumbing; and the `clinic` corpus (Tier 3, on the real
Kotlin/Native target) is the backstop that would catch a lying stub. It should nonetheless be reviewed
as load-bearing code, not as test scaffolding.

## Verification

Every mechanism claim below was proved by running the command shown, in a scratch directory. Claims not
listed here are **inferred**, and listed as such in the next section.

**Verified — KotlinPoet 2.2.0 (the repo's pinned version) renders raw, non-null types.** Compiled and run
against `kotlinpoet-jvm-2.2.0.jar`:

```
public fun export_cat_nickname(): String = handle.get().nickname()
public fun export_article_tags(): List = handle.get().tags
public fun export_client_get(block: Function1): Result = receiver.get(url)
```

`ClassName.bestGuess("kotlin.collections.List")` → `List`. `ClassName.bestGuess("kotlin.String")` →
non-null `String`. This is the mechanism behind cells 4, 5, 6, 10, 17, 18, 20, 23.

**Verified — the generated shapes do not compile** (kotlinc 2.4.0, the repo's pinned version):

```
gen.kt:11:55: error: return type mismatch: expected 'String', actual 'String?'.      // cell 4
gen.kt:15:42: error: one type argument expected for 'interface List<out E>'.          // cells 17/18/20
gen.kt:19:79: error: no value passed for parameter 'other'.                           // cells 15/16
gen.kt:19:47: error: return type mismatch: expected 'Int', actual 'Int?'.             // cell 5
gen.kt:6:10:  error: return type mismatch: expected 'Cat', actual 'Cat?'.             // cell 6
gen.kt:14:10: error: return type mismatch: expected 'Cat', actual 'Cat?'.             // cell 10
```

**Verified — BUG-010 is real**, and matches the NYTimes report exactly ("generated raw `Function1` and
`Result` types and failed suspend/type-inference compilation"). All four failure modes reproduced from
the `ExtensionFunctionExports.kt` shape:

```
error: 2 type arguments expected for 'interface Function1<in P1, out R>'.
error: one type argument expected for 'class Result<out T> : Serializable'.
error: cannot infer type for type parameter 'T'. Specify it explicitly.
error: suspend function 'suspend fun <reified T> HttpClient.fetch(...)' can only be called from a
       coroutine or another suspend function.
```

**Verified — `Char` is 2 bytes at the C ABI**, from the real Kotlin/Native-generated header
`test-library/build/bin/mingwX64/releaseShared/test_api.h`:

```c
typedef unsigned short     test_KChar;
```

C# declares `IntPtr` (8 bytes on x64) for the same parameter. `grep Char` on that header also returns
only the runtime's boilerplate typedefs and **no export**, confirming no fixture crosses a `Char` today.

**Verified — the C# failure modes** (`dotnet` 10 SDK, `net8.0`, `LangVersion` 12.0, `Nullable` enable):

```
Interop.cs(28,16): error CS0111: Type 'Projection' already defines a member called 'Projection'
                                 with the same parameter types                          // cell 21
Consumer.cs(7,43): error CS0029: Cannot implicitly convert type 'nint' to 'string'      // cells 1/2
Consumer.cs(10,59): error CS8625: Cannot convert null literal to non-nullable reference
                                  type.                                                  // cells 8/9
```

Two results here are load-bearing for the Decision:

- With the generated bindings compiled **alone** (i.e. exactly what `GeneratedBindingsCheck` does today),
  only `CS0111` fires. The object leak and the nullable parameter compile **clean**. This is why the gate
  has never caught them, and why Tier 2 needs a consumer-surface file.
- `CS0029` is an **error regardless of warning settings**; `CS8625` is an error **only** under
  `TreatWarningsAsErrors` and a warning without it (verified by re-running with the property removed).
  `IntegrationTests` does not set it. This kills Alternative 3 and fixes cell 9's diagnosis.

**Verified — the Tier 1 harness runs the real processor, and observes the real defect.** This was
inferred claim #1 and is now discharged. The `nuget-processor` sources compile standalone in 23s; the
harness drives `KotlinSymbolProcessing` from `symbol-processing-aa-embeddable-2.3.9` with
`KSPJvmConfig.Builder` from `symbol-processing-common-deps-2.3.9`, against Kotlin 2.4.0. **No kctfork.**

Real generated output for cell 15 (`value class ChartId { fun matches(other: String): Boolean }`):

```
=== KSP2 exit=OK in 2468ms ===
@CName("chartid_matches")
public fun export_chartid_matches(`value`: String): Boolean = ....ChartId(value).matches()
@CName("chartid_isValid")
public fun export_chartid_isValid(`value`: String): Boolean = ....ChartId(value).isValid()
```

The `other: String` parameter is **absent from the export signature** and the call has **literal empty
parens** — and the result is *shape-identical to the zero-arg control*, which is precisely why
`CatId.isValid()` being the entire value-class method corpus concealed this.

Compiling that real generated output (in-process `K2JVMCompiler`, JVM target, cinterop stubs):

```
  compile[cell15] COMPILATION_ERROR in 3032ms  errors=1
      -> gen_A.kt:35:134: error: no value passed for parameter 'other'.
  compile[cell4]  COMPILATION_ERROR in 483ms   errors=1
      -> gen_B.kt:80:94: error: return type mismatch: expected 'String', actual 'String?'.
  compile[cell15b] COMPILATION_ERROR in 287ms  errors=1
      -> gen_A.kt:35:134: error: no value passed for parameter 'other'.
```

Cells 15 and 4 are therefore **observed end to end through the real generator**, red for the real reason,
with a structured error list to assert on — not a hand-written approximation of the generated text.

**Verified — KSP2 is re-entrant in one JVM**, which is what makes the loop fast (three runs, one JVM):

```
=== THREE KSP2 runs in ONE JVM ===
  run[A1] exit=OK in 2398ms      run[A1] generated: 9 @CName exports
  run[B1] exit=OK in 178ms       run[B1] generated: 13 @CName exports
  run[A2] exit=OK in 126ms       run[A2] generated: 9 @CName exports
=== total wall (3 runs, 1 JVM): 2978ms ===
```

**Verified — the two constraints that shape Tier 1.** `value class` without `@JvmInline` does not compile
for JVM (`error: value classes without '@JvmInline' annotation are not yet supported`) and `test-library`
carries none; and stubbing `kotlin.native.CName` is refused (`error: only the Kotlin standard library is
allowed to use the 'kotlin' package`) without `-Xallow-kotlin-package`. Both have workarounds, both are
verified, and together they are why Tier 1's fixtures live in the harness.

**Verified — the observability classes, on the real Kotlin/Native toolchain.** The local
`kotlin-native-prebuilt-windows-x86_64-2.4.0` was driven directly (`konanc -target mingw_x64 -p dynamic`,
~18s), so the classifications that decide *what lands in `test-library` now* are not inferred. This
re-classified **three cells** against the earlier draft.

The non-quarantined subset **compiles and links a real `.dll`**, and its generated C header is the proof
for two cells at once:

```c
extern const char* clinic_greet(const char* name, void* errorOut);                 // cell 1  - fine
extern void* patient_get_grade(void* handle, void* errorOut);                      // cell 14 - N
extern const char* patient_tag(void* handle, crn_KChar initial, void* errorOut);   // cell 12 - C
```

- **Cell 12**: Kotlin exports `crn_KChar` (2 bytes) while the generated C# renders **public**
  `string Tag(IntPtr initial)` — verified in the real `Interop.cs`. The public leak is what makes this a
  call-site defect (**C**), not a runtime one; there is no `R` class.
- **Cell 14**: `patient_get_grade` is exported natively while the C# side skips it
  (`[warn] Skipping property 'Patient.grade': unsupported type 'kotlin.Char'`), and generation still
  succeeds — `ForwardAbiContract` permits a Kotlin export with no C# import by design (ADR-055). **N**.
- **Cell 2** — the cell flagged for re-checking, and the earlier draft had it wrong:

  ```
  CNameExports.kt:64:85: error: return type mismatch: expected 'Patient', actual 'Patient?'.
  public fun export_clinic_intake(name: String, errorOut: COpaquePointer?): Patient = try {
  ```
  So an `object` returning an object is **K**, not **C** — same root cause as cells 6 and 10.
- **Cell 13** — also re-classified:

  ```
  CNameExports.kt:137:94: error: return type mismatch: expected 'Char', actual 'Comparable<*>'.
  public fun export_patient_initial(handle: COpaquePointer, errorOut: COpaquePointer?): Char = try {
  ```
  `defaultValueFor("kotlin.Char")` is `"0"` (`Helpers.kt:27`), so the catch branch yields `Int`. **K**,
  not a wire mismatch — it never reaches the wire.
- **Cell 21**: `@CName` **does** accept a Kotlin-class parameter — the cell-21 export shape compiled and
  linked a real `.dll`. So its `CS0111` genuinely reaches `Interop.cs` and would brick every consumer.
  Combined with ADR-034's fail-fast being its correct end state, it is a permanent Tier 1 cell.

**Verified by source reading** (`file:line` given inline above): `parameterizedBy` = 0 occurrences;
`isMarkedNullable` = 4 sites; `ValueClassExports.kt:136-172` never reads `method.parameters`;
`CirObjectRenderer.kt:24` renders the native return type as the public one; `CirClassTranslator.kt:89-92`
excludes the handle constructor from its collision set while `CirClassRenderer.kt:62` always emits it;
`PackNugetTask.kt:149` ships C# as `buildAction="Compile"`; `NugetProcessor.kt:102-107` filters extension
functions by neither `suspend` nor type parameters.

## Inferred claims to validate before implementing

1. ~~**A KSP2 test harness runs against KSP 2.3.9 in this repo.**~~ **Spiked and verified** — see
   Verification. It runs via KSP's own `KotlinSymbolProcessing` entry point with no kctfork and no
   third-party harness, and cells 4 and 15 were driven red through the real processor. The claim is
   discharged; its residual is now the `kotlinx.cinterop` stub fidelity risk, recorded under Risk rather
   than here, because it is a thing to *guard* rather than a thing to *find out*.
2. **The `kotlinx.cinterop` / `kotlin.native.CName` stubs match Kotlin/Native closely enough.** The stubs
   are verified to *compile* and to let every real defect surface, but they are hand-written and are
   **not** Kotlin/Native's declarations. Falsified by a Tier 1 cell that is green while its `clinic`
   counterpart (real Kotlin/Native target) is red — which is exactly the backstop the corpus provides.
3. **The runtime symptom of the `Char` mismatch.** The *width* mismatch is verified (2 vs 8 bytes); what
   it does at runtime — garbage upper bits, stack imbalance, or a crash — is **inferred**. It does not
   change the fixture (a runtime assertion catches any of them), only the failure text.
4. **`ForwardAbiContract` does not catch `Char`.** Read in source, not executed: `kotlinType` maps
   `kotlin.Char` to `POINTER` via its `else` branch (`ForwardAbiContract.kt:155`) and `csharpType` maps
   `IntPtr` to `POINTER` (`:128`), so the two agree and the check passes. Falsified by a fixture with a
   `Char` parameter that makes the check fire.
5. **`@CName` on a function returning a Kotlin class.** Cells 6 and 10 are verified to fail Kotlin *type*
   checking before this matters, so it is moot for the fixture; but whether Kotlin/Native would also
   reject the signature as non-C-mappable is **inferred**. Would only surface if the type error were
   fixed without adding a `StableRef` branch.
6. **`.declaration` drops variance** as well as nullability and type arguments. Nullability and type
   arguments are verified; variance is inferred by analogy and is not load-bearing for any cell here.
