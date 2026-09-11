package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #52, then ADR-105 (issue #54) scope (c). A sealed type as a collection *component* of a
 * property first crashed the processor (`No C# property type for SpecializedProtocol(name=sealed
 * helper ...)`), then skipped named alongside the bare `Shape?` spelling. Under ADR-105 it
 * **binds**: the property planner rewrites the classifier's `sealed helper` protocol to the
 * `ObjectHandle(viaDiscriminator = true)` it carries, and every component is read through
 * `NugetMarshal.FromHandle<T>`, which dispatches to the ADR-009 discriminator via the issue-#40
 * `viaFromHandle` factory entry.
 *
 * An ADR-112-INELIGIBLE sealed **interface** stays skipped, and this fixture keeps
 * `filters: List<Filter>` to pin that: only a sealed type `rootSealedClasses` admits reaches the
 * ADR-009 renderer and gets a `FromHandle` to reconstruct through, and `Filter`'s subclass carries
 * a second superclass no nested C# subclass can express. The classifier therefore mints no
 * `sealedHandle` for it, and the property takes the same named `SKIPPED_UNSUPPORTED_PROPERTY`
 * route it always did, naming the offending component.
 */
class Tier1SealedCollectionPropertyTest {

  private val source: String = """
    package tier1.sealedcollectionproperty

    sealed class Shape {
      data class Circle(val radius: Double) : Shape()
    }

    open class Criterion

    // ADR-112: INELIGIBLE on purpose (`ById` has a second superclass), so the sealed route never
    // declares `Filter` and the component keeps skipping. An eligible sealed interface binds as a
    // collection component like a sealed class does (Tier1SealedInterfaceTest).
    sealed interface Filter {
      class ById(val id: String) : Criterion(), Filter
    }

    data class Album(
      val shapes: List<Shape>,
      val optional: List<Shape>?,
      val unique: Set<Shape>,
      val byName: Map<String, Shape>,
      val filters: List<Filter>,
      val title: String,
    )

    fun album(): Album = Album(emptyList(), null, emptySet(), emptyMap(), emptyList(), "x")
  """.trimIndent()

  @Test
  fun `sealed collection properties bind instead of skipping`() {
    val result = Tier1Harness.run(source)

    assertTrue(
      result.compiledClean,
      "expected the sealed collection properties to compile; got: ${result.compileErrors}",
    )
    listOf("shapes", "optional", "unique", "byName").forEach { property ->
      assertTrue(
        result.generated.contains("export_album_get_$property"),
        "expected Album.$property to bind; generated=${result.generated}",
      )
      assertFalse(
        result.kspWarnings.any {
          it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY.name) &&
              it.contains("Album.$property")
        },
        "expected no SKIPPED_UNSUPPORTED_PROPERTY for Album.$property; " +
            "kspWarnings=${result.kspWarnings}",
      )
    }
  }

  @Test
  fun `every sealed component is materialised through the discriminator`() {
    val result = Tier1Harness.run(source)

    assertTrue(
      result.generatedCSharp.contains(
        "(nativeResult, static h1 => NugetMarshal.FromHandle<global::Interop.Shape>(h1))",
      ),
      "expected the List element to read through FromHandle<Shape>; " +
          "generatedCSharp=${result.generatedCSharp.lines().filter { it.contains("Shape") }}",
    )
    assertTrue(
      result.generatedCSharp.contains("public IReadOnlyList<global::Interop.Shape> Shapes"),
      "expected the sealed base as the element spelling; " +
          "generatedCSharp=${result.generatedCSharp.lines().filter { it.contains("Shapes") }}",
    )
    assertTrue(
      result.generatedCSharp.contains("global::Interop.Shape> ByName"),
      "expected the Map value slot to spell the sealed base; " +
          "generatedCSharp=${result.generatedCSharp.lines().filter { it.contains("ByName") }}",
    )
  }

  @Test
  fun `an ineligible sealed interface component still skips named`() {
    val result = Tier1Harness.run(source)

    assertFalse(
      result.generated.contains("export_album_get_filters"),
      "expected Album.filters to be absent from the generated exports; " +
          "generated=${result.generated}",
    )
    assertTrue(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY.name) &&
            it.contains("Album.filters") &&
            // ADR-064's 2026-09-11 amendment: the element's own SEALED_POSITION reason now owns
            // the sentence, so the record names the sealed base and the missing discriminator
            // rather than the outer collection shape.
            it.contains(
              "its sealed type `tier1.sealedcollectionproperty.Filter` has no generated C# " +
                  "discriminator",
            )
      },
      "expected the sealed-interface List diagnostic to name its element; " +
          "kspWarnings=${result.kspWarnings}",
    )
  }

  @Test
  fun `the rest of the class still binds`() {
    val result = Tier1Harness.run(source)

    assertTrue(
      result.generated.contains("export_album_get_title"),
      "expected Album.title to bind; generated=${result.generated}",
    )
  }
}
