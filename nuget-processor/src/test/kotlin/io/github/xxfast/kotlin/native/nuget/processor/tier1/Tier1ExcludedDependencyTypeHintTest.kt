package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ROADMAP Phase 3: after following ADR-109's `exclude("<pkg>")` remedy, every callable reaching
 * the excluded dependency type skips as `SKIPPED_UNEXPORTED_DEPENDENCY_TYPE`, and the hint used
 * to say `add include("<pkg>")` — advice that cannot work, because `PackageScope.covers` tests
 * `exclude` first, so no `include` can override an exclude. The reachability closure now records
 * WHY it refused admission, and the hint names the author's own `exclude(...)` instead.
 *
 * Fixture shape copied from [Tier1DuplicatedDependencyTypeTest]: a genuinely separate `.jar`, so
 * the dependency type crosses a real compilation-unit boundary the way a Gradle-module dependency
 * does (a same-compilation type carries a containing file and never reaches this path).
 */
class Tier1ExcludedDependencyTypeHintTest {

  private val dependencyJar: File = Tier1DependencyLibrary.compile(
    """
    package dep.models

    class TopStory(val headline: String)
    """.trimIndent(),
    fileName = "TopStory.kt",
  )

  private val fixture: String = """
    package tier1.excluded

    import dep.models.TopStory

    class Newsroom {
      fun latest(): TopStory = TopStory("Acme")
    }
  """.trimIndent()

  private fun run(options: Map<String, String>): Tier1Result = Tier1Harness.run(
    fixture,
    processorOptions = mapOf("nuget.namespace" to "Lib") + options,
    libraries = listOf(dependencyJar),
  )

  private fun dependencySkips(warnings: List<String>): List<String> =
    warnings.filter {
      it.contains(ForwardDiagnosticKind.SKIPPED_UNEXPORTED_DEPENDENCY_TYPE.name) &&
          it.contains("Newsroom.latest")
    }

  @Test
  fun `a callable reaching an excluded dependency type is not told to include it`() {
    val result = run(
      mapOf(
        "nuget.includePackages" to "tier1.excluded,dep.models",
        "nuget.excludePackages" to "dep.models",
      ),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    assertFalse(
      "export_newsroom_latest" in result.generated,
      "the excluded type is still refused admission; generated:\n${result.generated}",
    )

    val warning: String = dependencySkips(result.kspWarnings).firstOrNull()
      ?: error("expected a dependency-type skip; got: ${result.kspWarnings}")
    assertFalse(
      "add include(" in warning,
      "include(...) cannot override an exclude, so it must not be the hint; got: $warning",
    )
    assertTrue(
      """exclude("dep.models")""" in warning,
      "expected the author's own exclude named as the cause; got: $warning",
    )
    assertTrue(
      "remove the exclude" in warning,
      "expected the only remedy that works; got: $warning",
    )
  }

  /** ADR-066 admission rule 4: with neither `rootPackage` nor `include`, the closure never crosses
   *  the module boundary. `include("dep.models")` alone would then replace the "everything"
   *  default and drop the module's own files, so the hint must say to keep them in scope too. */
  @Test
  fun `a dependency type refused because cross-module admission is off says how to turn it on`() {
    val result = run(emptyMap())

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val warning: String = dependencySkips(result.kspWarnings).firstOrNull()
      ?: error("expected a dependency-type skip; got: ${result.kspWarnings}")
    assertTrue(
      "rootPackage" in warning,
      "expected the hint to name the scope setting that admits cross-module types; got: $warning",
    )
    assertTrue(
      "tier1.excluded" in warning || "your own" in warning,
      "expected the hint to say the module's own packages must stay in scope; got: $warning",
    )
  }
}
