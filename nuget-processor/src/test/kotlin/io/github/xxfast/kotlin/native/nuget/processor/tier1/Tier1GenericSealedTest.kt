package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-199: a generic sealed class binds as `Outcome<T>` with its arms on the non-generic holder
 * `Outcome`. Before it, the hierarchy was a named skip on both halves: the Kotlin discriminator
 * read `asStableRef<Outcome>()` with no type argument, and the C# arm rendered `Ok(T v)` inside a
 * non-generic class (CS0246).
 *
 * A forwarding arm is generic over what it forwards (`Outcome.Ok<T>`), an arm that fixes the
 * variant argument carries a phantom (`Outcome.Err<T>`), and an arm-typed `Nothing` position takes
 * the shared contract's marker (`Outcome.Err<KotlinNothing>`). The discriminator reads star
 * projected (`asStableRef<Outcome<*>>()`), a closed parameter at its written type.
 */
class Tier1GenericSealedTest {

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      """
      package tier1.outcome

      sealed class Outcome<out T> {
        class Ok<T>(val v: T) : Outcome<T>()
        class Err(val e: String) : Outcome<Nothing>()
      }

      class Oracle {
        fun ask(): Outcome<Int> = Outcome.Ok(4)
        fun fail(): Outcome.Err = Outcome.Err("no")
        fun tell(outcome: Outcome<Int>): String = if (outcome is Outcome.Ok) "ok" else "err"
        fun name(): String = "Delphi"
      }
      """.trimIndent(),
      processorOptions = mapOf("nuget.rootPackage" to "tier1"),
    )
  }

  @Test
  fun `the hierarchy is declared on both halves and a consumer pattern matches its arms`() {
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    assertContains(result.generated, "asStableRef<tier1.outcome.Outcome<*>>()")
    assertContains(result.generated, "asStableRef<tier1.outcome.Outcome<kotlin.Int>>()")
    val cs: String = result.generatedCSharp
    assertContains(cs, "public static class Outcome")
    assertContains(cs, "public abstract class Outcome<T>")
    assertContains(cs, "public sealed class Ok<T> : Outcome<T>")
    assertContains(cs, "public sealed class Err<T> : Outcome<T>")

    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using Interop.Outcome;
      using Kotlin.Native.Interop;

      namespace Consumer
      {
          public static class Probe
          {
              public static string Run(Oracle oracle)
              {
                  using Outcome<int> asked = oracle.Ask();
                  using Outcome.Err<KotlinNothing> failed = oracle.Fail();
                  using var mine = new Outcome.Err<int>("mine");
                  string said = asked switch
                  {
                      Outcome.Ok<int> ok => ok.V.ToString(),
                      Outcome.Err<int> err => err.E,
                      _ => "",
                  };
                  return said + failed.E + oracle.Tell(mine) + oracle.Name();
              }
          }
      }
      """.trimIndent(),
    )
  }

  @Test
  fun `nothing about the hierarchy is skipped`() {
    val warnings: List<String> = result.kspWarnings
    assertFalse(
      warnings.any { warning -> "tier1.outcome" in warning },
      "no warning names the hierarchy or a member typed with it; kspWarnings=$warnings",
    )
  }
}
