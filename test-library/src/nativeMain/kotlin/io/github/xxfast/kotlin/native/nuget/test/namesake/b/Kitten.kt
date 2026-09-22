package io.github.xxfast.kotlin.native.nuget.test.namesake.b

/**
 * Namesake fixture, half B: the twin of
 * [io.github.xxfast.kotlin.native.nuget.test.namesake.a.Kitten], same simple name, same member
 * names, same constructor signature `(String, Int)`, different package and different answers.
 *
 * Mylo lives in house B: brown and creamy, like the drink.
 */
class Kitten(val name: String, val lives: Int) {
  /** Deliberately the same member name as half A's, with a distinguishable answer. */
  fun greet(): String = "b:$name has $lives lives"
}
