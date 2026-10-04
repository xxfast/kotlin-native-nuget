package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * ADR-199's inferred claims, each run: a non-arm generic subclass of a generic arm (claim 2), an
 * intermediate generic sealed arm and an ADR-157 enum arm (claim 3), and a closed instantiation
 * inside a `Flow` and a lambda (claim 4). Then the three named skips, each a C# language gap, and
 * the arm named like its base (CS0542 on the holder).
 */
class Tier1GenericSealedShapesTest {

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      """
      package tier1.shapes

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.flowOf

      sealed class Outcome<out T> {
        class Ok<T>(val value: T) : Outcome<T>()
        abstract class Pending<T> : Outcome<T>() { abstract fun eta(): Int }
        sealed class Fault<out T> : Outcome<T>() {
          class Timeout(val seconds: Int) : Fault<Nothing>()
          class Refused<T>(val offer: T) : Fault<T>()
        }
        sealed class Muddle : Outcome<Nothing>() {
          class Puddle(val depth: Int) : Muddle()
          data object Dry : Muddle()
        }
      }

      class Later<T>(val item: T) : Outcome.Pending<T>() { override fun eta(): Int = 3 }

      sealed interface Reply<out T>
      class Trill<T>(val pitch: T) : Reply<T>
      enum class Hiss : Reply<Nothing> { SOFT, LOUD }

      sealed class Box<T> {
        class Full<T>(val item: T) : Box<T>()
        sealed class Odd : Box<Int>() {
          class One(val n: Int) : Odd()
        }
      }

      interface Pet { val name: String }
      sealed class Bed<out T : Pet> {
        data object Empty : Bed<Nothing>()
      }

      class Desk {
        fun later(): Outcome<Int> = Later(7)
        fun timeout(): Outcome<Int> = Outcome.Fault.Timeout(30)
        fun refused(): Outcome.Fault<String> = Outcome.Fault.Refused("kibble")
        fun hiss(): Reply<Int> = Hiss.LOUD
        fun stream(): Flow<Outcome<Int>> = flowOf(Outcome.Ok(1))
        fun each(block: (Outcome<Int>) -> String): String = block(Outcome.Ok(2))
        val pick: () -> Outcome<Int> = { Outcome.Ok(3) }
        fun any(outcome: Outcome<*>): Int = 0
        fun batch(): Outcome<List<Int>> = Outcome.Ok(listOf(1))
        fun empty(): Bed.Empty = Bed.Empty
        fun muddle(): Outcome<Int> = Outcome.Muddle.Puddle(1)
        fun dry(): Outcome<String> = Outcome.Muddle.Dry
        fun odd(): Box<Int> = Box.Odd.One(2)
        fun peek(box: Box<Int>): Int = if (box is Box.Odd.One) box.n else 0
        fun name(): String = "Desk"
      }
      """.trimIndent(),
      processorOptions = mapOf("nuget.rootPackage" to "tier1"),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
  }

  @Test
  fun `every inferred shape binds and a consumer uses it`() {
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using System.Collections.Generic;
      using System.Threading.Tasks;
      using Interop.Shapes;
      using Interop;
      using Kotlin.Native.Interop;

      namespace Consumer
      {
          public static class Probe
          {
              public static async Task<string> Run(Desk desk)
              {
                  using var later = new Later<string>("tuna");
                  Outcome.Pending<string> pending = later;
                  using Outcome<int> waiting = desk.Later();
                  using Outcome<int> timeout = desk.Timeout();
                  using Outcome.Fault<string> refused = desk.Refused();
                  using Reply<int> hiss = desk.Hiss();
                  string said = timeout switch
                  {
                      Outcome.Fault.Timeout<int> t => t.Seconds.ToString(),
                      Outcome.Pending<int> p => p.Eta().ToString(),
                      _ => "",
                  };
                  await foreach (Outcome<int> item in desk.Stream())
                  {
                      said += item is Outcome.Ok<int>;
                  }
                  said += desk.Each(o => o is Outcome.Ok<int> ok ? ok.Value.ToString() : "");
                  said += hiss is HissArm<int>;
                  using KotlinFunc<Outcome<int>> pick = desk.Pick;
                  using Outcome<int> picked = pick.Invoke();
                  said += picked is Outcome.Ok<int>;
                  using Outcome<int> muddle = desk.Muddle();
                  said += muddle is Outcome.Muddle.Puddle<int> { Depth: 1 };
                  Outcome.Muddle<int> lapse = (Outcome.Muddle<int>)muddle;
                  using Outcome<string> dry = desk.Dry();
                  said += dry is Outcome.Muddle.Dry<string>;
                  using Box<int> odd = desk.Odd();
                  Box.Odd plain = (Box.Odd)odd;
                  said += plain is Box.Odd.One { N: 2 };
                  using var mine = new Box.Odd.One(4);
                  said += desk.Peek(mine);
                  said += refused is Outcome.Fault.Refused<string> r ? r.Offer : "";
                  return said + pending.Eta() + desk.Name();
              }
          }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }

  @Test
  fun `each use site C# cannot spell is a named sealed-position skip`() {
    val skips: Map<String, String> = mapOf(
      "Desk.any" to "C# has no projection of a generic class",
      "Desk.batch" to "the erased wire cannot read its type argument",
      "Desk.empty" to "`KotlinNothing` does not satisfy `where T :",
    )
    skips.forEach { (member, why) ->
      val warning: String? = result.kspWarnings.singleOrNull { member in it }
      assertTrue(warning != null, "$member is named once; kspWarnings=${result.kspWarnings}")
      assertContains(warning, "SKIPPED_SEALED_POSITION")
      assertContains(warning, why)
    }
  }

  @Test
  fun `an intermediate arm that fixes or closes its parent's argument binds with its arms`() {
    val cs: String = result.generatedCSharp
    // Variant `T` fixed to `Nothing`: the phantom carries down to the intermediate's own arms.
    assertContains(cs, "public abstract class Muddle<T> : Outcome<T>")
    assertContains(cs, "public sealed class Puddle<T> : Outcome.Muddle<T>")
    // Invariant `T` closed over `Int`: ADR-009's non-generic route, under the holder.
    assertContains(cs, "public abstract class Odd : Box<int>")
    assertTrue(
      result.kspWarnings.none { "Muddle" in it || "Box.Odd" in it },
      "nothing about either intermediate is skipped; kspWarnings=${result.kspWarnings}",
    )
  }

  @Test
  fun `an arm named like its base collides with the holder`() {
    val collision: Tier1Result = Tier1Harness.run(
      """
      package tier1.collide

      sealed class Outcome<out T> {
        class Outcome<T>(val value: T) : tier1.collide.Outcome<T>()
      }

      class Desk {
        fun name(): String = "Desk"
      }
      """.trimIndent(),
      processorOptions = mapOf("nuget.rootPackage" to "tier1"),
    )
    assertTrue(
      collision.kspErrors.any { error ->
        "ERROR_CSHARP_NAME_COLLISION" in error && "tier1.collide.Outcome.Outcome" in error &&
            "CS0542" in error
      },
      "expected the arm's CS0542 collision; kspErrors=${collision.kspErrors}",
    )
  }
}
