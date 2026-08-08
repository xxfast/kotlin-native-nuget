package io.github.xxfast.kotlin.native.nuget.test.models

/**
 * ADR-066's amended `SKIPPED_INHERITED_MEMBER` filter guard. `ForwardCallablePlanner`'s existing
 * rule excludes a member when `origin != Origin.KOTLIN`, which is correct in-module but wrong
 * cross-module: every member of a `KOTLIN_LIB` declaration reports `origin == KOTLIN_LIB`,
 * including `shout()` below, which is genuinely author-declared on this class. Applied literally,
 * the old rule would drop every member, including `shout()`; the amended rule (a supertype
 * declares a member with this simple name) must drop only the delegated `CharSequence` members
 * (`get`, `subSequence`, `length`) and let `shout()` survive.
 */
value class StoryUri(val value: String) : CharSequence by value {
  fun shout(): String = value.uppercase()

  /**
   * ADR-082 amendment (2026-08-08), fix A: a declared member whose simple name collides with a
   * supertype member (`CharSequence.get(index: Int): Char`) but is not that member's signature.
   * Same kind (function) and name (`get`) and arity (1), but the parameter type at position 0 is
   * `String` here versus `Int` on the supertype, so the new signature-level rule must classify
   * this as declared and export it, while `get(index: Int)` itself keeps skipping as the genuine
   * inherited signature. Reads `value` as a `?`-delimited, `&`-separated query string and returns
   * the first matching parameter, or the empty string if [key] is absent.
   */
  fun get(key: String): String =
    value.substringAfter('?', missingDelimiterValue = "")
      .split('&')
      .map { it.split('=', limit = 2) }
      .firstOrNull { it[0] == key }
      ?.getOrNull(1)
      ?: ""
}
