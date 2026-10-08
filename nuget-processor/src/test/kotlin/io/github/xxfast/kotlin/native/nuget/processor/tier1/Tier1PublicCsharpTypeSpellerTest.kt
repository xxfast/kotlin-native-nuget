package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * The property route and the callable route now spell a public C# type through the one
 * `forwardPublicCsharpType()`. The two arms the former private property-route copy was thought to
 * be missing between them, a `Throwable` and a bound C# interface, go through the real processor
 * on both an interface and a class in one run: the `Throwable` renders as `System.Exception`
 * where it binds, and the bound interface keeps ADR-088's named skip at the property position
 * rather than reaching a speller at all.
 */
class Tier1PublicCsharpTypeSpellerTest {

  private fun manifestOption(): Map<String, String> {
    val file: File = Files.createTempFile("nuget-bound-types-", ".json").toFile()
    file.deleteOnExit()
    file.writeText(
      """
      {
        "interfaces": [
          { "kotlinName": "bound.speller.IClock", "csharpName": "Test.Speller.IClock", "implementable": true }
        ]
      }
      """.trimIndent(),
    )
    return mapOf("nuget.boundTypesManifest" to file.absolutePath)
  }

  @Test
  fun `a Throwable interface property and a bound interface class property both spell`() {
    val result: Tier1Result = Tier1Harness.run(
      sources = mapOf(
        "Bound.kt" to """
          package bound.speller

          interface IClock {
            fun now(): Long
          }
        """.trimIndent(),
        "Fixture.kt" to """
          package tier1.speller

          import bound.speller.IClock

          interface Incident {
            val cause: Throwable
            val previous: Throwable?
          }

          class Report(override val cause: Throwable) : Incident {
            override val previous: Throwable? = null
            val fatal: Throwable get() = cause
            val clock: IClock get() = error("no clock")
          }

          class Desk {
            fun file(): Incident = Report(IllegalStateException("Oreo knocked the jar over"))
          }
        """.trimIndent(),
      ),
      processorOptions = manifestOption(),
    )

    assertTrue(result.kspSucceeded, "kspErrors=${result.kspErrors}")
    assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
    val cs: String = result.generatedCSharp
    // The interface route declares its members off the plan's type (ADR-113).
    assertContains(cs, "global::System.Exception Cause { get; }")
    assertContains(cs, "global::System.Exception? Previous { get; }")
    // The class route's property projection, which used the private copy.
    assertContains(cs, "public global::System.Exception Fatal")
    // ADR-088: a bound interface at a property position stays a named skip.
    assertTrue("report_get_clock" !in result.generated, result.generated)
    assertTrue(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_BOUND_TYPE_POSITION.name) && it.contains("clock")
      },
      "kspWarnings=${result.kspWarnings}",
    )
    Tier1CSharpCompile.assertCompiles(result, "", allowUnsafe = true)
  }
}
