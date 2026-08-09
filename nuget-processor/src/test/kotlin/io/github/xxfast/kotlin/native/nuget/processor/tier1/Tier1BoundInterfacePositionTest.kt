package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ADR-088's v1 boundary, driven through the real processor: a bound C# interface is marshalled at
 * ordinary, non-nullable parameter and return positions, and NOTHING else. Each deferred position
 * gets its own named diagnostic (`SKIPPED_BOUND_TYPE_POSITION`), never the generic
 * `SKIPPED_UNSUPPORTED_TYPE` this feature exists to remove, and never a partial emission.
 *
 * The fixtures stand in for the reverse pipeline: a public `interface IFeedable` in a package the
 * manifest names is exactly what `nugetGenerateBindings` emits. Only SKIPPED cases are pinned
 * here, deliberately: an admitted position emits Kotlin that calls the reverse pipeline's own
 * `nugetIFeedableValue`/`nugetIFeedableHandleOut`, which do not exist in a Tier 1 fixture, so the
 * admitted half is verified end to end by the `Farm_*` integration tests instead.
 */
class Tier1BoundInterfacePositionTest {

  private fun manifestOption(implementable: Boolean = true): Map<String, String> {
    val file: File = Files.createTempFile("nuget-bound-types-", ".json").toFile()
    file.deleteOnExit()
    file.writeText(
      """
      {
        "interfaces": [
          { "kotlinName": "bound.menagerie.IFeedable", "csharpName": "Test.Menagerie.IFeedable", "implementable": $implementable }
        ]
      }
      """.trimIndent()
    )
    return mapOf("nuget.boundTypesManifest" to file.absolutePath)
  }

  private val boundStub: String =
    """
    package bound.menagerie

    interface IFeedable {
      fun describe(): String
    }
    """.trimIndent()

  @Test
  fun `a nullable bound interface parameter fires SKIPPED_BOUND_TYPE_POSITION and is omitted`() {
    val result = Tier1Harness.run(
      sources = mapOf(
        "Bound.kt" to boundStub,
        "Fixture.kt" to """
          package tier1.boundnullable

          import bound.menagerie.IFeedable

          class Farm {
            fun adopt(feedable: IFeedable?): Int = if (feedable == null) 0 else 1
          }
        """.trimIndent(),
      ),
      processorOptions = manifestOption(),
    )

    assertTrue(
      result.compiledClean,
      "expected no broken source for adopt; got: ${result.compileErrors}",
    )
    assertTrue(
      "export_farm_adopt" !in result.generated,
      "expected the nullable bound-interface parameter to be entirely absent; " +
          "generated=${result.generated}",
    )
    assertTrue(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_BOUND_TYPE_POSITION.name)
      },
      "expected SKIPPED_BOUND_TYPE_POSITION for `IFeedable?`; kspWarnings=${result.kspWarnings}",
    )
  }

  @Test
  fun `a bound interface property fires SKIPPED_BOUND_TYPE_POSITION and is omitted`() {
    val result = Tier1Harness.run(
      sources = mapOf(
        "Bound.kt" to boundStub,
        "Fixture.kt" to """
          package tier1.boundproperty

          import bound.menagerie.IFeedable

          // No constructor parameter: a bound-interface PARAMETER is admitted, and its emitted
          // lowering would call the reverse pipeline's nugetIFeedableValue, absent here.
          class Farm {
            val resident: IFeedable get() = error("no resident")
          }
        """.trimIndent(),
      ),
      processorOptions = manifestOption(),
    )

    assertTrue(
      result.compiledClean,
      "expected no broken source for the property; got: ${result.compileErrors}",
    )
    assertTrue(
      "farm_get_resident" !in result.generated,
      "expected the bound-interface property to be entirely absent; generated=${result.generated}",
    )
    assertTrue(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_BOUND_TYPE_POSITION.name)
      },
      "expected SKIPPED_BOUND_TYPE_POSITION for the property; kspWarnings=${result.kspWarnings}",
    )
  }

  /**
   * The manifest's `implementable = false` case (`ITagged` before ADR-086/089 gave it a mint): a
   * plain Kotlin implementation returned here has nothing to become on the C# side, so the RETURN
   * is skipped with its own kind. This is not `SKIPPED_BOUND_TYPE_POSITION`: the position is fine,
   * the interface is the problem.
   */
  @Test
  fun `a return of a non-implementable bound interface fires SKIPPED_UNIMPLEMENTABLE_BOUND_INTERFACE`() {
    val result = Tier1Harness.run(
      sources = mapOf(
        "Bound.kt" to boundStub,
        "Fixture.kt" to """
          package tier1.boundunimplementable

          import bound.menagerie.IFeedable

          class Farm {
            fun resident(): IFeedable = error("no resident")
          }
        """.trimIndent(),
      ),
      processorOptions = manifestOption(implementable = false),
    )

    assertTrue(
      result.compiledClean,
      "expected no broken source for resident(); got: ${result.compileErrors}",
    )
    assertTrue(
      "export_farm_resident" !in result.generated,
      "expected the return to be entirely absent; generated=${result.generated}",
    )
    assertTrue(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_UNIMPLEMENTABLE_BOUND_INTERFACE.name)
      },
      "expected SKIPPED_UNIMPLEMENTABLE_BOUND_INTERFACE; kspWarnings=${result.kspWarnings}",
    )
  }

  /**
   * The guard against re-projection: with the stub public, the forward root buckets would
   * otherwise admit it and mint a duplicate `IIFeedable` + backing wrapper, which could never be
   * passed to the bound reverse API.
   */
  @Test
  fun `a manifest-listed interface is never re-projected as a duplicate I-prefixed type`() {
    val result = Tier1Harness.run(
      sources = mapOf(
        "Bound.kt" to boundStub,
        "Fixture.kt" to """
          package tier1.boundnoduplicate

          class Farm {
            fun size(): Int = 1
          }
        """.trimIndent(),
      ),
      processorOptions = manifestOption(),
    )

    val interop: String = result.generatedFiles.entries
      .single { entry -> entry.key.endsWith("Interop.cs") }
      .value
    assertTrue("IIFeedable" !in interop, "a duplicate IIFeedable was projected:\n$interop")
  }
}
