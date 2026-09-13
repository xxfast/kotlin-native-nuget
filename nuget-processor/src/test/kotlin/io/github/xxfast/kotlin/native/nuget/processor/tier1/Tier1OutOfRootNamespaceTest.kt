package io.github.xxfast.kotlin.native.nuget.processor.tier1

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ADR-066 §5 amendment (decided rule, option (i)): **a package outside `rootPackage` maps to
 * `<packageId>.<full Kotlin package, each segment PascalCased>`**.
 *
 * This is the only test in the repository that renders that shape through a real KSP2 run. The
 * existing unit cells beside `CirOrdinaryRendererTest`'s `keeps packages outside root as a suffix`
 * call `mapPackageToNamespace` directly; every Tier 1 cell that admits an out-of-root dependency
 * package today (`Tier1ReachabilityClosureTest`, `Tier1ExportScopingTest`, …) runs with
 * `nuget.rootPackage` blank, so everything collapses to `Interop` and the out-of-root branch is
 * never rendered. Here both knobs are set at once, which is what makes the branch reachable.
 *
 * **PIN**: expected green on current sources. Its value is regression cover — declaration site and
 * every reference site share `mapPackageToNamespace`, so one rule change moves both, and this cell
 * is what notices.
 *
 * [Tier1DependencyLibrary] compiles a genuinely separate `.jar`, so `dep.outside` crosses a real
 * compilation-unit boundary (`containingFile == null`) the way a Gradle module dependency does —
 * the same shape `:test-models`' `dev.other.admitted.Billboard` has against `:test-library`.
 *
 * The fixture is the cat flap: the flap (`dep.outside`) is a different module from the cat
 * (`tier1.oor`), and Oreo still gets a name that says where he came from.
 */
class Tier1OutOfRootNamespaceTest {

  private val dependencyJar: File = Tier1DependencyLibrary.compile(
    """
    package dep.outside

    class Flap(val fitted: String)
    """.trimIndent(),
    fileName = "Flap.kt",
  )

  private val fixture: String = """
    package tier1.oor

    import dep.outside.Flap

    class CatDoor {
      fun flap(): Flap = Flap("Oreo-sized")
    }
  """.trimIndent()

  @Test
  fun `an admitted out-of-root package is declared and referenced under the root namespace by its full path`() {
    val result: Tier1Result = Tier1Harness.run(
      fixture,
      processorOptions = mapOf(
        "nuget.rootPackage" to "tier1.oor",
        // ADR-063 / issue #55: an explicit include *replaces* the rootPackage default, so the own
        // package has to be listed alongside the dependency package.
        "nuget.includePackages" to "tier1.oor,dep.outside",
      ),
      libraries = listOf(dependencyJar),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")

    val csharp: String = result.generatedCSharp

    // Declaration site (`CirTranslator.namespaceOf`): the out-of-root package keeps every segment.
    assertTrue(
      csharp.lines().any { it.trim() == "namespace Interop.Dep.Outside" },
      "expected dep.outside to be declared in `namespace Interop.Dep.Outside`; generated:\n$csharp",
    )

    // Reference site (`ForwardBridgeTypeClassifier.csharpTypeNameFor`): same rule,
    // `global::`-qualified.
    assertTrue(
      "global::Interop.Dep.Outside.Flap" in csharp,
      "expected CatDoor.Flap() to be spelled `global::Interop.Dep.Outside.Flap`; " +
          "generated:\n$csharp",
    )

    // Control: the root package itself still collapses onto the root namespace. Matched on a
    // whole trimmed line, because `namespace Interop` is a prefix of every other namespace here
    // and a substring check would pass without the root class ever landing anywhere.
    assertTrue(
      csharp.lines().any { it.trim() == "namespace Interop" },
      "expected tier1.oor's own CatDoor to be declared in `namespace Interop`; generated:\n$csharp",
    )
  }
}
