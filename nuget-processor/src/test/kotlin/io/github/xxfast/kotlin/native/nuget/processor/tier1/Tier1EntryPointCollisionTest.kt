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
 *
 * ADR-163 removed the six CROSS-PACKAGE cells this file used to carry (two classes, a Flow property,
 * a Flow method, a sealed class, a generic class, a generic top-level function). Those shapes bind
 * now, and their positive counterparts live in `Tier1ExportSymbolSchemeTest`. What is left here is
 * the collision class package qualification does NOT touch: two declarations of one owner in one
 * package whose symbols meet (`fun dispose()` against the generated `Dispose`, a class's suspend
 * method against a top-level suspend function already spelled like the mangled symbol). ADR-117's
 * diagnostic stays the backstop for those and for a Kotlin `_` that reads as ADR-163's separator.
 */
class Tier1EntryPointCollisionTest {


  /**
   * ADR-162 (ROADMAP line 87): `fun dispose()` is a **C# signature** collision, not an ABI one.
   *
   * It used to reach this route: the generated `Dispose()` is renderer-owned, so it is not a
   * `CirMethod` in the list ADR-034's guard groups, the authored `dispose()` sailed past that
   * guard, and the pair surfaced two phases later as two owners of the `closer_dispose` entry
   * point. The message was true but read as an ABI accident; the defect is CS0111, one type with
   * two members of one signature, which is exactly what `ERROR_CSHARP_SIGNATURE_COLLISION` says.
   * The reserved renderer-owned signature now catches it during translate, which is *before* the
   * fatal-diagnostic gate, so the ABI contract never runs and this route is no longer reached at
   * all.
   */
  @Test
  fun `fun dispose collides with the generated Dispose as a C# signature collision`() {
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
        // `Closer.Dispose`, not the Kotlin `dispose`: ADR-034's guard names the generated C#
        // container and member, which is the pair the C# compiler would reject.
        message.contains(ForwardDiagnosticKind.ERROR_CSHARP_SIGNATURE_COLLISION.name) &&
            message.contains("Closer.Dispose") &&
            message.contains("IDisposable") &&
            message.contains("Fixture.kt:")
      },
      "expected a C# signature collision against the generated Dispose, located; " +
          "kspErrors=${result.kspErrors}",
    )
    assertTrue(
      result.kspErrors.none { message ->
        message.contains(ForwardDiagnosticKind.ERROR_C_ENTRY_POINT_COLLISION.name)
      },
      "the generic entry-point collision must no longer be what the author reads; " +
          "kspErrors=${result.kspErrors}",
    )
    assertTrue(
      result.generatedFiles.keys.none { name -> name.endsWith("CNameExports.kt") },
      "a collision must fail the round before the Kotlin export file is written; " +
          "generatedFiles=${result.generatedFiles.keys}",
    )
  }

  /**
   * ADR-162: the reserved renderer-owned signature is passed at the sealed **base** and sealed
   * **arm** sites too, which was inferred from the renderers (`CirSealedRenderer` gives the base
   * `: IDisposable, INugetHandle` and its own `Dispose()`, and an arm inherits it) and is verified
   * here. Both offending declarations are reported in ONE round, which is the containment claim
   * itself: before this item a build named one of them and stopped.
   */
  @Test
  fun `fun dispose on a sealed base and on an arm both collide with the generated Dispose`() {
    val result = Tier1Harness.run(
      """
      package tier1.abicollision.sealeddispose

      sealed class Feeding {
        fun dispose() {}

        data class Ready(val bowls: Int) : Feeding() {
          fun dispose() {}
        }
      }
      """.trimIndent(),
    )

    assertTrue(
      result.kspErrors.any { message ->
        message.contains(ForwardDiagnosticKind.ERROR_CSHARP_SIGNATURE_COLLISION.name) &&
            message.contains("Feeding.Dispose") &&
            !message.contains("Feeding.Ready.Dispose")
      },
      "expected the sealed base's own dispose to collide; kspErrors=${result.kspErrors}",
    )
    assertTrue(
      result.kspErrors.any { message ->
        message.contains(ForwardDiagnosticKind.ERROR_CSHARP_SIGNATURE_COLLISION.name) &&
            message.contains("Feeding.Ready.Dispose")
      },
      "expected the arm's dispose to collide with the inherited Dispose; " +
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
