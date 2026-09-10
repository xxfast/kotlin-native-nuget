package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-105 amendment: an extension function whose RECEIVER is a sealed base binds. `extensionEntry`
 * passes its receiver through `sealedAsHandle()` before planning, so the receiver rides the same
 * ordinary `ObjectHandle` path scope (d) already gave every declared parameter: Kotlin reads the
 * base handle back with `asStableRef<Shape>().get()` (the `_get_type` idiom) and C# renders
 * `this Shape receiver` in `ShapeExtensions`, passing `receiver._handle`.
 *
 * The control is an ADR-112-INELIGIBLE sealed interface receiver: the classifier mints no
 * `sealedHandle` for it, so it still skips as `SEALED_POSITION` and the rewrite is provably not a
 * blanket "every sealed receiver is a handle now".
 */
class Tier1SealedReceiverExtensionTest {

  private val source: String = """
    package tier1.sealedreceiver

    sealed class Shape {
      data object Empty : Shape()
      data class Circle(val radius: Double) : Shape()
    }

    open class Haunting

    // ADR-112: INELIGIBLE on purpose (`Nobody` has a second superclass), so `Ghost` has no ADR-009
    // discriminator and `Ghost.haunt` still skips.
    sealed interface Ghost {
      class Nobody : Haunting(), Ghost
    }

    fun Shape.describe(): String = if (this is Shape.Circle) "circle" else "empty"

    fun Shape.covers(other: Shape): Boolean = this is Shape.Circle || other is Shape.Empty

    fun Ghost.haunt(): String = toString()
  """.trimIndent()

  @Test
  fun `a sealed receiver dereferences its base handle`() {
    val result = Tier1Harness.run(source)

    assertTrue(
      result.compiledClean,
      "expected the fixture to compile; got: ${result.compileErrors}",
    )
    assertTrue(
      result.generated.contains(
        "receiver.asStableRef<tier1.sealedreceiver.Shape>().get().describe()",
      ),
      "expected the export to read the receiver back through its StableRef; " +
          "generated=${result.generated}",
    )
  }

  @Test
  fun `a sealed receiver renders as a C# extension on the base`() {
    val result = Tier1Harness.run(source)

    assertTrue(
      result.generatedCSharp.contains(
        "public static string Describe(this global::Interop.Shape receiver)",
      ),
      "expected a C# extension method on the sealed base; generatedCSharp=" +
          "${result.generatedCSharp.lines().filter { it.contains("Describe") }}",
    )
    assertTrue(
      result.generatedCSharp.contains("Native_Describe(receiver._handle, out IntPtr error)"),
      "expected the C# call to pass the receiver handle; generatedCSharp=" +
          "${result.generatedCSharp.lines().filter { it.contains("Native_Describe") }}",
    )
  }

  /** Receiver rewrite and scope (d) on one export: both handles cross the same call. */
  @Test
  fun `a sealed receiver and a sealed parameter bind on one export`() {
    val result = Tier1Harness.run(source)

    assertTrue(
      result.generated.contains("""@CName("shape_covers")"""),
      "expected the two-handle extension to bind; generated=${result.generated}",
    )
    assertTrue(
      result.generatedCSharp.contains(
        "this global::Interop.Shape receiver, global::Interop.Shape other",
      ),
      "expected both the receiver and the parameter to be the sealed base; generatedCSharp=" +
          "${result.generatedCSharp.lines().filter { it.contains("Covers") }}",
    )
  }

  @Test
  fun `an ineligible sealed interface receiver still skips as a sealed position`() {
    val result = Tier1Harness.run(source)

    assertFalse(
      result.generated.contains("export_ghost_haunt"),
      "expected the sealed-interface receiver to stay skipped; generated=${result.generated}",
    )
    assertTrue(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_SEALED_POSITION.name) && it.contains("haunt")
      },
      "expected the sealed-position skip to name haunt; kspWarnings=${result.kspWarnings}",
    )
  }
}
