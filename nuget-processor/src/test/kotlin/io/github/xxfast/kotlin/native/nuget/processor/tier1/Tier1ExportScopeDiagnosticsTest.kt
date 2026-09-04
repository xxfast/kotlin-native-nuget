package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #55: an explicit `include(...)` replaces the `rootPackage` default (ADR-063, deliberate),
 * and two things around that rule made it a trap. The `SKIPPED_UNEXPORTED_DEPENDENCY_TYPE` hint
 * named only the missing package, so following it emptied the export set; and an empty export set
 * returned silently, leaving a green `packNuget` with no `Interop.cs` in the package.
 */
class Tier1ExportScopeDiagnosticsTest {

  private val dependencyJar: File = Tier1DependencyLibrary.compile(
    """
    package dep.outside

    class Advert(val sponsor: String)
    """.trimIndent(),
    fileName = "Advert.kt",
  )

  @Test
  fun `the include hint lists the explicit include set ahead of the missing package`() {
    val result = Tier1Harness.run(
      """
      package tier1.scopehint.api

      import dep.outside.Advert

      class Newsroom {
        fun sponsor(): Advert = Advert("Acme")
      }
      """.trimIndent(),
      processorOptions = mapOf(
        "nuget.rootPackage" to "tier1.scopehint",
        "nuget.includePackages" to "tier1.scopehint.api",
      ),
      libraries = listOf(dependencyJar),
    )

    val diagnostic: String = requireNotNull(
      result.kspWarnings.firstOrNull {
        it.contains(ForwardDiagnosticKind.SKIPPED_UNEXPORTED_DEPENDENCY_TYPE.name)
      },
    ) {
      "expected a SKIPPED_UNEXPORTED_DEPENDENCY_TYPE diagnostic; kspWarnings=${result.kspWarnings}"
    }
    assertTrue(
      diagnostic.contains("include(\"tier1.scopehint.api\", \"dep.outside\")"),
      "expected the hint to name the full include line; got: $diagnostic",
    )
    assertTrue(
      diagnostic.contains("replaces the rootPackage default"),
      "expected the hint to say why the own package must stay listed; got: $diagnostic",
    )
  }

  @Test
  fun `a stdlib type gets no include hint at all`() {
    val result = Tier1Harness.run(
      """
      package tier1.scopehint

      import kotlin.uuid.ExperimentalUuidApi
      import kotlin.uuid.Uuid

      @OptIn(ExperimentalUuidApi::class)
      class Record(val id: Uuid)

      @OptIn(ExperimentalUuidApi::class)
      fun record(): Record = Record(Uuid.random())
      """.trimIndent(),
      processorOptions = mapOf("nuget.rootPackage" to "tier1.scopehint"),
    )

    val diagnostic: String = requireNotNull(
      result.kspWarnings.firstOrNull {
        it.contains(ForwardDiagnosticKind.SKIPPED_UNEXPORTED_DEPENDENCY_TYPE.name)
      },
    ) {
      "expected a SKIPPED_UNEXPORTED_DEPENDENCY_TYPE diagnostic; kspWarnings=${result.kspWarnings}"
    }
    assertFalse(
      diagnostic.contains("add include("),
      "expected no include(...) suggestion for a stdlib type; got: $diagnostic",
    )
    assertTrue(
      diagnostic.contains("kotlin.uuid.Uuid is a Kotlin stdlib type"),
      "expected the hint to name the stdlib type; got: $diagnostic",
    )
  }

  @Test
  fun `an include that admits nothing warns once instead of returning silently`() {
    val result = Tier1Harness.run(
      """
      package tier1.scopehint

      class Holder(val name: String)

      fun holder(): Holder = Holder("Oreo")
      """.trimIndent(),
      processorOptions = mapOf(
        "nuget.rootPackage" to "tier1.scopehint",
        "nuget.includePackages" to "kotlin",
      ),
    )

    val warnings: List<String> = result.kspWarnings.filter {
      it.contains(ForwardDiagnosticKind.SKIPPED_ALL_DECLARATIONS.name)
    }
    assertTrue(
      warnings.size == 1,
      "expected exactly one SKIPPED_ALL_DECLARATIONS; got: ${result.kspWarnings}",
    )
    val warning: String = warnings.single()
    assertTrue(warning.contains("include(\"kotlin\")"), "expected the scope named; got: $warning")
    assertTrue(
      warning.contains("rootPackage = \"tier1.scopehint\""),
      "expected rootPackage named; got: $warning",
    )
    assertTrue(
      warning.contains("\"tier1.scopehint\""),
      "expected the skipped package named; got: $warning",
    )
    assertTrue(
      warning.contains("replaces the rootPackage default"),
      "expected the hint to explain the replacement rule; got: $warning",
    )
    assertFalse(
      result.generatedFiles.keys.any { it.endsWith("Interop.cs") },
      "expected no Interop.cs; generatedFiles=${result.generatedFiles.keys}",
    )
    assertTrue(
      result.generatedFiles.keys.any { it.endsWith("NugetDiagnostics.json") },
      "expected the diagnostics file to still be written so nugetReportDiagnostics can re-emit " +
          "the warning; generatedFiles=${result.generatedFiles.keys}",
    )
    assertTrue(
      result.generatedFiles.entries.single { it.key.endsWith("NugetDiagnostics.json") }.value
        .contains(ForwardDiagnosticKind.SKIPPED_ALL_DECLARATIONS.name),
      "expected the warning inside NugetDiagnostics.json",
    )
  }

  @Test
  fun `a module with no public declarations at all does not warn`() {
    val result = Tier1Harness.run(
      """
      package tier1.scopehint

      internal class Hidden(val name: String)
      """.trimIndent(),
      processorOptions = mapOf("nuget.rootPackage" to "tier1.scopehint"),
    )

    assertFalse(
      result.kspWarnings.any { it.contains(ForwardDiagnosticKind.SKIPPED_ALL_DECLARATIONS.name) },
      "expected no SKIPPED_ALL_DECLARATIONS when scoping removed nothing; " +
          "got: ${result.kspWarnings}",
    )
  }
}
