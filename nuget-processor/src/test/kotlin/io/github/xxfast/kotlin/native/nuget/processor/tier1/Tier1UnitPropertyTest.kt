package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A `Unit` property carries no value and C# has no `void` property, so it reaches no owner. It
 * used to plan a `VOID` getter and render `public void Reading { get; }` (CS1547) on a class and on
 * the Kotlin-backed wrapper of an interface; a C# implementation of that interface was already
 * refused for the same reason.
 */
class Tier1UnitPropertyTest {

  @Test
  fun `a Unit property is a named skip on a class and an interface`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.unitproperty

      class Gauge { val reading: Unit get() = Unit }

      interface Meter { val reading: Unit }

      fun meter(): Meter = object : Meter { override val reading: Unit get() = Unit }
      """.trimIndent(),
      processorOptions = mapOf("nuget.namespace" to "Tier1"),
    )

    assertTrue(result.kspSucceeded, "ksp: ${result.kspErrors}")
    assertTrue(result.compiledClean, "generated Kotlin: ${result.compileErrors}")
    val warnings: String = result.kspWarnings.joinToString("\n")
    val skipped: String = "[nuget:SKIPPED_UNSUPPORTED_PROPERTY] Skipping tier1.unitproperty"
    assertContains(warnings, "$skipped.Gauge.reading")
    assertContains(warnings, "$skipped.Meter.reading")
    assertFalse(result.generatedCSharp.contains("void Reading"), result.generatedCSharp)
    assertFalse(result.generated.contains("_get_reading"), result.generated)
  }
}
