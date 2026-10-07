package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-159 rules 4 and 5 on two chain shapes the ADR left "inferred, no fixture".
 *
 * Cell 1, a dropped middle base: `Dinghy : Skiff : Vessel` where `Skiff` sits outside the export
 * root (ADR-101) and all three declare the same `suspend fun launch()`. `Vessel` is kept and
 * already projects `LaunchAsync`, so `Dinghy` must not project a second one (CS0108), even though
 * the overridee it finds nearest is `Skiff`'s, on the dropped base.
 *
 * Cell 2, an `abstract suspend fun` with no body on an abstract base: the base's export calls it on
 * `asStableRef<Groomer>().get()`, so it needs no body to lower, and Kotlin's dispatch reaches each
 * concrete override. The base owns the scope and projects the member once; neither concrete class
 * re-projects it.
 *
 * Both cells build the generated C# with warnings as errors, so a CS0108 fails the cell.
 */
class Tier1SuspendOverrideChainTest {

  @Test
  fun `a dropped middle base does not re-project a member the kept base already projects`() {
    val result: Tier1Result = Tier1Harness.run(
      mapOf(
        "Skiff.kt" to """
          package tier1.chainhidden

          import tier1.chain.Vessel

          open class Skiff : Vessel() {
            override suspend fun launch(): String = "skiff launched"
          }
        """.trimIndent(),
        "Vessel.kt" to """
          package tier1.chain

          import tier1.chainhidden.Skiff

          open class Vessel {
            open suspend fun launch(): String = "vessel launched"
          }

          class Dinghy : Skiff() {
            override suspend fun launch(): String = "dinghy launched"
          }
        """.trimIndent(),
      ),
      processorOptions = mapOf("nuget.rootPackage" to "tier1.chain"),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(result.kspErrors.isEmpty(), "kspErrors=${result.kspErrors}")
    assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
    assertFalse("class Skiff" in result.generatedCSharp, "Skiff is outside the export root")
    assertContains(result.generatedCSharp, "public class Dinghy : Vessel")
    assertEquals(
      1,
      Regex("public (virtual |override )?Task<string> LaunchAsync[(]")
        .findAll(result.generatedCSharp).count(),
      "expected exactly one LaunchAsync in the kept chain, on Vessel; got: " +
          result.generatedCSharp.lines().filter { "LaunchAsync" in it }.map(String::trim),
    )
    assertContains(result.generated, "library_vessel_launch_async\")")
    assertFalse(
      "dinghy_launch_async" in result.generated,
      "expected no Kotlin export for Dinghy's override; got: " +
          result.generated.lines().filter { "dinghy" in it }.map(String::trim),
    )
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using System.Threading.Tasks;
      using Interop;
      class Consumer {
        static Task<string> Launch(Dinghy dinghy) => dinghy.LaunchAsync();
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }

  @Test
  fun `an abstract suspend fun with no body is projected once, on the abstract base`() {
    val result: Tier1Result = Tier1Harness.run(
      """
        package tier1.groomers

        abstract class Groomer {
          abstract suspend fun groom(cat: String): String
        }

        class MittGroomer : Groomer() {
          override suspend fun groom(cat: String): String = "${'$'}cat groomed with a mitt"
        }

        class CombGroomer : Groomer() {
          override suspend fun groom(cat: String): String = "${'$'}cat groomed with a comb"
        }
      """.trimIndent(),
      fileName = "Groomers.kt",
      processorOptions = mapOf("nuget.rootPackage" to "tier1"),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(result.kspErrors.isEmpty(), "kspErrors=${result.kspErrors}")
    assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
    val cs: String = result.generatedCSharp
    assertContains(cs, "public abstract class Groomer : IDisposable, IAsyncDisposable")
    assertEquals(
      1,
      Regex("Task<string> GroomAsync[(]").findAll(cs).count(),
      "expected exactly one GroomAsync, on Groomer; got: " +
          cs.lines().filter { "GroomAsync" in it }.map(String::trim),
    )
    // The base export dispatches through Kotlin, so it reaches each concrete body.
    assertContains(result.generated, "__groomer_groom_async\")")
    assertContains(result.generated, ".groom(")
    assertFalse("mittgroomer_groom_async" in result.generated, "no re-projection on MittGroomer")
    assertFalse("combgroomer_groom_async" in result.generated, "no re-projection on CombGroomer")
    assertFalse(
      Regex("class (Mitt|Comb)Groomer[^{]*IAsyncDisposable").containsMatchIn(cs),
      "expected the concrete classes to reuse the abstract owner's scope",
    )
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using System.Threading.Tasks;
      using Interop.Groomers;
      class Consumer {
        static Task<string> Mitt(MittGroomer groomer) => groomer.GroomAsync("Oreo");
        static Task<string> Comb(CombGroomer groomer) => groomer.GroomAsync("Mylo");
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }
}
