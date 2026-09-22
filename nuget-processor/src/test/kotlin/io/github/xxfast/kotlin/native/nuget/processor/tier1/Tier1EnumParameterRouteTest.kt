package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ADR-162 (ROADMAP line 236): the last fatal forward diagnostic that lived outside
 * `ForwardDiagnosticKind`, now inside it.
 *
 * `enumParamsUnsupported` in `CirFunctionTranslator` was a bare `logger.error` with no
 * `[nuget:KIND]` tag, so it was the one build failure from this processor a consumer could not
 * grep for by kind. Behaviour is unchanged on purpose (same node, same severity, the translator
 * still returns no members); the cell exists to pin that the label is there and that the round
 * still fails before the Kotlin export file is written.
 *
 * Deliberately in-process only, never in `test-library/`: the correct outcome is a failed build,
 * which would break `packNuget`.
 */
class Tier1EnumParameterRouteTest {

  @Test
  fun `an enum parameter on a lambda-returning top-level function fails by kind`() {
    val result = Tier1Harness.run(
      """
      package tier1.enumparam

      enum class Mood { Purr, Hiss }

      fun reaction(mood: Mood): () -> Int = { mood.ordinal }
      """.trimIndent(),
    )

    assertTrue(
      result.kspErrors.any { message ->
        message.contains(ForwardDiagnosticKind.ERROR_UNSUPPORTED_ENUM_PARAMETER_ROUTE.name) &&
            message.contains("tier1.enumparam.reaction") &&
            message.contains("Fixture.kt:")
      },
      "expected the enum-parameter route failure by kind, located at the function; " +
          "kspErrors=${result.kspErrors}",
    )
    assertTrue(
      result.generatedFiles.keys.none { name -> name.endsWith("CNameExports.kt") },
      "a fatal diagnostic must fail the round before the Kotlin export file is written; " +
          "generatedFiles=${result.generatedFiles.keys}",
    )
  }
}
