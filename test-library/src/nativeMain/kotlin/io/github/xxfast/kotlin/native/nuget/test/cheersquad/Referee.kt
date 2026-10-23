package io.github.xxfast.kotlin.native.nuget.test.cheersquad

/**
 * ADR-239: a `Throwable` at a lambda's edges: the result of a `suspend` lambda parameter, a
 * nullable `Throwable?` payload of a plain lambda parameter, and the result of a returned
 * `suspend` lambda. Mylo referees; Oreo fouls.
 */
class Referee {
  /** Reads the message of the exception the C# judge hands back. */
  suspend fun verdict(judge: suspend (Int) -> Throwable): String = judge(1).message ?: "none"

  /** Calls [onFoul] with no foul, then with one. */
  fun whistle(onFoul: (Throwable?) -> String): String =
    onFoul(null) + " / " + onFoul(IllegalStateException("offside"))

  /** A returned `suspend` lambda whose result is an exception. */
  fun verdictLater(): suspend (Int) -> Throwable = { code -> IllegalStateException("code $code") }
}
