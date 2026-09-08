package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.ForwardAbiGuard
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
 * The assertions mostly do not name *which* of the three duplicate guards fires (`duplicate C#
 * import`, `duplicate Kotlin export`, `conflicting C# legacy imports`): which one a shape hits
 * depends on its universe and on signature equality (ADR-117 Context), and all three render the
 * same owner-naming body. The suspend cell below is the exception -- ADR-118 asked for the guard
 * by name there, and the answer turned out not to be the one the ADR predicted.
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

  /**
   * The issue's own shape, on the live suspend legacy route. ADR-118 numbers a suspend **overload**
   * pair, so `play(Player)`/`play(Track)` no longer collide; the collision that still reaches this
   * route across two *different* owners is a class method against a top-level function whose Kotlin
   * name is already the mangled symbol (`toCName` is the identity apart from C-reserved names).
   *
   * ADR-118 predicted (Inferred, not run) that this shape would reach the third of ADR-117's three
   * duplicate guards, `conflicting C# legacy imports`, because the two imports differ in signature.
   * **Verified by execution here: it does not.** `DUPLICATE_CSHARP_IMPORT` fires first, the same
   * guard the pre-ADR-118 overload cell tripped, so ADR-117's recorded residual (no Tier 1 cell
   * reaches `CONFLICTING_LEGACY_IMPORTS` through a real KSP round) stays open. Per ADR-118's own
   * gate instruction 2 the assertion follows the observed guard: the point of the cell is the
   * reach, not the prediction. Both owners are still named off their own `FunSpec` tags, one per
   * builder, which is what this cell exists to pin.
   */
  @Test
  fun `a suspend method and a top-level suspend function name both owners`() {
    val result = Tier1Harness.run(
      """
      package tier1.abicollision.suspend

      class Player(val name: String)

      class Radio {
        suspend fun play(p: Player): Int = 1
      }

      suspend fun radio_play(): Int = 2
      """.trimIndent(),
    )

    assertTrue(
      result.kspErrors.any { message ->
        message.contains(ForwardDiagnosticKind.ERROR_C_ENTRY_POINT_COLLISION.name) &&
            message.contains("radio_play_async") &&
            message.contains("tier1.abicollision.suspend.Radio.play(Player)") &&
            message.contains("tier1.abicollision.suspend.radio_play()")
      },
      "expected a collision naming the method and the top-level function; " +
          "kspErrors=${result.kspErrors}",
    )
    assertTrue(
      result.kspErrors.any { message ->
        message.contains(ForwardAbiGuard.DUPLICATE_CSHARP_IMPORT.phrase)
      },
      "expected the duplicate-C#-import guard by name (the one this shape actually reaches); " +
          "kspErrors=${result.kspErrors}",
    )
    assertTrue(
      result.generatedFiles.keys.none { name -> name.endsWith("CNameExports.kt") },
      "a collision must fail the round before the Kotlin export file is written; " +
          "generatedFiles=${result.generatedFiles.keys}",
    )
  }
}
