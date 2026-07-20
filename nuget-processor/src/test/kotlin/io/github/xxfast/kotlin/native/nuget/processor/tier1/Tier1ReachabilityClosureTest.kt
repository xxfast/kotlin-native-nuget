package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-066: the two diagnostic kinds the reachability closure adds, `SKIPPED_UNEXPORTED_DEPENDENCY
 * _TYPE` and `INFO_EXPORTED_FROM_DEPENDENCY` — deliberately KSP-build-log-only (the ADR's
 * `include(...)` fix is a message a build log carries, not something visible from compiled C#, so
 * this belongs at the Tier 1 unit level rather than the `:test-library`/`NewsroomReachabilityTests
 * .cs` integration fixture, which already exercises the successful-admission path end to end).
 *
 * [Tier1DependencyLibrary] compiles a genuinely separate `.jar` so the fixture crosses a real
 * compilation-unit boundary (`Origin.KOTLIN_LIB`, `containingFile == null`) the way a Gradle
 * module dependency does — a same-round, multi-package fixture cannot reproduce either signal.
 */
class Tier1ReachabilityClosureTest {

  private val dependencyJar: File = Tier1DependencyLibrary.compile(
    """
    package dep.outside

    class Advert(val sponsor: String)
    """.trimIndent(),
    fileName = "Advert.kt",
  )

  private val fixture: String = """
    package tier1.reachabilityclosure

    import dep.outside.Advert

    class Newsroom {
      fun sponsor(): Advert = Advert("Acme")
    }
  """.trimIndent()

  @Test
  fun `out-of-scope dependency type fires SKIPPED_UNEXPORTED_DEPENDENCY_TYPE naming the include fix`() {
    val result = Tier1Harness.run(
      fixture,
      processorOptions = mapOf("nuget.rootPackage" to "tier1.reachabilityclosure"),
      libraries = listOf(dependencyJar),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    assertFalse(
      "export_newsroom_sponsor" in result.generated,
      "expected Newsroom.sponsor to be entirely absent from the generated CNameExports.kt; " +
          "generated:\n${result.generated}",
    )

    val diagnostic: String = requireNotNull(
      result.kspWarnings.firstOrNull { it.contains(ForwardDiagnosticKind.SKIPPED_UNEXPORTED_DEPENDENCY_TYPE.name) },
    ) {
      "expected a SKIPPED_UNEXPORTED_DEPENDENCY_TYPE diagnostic naming Newsroom.sponsor's " +
          "out-of-scope dep.outside.Advert return type; kspWarnings=${result.kspWarnings}"
    }
    assertTrue(
      diagnostic.contains("include(\"dep.outside\")"),
      "expected the diagnostic to name the exact include(\"dep.outside\") fix; got: $diagnostic",
    )
  }

  @Test
  fun `admitted dependency type fires the aggregate INFO_EXPORTED_FROM_DEPENDENCY manifest once`() {
    val result = Tier1Harness.run(
      fixture,
      // Widening the include set to cover both packages is the closure's own escape hatch (the
      // fix the negative test above asserts the hint names verbatim).
      processorOptions = mapOf("nuget.includePackages" to "tier1.reachabilityclosure,dep.outside"),
      libraries = listOf(dependencyJar),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    assertTrue(
      "export_newsroom_sponsor" in result.generated,
      "expected Newsroom.sponsor to bind once its dependency type is admitted; " +
          "generated:\n${result.generated}",
    )

    val manifest: List<String> = result.kspWarnings
      .filter { it.contains(ForwardDiagnosticKind.INFO_EXPORTED_FROM_DEPENDENCY.name) }
    assertTrue(
      manifest.size == 1,
      "expected exactly one aggregate INFO_EXPORTED_FROM_DEPENDENCY line, not one per admitted " +
          "type (ADR-066's deliberate deviation from ADR-064's per-member shape); got: $manifest",
    )
    assertTrue(
      manifest.single().contains("dep.outside.Advert"),
      "expected the manifest to name the admitted dep.outside.Advert; got: ${manifest.single()}",
    )
  }
}
