package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * ADR-233 amendment: a backticked interface member reaches a C# implementation under the name the
 * class route gives it. A keyword (`in`) needs no `@CSharpName` and is `In` on both routes; a name
 * with a space (`tug hard`) has no C# spelling on either route without one, so the class route
 * names a `NON_IDENTIFIER_NAME` skip and the bridge names the same member as the reason it cannot
 * be implemented. Kotlin/Native cannot link a symbol with a space, so no runtime fixture can carry
 * the second shape: this cell is its evidence.
 */
class Tier1BridgeBacktickedNameTest {

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      """
      package tier1.bridgebackticks

      interface Doorman {
        fun `in`(cat: String): String
      }

      fun knock(doorman: Doorman): String = doorman.`in`("Oreo")

      interface Rope {
        fun `tug hard`(): Int
      }

      fun tug(rope: Rope): Int = rope.`tug hard`()
      """.trimIndent(),
    )
  }

  @Test
  fun `a keyword member is In on the class route and on the bridge`() {
    assertTrue(result.kspErrors.isEmpty(), "kspErrors=${result.kspErrors}")
    assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
    assertContains(result.generated, "doorman_bridge_create")
    val cs: String = result.generatedCSharp
    assertContains(cs, "string In(string cat);")
    assertContains(cs, "impl.In(")
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      namespace Consumer
      {
          public sealed class Porter : global::Interop.IDoorman
          {
              public string In(string cat) => "in:" + cat;
              public void Dispose() { }
          }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }

  @Test
  fun `a spaced member without a declared name is skipped on both routes, named the same`() {
    val warnings: List<String> = result.kspWarnings
    val unimplementable: String = ForwardDiagnosticKind.SKIPPED_UNIMPLEMENTABLE_INTERFACE.name
    assertTrue(
      warnings.any { it.contains("Rope.tug hard") && it.contains("is not an identifier") },
      "expected the class route's NON_IDENTIFIER_NAME skip for `tug hard`; kspWarnings=$warnings",
    )
    assertTrue(
      warnings.any { it.contains(unimplementable) && it.contains("tug hard") },
      "expected the bridge to name `tug hard`; kspWarnings=$warnings",
    )
    assertTrue("rope_bridge_create" !in result.generated, result.generated)
  }
}
