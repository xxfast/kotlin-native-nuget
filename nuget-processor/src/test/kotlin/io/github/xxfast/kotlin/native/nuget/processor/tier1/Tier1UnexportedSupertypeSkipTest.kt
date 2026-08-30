package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-101 / issue #42: an exported class whose supertype is declared outside the export set used
 * to render `public class Api : IOutsideThing, INugetHandle` for an interface nothing ever
 * generates, so the whole `Interop.cs` died on CS0246 (the reporter's real case was Koin's
 * `KoinComponent`). The supertype must be dropped with a `SKIPPED_UNEXPORTED_SUPERTYPE` warning
 * while the class and its own members keep exporting.
 *
 * Modelled on [Tier1ReachabilityClosureTest]: [Tier1DependencyLibrary] compiles a genuinely
 * separate `.jar`, so the supertype crosses a real compilation-unit boundary
 * (`Origin.KOTLIN_LIB`, `containingFile == null`) the way a Gradle module dependency does. A
 * same-round multi-package fixture cannot reproduce that.
 */
class Tier1UnexportedSupertypeSkipTest {

  private val dependencyJar: File = Tier1DependencyLibrary.compile(
    """
    package dep.outside

    interface OutsideThing {
      fun tag(): String = "outside"
    }
    """.trimIndent(),
    fileName = "OutsideThing.kt",
  )

  // No base class, on purpose: `CirClassTranslator` only populates its `interfaces` list when
  // `forwardSuperClass()` is null, so a superclass here would mask the defect entirely.
  private val fixture: String = """
    package tier1.issue42

    import dep.outside.OutsideThing

    class Api(val port: Int) : OutsideThing {
      fun ping(): String = "pong:${'$'}port"
    }
  """.trimIndent()

  @Test
  fun `out-of-scope supertype is dropped with SKIPPED_UNEXPORTED_SUPERTYPE and the class still exports`() {
    val result = Tier1Harness.run(
      fixture,
      processorOptions = mapOf("nuget.rootPackage" to "tier1.issue42"),
      libraries = listOf(dependencyJar),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    assertTrue(
      "export_api_ping" in result.generated,
      "dropping the supertype must not cost the class its own members; generated:\n${result.generated}",
    )
    assertTrue(
      "export_api_get_port" in result.generated,
      "dropping the supertype must not cost the class its own properties; " +
          "generated:\n${result.generated}",
    )
    assertTrue(
      "export_api_tag" in result.generated,
      "the dropped supertype's *defaulted* member still binds on the class itself " +
          "(`ForwardClassMembership.kt`), so the C# surface loses nothing at all; " +
          "generated:\n${result.generated}",
    )
    assertFalse(
      "IOutsideThing" in result.generatedCSharp,
      "the dangling supertype must be absent from the generated C# entirely — it is the CS0246 " +
          "in issue #42; generated C#:\n${result.generatedCSharp}",
    )

    val diagnostic: String = requireNotNull(
      result.kspWarnings.firstOrNull { it.contains(ForwardDiagnosticKind.SKIPPED_UNEXPORTED_SUPERTYPE.name) },
    ) {
      "expected a SKIPPED_UNEXPORTED_SUPERTYPE diagnostic naming Api's out-of-scope supertype; " +
          "kspWarnings=${result.kspWarnings}"
    }
    assertTrue(
      diagnostic.contains("dep.outside.OutsideThing"),
      "the diagnostic must name the skipped supertype's qualified name; got: $diagnostic",
    )
  }

  /**
   * ADR-101's load-bearing open question, and the reason the hint text is what it is: does
   * `include("dep.outside")` actually admit a type that is reachable *only* as a supertype? The
   * ADR-066 closure walks returns, parameters, property types, type arguments, sealed subclasses
   * and primary-constructor parameters, and never `superTypes` — so admitting the package cannot
   * pull the interface in, and an `include(...)` hint would send the author round a loop they
   * have already run. This test pins that measurement so the hint stays honest.
   */
  @Test
  fun `including the dependency package does not admit a supertype-only interface`() {
    val result = Tier1Harness.run(
      fixture,
      processorOptions = mapOf("nuget.includePackages" to "tier1.issue42,dep.outside"),
      libraries = listOf(dependencyJar),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    assertFalse(
      "IOutsideThing" in result.generatedCSharp,
      "measured: the closure has no superTypes edge, so include(\"dep.outside\") leaves a " +
          "supertype-only interface out of the export set; generated C#:\n${result.generatedCSharp}",
    )
    assertTrue(
      result.kspWarnings.any { it.contains(ForwardDiagnosticKind.SKIPPED_UNEXPORTED_SUPERTYPE.name) },
      "the skip keeps firing even with the package included, which is why the hint must not " +
          "promise include(...) fixes it; kspWarnings=${result.kspWarnings}",
    )
  }
}
