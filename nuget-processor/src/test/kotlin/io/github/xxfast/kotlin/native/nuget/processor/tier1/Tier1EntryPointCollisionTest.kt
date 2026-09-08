package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ADR-117 / issue #106: when two Kotlin declarations derive the same C entry point, the failure
 * must name **both owning declarations**, not only the mangled symbol, and must fail the KSP round
 * before `CNameExports.kt` exists.
 *
 * Deliberately in-process only (never in `test-library/`): each cell's correct outcome is a failed
 * build, which would break `packNuget`.
 *
 * The assertions never name *which* of the three duplicate guards fires (`duplicate C# import`,
 * `duplicate Kotlin export`, `conflicting C# legacy imports`): which one a shape hits depends on
 * its universe and on signature equality (ADR-117 Context), and all three render the same
 * owner-naming body.
 */
class Tier1EntryPointCollisionTest {

  /** The fine (planned) path: a constructor plan carries its `node`, so each owner is a ctor. */
  @Test
  fun `two classes with one simple name in different packages name both constructors`() {
    val result = Tier1Harness.run(
      mapOf(
        "A.kt" to "package tier1.abicollision.a\n\nclass Kitten(val name: String)",
        "B.kt" to "package tier1.abicollision.b\n\nclass Kitten(val name: String)",
      ),
    )

    assertTrue(
      result.kspErrors.any { message ->
        message.contains(ForwardDiagnosticKind.ERROR_C_ENTRY_POINT_COLLISION.name) &&
            message.contains("kitten_create") &&
            message.contains("tier1.abicollision.a.Kitten(String)") &&
            message.contains("A.kt:") &&
            message.contains("B.kt:") &&
            message.contains("tier1.abicollision.b.Kitten(String)")
      },
      "expected a collision naming both constructors; kspErrors=${result.kspErrors}",
    )
    assertTrue(
      result.generatedFiles.keys.none { name -> name.endsWith("CNameExports.kt") },
      "a collision must fail the round before the Kotlin export file is written; " +
          "generatedFiles=${result.generatedFiles.keys}",
    )
  }

  /** Fine + coarse together: the user's method by plan tag, the generated `Dispose` by range. */
  @Test
  fun `fun dispose names the method and the generated Dispose`() {
    val result = Tier1Harness.run(
      """
      package tier1.abicollision.dispose

      class Closer {
        fun dispose() {}
      }
      """.trimIndent(),
    )

    assertTrue(
      result.kspErrors.any { message ->
        message.contains(ForwardDiagnosticKind.ERROR_C_ENTRY_POINT_COLLISION.name) &&
            message.contains("closer_dispose") &&
            message.contains("tier1.abicollision.dispose.Closer.dispose()") &&
            message.contains("route-owned export")
      },
      "expected a collision naming the method and the route-owned generated Dispose; " +
          "kspErrors=${result.kspErrors}",
    )
    assertTrue(
      result.generatedFiles.keys.none { name -> name.endsWith("CNameExports.kt") },
      "a collision must fail the round before the Kotlin export file is written; " +
          "generatedFiles=${result.generatedFiles.keys}",
    )
  }

  /** The issue's own shape, on the live suspend legacy route (ROADMAP line 54's spike). */
  @Test
  fun `two suspend overloads name both methods by parameter type`() {
    val result = Tier1Harness.run(
      """
      package tier1.abicollision.suspend

      class Player(val name: String)

      class Track(val title: String)

      class Radio {
        suspend fun play(p: Player): Int = 1
        suspend fun play(t: Track): Int = 2
      }
      """.trimIndent(),
    )

    assertTrue(
      result.kspErrors.any { message ->
        message.contains(ForwardDiagnosticKind.ERROR_C_ENTRY_POINT_COLLISION.name) &&
            message.contains("radio_play_async") &&
            message.contains("tier1.abicollision.suspend.Radio.play(Player)") &&
            message.contains("tier1.abicollision.suspend.Radio.play(Track)")
      },
      "expected a collision naming both suspend overloads; kspErrors=${result.kspErrors}",
    )
    assertTrue(
      result.generatedFiles.keys.none { name -> name.endsWith("CNameExports.kt") },
      "a collision must fail the round before the Kotlin export file is written; " +
          "generatedFiles=${result.generatedFiles.keys}",
    )
  }
}
