package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #52: a sealed type as a collection *component* of a property used to crash the processor
 * (`No C# property type for SpecializedProtocol(name=sealed helper ...)`) instead of skipping the
 * property the way the bare `Shape?` spelling already does. Every collection kind and the nullable
 * collection reference take the same named `SKIPPED_UNSUPPORTED_PROPERTY` route, naming the
 * offending component, and the rest of the class still binds. A sealed *interface* crashed
 * identically (the classifier mints the same `sealed helper` protocol for both), so it is covered
 * by the same fixture.
 */
class Tier1SealedCollectionPropertyTest {

  private val source: String = """
    package tier1.sealedcollectionproperty

    sealed class Shape {
      data class Circle(val radius: Double) : Shape()
    }

    sealed interface Filter {
      class ById(val id: String) : Filter
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

  private val sealedHelper: String = "sealed helper tier1.sealedcollectionproperty.Shape"

  @Test
  fun `sealed collection properties skip named instead of crashing`() {
    val result = Tier1Harness.run(source)

    assertTrue(
      result.compiledClean,
      "expected the sealed collection properties to skip cleanly; got: ${result.compileErrors}",
    )
    val expected: Map<String, String> = mapOf(
      "shapes" to sealedHelper,
      "optional" to sealedHelper,
      "unique" to sealedHelper,
      "byName" to sealedHelper,
      "filters" to "sealed helper tier1.sealedcollectionproperty.Filter",
    )
    for ((property, helper) in expected) {
      assertFalse(
        result.generated.contains("export_album_get_$property"),
        "expected Album.$property to be absent from the generated exports; " +
            "generated=${result.generated}",
      )
      assertTrue(
        result.kspWarnings.any {
          it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY.name) &&
              it.contains("Album.$property") && it.contains(helper)
        },
        "expected a SKIPPED_UNSUPPORTED_PROPERTY diagnostic naming Album.$property and " +
            "$helper; kspWarnings=${result.kspWarnings}",
      )
    }
  }

  @Test
  fun `the diagnostic names the component slot that failed`() {
    val result = Tier1Harness.run(source)

    assertTrue(
      result.kspWarnings.any {
        it.contains("Album.shapes") && it.contains("Collection (element type $sealedHelper)")
      },
      "expected the List diagnostic to name its element; kspWarnings=${result.kspWarnings}",
    )
    assertTrue(
      result.kspWarnings.any {
        it.contains("Album.optional") && it.contains("Collection? (element type $sealedHelper)")
      },
      "expected the nullable List diagnostic to keep the `?`; kspWarnings=${result.kspWarnings}",
    )
    assertTrue(
      result.kspWarnings.any {
        it.contains("Album.byName") && it.contains("Collection (value type $sealedHelper)")
      },
      "expected the Map diagnostic to name its value slot; kspWarnings=${result.kspWarnings}",
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
