package io.github.xxfast.kotlin.native.nuget.test.namesake.a

/**
 * Namesake fixture, half A: the class shape of the export-symbol collision (ROADMAP line 47,
 * backlog `two-exported-types-same-simple-name-different.md` shape 1).
 *
 * This `Kitten` and [io.github.xxfast.kotlin.native.nuget.test.namesake.b.Kitten] are the same
 * simple name in two packages, so today every symbol derived from the owner's bare simple name
 * collides: `kitten_create`, `kitten_dispose`, `kitten_get_name`, `kitten_greet`. The C symbol is a
 * private contract between the generated `CNameExports.kt` and the generated `Interop.cs`, so the
 * xunit cells pin only C# reachability and behaviour, never a symbol string.
 *
 * Oreo lives in house A: black, with a little white in the middle.
 */
class Kitten(val name: String, val lives: Int) {
  /** Deliberately the same member name as half B's, with a distinguishable answer. */
  fun greet(): String = "a:$name has $lives lives"
}
