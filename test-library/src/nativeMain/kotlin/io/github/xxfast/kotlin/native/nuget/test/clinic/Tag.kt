package io.github.xxfast.kotlin.native.nuget.test.clinic

/**
 * Part C of the boundary-nullability item: a **bare `Char?`** at every ordinary position.
 *
 * ADR-098 already minted the `Char` wire (`typedef unsigned short libtest_KChar`, `[MarshalAs(
 * UnmanagedType.U2)]` on every by-value slot), so what is missing is only the has-value fan-out that
 * ADR-079/080 built for `Primitive?`, `Instant?`, `Duration?` and `Enum?`. `Char` is its own
 * `BridgeType`, not a `PrimitiveKind`, so every `is BridgeType.Primitive` arm misses it. The
 * consequences differ by position, which is why one cell per position is the point of this file:
 *
 *  - [initial] is the **property** cell, get AND set, and it is the one that does not merely skip:
 *    the planner admits `Nullable(Char)`, no `Char` arm exists in the has-value fan-out, so the
 *    getter is planned `Direct` and the Kotlin emitter falls into
 *    `error("Forward property direct nullable getter is invalid ...")`. That is an uncaught
 *    processor exception, so ONE such property aborts generation for the whole module rather than
 *    dropping the member. It is a constructor `var`, so it exercises the setter lowering too.
 *  - [echo] is the **parameter and member-return** cell in one call: today the parameter alone is
 *    `SKIPPED_UNSUPPORTED_INPUT` ("expose a non-nullable wrapper, or a separate has-value/value
 *    pair, instead") and the return alone is `SKIPPED_UNSUPPORTED_RETURN` with the same hint. Those
 *    two hint sentences become false for `Char?` the moment the has-value pair exists, which is
 *    what this cell is here to notice.
 *  - [firstLetter] is the **top-level function return**, a different (legacy two-call) route from
 *    the member return, reached through a reroute that ADR-076, ADR-079 and ADR-080 each had to add
 *    their own type to.
 *  - [mascotInitial] is the **top-level property**, which crashes through a different caller
 *    (`PropertyExports`) than a class property (`ClassExports`), so it is not covered by [initial].
 *
 * Three payload values, all three load-bearing:
 *
 *  - `null`, the whole point of the item,
 *  - `'é'` (U+00E9), a BMP character outside ASCII: it survives an all-ASCII corpus unharmed, which
 *    is exactly how the shipped ordinary-position width bug lived as long as it did,
 *  - `'한'` (U+D55C), a character ABOVE 0x7FFF: it is the sign/width seam. Every value below
 *    0x8000 round-trips identically through a SIGNED 16-bit slot and an UNSIGNED one, so a `short`
 *    where the wire wants `ushort` passes `'é'` and `'日'` alike. Only a character with the top bit
 *    set can tell them apart. A bare `out char` (no `MarshalAs`) is verified to corrupt both
 *    non-ASCII cells silently, so neither of them is decorative.
 *
 * A lone surrogate is deliberately not a cell: it fails to round-trip under every candidate wire,
 * which makes it degenerate input rather than a marshalling question.
 *
 * The file declares `Tag`, so ADR-007 renames the file class and the top-level pair lives on
 * `TagKt`.
 *
 * Oreo's collar tag is blank. Mylo's has a character on it nobody at the clinic can pronounce.
 */
class Tag(var initial: Char?) {
  /** Parameter and member return in one call: `null` in, `null` out; `'한'` in, `'한'` out. */
  fun echo(c: Char?): Char? = c
}

/** Top-level function return position: `null` for an empty name, never an exception. */
fun firstLetter(name: String): Char? = name.firstOrNull()

/** Top-level property position: above 0x7FFF, so a signed slot cannot fake it. */
val mascotInitial: Char? = '한'
