# `forwardTypeKey()` keys the outer declaration only, so overloads differing in a type argument collide

- ROADMAP: line text as of 2026-09-14: "Inferred: `forwardTypeKey()` keys the outer declaration's qualified name only, so a substituted `describe(tags: List<String>)` and a declared `describe(tags: List<Int>)` both spell `kotlin.collections.List` and the duplicate-overload bug ADR-101's 2026-09-13 amendment fixed returns for that shape (Kotlin/Native allows the overload); ADR-082's `typeKey` had the same limit before it started sharing `forwardTypeKey()`."
- Researched: 2026-09-14, ~10 min of a 15 min budget (one spike run in a detached worktree, removed)
- Restatement: forward. Two functions that differ only in a generic type argument (`List<String>` vs `List<Int>`) must get distinct keys in the declared-vs-substituted comparison (`isDeclaredBy`) so a subclass declaring `describe(tags: List<Int>)` beside a substituted `describe(tags: List<String>)` renders exactly its own overload, no `_2` export, exactly as ADR-101's 2026-09-13 amendment already guarantees for `describe(tag: Int)` beside `describe(tag: String)`. C# accepts `Describe(IReadOnlyList<int>)` beside `Describe(IReadOnlyList<string>)`, so this is a key fix, not a refusal.
- Verdict: fix. No ADR (an established pattern; ADR-101's amendment already made the design decision, this is one missing recursion). ADR number 148 stays reserved and unused.

## Findings

1. **Verified by reading.** `KSType.forwardTypeKey()` (`nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/forward/ForwardClassMembership.kt:442-448`) returns `qualifiedName` of the alias-expanded declaration plus `?` for nullability and nothing else. `KSType.arguments` is never read. `List<String>`, `List<Int>`, `List<T>` and `List<String?>` all key to `kotlin.collections.List`.

2. **Verified by reading.** Callers: `forwardSignatureKey()` (`ForwardClassMembership.kt:432-435`, name + receiver key + one key per parameter), used by `isDeclaredBy` (`ForwardClassMembership.kt:407-421`) for the ADR-101 declared-vs-substituted match; and `ForwardSupertypeMembers.typeKey` (`ForwardCallablePlanner.kt:396-399`), ADR-082's wildcard variant (`null` when the position *is* a type parameter, else `forwardTypeKey()`), used by `declares(function)` (`ForwardCallablePlanner.kt:353-365`) at `ForwardCallablePlanner.kt:876` to skip a value class's inherited/delegated members as `INHERITED_MEMBER`. Introduced by commit `271f808e` (ADR-101's 2026-09-13 amendment, `docs/adr/101-unexported-supertype-skip.md:720-793`).

3. **Verified by spike (Tier 1 harness, detached worktree of `main` at `112475a1`, single `:nuget-processor:test --tests '*Tier1SpikeTypeArgOverloadTest*'` run, worktree removed).** Fixture:

   ```kotlin
   open class Crate<T>(val item: T) { fun describe(tags: List<T>): String = "$tags:$item" }
   class LabelledCrate(item: String) : Crate<String>(item) { fun describe(tags: List<Int>): String = "#$tags:$item" }
   ```

   Generated C# on `LabelledCrate`: **two** overloads, `public string Describe(IReadOnlyList<int> tags)` bound to `EntryPoint = "labelledcrate_describe"` and `public string Describe(IReadOnlyList<string> tags)` bound to `EntryPoint = "labelledcrate_describe_2"`. Generated Kotlin: `@CName("labelledcrate_describe")` mapping elements `it as kotlin.Int` and `@CName("labelledcrate_describe_2")` mapping `it as kotlin.String`, both dispatching `handle.asStableRef<LabelledCrate>().get().describe(...)`. So the ROADMAP line's inference is confirmed: the substituted member passes `isDeclaredBy` and the ADR-101 bug returns for this shape. The failure mode is a silent second overload plus a spurious numbered export (ADR-090 numbering distinguishes them by *occurrence*, not by type, so it does not catch the collision), never CS0111: C# legally overloads on `IReadOnlyList<int>` vs `IReadOnlyList<string>`.

4. **Verified by spike, load-bearing for the Tier 1 cell.** The Tier 1 harness compiles the fixture with the JVM backend, and the JVM rejects that fixture as written: `Fixture.kt:8:3: Accidental override: The following declarations have the same JVM signature (describe(Ljava/util/List;)Ljava/lang/String;)`, so `result.compiledClean` is `false` while KSP output is still produced. Kotlin/Native has no erasure clash (the ROADMAP line's "Kotlin/Native allows the overload" is inferred, not spiked here). A Tier 1 cell must therefore put `@JvmName("describeInts")` on the subclass's `describe(tags: List<Int>)` to keep the fixture JVM-clean; the processor's keys do not read `@JvmName`, so the spelling under test is unchanged. Inferred (not spiked): `kotlin.jvm.JvmName` is an optional-expectation annotation usable in the fixture; if the harness rejects it, drop the `compiledClean` assertion for this one cell and assert on generated text only (the spike shows generation completes either way).

5. **Verified by reading.** The substituted member's parameter arrives already substituted (`IReadOnlyList<string>` in the spike's C#, `it as kotlin.String` in the Kotlin), so a key that recurses into arguments spells `kotlin.collections.List<kotlin.String>` vs `kotlin.collections.List<kotlin.Int>`; had KSP handed back `List<T>` instead, the recursion would spell `kotlin.collections.List<T>`, still unequal. Strictness holds under either KSP behaviour.

6. **Verified by reading.** ADR-082's `ForwardSupertypeMembers.typeKey` wildcards only when the *whole position* is a type parameter. Once `forwardTypeKey()` recurses, a value class delegating `List<T>`-typed members (a supertype spelling `foo(x: List<T>)` beside the delegated `foo(x: List<String>)`) would stop matching (`List<T>` vs `List<kotlin.String>`), and the delegated member would leak out of `INHERITED_MEMBER` into the plan. Today both spell `kotlin.collections.List` and match by over-approximation. The wildcard must therefore recurse too: a type-parameter *argument* wildcards, not only a type-parameter position. No shipped fixture exercises a delegated generic-argument signature (inferred: grepped `ForwardSupertypeMembers`, only the value-class callable path at `:876` uses it), so the regression would be silent, which is what makes it load-bearing.

7. **Verified by reading.** `baseClassOverridee`'s fallback (`ForwardClassMembership.kt:349-372`) is name-only and untouched by this item; it is its own ROADMAP line (the one directly below this item) and needs the ADR-082 wildcard, not this strict key.

8. **Verified by reading.** `CirTypeMapping.kt:411` and `:423-445` already walk `KSType.arguments` (`argument.type?.resolve()`) for C# spelling, so the KSP surface needed (`KSTypeArgument.type`, nullable for a star projection, `variance`) is in use in the repo.

## Recommendation

Recurse into `type.arguments` inside `forwardTypeKey()`, one file for the key and one for the wildcard:

- `ForwardClassMembership.kt` `forwardTypeKey()`: `name` + (if `expanded.arguments` non-empty) `<` + arguments joined by `,` + `>` + `?`. Each argument: star projection (`type == null`) spells `*`; otherwise the variance keyword if not `INVARIANT` (`in `/`out `) followed by the argument type's own `forwardTypeKey()`. Nullability of the argument comes along from the recursion. Keep the type-parameter arm as is (a bare `T` spells `T`).
- `ForwardCallablePlanner.kt` `ForwardSupertypeMembers`: the wildcard has to become structural to keep finding 6 green. Narrowest shape: keep `Signature.parameters: List<String?>` and make `typeKey` return `null` when the position is a type parameter **or mentions one anywhere in its arguments** (a small recursive `mentionsTypeParameter(KSType)`), so `List<T>` wildcards the whole position exactly as bare `T` does today. Over-matches conservatively, the direction ADR-082 already chose; no new data shape.
- Extend `Tier1GenericBaseOverloadTest.kt` with the cell below (or a sibling file). Add a unit test for `forwardTypeKey()` if one exists for `forwardSignatureKey` (grep found none under `processor/forward/` tests; Tier 1 is the pin).
- FEATURES.md: note on the ADR-101 row; ADR-101: strike the deferred bullet at `docs/adr/101-unexported-supertype-skip.md:782-791` in an amendment line; ROADMAP: delete the line.

Alternatives rejected:
- Refuse the shape named (`SKIPPED_...`): wrong, C# and Kotlin/Native both accept the overload pair; the declared overload is fully bindable.
- Fix only `forwardTypeKey()` and leave the ADR-082 wildcard: silently flips finding 6, a delegated `List<T>` member would render on a value class.
- Key the C# spelling instead of the Kotlin qualified name: would collapse `List<Int>`/`MutableList<Int>` differently and drags `CirTypeMapping` into a comparison that only needs Kotlin identity.

## Files an implementation touches

- `nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/forward/ForwardClassMembership.kt` (`forwardTypeKey`)
- `nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/forward/ForwardCallablePlanner.kt` (`ForwardSupertypeMembers.typeKey`)
- `nuget-processor/src/test/kotlin/io/github/xxfast/kotlin/native/nuget/processor/tier1/Tier1GenericBaseOverloadTest.kt` (new cell)
- Optional xunit row in `IntegrationTests/GenericBaseOverloadTests.cs` plus a `List<T>`-taking `describe` on the `TestLibrary` fixture's `Crate`/`LabelledCrate` if the human wants an end-to-end pin; generator-only otherwise. No leak row: the fix removes an export and mints none.
- `docs/adr/101-unexported-supertype-skip.md` (amendment note), `FEATURES.md`, `ROADMAP.md`.

## Sample test

Tier 1 cell (generator-only):

```kotlin
@Test
fun `a substituted generic-base function differing only in a type argument is not re-declared`() {
  val result = Tier1Harness.run(
    """
    package tier1.genericbase.typeargs

    open class Crate<T>(val item: T) {
      fun describe(tags: List<T>): String = "${'$'}tags:${'$'}item"
    }

    class LabelledCrate(item: String) : Crate<String>(item) {
      @JvmName("describeInts")
      fun describe(tags: List<Int>): String = "#${'$'}tags:${'$'}item"
    }
    """.trimIndent(),
  )
  assertTrue(result.compiledClean, "got: ${result.compileErrors}")
  val cs: String = result.generatedCSharp
  assertEquals(1, cs.split("public string Describe(").size - 1, cs)
  assertContains(cs, "public string Describe(IReadOnlyList<int> tags)")
  assertFalse("Describe(IReadOnlyList<string>" in cs, cs)
  assertFalse("labelledcrate_describe_2" in cs, cs)
  assertEquals(1, result.generated.lines().count { "@CName(" in it && "labelledcrate_describe" in it })
}
```

Red today: the spike produced `describes == 2` and a `labelledcrate_describe_2` export (finding 3).

## Deferred scope

- `baseClassOverridee`'s name-only fallback (next ROADMAP line): separate item, needs the wildcard comparison.
- A generic base's own declared functions still have no C# carrier (`GenericBase_CarriesNoDescribeOfItsOwn`, ADR-147 territory): untouched.
- Variance-only differences (`List<out T>` vs `List<T>`) are not a real overload in Kotlin; the key spells variance for completeness but no cell pins it.

## Open what-questions

- Should the cell be pinned end to end (xunit + `TestLibrary` fixture change) or Tier 1 only? Main-thread recommendation: Tier 1 only, the ADR-101 xunit rows already cover the mechanism and this adds no new export. Human decision: pending.
- `@JvmName` on the fixture (finding 4): acceptable in the Tier 1 fixture, or drop the `compiledClean` assertion for this cell? Main-thread recommendation: `@JvmName`, it keeps the cell's shape identical to the shipped one. Human decision: pending.
