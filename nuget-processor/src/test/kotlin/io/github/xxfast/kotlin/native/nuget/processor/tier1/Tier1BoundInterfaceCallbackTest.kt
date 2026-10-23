package io.github.xxfast.kotlin.native.nuget.processor.tier1

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ADR-239: a bound C# interface (ADR-088) as a callback payload and result, on the plain and the
 * `suspend` callback routes. Both halves cross it as the reverse pipeline's GCHandle: Kotlin hands
 * a payload out through `nuget{Iface}HandleOut` and takes a result in through `nuget{Iface}Value`,
 * and C# reads with `NugetMarshal.TakeGcHandle<T>` and mints with `NugetMarshal.GiveGcHandle`.
 *
 * As in [Tier1BoundInterfacePositionTest], the reverse pipeline's helpers do not exist in a Tier 1
 * fixture, so the generated Kotlin is not compiled here; the `Pen` integration tests run it.
 */
class Tier1BoundInterfaceCallbackTest {

  private fun manifestOption(): Map<String, String> {
    val file: File = Files.createTempFile("nuget-bound-types-", ".json").toFile()
    file.deleteOnExit()
    file.writeText(
      """
      {
        "interfaces": [
          {
            "kotlinName": "bound.menagerie.IFeedable",
            "csharpName": "Test.Menagerie.IFeedable",
            "implementable": true
          }
        ]
      }
      """.trimIndent()
    )
    return mapOf("nuget.boundTypesManifest" to file.absolutePath)
  }

  @Test
  fun `a bound interface binds as a callback payload and result on the plain and suspend routes`() {
    val result = Tier1Harness.run(
      sources = mapOf(
        "Bound.kt" to """
          package bound.menagerie

          interface IFeedable {
            fun describe(): String
          }
        """.trimIndent(),
        "Fixture.kt" to """
          package tier1.boundcallback

          import bound.menagerie.IFeedable

          class Pen {
            fun inspect(look: (IFeedable) -> Int): Int = 0
            fun fetch(make: () -> IFeedable): Int = 0
            suspend fun inspectLater(look: suspend (IFeedable) -> Int): Int = 0
            suspend fun fetchLater(make: suspend () -> IFeedable): Int = 0
          }
        """.trimIndent(),
      ),
      processorOptions = manifestOption(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    val cs: String = result.generatedCSharp
    listOf(
      "public int Inspect(Func<global::Test.Menagerie.IFeedable, int> look)",
      "public int Fetch(Func<global::Test.Menagerie.IFeedable> make)",
      "InspectLaterAsync(Func<global::Test.Menagerie.IFeedable, ",
      "FetchLaterAsync(Func<global::System.Threading.CancellationToken, " +
          "global::System.Threading.Tasks.Task<global::Test.Menagerie.IFeedable>> make",
      "NugetMarshal.TakeGcHandle<global::Test.Menagerie.IFeedable>(",
      "NugetMarshal.GiveGcHandle(",
    ).forEach { expected -> assertTrue(expected in cs, "expected `$expected` in the C#") }
    listOf("nugetIFeedableHandleOut(", "nugetIFeedableValue(").forEach { expected ->
      assertTrue(expected in result.generated, "expected `$expected` in the Kotlin")
    }
    assertTrue(
      result.kspWarnings.none { warning -> "Pen." in warning },
      "a member that binds must not be reported; got: ${result.kspWarnings}",
    )
  }

  /**
   * The erased positions: a stored pair, a returned lambda and a lambda property carry the bound
   * interface through `NugetMarshal`'s bound tables, over two generated exports per interface (a
   * GCHandle kept as a Kotlin handle, and the reverse).
   */
  @Test
  fun `a bound interface binds at the erased positions over the generated box exports`() {
    val result = Tier1Harness.run(
      sources = mapOf(
        "Bound.kt" to """
          package bound.menagerie

          interface IFeedable {
            fun describe(): String
          }
        """.trimIndent(),
        "Fixture.kt" to """
          package tier1.bounderased

          import bound.menagerie.IFeedable

          class Pen {
            fun addKeeper(listener: (IFeedable) -> Int) {}
            fun removeKeeper(listener: (IFeedable) -> Int) {}
            fun legCounter(): (IFeedable) -> Int = { 0 }
            var onSwap: (() -> IFeedable)? = null
          }
        """.trimIndent(),
      ),
      processorOptions = manifestOption(),
    )

    val cs: String = result.generatedCSharp
    listOf(
      "public IDisposable AddKeeper(Func<global::Test.Menagerie.IFeedable, int> listener)",
      "public Func<global::Test.Menagerie.IFeedable, int> LegCounter()",
      "Func<global::Test.Menagerie.IFeedable>? OnSwap",
      "[typeof(global::Test.Menagerie.IFeedable)] = static value => Native_bound_box_0(",
      "[typeof(global::Test.Menagerie.IFeedable)] = static handle => Native_bound_unbox_0(",
    ).forEach { expected -> assertTrue(expected in cs, "expected `$expected` in the C#") }
    listOf("__bound_bound_menagerie_IFeedable_box", "__bound_bound_menagerie_IFeedable_unbox")
      .forEach { export -> assertTrue(export in result.generated, "expected the `$export` export") }
    assertTrue(
      result.kspWarnings.none { warning -> "Pen." in warning },
      "a member that binds must not be reported; got: ${result.kspWarnings}",
    )
  }
}
