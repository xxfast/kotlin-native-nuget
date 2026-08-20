package io.github.xxfast.kotlin.native.nuget.test.clinic

/**
 * ADR-099 ("Forward, nested collection components: the inner handle in the component slot, disposed
 * by whoever minted it"). One class, cells as members, following ADR-081's
 * `ValueClassCollectionsSample.kt`, ADR-083's `NullableComponentCollectionsSample.kt`, ADR-097's
 * `EnumComponentCollectionsSample.kt` and ADR-098's `NarrowComponentCollectionsSample.kt`
 * precedents. What is new here is a component slot holding *another collection*, so the recursion
 * has to run through `CreateList`/`CreateSet`/`CreateMap` on the write side and through the new
 * `ReadList`/`ReadSet`/`ReadMap` helpers on the read side.
 *
 * The ward board is a whiteboard of cage rows. Oreo naps on the top row, Mylo works his way along
 * the bottom one, and every cell below is some shape of "rows of things".
 *
 * The two positions are **not** symmetric, which is the reason this fixture exists at all:
 *
 * - **Input position** ([logGrid], [chartRuns], [tallyGroups], [logCages], [trailGrid],
 *   [weighLitters]) is a clean named `SKIPPED_UNSUPPORTED_INPUT` today, because ADR-097 narrowed
 *   `List`'s callable-input gate to `isWrappableComponent()` and `Map`/`Set` have run that gate
 *   since ADR-073. No C# member is generated, so these cells fail to *compile* on the consumer
 *   side.
 * - **Return position** ([grid], [runsByPatient]) is a shipped **bind-then-throw landmine**. The
 *   result gate is the wider `isBridgeableComponent()`, which already recurses through
 *   `Collection`, so `fun grid(): List<List<String>>` binds, renders
 *   `IReadOnlyList<IReadOnlyList<string>>` and emits
 *   `NugetMarshal.FromHandle<IReadOnlyList<string>>`
 *   -- which has no branch for that `T`, falls to `Materialize<T>`, finds no `Factories` entry and
 *   throws `NotSupportedException`. This is the exact shape `List<Mood>` had before ADR-097, one
 *   position over. No fixture in the corpus declared a nested return before this file, which is why
 *   nobody has hit it.
 *
 * Cell-by-cell, each crossing a seam none of the others reaches:
 *
 * - [logGrid] is the restatement: recursive `CreateList` on the write side, recursive
 *   `componentLowering` on the Kotlin side, and no conversion at any level.
 * - [grid] is the landmine above, and the cell for the new recursive `ReadList`.
 * - [chartRuns] is an outer `Map`: a plain `String` key needing no conversion sits **beside** a
 *   nested value whose leaf is an ADR-097 enum needing one, so the mixed projection is compiled by
 *   a real compiler rather than only asserted as generated text.
 * - [tallyGroups] is an outer `Set` over an inner `List`, the cross-kind cell: the generated file
 *   needs `NugetSetNative` **and** `NugetListNative` emitted together, which is the helper-gating
 *   bug class `ROADMAP.md:141` already records.
 * - [runsByPatient] is the `Map` return: `ReadMap` over a nested value with a converting leaf,
 *   genuinely distinct code from [grid]'s list read.
 * - [logCages] is depth **3**. The ADR chose arbitrary depth over a depth-1 cap because the
 *   recursion is one `when` arm while a cap needs a guard plus a second skip reason in each of the
 *   four projection functions. This cell is what makes that claim observable instead of argued.
 * - [trailGrid] is a nullable **leaf** under nesting (`List<List<String?>>`), which the ADR admits:
 *   it rides ADR-083's existing arms underneath the recursion, and it is the cell that forces the
 *   read lambda to be block-bodied rather than expression-bodied.
 * - [weighLitters] composes ADR-098 with this ADR: the leaf is a narrow primitive, so the inner
 *   `CreateList<short>` needs `nuget_wrap_short` to be gated in from **two levels down** rather
 *   than from a top-level parameter. It is also the second "converting slot beside a
 *   non-converting one" spelling, with the conversion at a different depth than [chartRuns]'s.
 * - [logSparse] is a **nullable nested collection** (`List<List<String>?>`) and must stay a named
 *   `SKIPPED_UNSUPPORTED_INPUT`. `isWrappableComponent()`'s `Nullable` branch delegates to its
 *   inner type, so the instant `Collection` becomes wrappable this shape is admitted
 *   **automatically**, and it would bind with a write projection that has no null arm: precisely
 *   the trap ADR-097 hit with `List<Mood?>`. ADR-099 specifies an explicit
 *   `type !is BridgeType.Collection` guard in that branch. The consumer-side assertion is that
 *   `LogSparse` is absent from the generated C#.
 *
 * The property-setter cell lives on `Chart` (`var grid`), where ADR-075's setter-eligibility cells
 * already are, because `isSetterEligible()` uses the same `isWrappableComponent()` predicate and
 * therefore flips for free with the callable inputs.
 *
 * Every assertion value is asymmetric on purpose: rows have different lengths, no row is a prefix
 * of another, and no enum cell uses ordinal 0 alone. A recursion that flattened, transposed or
 * dropped the innermost level cannot pass any of them by coincidence.
 */
class WardBoard {
  /**
   * ADR-099 · `List<List<String>>` parameter -- the restatement's exact shape and the simplest
   * possible nesting: `List` outside, `List` inside, nothing converting at either level, so the
   * only new machinery under test is the recursion itself. Not generated today.
   */
  fun logGrid(rows: List<List<String>>): String = rows.joinToString(";") { it.joinToString(",") }

  /**
   * ADR-099 · `List<List<String>>` **return** -- the shipped bind-then-throw landmine. Binds and
   * compiles today, then throws `NotSupportedException` out of
   * `FromHandle<IReadOnlyList<string>>` -> `Materialize`. Rows are deliberately ragged so a read
   * that flattened or padded is visible in the assertion.
   */
  fun grid(): List<List<String>> = listOf(listOf("oreo", "mylo"), listOf("biscuit"))

  /**
   * ADR-099 · `Map<String, List<Mood>>` parameter -- an outer `Map` whose KEY slot needs no
   * conversion at the seam and whose VALUE slot is a nested collection with an ADR-097 enum leaf
   * that does. The one input cell where a converting and a non-converting slot sit in the same
   * collection, so the mixed
   * `new KeyValuePair<string, IntPtr>(x.Key, CreateList<int>(Select(x.Value, y => (int)y)))`
   * projection is compiled rather than only asserted.
   */
  fun chartRuns(runs: Map<String, List<Mood>>): String =
    runs.entries.sortedBy { it.key }.joinToString(";") { (name, moods) ->
      "$name=${moods.joinToString(",")}"
    }

  /**
   * ADR-099 · `Set<List<String>>` parameter -- outer `Set`, inner `List`. The cross-kind cell:
   * this one member forces `NugetSetNative` and `NugetListNative` to be emitted into the same
   * generated file, which no existing fixture requires of a single declaration and which is
   * exactly the helper-gating bug class `ROADMAP.md:141` records.
   */
  fun tallyGroups(groups: Set<List<String>>): String =
    groups.map { it.joinToString(",") }.sorted().joinToString(";")

  /**
   * ADR-099 · `Map<String, List<Mood>>` **return** -- the second half of the landmine, and the
   * cell for the new `ReadMap` helper over a nested value with a converting leaf. Distinct code
   * from [grid]'s element-slot read: the value slot's read projection is its own seam.
   */
  fun runsByPatient(): Map<String, List<Mood>> =
    mapOf("oreo" to listOf(Mood.CALM, Mood.PLAYFUL), "mylo" to listOf(Mood.ANXIOUS))

  /**
   * ADR-099 · `List<List<List<String>>>` parameter -- depth **3**. The ADR rejected a one-level
   * cap on cost grounds: `BridgeType.Collection` already holds `BridgeType` components, so the
   * recursive form is a single `is BridgeType.Collection ->` arm per function while a cap needs a
   * depth guard plus a second skip reason in each of the four. This cell is the one that would
   * fail if anyone special-cased depth 1, and it is the reason "arbitrary depth" is a checked
   * claim here rather than a design aspiration.
   */
  fun logCages(cages: List<List<List<String>>>): String =
    cages.joinToString("|") { row -> row.joinToString(";") { it.joinToString(",") } }

  /**
   * ADR-099 · `List<List<String?>>` parameter -- a nullable **leaf** under a nesting level, which
   * the ADR admits (unlike [logSparse]'s nullable *collection*). It rides ADR-083's existing
   * component-slot null pointer underneath the recursion, so the two features have to compose
   * rather than merely coexist, and it is the cell that forces the inner read lambda to be
   * block-bodied (ADR-083's `CirComponentRead` carries a `declaration`, which no expression-bodied
   * lambda can hold). The null sits at a non-first index in a non-first row, so a collapsed or
   * zero-filled slot cannot pass.
   */
  fun trailGrid(rows: List<List<String?>>): String =
    rows.joinToString(";") { row -> row.joinToString(",") { it ?: "null" } }

  /**
   * ADR-099 x ADR-098 · `Map<String, List<Short>>` parameter -- the leaf is a narrow primitive, so
   * the inner `CreateList<short>` needs `nuget_wrap_short` gated into the generated file from **two
   * levels down** rather than from a top-level parameter, which is a different question from
   * [chartRuns]'s enum leaf (an enum projects to `int` at the call site and needs no new wrap
   * export). Second spelling of "one slot converting, one not", with the conversion at a different
   * depth. Values are chosen past `sbyte` range and negative so a narrowing or sign error at depth
   * shows up rather than hiding.
   */
  fun weighLitters(litters: Map<String, List<Short>>): String =
    litters.entries.sortedBy { it.key }.joinToString(";") { (name, grams) ->
      "$name=${grams.joinToString(",")}"
    }

  /**
   * ADR-099 gate cell · `List<List<String>?>` parameter -- a nullable **nested collection**, which
   * must stay a named `SKIPPED_UNSUPPORTED_INPUT` and must have **no** C# member.
   *
   * `isWrappableComponent()`'s `Nullable` branch is `type !is BridgeType.Nullable &&
   * type.isWrappableComponent()`, so it delegates to the inner type: the moment `Collection`
   * becomes wrappable, this shape is admitted automatically, whether anyone asked for it or not.
   * Admitted, it would bind with a write projection that has no null arm and a read with no
   * zero-handle arm, which is exactly the trap ADR-097 hit with `List<Mood?>`. ADR-099 therefore
   * adds an explicit `type !is BridgeType.Collection` guard to that branch, and this cell is what
   * proves the guard is there. It is the direct sibling of ADR-097's `MoodLedger.logSpans`: a
   * declaration that exists so an *absence* can be asserted.
   */
  fun logSparse(rows: List<List<String>?>): String =
    rows.joinToString(";") { it?.joinToString(",") ?: "null" }
}
