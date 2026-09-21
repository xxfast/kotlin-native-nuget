# An enum entry's internal capitals are lost, so `SecondValue` is only reachable as `Secondvalue` (issue #285)

- ROADMAP: no line exists as of 2026-09-21. Source is GitHub issue #285 (open, label `bug`, plugin 0.7.0): "A PascalCase Kotlin enum entry reaches C# with everything after the first character lowercased."
- Researched: 2026-09-21, about 15 of 20 minutes of source reading and web research, then a second pass the same day (about 10 of 25 minutes) that RAN the spikes: scratch Tier 1 cells through `Tier1Harness.run` in a separate worktree at main `651cb7d0`, plus a scratch `dotnet build` (SDK 10.0.301) of the emitted enum text. Every claim about emitted text below is now **verified by spike** with the observed C# quoted, unless it still says **inferred**. See "Spikes run (2026-09-21)".
- Restatement (from the issue's "What we need"): a C# consumer reaches a Kotlin enum entry under a name they can predict from the Kotlin declaration alone, without compiling to read the error and without opening `Interop.cs`. Forward; Kotlin declares, C# consumes.
- Verdict: **fix**, as a dated amendment to ADR-006 (not drafted; the `AB1C` rule and the breaking-change handling are human what-questions first). No new ADR number recommended.

## Findings

### 1. The conversion lives in exactly one expression (verified by reading)

`nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/cir/CirClassTranslator.kt:2996-2999`, inside `translateEnum` (`:2979`):

```kotlin
val entryName: String = entry.simpleName.asString()
val csEntryName: String = entryName.split("_")
  .joinToString("") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
CirEnumEntry(csEntryName, index, doc = ...)
```

Every `_` segment is lowercased whole, then its first character uppercased. `SecondValue` is one segment, so it becomes `Secondvalue`; `AB1C` becomes `Ab1c`. This reproduces the issue's output exactly. **Verified by spike** (S1): `enum class Example { First, SecondValue, ThirdValueHere, AB1C }` generates, with no KSP error, warning or `NugetDiagnostics.json` entry:

```csharp
    public enum Example
    {
        First = 0,
        Secondvalue = 1,
        Thirdvaluehere = 2,
        Ab1c = 3,
    }
```

and `enum class Wide { camelCase, SCREAMING_SNAKE, snake_case, XMLParser_V2, HTTP_Status, default, event, `class` }` generates `Camelcase = 0`, `ScreamingSnake = 1`, `SnakeCase = 2`, `XmlparserV2 = 3`, `HttpStatus = 4`, `Default = 5`, `Event = 6`, `Class = 7`. Every "Today" cell of the rule table is therefore observed, not derived.

### 2. It was designed for SCREAMING_SNAKE_CASE only (verified by reading)

ADR-006 (`docs/adr/006-enum-mapping.md:60`) owns the rule in one line: "Entry names: `SCREAMING_SNAKE_CASE` to `PascalCase` (`HAPPY` to `Happy`)". It never considers a PascalCase or camelCase entry. `docs/topics/enums.md:27` repeats it: "Entries rename from Kotlin's `SCREAMING_SNAKE_CASE` to C#'s `PascalCase`." The Kotlin coding conventions allow both `UPPER_SNAKE` and upper camel case for enum constants (inferred, from the official conventions page; not re-fetched), so the uncovered input is an ordinary one.

### 3. No other site spells an entry name: the fix lands in one place (verified by reading, and by spike for defaults, enum arms and KDoc)

- Renderer: `cir/CirEnumRenderer.kt:15` prints `entry.name` from `CirEnumEntry` (`cir/CirModel.kt:563`). Grep for `CirEnumEntry(` in `src/main` finds the single construction site in finding 1.
- Every value position crosses as the `int` ordinal (ADR-006 `:66-85`; extension getters `CirEnumRenderer.kt:51-60`; ADR-097 enum collection components cross as `int` per `docs/topics/enums.md:58`). No position spells an entry by name.
- ADR-157 enum-armed sealed interface: the arm is named after the enum TYPE (`FamilyArm(Family entry)`, `docs/adr/157-enum-armed-sealed-interface.md:180`), the entry crosses as ordinal or `StableRef`. No entry name is spelled. **Verified by spike** (S7): `sealed interface Marking; enum class Patch : Marking { BibFront, SOCKS }` generates `public PatchArm(global::Interop.Patch entry) : base(IntPtr.Zero)` and the dispatch arm `0 => new PatchArm(handle)`; the only place `BibFront` surfaces is the enum declaration itself, as `Bibfront = 0`. So the arm route needs no change, and it inherits the fix.
- ADR-096 default parameters: `docs/adr/096-function-default-parameters.md` contains no occurrence of "enum" (grep). **Verified by spike** (S6): `fun describe(level: Level = Level.VeryHigh, n: Int = 2)` generates the overload pair `Describe(global::Interop.Level level, int n)` and `Describe(global::Interop.Level level)`, and `class Box(val level: Level = Level.VeryHigh)` generates only `public Box(global::Interop.Level level)`. An enum default is never rendered as a C# literal, and no `Level.Veryhigh` text appears anywhere but the enum declaration. The Kotlin side spells the entry by ordinal (`spike.e.Level.entries[level]`), never by name.
- KDoc: `cir/CirDocLinks.kt:84-93` (`docLinkName`) indexes declarations by TYPE name only; `CirEnum` is indexed, its entries are not. A `[Mood.HAPPY]` link is never resolved to a cref and falls to the `<c>` text fallback with the Kotlin spelling. **Verified by spike** (S8): `/** See [Mood.SecondValue] and [Mood]. */` generates `/// <summary>See <c>Mood.SecondValue</c> and <see cref="global::Interop.Mood"/>.</summary>`. Side effect worth knowing: today the doc text says `Mood.SecondValue` while the member is `Secondvalue`; after the fix the two agree for Pascal entries (and still differ for `HAPPY`, as they do today).
- Both `translateEnum` call sites (`cir/CirTranslator.kt:424` nested, `:478` top-level) go through the same function, so nested enums (ADR-133/134) are covered by the one fix.

### 4. Twin defect: `const val` uses the identical expression (verified by reading)

`cir/CirTranslator.kt:1517-1520` (`translateConstProperty`): `propName.split("_").joinToString("") { segment -> segment.lowercase().replaceFirstChar { it.uppercase() } }`. **Verified by spike** (S3), on all three const positions (top-level, `object`, companion):

```csharp
        public const int Toplevel = 1;      // const val TopLevel
        public const int Maxretries = 3;    // object Limits { const val MaxRetries }
        public const int Maxdelay = 4;      // const val maxDelay
        public const int MaxSize = 5;       // const val MAX_SIZE
        public const string Defaultname ... // companion const val DefaultName
```

Same rule, same silent rename, no issue filed. It should share the helper and ship in the same PR (same path, per the "do not grow the phase with deferrals" rule).

### 5. No existing fixture or test expectation moves (verified by reading/grep)

Every `enum class` in `test-library/src` has all-caps entries (`HAPPY`, `CALM`, `BIB`, `INDOOR`, `LOW`, ...; multiline grep over `test-library/src`, 2026-09-21). Tier 1 enum cells use `CALM`/`ANXIOUS` (`tier1/Tier1OrdinarySurfaceTest.kt:126-128`). Under the recommended rule an all-caps or `_`-separated segment converts exactly as today, so no IntegrationTests, LeakTests or Tier 1 expectation changes. No fixture with a PascalCase entry exists, which is why this shipped unnoticed.

### 6. C# `ToString()` / `Enum.Parse` already disagree with Kotlin `name` (verified by reading for the exclusion; consequence inferred)

`translateEnum` excludes the inherited `name` property (`CirClassTranslator.kt:3005`), so the C# spelling is the only name a consumer sees. `Mood.Happy.ToString()` is `"Happy"` while Kotlin `name` is `"HAPPY"`. This is the accepted ADR-006 trade and stays; the fix narrows the gap for PascalCase entries to zero.

### 7. Collision handling does not exist (verified by reading)

`translateEnum` takes no logger and emits no diagnostic (`:2979-2987`). Today `FOO_BAR` maps to `FooBar` and `FooBar` maps to `Foobar`, so that pair does NOT collide; `FOO` and `Foo` DO collide today (both `Foo`) and render a duplicate C# enum member, CS0102 at the consumer's compile with no generator diagnostic. **Verified by spike** (S2): `enum class Clash { FOO, Foo }` runs with `kspExit=OK`, `kspErrors=[]`, `kspWarnings=[]`, `NugetDiagnostics.json` = `[]`, and generates `public enum Clash { Foo = 0, Foo = 1, }`. That exact text in a scratch `dotnet new classlib` (SDK 10.0.301) fails with `E.cs(5,9): error CS0102: The type 'Clash' already contains a definition for 'Foo'`. Under the recommended rule `FOO_BAR` + `FooBar` newly collide. The repo precedent for a C# name collision is a fatal `ForwardDiagnosticKind.ERROR_CSHARP_NAME_COLLISION` naming both Kotlin declarations (`CirClassTranslator.kt:2471`, `:2886-2900`; kind at `forward/ForwardDiagnostic.kt:346`). `logger` is in scope at both `translateEnum` call sites (`CirTranslator.kt:418` passes it to the sibling `translateClass`).

### 8. Keywords cannot collide (verified by reading, repo already relies on it)

Every C# keyword is all lowercase, and the rule always uppercases the first character, so no entry can spell a keyword. The repo states and relies on this at `forward/ForwardCirPlanProjection.kt:1611` and `forward/ForwardCallablePlanner.kt:1802-1803`. Two real edge cases the current code also mishandles, both **verified by spike** (S4, S5): Kotlin accepts all three names as enum entries (the harness's in-process `K2JVMCompiler`, language version 2.0, compiled `enum class Digit { _1ST, OK }`, `enum class Bare { _, OK }` and `enum class Bare2 { __, OK }` with `compileErrors=[]`; only the JVM frontend was run, Kotlin/Native acceptance is inferred from the shared frontend), and the generator emits illegal C# for each with no diagnostic:

```csharp
    public enum Digit { 1st = 0, Ok = 1, }   // from _1ST
    public enum Bare  {  = 0, Ok = 1, }      // from _  (and identically from __)
```

`dotnet build` rejects both with `error CS1001: Identifier expected`. So the guard rows are reachable and testable at Tier 1; they stay in the table. Both need a `_` prefix guard; `Reserved.kt:68` (`csharpIdentifier`) already has the digit guard pattern. Note `_` and `__` both convert to the empty name, so under the guard both become `_` and an enum declaring both lands in the collision diagnostic, which is the right outcome.

### 9. Prior art

- Kotlin ObjC export: **verified by reading upstream source** (`ObjCExportNamer.kt`, `getEnumEntryName`, fetched 2026-09-21 from `https://raw.githubusercontent.com/JetBrains/kotlin/master/native/objcexport-header-generator/impl/k1/src/org/jetbrains/kotlin/backend/konan/objcexport/ObjCExportNamer.kt`): `it.split('_').mapIndexed { index, s -> val lower = s.lowercase(); if (index == 0) lower else lower.replaceFirstChar(Char::uppercaseChar) }`. Kotlin's own exporter has the SAME defect (`SecondValue` to `secondvalue`); its escape hatch is `@ObjCName`. This is precedent for how the bug arose, not for the fix.
- SKIE: **inferred** (web search summary of `https://github.com/touchlab/SKIE/discussions/36` and `https://skie.touchlab.co/features/enums`, pages not opened): users hit exactly this, and SKIE's case naming handles PascalCase entries instead of flattening them. Precedent for the fix: preserve internal capitals when the entry is not all-caps.
- Swift export: **inferred** (`https://kotlinlang.org/docs/native-swift-export.html`, via search summary): not examined in depth; skipped because ObjC export plus SKIE already bracket the decision.
- Xamarin / .NET for Android: **inferred from memory, not fetched**: Java constants are PascalCased segment-wise with all-caps segments lowercased (`Bitmap.Config.ARGB_8888` binds as `Argb8888`), which matches both .NET's acronym guideline (`HttpClient`, `Xml`) and the `AB1C` to `Ab1c` outcome below.
- JS/Wasm export, Java interop: skipped; they keep the Kotlin spelling verbatim and have no casing convention to translate to.

### 10. Reverse direction has a mirror bug, separate route (verified by reading)

`nuget-plugin/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/NugetGenerateBindingsTask.kt:1897-1902` (`toEnumScreamingSnake`) inserts `_` before EVERY uppercase character: C# `AB1C` becomes `A_B1_C`, `HTTPStatus` becomes `H_T_T_P_STATUS` (inferred, not run). Different module, different rule, no issue filed. Out of scope here; see open question 5.

### 11. No rename annotation exists (verified by grep)

No `annotation class` in `nuget-runtime` other than `NugetRuntimeApi`; there is no `@CSharpName`-style escape hatch. So the automatic rule is the only lever, and it must be predictable.

### 12. Two unrelated generator defects the spikes tripped over (verified by spike, 2026-09-21; NOT part of this item)

Recorded so the fixture author does not walk into them and so they are not lost:

- An `is`-prefixed Boolean property on an enum aborts generation. `enum class Mood(val isSecond: Boolean) { FIRST(false), SECOND(true) }` (S9c), and equally a body property `val isSecond: Boolean get() = ...` on an all-caps enum (S9a, S9b), throws out of the processor: `IllegalArgumentException: Forward ABI missing Kotlin export for mood_get_issecond; expected mood_get_issecond(in int) -> bool` at `ForwardAbiContract.kt:180` via `NugetProcessor.kt:1610`. A non-`is` body property (`val doubled: Int get() = ordinal * 2`, S9d) generates fine. Casing-independent (all-caps entries reproduce it). No ROADMAP line found by grep. Do not put an `is`-prefixed property on the issue #285 fixture enum.
- `extractConstValue` (`cir/CirTranslator.kt:1544`) captures to end of line, so a one-line `class Holder { companion object { const val DefaultName = "x" } }` generates `public const string Defaultname = "x" } };` (S3), which is illegal C#. Put each fixture `const val` on its own line.

## Recommendation

One shared helper, per-segment, applied to enum entries and `const val`:

> Split the Kotlin name on `_`, drop empty segments. A segment that contains at least one lowercase letter keeps its internal casing and only gets its first character uppercased. A segment with no lowercase letter (all caps and digits) is lowercased then first-character uppercased, exactly as today. Join. If the result is empty or starts with a digit, prefix `_`.

| Kotlin entry | Today | Recommended | Moves? |
|---|---|---|---|
| `First` | `First` | `First` | no |
| `SecondValue` | `Secondvalue` | `SecondValue` | yes (fix) |
| `ThirdValueHere` | `Thirdvaluehere` | `ThirdValueHere` | yes (fix) |
| `AB1C` | `Ab1c` | `Ab1c` | no (see open question 1) |
| `Ab1c` | `Ab1c` | `Ab1c` | no |
| `HAPPY` | `Happy` | `Happy` | no |
| `SCREAMING_SNAKE` | `ScreamingSnake` | `ScreamingSnake` | no |
| `snake_case` | `SnakeCase` | `SnakeCase` | no |
| `camelCase` | `Camelcase` | `CamelCase` | yes (fix) |
| `XMLParser_V2` | `XmlparserV2` | `XMLParserV2` | yes (fix) |
| `HTTP_Status` | `HttpStatus` | `HttpStatus` | no |
| `class`, `default`, `event` (keyword-colliding) | `Class`, `Default`, `Event` | same | no; never a keyword (finding 8) |
| `_1ST` | `1st` (illegal C#, CS1001) | `_1st` | yes (fix) |
| `_` or `__` | empty name (illegal C#, CS1001) | `_` | yes (fix) |
| `FOO_BAR` + `FooBar` in one enum | `FooBar` + `Foobar` | collision: fatal `ERROR_CSHARP_NAME_COLLISION` naming both entries | new error |
| `FOO` + `Foo` in one enum | duplicate member, CS0102 at consumer | same fatal error, at generation | new error replaces a worse one |

Note (2026-09-21, after the spikes): every "Today" cell above was observed in generated C# (S1 to S5) and none contradicted the source-reading prediction, so the rule, the alternatives and the pricing stand unchanged. The one addition is the `_` / `__` row, which the first pass mentioned in finding 8 but left out of the table; spike S5 showed Kotlin accepts those names, so the empty-name guard is reachable code and gets a row and a Tier 1 assertion.

The predictability statement for the docs: "an entry written in UPPER_SNAKE becomes PascalCase; an entry already in Pascal or camel case keeps its spelling with a capital first letter."

Collision: group converted names per enum; on a duplicate emit `ERROR_CSHARP_NAME_COLLISION` with both Kotlin entry names and the hint "rename one entry". Fatal, not a suffix rename: a silent `FooBar_1` is the very thing the issue complains about, and ordinals must not move.

Priced: 3 generator files (`Reserved.kt` new helper `kotlinConstantToPascalCase()` beside `csharpIdentifier` at `:60`; `CirClassTranslator.kt:2997-2998` plus a `logger` parameter and the collision check; `CirTranslator.kt:1517-1520` plus the two call sites `:424`/`:478` passing `logger`), 1 fixture file, 1 xunit file, 1 Tier 1 file, 1 unit test for the helper, 4 docs (ADR-006 amendment, `docs/topics/enums.md:27`, FEATURES.md enum row, CHANGELOG/migration note). Size S.

Alternatives rejected:
- Whole-name gate (if the name has `_` or no lowercase, legacy rule; else first-char uppercase): identical on every issue input but mangles mixed names (`XMLParser_V2` to `XmlparserV2`). The per-segment rule is the same cost.
- Keep Kotlin spelling verbatim (`HAPPY` stays `HAPPY`): maximally predictable, but breaks every published consumer and every fixture, and abandons ADR-006's "feels like C#" goal.
- Word-boundary re-casing of everything (`AB1C` to `Ab1C`, `HTTPStatus` to `HttpStatus`): needs acronym heuristics, unpredictable, moves more names.
- Keep today's rule and add an INFO diagnostic: reports the rename but leaves `Secondvalue`; does not meet the restatement.
- Emit both spellings with `[Obsolete]` alias: two C# enum members with one value is legal, but it makes `ToString()` nondeterministic between the two names (inferred, documented .NET behaviour) and doubles IntelliSense. Rejected as default; see open question 2.

## Files an implementation touches

Generator: `nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/Reserved.kt`, `cir/CirClassTranslator.kt` (`translateEnum`), `cir/CirTranslator.kt` (`translateConstProperty`, two `translateEnum` call sites). No Kotlin emission, ABI (`ForwardAbiContract.kt`), runtime or plugin change: entries cross as ordinals (finding 3).
Fixtures: new `test-library/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/test/issue285/Issue285Sample.kt` (the issue's `Example` enum plus a function taking and returning it, and an `object` with `const val MaxRetries`).
Tests: new `IntegrationTests/Issue285Tests.cs`; new `nuget-processor/src/test/kotlin/.../tier1/Tier1EnumEntryCasingTest.kt` (casing cell plus collision cell); a table-driven unit test for the helper beside the existing processor unit tests. No leak rows (no handles involved).
Docs: `docs/adr/006-enum-mapping.md` (dated amendment), `docs/topics/enums.md` (`:27`, add the table), `FEATURES.md` enum row, release notes for the behaviour change. ROADMAP: no line exists; if built immediately none is needed, otherwise add one Phase 4 line citing #285 and this memo.

## Sample test

```csharp
// IntegrationTests/Issue285Tests.cs
[Fact]
public void PascalCaseEntries_KeepTheirKotlinSpelling()
{
    Assert.Equal(new[] { "First", "SecondValue", "ThirdValueHere", "Ab1c" },
        Enum.GetNames<Example>());
    Assert.Equal(1, (int)Example.SecondValue);
    Assert.Equal(Example.SecondValue, Issue285Sample.Echo(Example.SecondValue));
    Assert.Equal(3, Issue285Limits.MaxRetries);   // const val MaxRetries
}
```

Tier 1 cells (`Tier1EnumEntryCasingTest.kt`, using `Tier1Harness.run` as `Tier1OrdinarySurfaceTest.kt:121-139` does):

```kotlin
@Test fun `an entry keeps its internal capitals`() {
  val result = Tier1Harness.run("""
    package tier1.enumcasing
    enum class Example { First, SecondValue, camelCase, AB1C, SCREAMING_SNAKE, snake_case, XMLParser_V2 }
  """.trimIndent())
  assertTrue(result.compiledClean, "got: ${result.compileErrors}")
  val cs = result.generatedCSharp
  assertContains(cs, "SecondValue = 1")
  assertContains(cs, "CamelCase = 2")
  assertContains(cs, "Ab1c = 3")
  assertContains(cs, "ScreamingSnake = 4")
  assertContains(cs, "SnakeCase = 5")
  assertContains(cs, "XMLParserV2 = 6")
}

@Test fun `a digit-led or empty converted name gets an underscore`() {
  // Spike S4/S5: Kotlin accepts these entries and today's output is `1st = 0` and ` = 0`.
  val result = Tier1Harness.run("""
    package tier1.enumcasing.guard
    enum class Digit { _1ST, OK }
    enum class Bare { _, OK }
  """.trimIndent())
  assertTrue(result.compiledClean, "got: ${result.compileErrors}")
  assertContains(result.generatedCSharp, "_1st = 0")
  assertContains(result.generatedCSharp, "_ = 0")
}

@Test fun `two entries landing on one C# name fail generation naming both`() {
  // enum class Clash { FOO_BAR, FooBar } expects ERROR_CSHARP_NAME_COLLISION mentioning
  // both `FOO_BAR` and `FooBar`. Note (memory, ADR-117 work): the Tier 1 harness does not capture
  // uncaught processor exceptions, so assert on the emitted diagnostic, not on a throw.
}
```

Note (2026-09-21, after the spikes): the red-before-fix values for the first cell are observed, not predicted: today it generates `Secondvalue = 1`, `Camelcase = ...`, `XmlparserV2 = ...` (S1). The Tier 1 harness never compiles C# (`Tier1Result.kt:28-32`), so the collision cell can only assert the diagnostic and the absence of a second `Foo =` line; CS0102 itself was shown with a scratch `dotnet build` (S2). Fixture constraints from finding 12: no `is`-prefixed property on the fixture enum, and each `const val` on its own line.

## Deferred scope

- A per-declaration rename annotation (`@CSharpName("...")`, the `@ObjCName` analogue): a real feature with its own design (finding 11); not needed once the rule is predictable.
- Reverse-direction `toEnumScreamingSnake` acronym splitting (finding 10): separate route and module.
- Resolving KDoc `[Enum.ENTRY]` links to a `cref` on the C# member: today a text fallback (finding 3); would need the entry index to use the same helper.
- Enum-entry default parameter values (ADR-096 has none today); when added they must call the same helper.

## Open what-questions

1. `AB1C`: `Ab1c` (unchanged; indistinguishable from `HAPPY` to `Happy`, matches .NET acronym casing and Xamarin's `Argb8888`) or kept verbatim as `AB1C`? The issue author called `Ab1c` a misspelling. Recommendation: keep `Ab1c`. Any rule that preserves `AB1C` must also preserve `HAPPY`, which reverses ADR-006 and moves every published enum. Document it in the table so it is predictable. Human decision: pending.
2. Breaking change for 0.7.0 consumers: PascalCase, camelCase and mixed-segment entries (and the same `const val` shapes) get a new C# name, so `Example.Secondvalue` stops compiling (loud CS0117, never silent; ordinals and ABI do not move, so no binary break for the native library). Recommendation: ship as a bug fix in the next minor (0.8.0) with a "Behaviour changes" release note and no `[Obsolete]` alias, since the old spelling reads as a typo nobody wants to keep and duplicate enum members make `ToString()` ambiguous. Human decision: pending.
3. Collision: fatal `ERROR_CSHARP_NAME_COLLISION` at generation (recommended; also upgrades today's silent `FOO` + `Foo` duplicate member) versus a warning plus a deterministic suffix. Human decision: pending.
4. Fold the `const val` twin (finding 4) into the same PR? Recommendation: yes; same expression, same helper, one docs change. Human decision: pending.
5. Reverse mirror (`AB1C` to `A_B1_C`, finding 10): file a separate issue and ROADMAP line, or fold in? Recommendation: separate issue; it is a different module (`nuget-plugin`) and needs its own rule decision (acronym runs), so folding it in would block this S-sized fix. Human decision: pending.
6. ADR vehicle: dated amendment to ADR-006 (recommended; ADR-006 owns the "Entry names" line, and ADR-110's 2026-09-20 amendment is the precedent) versus a new ADR. A new ADR is only warranted if the human picks a non-default answer to question 1 or 2. Human decision: pending.

## Spikes run (2026-09-21)

Seam for S1 to S9: a scratch test class calling `Tier1Harness.run` (real `NugetProcessorProvider` through KSP2, then the in-process `K2JVMCompiler` over fixture plus generated Kotlin), run as `./gradlew :nuget-processor:test --tests '*Tier1EnumCasingSpikeTest*' -q --max-workers=2` in a separate worktree at main `651cb7d0`, dumping `kspErrors`, `kspWarnings`, `compileErrors` and every generated file. The scratch class was deleted afterwards and the worktree restored; nothing was added to the repo. Seam for the C# compile: `dotnet new classlib` in `mktemp -d`, SDK 10.0.301, with the generated enum text pasted in. No Kotlin/Native compile or link and no `scripts/verify.sh` was run.

| Spike | Input | Result |
|---|---|---|
| S1 (was a) | issue #285 `Example`, plus `Wide { camelCase, SCREAMING_SNAKE, snake_case, XMLParser_V2, HTTP_Status, default, event, class }` | CONFIRMED. `First = 0, Secondvalue = 1, Thirdvaluehere = 2, Ab1c = 3`; `Camelcase, ScreamingSnake, SnakeCase, XmlparserV2, HttpStatus, Default, Event, Class`. No diagnostic of any kind. |
| S2 (was b) | `enum class Clash { FOO, Foo }` | CONFIRMED. `Foo = 0, Foo = 1`, KSP exit OK, zero errors, warnings and diagnostics; `dotnet build` of that text: `error CS0102: The type 'Clash' already contains a definition for 'Foo'`. |
| S3 (was c) | `const val` top-level `TopLevel`, object `MaxRetries` / `maxDelay` / `MAX_SIZE`, companion `DefaultName` | CONFIRMED. `Toplevel`, `Maxretries`, `Maxdelay`, `MaxSize`, `Defaultname`. Side find: one-line companion const renders `= "x" } };` (finding 12). |
| S4 (was d) | `enum class Digit { _1ST, OK }` | Kotlin ACCEPTS it (`compileErrors=[]`, K2 JVM frontend, LV 2.0). Generates `1st = 0`; `dotnet build`: CS1001. Guard row stays. |
| S5 (was d) | `enum class Bare { _, OK }`, `enum class Bare2 { __, OK }` | Kotlin ACCEPTS both. Both generate an empty member name (` = 0`); `dotnet build`: CS1001. Guard row added to the table. |
| S6 | enum default on a function, a constructor and a method parameter | No entry name rendered: overloads only (`Describe(Level level, int n)` + `Describe(Level level)`), Kotlin side uses `Level.entries[level]`. Finding 3's ADR-096 bullet flipped to verified. |
| S7 | ADR-157 `enum class Patch : Marking { BibFront, SOCKS }` | Arm is `PatchArm(global::Interop.Patch entry)`, dispatch by ordinal; entry name appears only in the enum declaration (`Bibfront = 0`). |
| S8 | KDoc `[Mood.SecondValue]` and `[Mood]` | `<c>Mood.SecondValue</c>` text fallback; the type link becomes a `cref`. Finding 3's KDoc bullet flipped to verified. |
| S9a to S9d | enum with `val isSecond: Boolean` (body, Pascal entries; body, all-caps; constructor) versus `val doubled: Int` | `is`-prefixed Boolean property aborts generation with `Forward ABI missing Kotlin export for mood_get_issecond`; `doubled` is fine. Unrelated to casing (finding 12). |

Contradicted: nothing. Every prediction the first pass made from source reading held. Open question answered: spike d's "if not, drop the guard rows" branch is not taken; the guards are reachable.

Still **inferred** after this pass: Kotlin/Native (as opposed to the K2 JVM frontend) accepting `_1ST`, `_`, `__` as entry names; the SKIE, Swift export and Xamarin prior-art summaries (finding 9); the reverse-direction `toEnumScreamingSnake` outputs (finding 10, out of scope); the `ToString()` ambiguity of duplicate-valued enum members (rejected alternative only). None is load-bearing for the recommended rule.

## Spike first (for `kotlin-dev`)

All pre-implementation spikes are done (above). What remains can only be checked once the fix exists:

a. The collision cell: confirm `ERROR_CSHARP_NAME_COLLISION` raised from `translateEnum` surfaces in `Tier1Result.kspErrors` (or `NugetDiagnostics.json`) rather than as an uncaught throw, since the Tier 1 harness does not capture uncaught processor exceptions (S9 shows what that looks like: the throw escapes `Tier1Harness.run`).
b. The real-toolchain leg: the new `Issue285Sample.kt` fixture through `scripts/verify.sh`, which is the first time a Pascal, `_1ST`-style or `_` entry meets Kotlin/Native's C export and the real `dotnet` compile together.
c. Confirm no existing IntegrationTests, LeakTests or Tier 1 expectation moves (finding 5 is by grep, not by a run).
