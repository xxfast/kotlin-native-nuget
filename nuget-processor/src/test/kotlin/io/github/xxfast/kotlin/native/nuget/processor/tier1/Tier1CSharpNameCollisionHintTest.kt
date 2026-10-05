package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #464: `ERROR_CSHARP_NAME_COLLISION` offers `@CSharpName` (ADR-179) as the alternative to a
 * Kotlin rename, and when the declaration that would carry it was read from a dependency
 * ([Tier1DependencyLibrary], `containingFile == null`) it says that module needs the
 * annotations-only plugin, since the main plugin adds `nuget-annotations` only to the module that
 * applies it.
 */
class Tier1CSharpNameCollisionHintTest {

  private val dependencyJar: File = Tier1DependencyLibrary.compile(
    """
    package dep.ble

    interface Advertisement {
      val manufacturerData: String?
      fun manufacturerData(code: Int): ByteArray?
    }

    interface Labelled {
      val label: String
    }

    open class Probe(val reading: String)
    """.trimIndent(),
    fileName = "Ble.kt",
  )

  private fun runAgainstDependency(source: String): Tier1Result = Tier1Harness.run(
    source,
    processorOptions = mapOf(
      "nuget.includePackages" to "tier1.icv",
      "nuget.admit" to "dep.ble",
    ),
    libraries = listOf(dependencyJar),
  )

  private fun Tier1Result.collision(declaration: String): String {
    val error: String? = kspErrors.firstOrNull { message ->
      message.contains(ForwardDiagnosticKind.ERROR_CSHARP_NAME_COLLISION.name) &&
          message.contains(declaration)
    }
    assertTrue(error != null, "expected a collision at $declaration; kspErrors=$kspErrors")
    return error
  }

  @Test
  fun `the annotation the hint names resolves the issue 464 collision`() {
    val result = Tier1Harness.run(
      """
      package tier1.icv

      import io.github.xxfast.kotlin.native.nuget.annotations.CSharpName

      interface Advertisement {
        val manufacturerData: String?
        @CSharpName("ManufacturerDataFor")
        fun manufacturerData(code: Int): ByteArray?
      }

      fun advertisement(): Advertisement? = null
      """.trimIndent(),
      libraries = listOf(csharpNameLibrary),
    )

    assertFalse(
      result.kspErrors.any { ForwardDiagnosticKind.ERROR_CSHARP_NAME_COLLISION.name in it },
      "kspErrors=${result.kspErrors}",
    )
    assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
    assertContains(result.generatedCSharp, " ManufacturerDataFor(int code);")
    assertContains(result.generatedCSharp, " ManufacturerData { get; }")
  }

  @Test
  fun `a collision on a dependency interface says that module needs the annotations plugin`() {
    val result = runAgainstDependency(
      """
      package tier1.icv

      import dep.ble.Advertisement

      fun advertisement(): Advertisement? = null
      """.trimIndent(),
    )

    val error: String = result.collision("IAdvertisement.ManufacturerData")
    assertContains(
      error,
      "rename the Kotlin function 'manufacturerData' or the property it collides with, or give " +
          "it a different `@CSharpName`",
    )
    assertContains(
      error,
      "dep.ble.Advertisement is declared in a dependency module, which needs the " +
          "`io.github.xxfast.kotlin.native.nuget.annotations` plugin to use the annotation (the " +
          "main plugin adds `nuget-annotations` only to the module that applies it)",
    )
  }

  @Test
  fun `a dependency super-interface member flattened onto an interface names that super`() {
    val result = runAgainstDependency(
      """
      package tier1.icv

      import dep.ble.Labelled

      interface Badge : Labelled {
        fun label(code: Int): String
      }

      fun badge(): Badge? = null
      """.trimIndent(),
    )

    val error: String = result.collision("IBadge.Label")
    assertContains(error, "or give it a different `@CSharpName`")
    assertContains(error, "dep.ble.Labelled is declared in a dependency module")
    assertFalse("tier1.icv.Badge is declared" in error, "Badge is this module's own; $error")
  }

  @Test
  fun `a collision with a kept dependency super-interface names that super`() {
    val result = runAgainstDependency(
      """
      package tier1.icv

      import dep.ble.Labelled

      interface Tagline : Labelled {
        fun label(code: Int): String
      }

      fun tagline(): Tagline? = null
      fun labelled(): Labelled? = null
      """.trimIndent(),
    )

    val error: String = result.collision("ITagline.Label")
    assertContains(
      error,
      "rename the Kotlin function 'label' or the inherited property, or give it a different " +
          "`@CSharpName`",
    )
    assertContains(error, "dep.ble.Labelled is declared in a dependency module")
    assertFalse("tier1.icv.Tagline is declared" in error, "Tagline is this module's own; $error")
  }

  @Test
  fun `a dependency base member flattened onto a class names that base`() {
    val result = runAgainstDependency(
      """
      package tier1.icv

      import dep.ble.Probe

      class Sensor(reading: String) : Probe(reading) {
        fun reading(code: Int): String = code.toString()
      }
      """.trimIndent(),
    )

    val error: String = result.collision("Sensor.Reading")
    assertContains(error, "or give one a different `@CSharpName`")
    assertContains(error, "dep.ble.Probe is declared in a dependency module")
    assertFalse("tier1.icv.Sensor is declared" in error, "Sensor is this module's own; $error")
  }

  @Test
  fun `a collision with a kept dependency base names that base`() {
    val result = runAgainstDependency(
      """
      package tier1.icv

      import dep.ble.Probe

      class Gauge(reading: String) : Probe(reading) {
        fun reading(code: Int): String = code.toString()
      }

      fun probe(): Probe = Gauge("dial")
      """.trimIndent(),
    )

    val error: String = result.collision("Gauge.Reading")
    assertContains(error, "rename the member on Gauge or on Probe, or give it a different `@CSharpName`")
    assertContains(error, "dep.ble.Probe is declared in a dependency module")
    assertFalse("tier1.icv.Gauge is declared" in error, "Gauge is this module's own; $error")
  }
}
