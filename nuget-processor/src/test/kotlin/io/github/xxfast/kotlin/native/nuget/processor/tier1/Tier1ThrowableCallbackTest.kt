package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A `Throwable` at a lambda's edges: the RESULT of a `suspend` lambda parameter (the
 * managed-exception text the plain route boxes, read through a `Task<Exception>`), a nullable
 * `Throwable?` payload of a plain lambda parameter (its ADR-107 envelope, no handle for `null`),
 * and the result of a returned `suspend` lambda (an erased position: the envelope over a generated
 * export). Each binds on both halves and is named nowhere.
 */
class Tier1ThrowableCallbackTest {

  private val source: String = """
    package tier1.throwablecallback

    class Referee {
      suspend fun verdict(judge: suspend (Int) -> Throwable): String = judge(1).message ?: "none"

      fun whistle(onFoul: (Throwable?) -> Unit) = onFoul(null)

      fun verdictLater(): suspend (Int) -> Throwable =
        { code -> IllegalStateException("code ${'$'}code") }
    }
  """.trimIndent()

  @Test
  fun `a Throwable binds as a suspend lambda result and as a nullable plain payload`() {
    val result: Tier1Result =
      Tier1Harness.run(source, libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore))
    assertTrue(result.kspErrors.isEmpty(), "kspErrors=${result.kspErrors}")
    assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
    val cs: String = result.generatedCSharp
    listOf("VerdictAsync(", "Whistle(", "VerdictLater(").forEach { member ->
      assertTrue(
        cs.lines().any { line -> line.trimStart().startsWith("public ") && member in line },
        "expected `$member` declared; warnings=${result.kspWarnings}",
      )
    }
    assertTrue(
      result.kspWarnings.none { warning -> "Referee." in warning },
      "a member that binds must not be reported; got: ${result.kspWarnings}",
    )
  }
}
