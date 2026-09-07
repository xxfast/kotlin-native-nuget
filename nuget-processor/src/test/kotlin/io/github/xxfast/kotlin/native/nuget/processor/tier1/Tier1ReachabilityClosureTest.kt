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
    // Issue #55: the hint names the whole include line, own package first, because an explicit
    // include replaces the rootPackage default rather than adding to it.
    assertTrue(
      diagnostic.contains("include(\"tier1.reachabilityclosure\", \"dep.outside\")"),
      "expected the diagnostic to name the full include(...) line; got: $diagnostic",
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

  private val nestedDependencyJar: File = Tier1DependencyLibrary.compile(
    """
    package dep.nested

    class Outer(val tag: String) {
      class Inner(val n: Int)
      object Defaults
    }

    sealed class Shape {
      class Circle(val radius: Int) : Shape()
    }
    """.trimIndent(),
    fileName = "Outer.kt",
  )

  private val nestedFixture: String = """
    package tier1.reachabilityclosure.nested

    import dep.nested.Outer
    import dep.nested.Shape

    class Newsroom {
      fun outer(): Outer = Outer("acme")
      fun inner(): Outer.Inner = Outer.Inner(1)
      fun defaults(): Outer.Defaults = Outer.Defaults
      fun shape(): Shape = Shape.Circle(2)
    }
  """.trimIndent()

  private fun nestedResult(): Tier1Result = Tier1Harness.run(
    nestedFixture,
    processorOptions = mapOf(
      "nuget.includePackages" to "tier1.reachabilityclosure.nested,dep.nested",
    ),
    libraries = listOf(nestedDependencyJar),
  )

  /** The closure admits a dependency type into THIS module's namespace, and `translateClass`
   *  declares it at namespace root under its simple name while every reference to it is spelled
   *  `Outer.Inner` -- so admitting a *nested* one emits a `class Inner` no reference resolves
   *  against (CS0426). The enum bucket already refused nested; every bucket does now. */
  @Test
  fun `a nested dependency class or object is neither declared nor referenced`() {
    val result = nestedResult()

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    listOf("Inner", "Defaults").forEach { nested ->
      assertFalse(
        result.generatedCSharp.contains(nested),
        "expected no flat declaration of, or nested reference to, $nested; generatedCSharp=" +
            "${result.generatedCSharp.lines().filter { it.contains(nested) }}",
      )
    }
    assertTrue(
      "export_outer_get_tag" in result.generated,
      "expected the admitted OUTER type and its own members to survive; " +
          "generated:\n${result.generated}",
    )

    listOf("Newsroom.inner", "Newsroom.defaults").forEach { member ->
      val diagnostic: String = requireNotNull(
        result.kspWarnings.firstOrNull { it.contains("SKIPPED_") && it.contains(member) },
      ) { "expected a skip diagnostic for $member; kspWarnings=${result.kspWarnings}" }
      assertTrue(
        diagnostic.contains("UNDECLARED_CLASS") && diagnostic.contains("move it to the top level"),
        "expected $member to take the undeclarable route, not the closure's scope route; " +
            "got: $diagnostic",
      )
      // `include(...)` cannot make a nested declaration declarable, so it must not be offered.
      assertFalse(
        diagnostic.contains("include("),
        "expected no include(...) advice for a nested dependency declaration; got: $diagnostic",
      )
    }
  }

  /** The carve-out: ADR-009 declares a sealed subclass nested, and `nestedCsName` spells it the
   *  same way, so the sealed route's `getSealedSubclasses()` admissions must stay admitted. */
  @Test
  fun `a nested dependency sealed subclass stays admitted`() {
    val result = nestedResult()

    assertTrue(
      result.generatedCSharp.contains("Circle"),
      "expected the dependency sealed subclass to still be declared under its base; " +
          "generatedCSharp=${result.generatedCSharp.lines().filter { it.contains("Shape") }}",
    )
    val manifest: String = result.kspWarnings
      .first { it.contains(ForwardDiagnosticKind.INFO_EXPORTED_FROM_DEPENDENCY.name) }
    assertTrue(
      manifest.contains("dep.nested.Shape.Circle"),
      "expected the sealed subclass to still be admitted by the closure; got: $manifest",
    )
    assertFalse(
      manifest.contains("dep.nested.Outer.Inner") ||
          manifest.contains("dep.nested.Outer.Defaults"),
      "expected the plain nested declarations to be refused admission; got: $manifest",
    )
  }
}
