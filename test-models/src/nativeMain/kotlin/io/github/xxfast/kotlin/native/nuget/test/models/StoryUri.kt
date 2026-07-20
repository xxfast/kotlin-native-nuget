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
}
