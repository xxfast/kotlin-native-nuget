package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A generic sealed class broke both halves: the Kotlin discriminator read
 * `asStableRef<Outcome>()` with no type argument, and the C# arm rendered `Ok(T v)` inside a
 * non-generic class (CS0246). Its arms need not even share the base's type argument
 * (`Err : Outcome<Nothing>`), so no honest C# hierarchy exists for the sealed route to declare.
 *
 * The hierarchy is now a named skip: neither the base nor an arm is declared on either half, the
 * declaration says why, and a member typed with it skips on its own owner.
 */
class Tier1GenericSealedSkipTest {

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      """
      package tier1.outcome

      sealed class Outcome<T> {
        class Ok<T>(val v: T) : Outcome<T>()
        class Err(val e: String) : Outcome<Nothing>()
      }

      class Oracle {
        fun ask(): Outcome<Int> = Outcome.Ok(4)
        fun fail(): Outcome.Err = Outcome.Err("no")
        fun name(): String = "Delphi"
      }
      """.trimIndent(),
      processorOptions = mapOf("nuget.rootPackage" to "tier1"),
    )
  }

  @Test
  fun `the hierarchy is declared on neither half and both halves compile`() {
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    val cs: String = result.generatedCSharp
    val declarations = Regex("""(class|struct|interface) (Outcome|Ok|Err)\b""")
    assertFalse(declarations.containsMatchIn(cs), "no Outcome hierarchy in C#; generated=$cs")
    assertFalse("asStableRef<tier1.outcome.Outcome" in result.generated, result.generated)
    assertContains(cs, "public string Name()")

    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using Interop.Outcome;

      namespace Consumer
      {
          public static class Probe
          {
              public static string Run(Oracle oracle) => oracle.Name();
          }
      }
      """.trimIndent(),
    )
  }

  @Test
  fun `the declaration and every position typed with it are named`() {
    val warnings: List<String> = result.kspWarnings
    assertTrue(
      warnings.any { warning ->
        "SKIPPED_UNSUPPORTED_TYPE" in warning && "tier1.outcome.Outcome" in warning &&
            "type parameters" in warning && "`Err`, `Ok`" in warning
      },
      "the generic sealed base is named with its arms; kspWarnings=$warnings",
    )
    // Each position points at the generic-sealed reason, not at export scope or arm shapes.
    listOf("Oracle.ask", "Oracle.fail").forEach { member ->
      val warning: String? = warnings.singleOrNull { member in it }
      assertTrue(warning != null, "$member is named once; kspWarnings=$warnings")
      assertContains(warning, "belongs to a generic sealed hierarchy")
      assertFalse("export it from an included package" in warning, warning)
    }
  }
}
