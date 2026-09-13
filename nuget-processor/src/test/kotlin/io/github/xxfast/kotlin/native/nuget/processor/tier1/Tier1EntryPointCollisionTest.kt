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

  /**
   * Two fine owners: the user's `dispose()` by its plan tag, the generated `Dispose` by its own
   * per-site owner tag with the `generated Dispose` role (ADR-117 amendment, Alternative 3). Before
   * the amendment the second owner rendered the class-level `"route-owned export"` range text.
   */
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
            message.contains("tier1.abicollision.dispose.Closer (generated Dispose)")
      },
      "expected a collision naming the method and the generated Dispose by role; " +
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

  /**
   * ADR-117 amendment cell 1 -- the Flow **property** legacy route (`FlowExports.kt:164`,
   * `{prefix}_get_{propName}_collect`). Oreo's and Mylo's radios each stream a purr count, so
   * `radio_get_purrs_collect` is minted twice and the error must name the two *properties*, not
   * the two classes.
   *
   * **Resolved 2026-09-13 (run, not inferred): candidate (a) -- the fixture, not the pipeline.**
   * The first draft of this cell omitted `libraries`, so `kotlinx-coroutines-core` sat on the
   * compile classpath but NOT on the KSP *resolution* classpath: `Flow` resolved to
   * `<ERROR TYPE: Flow>`, the property was dropped as SKIPPED_UNSUPPORTED_PROPERTY, and the only
   * Kotlin exports minted were `radio_create` / `radio_dispose` (spike: the exports file held four
   * FunSpecs and not one `_collect`). With the library on the resolution classpath the `_collect`
   * export is minted twice and the guard fires. Candidate (b) -- a Flow export invisible to both
   * compared universes -- is therefore not what happened: the C# half renders the Flow
   * `DllImport` and `csharpLegacy` scrapes it. Every Flow Tier 1 cell needs the `libraries` line
   * (`Tier1FlowCollectionElementTest` carries it too).
   */
  @Test
  fun `a Flow property in two packages names both properties`() {
    val result = Tier1Harness.run(
      mapOf(
        "A.kt" to """
          package tier1.abicollision.flowprop.oreo

          import kotlinx.coroutines.flow.Flow
          import kotlinx.coroutines.flow.flowOf

          class Radio {
            val purrs: Flow<Int> = flowOf(1)
          }
        """.trimIndent(),
        "B.kt" to """
          package tier1.abicollision.flowprop.mylo

          import kotlinx.coroutines.flow.Flow
          import kotlinx.coroutines.flow.flowOf

          class Radio {
            val purrs: Flow<Int> = flowOf(2)
          }
        """.trimIndent(),
      ),
      // `kotlinx-coroutines-core` on the KSP *resolution* classpath, not only the compile one:
      // without it `Flow` resolves to `<ERROR TYPE: Flow>`, the property is dropped as
      // SKIPPED_UNSUPPORTED_PROPERTY and no `_collect` export is minted to collide.
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(
      result.kspErrors.any { message ->
        message.contains(ForwardDiagnosticKind.ERROR_C_ENTRY_POINT_COLLISION.name) &&
            message.contains("radio_get_purrs_collect") &&
            message.contains("tier1.abicollision.flowprop.oreo.Radio.purrs") &&
            message.contains("tier1.abicollision.flowprop.mylo.Radio.purrs")
      },
      "expected a collision naming both Flow properties; kspErrors=${result.kspErrors}",
    )
    assertTrue(
      result.generatedFiles.keys.none { name -> name.endsWith("CNameExports.kt") },
      "a collision must fail the round before the Kotlin export file is written; " +
          "generatedFiles=${result.generatedFiles.keys}",
    )
  }

  /**
   * ADR-117 amendment cell 2 -- the Flow **method** legacy route exists and does export methods
   * (`FlowExports.kt:351`, `{prefix}_{cname}_collect`, `cname` = `toCName(methodName)` plus any
   * ADR-118 overload suffix). One method per class, so no suffix: `radio_stream_collect` twice.
   * The owner renders with parameter simple names, hence the empty `()`.
   *
   * **Resolved 2026-09-13**: same cause and same fix as the Flow-property cell above -- the
   * fixture needs `kotlinx-coroutines-core` on the KSP resolution classpath, not only on the
   * compile classpath, or the method return resolves to `<ERROR TYPE: Flow>` and no `_collect`
   * export exists to collide.
   */
  @Test
  fun `a Flow method in two packages names both methods`() {
    val result = Tier1Harness.run(
      mapOf(
        "A.kt" to """
          package tier1.abicollision.flowmethod.oreo

          import kotlinx.coroutines.flow.Flow
          import kotlinx.coroutines.flow.flowOf

          class Radio {
            fun stream(): Flow<Int> = flowOf(1)
          }
        """.trimIndent(),
        "B.kt" to """
          package tier1.abicollision.flowmethod.mylo

          import kotlinx.coroutines.flow.Flow
          import kotlinx.coroutines.flow.flowOf

          class Radio {
            fun stream(): Flow<Int> = flowOf(2)
          }
        """.trimIndent(),
      ),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(
      result.kspErrors.any { message ->
        message.contains(ForwardDiagnosticKind.ERROR_C_ENTRY_POINT_COLLISION.name) &&
            message.contains("radio_stream_collect") &&
            message.contains("tier1.abicollision.flowmethod.oreo.Radio.stream()") &&
            message.contains("tier1.abicollision.flowmethod.mylo.Radio.stream()")
      },
      "expected a collision naming both Flow methods; kspErrors=${result.kspErrors}",
    )
    assertTrue(
      result.generatedFiles.keys.none { name -> name.endsWith("CNameExports.kt") },
      "a collision must fail the round before the Kotlin export file is written; " +
          "generatedFiles=${result.generatedFiles.keys}",
    )
  }

  /**
   * ADR-117 amendment cell 3 -- the sealed route, both its class-owned discriminator
   * (`SealedClassExports.kt:43`, `{prefix}_get_type`) and an arm's generated data-class member
   * (`:139`, `{prefix}_{arm.lowercase()}_equals`). Two feeding states for two cats; the
   * discriminator owner carries the `sealed discriminator` role and the arm's `equals` carries
   * `data-class equals` on the **arm**, which is what tells the two generated members apart.
   */
  @Test
  fun `a sealed class in two packages names the discriminator and the arm`() {
    val result = Tier1Harness.run(
      mapOf(
        "A.kt" to """
          package tier1.abicollision.sealedstate.oreo

          sealed class LoadState {
            data class Ready(val bowls: Int) : LoadState()
          }
        """.trimIndent(),
        "B.kt" to """
          package tier1.abicollision.sealedstate.mylo

          sealed class LoadState {
            data class Ready(val bowls: Int) : LoadState()
          }
        """.trimIndent(),
      ),
    )

    assertTrue(
      result.kspErrors.any { message ->
        message.contains(ForwardDiagnosticKind.ERROR_C_ENTRY_POINT_COLLISION.name) &&
            message.contains("loadstate_get_type") &&
            message.contains(
              "tier1.abicollision.sealedstate.oreo.LoadState (sealed discriminator)",
            ) &&
            message.contains(
              "tier1.abicollision.sealedstate.mylo.LoadState (sealed discriminator)",
            )
      },
      "expected a collision naming both sealed discriminators by role; " +
          "kspErrors=${result.kspErrors}",
    )
    assertTrue(
      result.kspErrors.any { message ->
        message.contains(ForwardDiagnosticKind.ERROR_C_ENTRY_POINT_COLLISION.name) &&
            message.contains("loadstate_ready_equals") &&
            message.contains(
              "tier1.abicollision.sealedstate.oreo.LoadState.Ready (data-class equals)",
            ) &&
            message.contains(
              "tier1.abicollision.sealedstate.mylo.LoadState.Ready (data-class equals)",
            )
      },
      "expected a collision naming both arms' generated equals by role; " +
          "kspErrors=${result.kspErrors}",
    )
    assertTrue(
      result.generatedFiles.keys.none { name -> name.endsWith("CNameExports.kt") },
      "a collision must fail the round before the Kotlin export file is written; " +
          "generatedFiles=${result.generatedFiles.keys}",
    )
  }

  /**
   * ADR-117 amendment cell 4 -- the generic-class route: the per-variant `_create_<suffix>` export
   * (`GenericClassExports.kt:117`, emitted from the private `addGenericCreateExport`, one of the
   * three sites that has to thread its owner) and the generic property getter (`:89`). A treat
   * `Box<T>` in each cat's package collides on `box_create_string`; the role must say *which*
   * variant, because twelve primitive variants share the class.
   */
  @Test
  fun `a generic class in two packages names the create variant and the property`() {
    val result = Tier1Harness.run(
      mapOf(
        "A.kt" to """
          package tier1.abicollision.generic.oreo

          class Box<T>(val value: T)
        """.trimIndent(),
        "B.kt" to """
          package tier1.abicollision.generic.mylo

          class Box<T>(val value: T)
        """.trimIndent(),
      ),
    )

    assertTrue(
      result.kspErrors.any { message ->
        message.contains(ForwardDiagnosticKind.ERROR_C_ENTRY_POINT_COLLISION.name) &&
            message.contains("box_create_string") &&
            message.contains(
              "tier1.abicollision.generic.oreo.Box (generic create variant: string)",
            ) &&
            message.contains(
              "tier1.abicollision.generic.mylo.Box (generic create variant: string)",
            )
      },
      "expected a collision naming both generic create variants by role; " +
          "kspErrors=${result.kspErrors}",
    )
    assertTrue(
      result.kspErrors.any { message ->
        message.contains(ForwardDiagnosticKind.ERROR_C_ENTRY_POINT_COLLISION.name) &&
            message.contains("box_get_value") &&
            message.contains("tier1.abicollision.generic.oreo.Box.value") &&
            message.contains("tier1.abicollision.generic.mylo.Box.value")
      },
      "expected a collision naming both generic property getters; kspErrors=${result.kspErrors}",
    )
    assertTrue(
      result.generatedFiles.keys.none { name -> name.endsWith("CNameExports.kt") },
      "a collision must fail the round before the Kotlin export file is written; " +
          "generatedFiles=${result.generatedFiles.keys}",
    )
  }

  /**
   * ADR-117 amendment cell 5 -- the generic top-level **function** route
   * (`GenericFunctionExports.kt`, `{funcName}_<suffix>` per primitive variant). Each cat's package
   * wraps a treat; `wrap_string` is minted twice. `render()` prints a function's parameter *simple*
   * type names, so a type-parameter position renders `wrap(T)`; the variant lives in the role, not
   * in the rendered signature.
   */
  @Test
  fun `a generic top-level function in two packages names both functions and the variant`() {
    val result = Tier1Harness.run(
      mapOf(
        "A.kt" to """
          package tier1.abicollision.genericfun.oreo

          fun <T> wrap(treat: T): T = treat
        """.trimIndent(),
        "B.kt" to """
          package tier1.abicollision.genericfun.mylo

          fun <T> wrap(treat: T): T = treat
        """.trimIndent(),
      ),
    )

    assertTrue(
      result.kspErrors.any { message ->
        message.contains(ForwardDiagnosticKind.ERROR_C_ENTRY_POINT_COLLISION.name) &&
            message.contains("wrap_string") &&
            message.contains(
              "tier1.abicollision.genericfun.oreo.wrap(T) (generic variant: string)",
            ) &&
            message.contains(
              "tier1.abicollision.genericfun.mylo.wrap(T) (generic variant: string)",
            )
      },
      "expected a collision naming both generic functions with the variant role; " +
          "kspErrors=${result.kspErrors}",
    )
    assertTrue(
      result.generatedFiles.keys.none { name -> name.endsWith("CNameExports.kt") },
      "a collision must fail the round before the Kotlin export file is written; " +
          "generatedFiles=${result.generatedFiles.keys}",
    )
  }
}
