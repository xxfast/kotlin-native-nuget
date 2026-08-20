package io.github.xxfast.kotlin.native.nuget.test.clinic

/**
 * ADR-098 ("Forward, narrow-primitive and `Char` collection components: six new `nuget_wrap_*`
 * exports, and a `ushort`-shaped `Char` wire fixed at every position"). One class, cells as
 * members, mirroring ADR-081's `ValueClassCollectionsSample.kt`, ADR-083's
 * `NullableComponentCollectionsSample.kt` and ADR-097's `EnumComponentCollectionsSample.kt`
 * precedents -- but the new thing crossing here is a component slot too narrow for the shipped
 * `Wrap<T>`, which has exactly six branches (`string`, `int`, `long`, `float`, `double`, `bool`)
 * and then throws.
 *
 * The clinic weighs Oreo and Mylo constantly, and none of those numbers need an `Int`.
 *
 * **Part A, the six narrow primitives.** They share one code path and one `typeof(T)` shape, so
 * this fixture picks the three that differ *at the seam* rather than one cell each: [chart] is
 * signed (`Short`, no conversion, the wire type is C#'s own `short`), [weigh] is unsigned and the
 * widest of the six (`ULong`, a Kotlin inline-class box on one side and a plain `ulong` on the
 * other) *and* sits at a `Set` position so the shared predicate is proven at a second collection
 * kind, and [census] puts a narrow unsigned type in the `Map` **key** slot with a narrow signed
 * type in the value slot, because the two slots project independently.
 *
 * [trail] is `List<Short?>`. `isWrappableComponent()`'s `Nullable` branch delegates to the inner
 * type, so admitting `SHORT` admits `Short?` in the same edit whether anyone asked for it or not.
 * The cell exists to prove that "free" is actually free instead of assuming it.
 *
 * **Part B, `Char`.** [initials] is the write side and [marks] is the read side; `FromHandle<T>`
 * has no `char` branch today, so the read side falls through to `Materialize<char>` and throws.
 * Both carry non-ASCII characters on purpose: a `Char` wire that truncates to one ANSI byte passes
 * every ASCII cell in the corpus, which is exactly how the shipped ordinary-position bug survived
 * this long (see `CharPositionMarshallingTests.cs` for that half, which needs no new Kotlin).
 * [glyph] is the property-getter position of the same wire -- `Patient.grade` is `'A'`, so no
 * shipped cell can tell a correct getter from a truncated one.
 *
 * A bare `Char?` is deliberately **not** a cell: ADR-098 mints the wire but not the has-value
 * fan-out, so a nullable `Char` property still aborts `packNuget`. `List<Char?>` would work, but
 * `List<Short?>` already proves the same `Nullable` composition without needing a second wire.
 *
 * Lone surrogates are deliberately not a cell either: they fail to round-trip under every
 * candidate wire shape, which makes them degenerate input rather than a marshalling question.
 */
class Readings {
  /**
   * ADR-098 part A · `List<Short>` parameter. Signed and narrow: C#'s `short` *is* the wire type,
   * so nothing converts at the seam and the only missing piece is `Wrap<T>`'s branch. This is the
   * shape ADR-097 named `SKIPPED_UNSUPPORTED_INPUT` when it narrowed `List`'s input gate.
   */
  fun chart(samples: List<Short>): String = samples.joinToString(",")

  /**
   * ADR-098 part A · `Set<ULong>` parameter. Unsigned, so Kotlin boxes an inline class while C#
   * sends a plain `ulong`, and the widest of the six, so a wire that quietly narrowed would lose
   * `ULong.MAX_VALUE`. A `Set` position as well, so the shared predicate is proven at a second
   * collection kind and a second native export.
   */
  fun weigh(grams: Set<ULong>): String = grams.sorted().joinToString(",")

  /**
   * ADR-098 part A · `Map<UByte, Short>` parameter -- narrow in BOTH slots, unsigned key beside a
   * signed value. The key slot projects independently of the value slot, so [chart] and [weigh]
   * cannot reach it, and `UByte` is the narrowest of the six.
   */
  fun census(byGrade: Map<UByte, Short>): String =
    byGrade.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }

  /**
   * ADR-098 part A · `List<Short?>` parameter. `isWrappableComponent()`'s `Nullable` branch
   * delegates to the inner type, so this is admitted by the same edit that admits [chart] -- not a
   * scope fork, a consequence. The cell exists so "free" is proven rather than assumed.
   */
  fun trail(samples: List<Short?>): String = samples.joinToString(",") { it?.toString() ?: "null" }

  /**
   * ADR-098 part B · `List<Char>` parameter, the element that forces the UTF-16 width decision.
   * There is no `nuget_wrap_char` at all today, so this member is not generated.
   */
  fun initials(marks: List<Char>): String = marks.joinToString("")

  /**
   * ADR-098 part B · `List<Char>` return. `FromHandle<T>` has no `char` branch, so this binds and
   * throws out of `Materialize<char>` at the first read. Non-ASCII on purpose: a truncating wire
   * would still pass an ASCII cell.
   */
  fun marks(): List<Char> = listOf('é', '日')

  /**
   * ADR-098 part B · the property-getter position of the same `Char` wire. `Patient.grade` is
   * `'A'`, so no shipped cell distinguishes a correct getter from an ANSI-truncated one, and the
   * ADR's claim that the fix spans parameters, returns *and* property getters is otherwise
   * untested. Non-null: a bare `Char?` still has no has-value fan-out and aborts `packNuget`.
   */
  val glyph: Char = 'Ω'
}
