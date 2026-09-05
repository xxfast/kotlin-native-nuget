package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-109: when this module's ADR-066 export closure admits a dependency-module type whose package
 * ANOTHER forward publisher in the same Gradle build also exports, a consumer referencing both
 * NuGet packages gets two unrelated C# types for one Kotlin type, with no conversion between them.
 * Neither KSP run can see the other, so the plugin lowers every publisher's ADR-063 predicate into
 * the `nuget.publishedScopes` option and the processor matches admitted types against it by
 * package (the only handle it has: a cross-module declaration carries no module identity).
 *
 * The type is still exported — nothing about the generated output changes — so the rendered
 * line says "Duplicating", not the severity-keyed "Skipping" every other WARNING gets.
 *
 * Modelled on [Tier1ReachabilityClosureTest]: [Tier1DependencyLibrary] compiles a genuinely
 * separate `.jar` so the fixture crosses a real compilation-unit boundary the way a Gradle-module
 * dependency does.
 */
class Tier1DuplicatedDependencyTypeTest {

  private val dependencyJar: File = Tier1DependencyLibrary.compile(
    """
    package dep.models

    class TopStory(val headline: String)
    """.trimIndent(),
    fileName = "TopStory.kt",
  )

  private val fixture: String = """
    package tier1.dup

    import dep.models.TopStory

    class Newsroom {
      fun latest(): TopStory = TopStory("Acme")
    }
  """.trimIndent()

  /** The self entry is always present (the plugin lists this project too, so the single-publisher
   *  real build exercises the plumbing) and must never warn about itself. */
  private fun run(publishedScopes: String): Tier1Result = Tier1Harness.run(
    fixture,
    processorOptions = mapOf(
      "nuget.namespace" to "Lib",
      "nuget.includePackages" to "tier1.dup,dep.models",
      "nuget.publishedScopes" to publishedScopes,
    ),
    libraries = listOf(dependencyJar),
  )

  private fun duplicationWarnings(warnings: List<String>): List<String> =
    warnings.filter { it.contains(ForwardDiagnosticKind.WARNING_DUPLICATED_DEPENDENCY_TYPE.name) }

  @Test
  fun `an admitted dependency type another publisher's scope covers warns and still exports`() {
    val result = run("Lib:tier1.dup|dep.models:;OtherLib:dep.models:")

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    assertTrue(
      "export_newsroom_latest" in result.generated,
      "ADR-109 changes no generated output: the duplicated type must still export; " +
          "generated:\n${result.generated}",
    )

    val warnings: List<String> = duplicationWarnings(result.kspWarnings)
    assertEquals(
      1,
      warnings.size,
      "expected exactly one line for (dep.models.TopStory, OtherLib), and none for the self " +
          "entry Lib; got: $warnings",
    )

    val warning: String = warnings.single()
    assertTrue("dep.models.TopStory" in warning, "expected the admitted type named; got: $warning")
    assertTrue("OtherLib" in warning, "expected the other publisher named; got: $warning")
    assertTrue(
      """exclude("dep.models")""" in warning,
      "expected the exclude(...) remedy naming the duplicated package; got: $warning",
    )
    // ADR-109 Decision 4: `format()` keys the verb off severity, and "Skipping" would be a lie —
    // this warning skips nothing.
    assertTrue(
      "] Duplicating dep.models.TopStory" in warning,
      "expected the per-kind verb override; got: $warning",
    )
    assertFalse("Skipping dep.models.TopStory" in warning, "got: $warning")
    // The self entry ("Lib", this module's own nuget.namespace) is dropped at parse time, so it
    // can never appear as the *covering publisher* the message names between the duplicated type
    // and the other package's id.
    assertFalse(
      "Lib" in warning.substringAfter("Duplicating").substringBefore("OtherLib"),
      "the self entry must be dropped at parse time, not named in the message; got: $warning",
    )
    // ADR-109 retracts ADR-066's shape (1): two published modules are two native libraries, each
    // with its own Kotlin runtime and heap, so a shared "models" NuGet cannot carry handles
    // between them. The hint must never suggest depending on the other package instead.
    assertFalse("NuGet package instead" in warning, "got: $warning")
    assertTrue(
      "umbrella module" in warning,
      "expected the only two workable remedies (one umbrella publisher, or exclude(...)); " +
          "got: $warning",
    )
  }

  /** The single-publisher case the real `scripts/verify.sh` build is: only the self entry, which
   *  is dropped, so nothing warns. */
  @Test
  fun `the self entry alone is silent`() {
    val result = run("Lib:tier1.dup|dep.models:")

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    assertTrue(
      duplicationWarnings(result.kspWarnings).isEmpty(),
      "a publisher must never warn about its own scope; got: ${result.kspWarnings}",
    )
  }

  /** Another publisher whose own `exclude(...)` covers the package will not declare a copy, so
   *  there is nothing to warn about. */
  @Test
  fun `another publisher that excludes the package is silent`() {
    val result = run("Lib:tier1.dup|dep.models:;OtherLib:dep:dep.models")

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    assertTrue(
      duplicationWarnings(result.kspWarnings).isEmpty(),
      "the other publisher excludes dep.models, so it declares no copy; got: ${result.kspWarnings}",
    )
  }

  /** A scope that does not reach the admitted type's package at all. */
  @Test
  fun `another publisher whose scope does not cover the package is silent`() {
    val result = run("Lib:tier1.dup|dep.models:;OtherLib:dep.other:")

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    assertTrue(
      duplicationWarnings(result.kspWarnings).isEmpty(),
      "dep.other does not cover dep.models; got: ${result.kspWarnings}",
    )
  }
}
