/**
 * Fixture for a Kotlin default that a real shorter overload shadows (ADR-164). Beside
 * `Kitten(name)`, the call `Kitten(name)` always resolves to that real overload, never to
 * `Kitten(name, lives = 9)` with `lives` defaulted: Kotlin prefers the candidate that uses no
 * defaults. So `lives`'s default is unreachable from any Kotlin call site while the shorter
 * overload exists, and the generator keeps `lives` REQUIRED in C# rather than widening it to an
 * `int? lives = null` whose "unset" arm would silently call the other overload.
 *
 * Each overload returns text naming which one ran, so a call reaching the wrong one reads back as
 * the wrong text. Oreo (black, white in the middle) and Mylo (brown and creamy) take turns.
 */
package io.github.xxfast.kotlin.native.nuget.test.shadowed

/** The constructor pair: `Kitten(name)` is real, `Kitten(name, lives = 9)` defaults `lives`. */
class Kitten(val name: String, val lives: Int = 9) {
  /** The real shorter overload. Its own `lives` is 1, never the other overload's default 9. */
  constructor(name: String) : this(name, 1)

  /** The method pair, shorter half. */
  fun greet(visitor: String): String = "short:$visitor"

  /** The method pair, defaulted half: `times` stays required because [greet] shadows it. */
  fun greet(visitor: String, times: Int = 2): String = "long:$visitor:$times"
}

/** The extension pair, shorter half. */
fun Kitten.purr(): String = "short:$name"

/** The extension pair, defaulted half: `loud` stays required because [purr] shadows it. */
fun Kitten.purr(loud: Boolean = true): String = "long:$name:$loud"

/** The top-level pair, shorter half. */
fun summon(name: String): String = "short:$name"

/** The top-level pair, defaulted half: `loud` stays required because [summon] shadows it. */
fun summon(name: String, loud: Boolean = true): String = "long:$name:$loud"
