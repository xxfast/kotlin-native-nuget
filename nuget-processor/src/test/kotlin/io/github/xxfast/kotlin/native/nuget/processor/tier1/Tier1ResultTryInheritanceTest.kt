package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `Result<T>` `TryX` twin's collision rule across a C# type's whole surface: what it inherits
 * (CS0108), what it nests and what other routes render into the same class (CS0102), and the
 * override chain a dropped twin belongs to (CS0115, CS0534). Each cell builds the generated C#
 * with warnings as errors, because every one of these used to be C# that does not compile.
 */
class Tier1ResultTryInheritanceTest {

  /** A base property or nested type named `TryX` is hidden by a derived twin: CS0108. */
  @Test
  fun `a twin named like an inherited property or nested type is dropped`() {
    val result = Tier1Harness.run(
      """
      package tier1.resulttryinherited

      open class Pantry {
        val tryStock: String = "flour"
      }

      class Larder : Pantry() {
        fun stock(): Result<String> = Result.success("sugar")
      }

      open class Kitchen {
        class TryCook(val dish: String)
      }

      class Chef : Kitchen() {
        fun cook(): Result<Int> = Result.success(1)
      }
      """.trimIndent()
    )

    assertCompilesWithout(result, listOf("TryStock", "TryCook"))
    assertWarned(result, listOf("Larder.TryStock", "Chef.TryCook"))
    assertTrue(
      "public string Stock()" in result.generatedCSharp &&
          "public int Cook()" in result.generatedCSharp,
      "expected the throwing members to survive; generatedCSharp=${result.generatedCSharp}",
    )
  }

  /**
   * A twin dropped anywhere in an override chain is dropped from the whole chain. `TryNick` on
   * the base is CS0542 (named like its class), which left the derived `override` twin with nothing
   * to override (CS0115); `TryWeigh` on the derived class collides with its own property, which
   * left the abstract base twin unimplemented (CS0534).
   */
  @Test
  fun `a twin dropped in an override chain is dropped from every member of the chain`() {
    val result = Tier1Harness.run(
      """
      package tier1.resulttrychain

      open class TryNick {
        open fun nick(): Result<String> = Result.success("Oreo")
      }

      class Nickname : TryNick() {
        override fun nick(): Result<String> = Result.success("Mylo")
      }

      abstract class Scale {
        abstract fun weigh(): Result<Int>
      }

      class Bathroom : Scale() {
        val tryWeigh: Int = 0
        override fun weigh(): Result<Int> = Result.success(4)
      }
      """.trimIndent()
    )

    assertCompilesWithout(result, listOf("TryNick", "TryWeigh"))
    assertWarned(
      result,
      listOf("TryNick.TryNick", "Nickname.TryNick", "Scale.TryWeigh", "Bathroom.TryWeigh"),
    )
    assertEquals(
      1,
      result.kspWarnings.count { it.contains("Nickname.TryNick") },
      "expected one warning per dropped twin; kspWarnings=${result.kspWarnings}",
    )
  }

  /**
   * The same type's own nested type is CS0102. An extension property of the same receiver in the
   * same `{Receiver}Extensions` class compiles beside the twin but makes every `cat.TryGreet`
   * property read CS9339 (ambiguous extension resolution, measured), so it drops the twin too. An
   * enum member property renders as a `TryDescribe(this Mood)` extension METHOD, which the twin
   * simply overloads: that one is kept.
   */
  @Test
  fun `a twin named like a nested type or a same-receiver extension property is dropped`() {
    val result = Tier1Harness.run(
      """
      package tier1.resulttrysameclass

      class Oven {
        class TryBake(val minutes: Int)
        fun bake(): Result<Int> = Result.success(20)
      }

      class Cat(val name: String)

      fun Cat.greet(): Result<String> = Result.success(name)

      val Cat.tryGreet: String get() = name

      enum class Mood {
        HAPPY;

        val tryDescribe: String get() = name
        fun describe(): Result<String> = Result.success(name)
      }
      """.trimIndent()
    )

    assertCompilesWithout(
      result,
      listOf("TryBake", "TryGreet"),
      consumer = """
        using Interop;

        public static class Consumer
        {
            public static int Use(Cat cat, Mood mood)
            {
                string greeting = cat.TryGreet;
                string described = mood.TryDescribe();
                if (mood.TryDescribe(out string? twin, out _)) return twin.Length;
                return greeting.Length + described.Length;
            }
        }
      """.trimIndent(),
    )
    assertWarned(result, listOf("Oven.TryBake", "CatExtensions.TryGreet"))
    assertTrue(
      result.kspWarnings.none { it.contains("TryDescribe") },
      "expected the enum twin kept as an overload; kspWarnings=${result.kspWarnings}",
    )
  }

  /**
   * Owners C# gives no legal twin, by construction rather than by collision: a sealed arm whose
   * covariant `Result<Ball>` override renders `new` over the base's `Result<Toy>` (its `out Ball`
   * twin would be an overload, and `new` on it CS0109). Nothing is reported: the twin is an
   * additive convenience over a member that still binds, not a Kotlin declaration absent from C#,
   * which is what ADR-064's named skips are for.
   *
   * A variant `I<out T>` still declares the twin of a member whose payload does not mention `T`;
   * only an `out T value` would be CS1961, and a `Result<T>` member of a generic interface does
   * not bind on the interface at all today.
   */
  @Test
  fun `a covariant sealed arm carries no twin and a variant interface keeps its legal ones`() {
    val result = Tier1Harness.run(
      """
      package tier1.resulttryvariance

      open class Toy(val name: String)
      class Ball : Toy("ball")

      sealed class Box {
        open fun open(): Result<Toy> = Result.success(Toy("yarn"))
        class Crate : Box() {
          override fun open(): Result<Ball> = Result.success(Ball())
        }
      }

      interface Source<out T> {
        fun count(): Result<Int>
        fun next(): Result<T>
      }

      class Counter : Source<Int> {
        override fun count(): Result<Int> = Result.success(1)
        override fun next(): Result<Int> = Result.success(2)
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected the generated exports to compile; got: ${result.compileErrors}",
    )
    val csharp: String = result.generatedCSharp
    assertTrue(
      "public new global::Interop.Ball Open()" in csharp,
      "expected the arm's covariant member to hide the base's; generatedCSharp=$csharp",
    )
    assertEquals(
      1,
      Regex("""bool TryOpen\(""").findAll(csharp).count(),
      "expected only the sealed base's twin, none on the `new` arm; generatedCSharp=$csharp",
    )
    assertTrue(
      Regex("""\n\s*bool TryCount\(out int value""") in csharp &&
          Regex("""\n\s*bool TryNext\(""") !in csharp,
      "expected the variant interface to keep the twin that does not mention T; " +
          "generatedCSharp=$csharp",
    )
    assertTrue(
      result.kspWarnings.none { it.contains("TryOpen") || it.contains("TryNext") },
      "expected no diagnostic for a twin C# cannot declare; kspWarnings=${result.kspWarnings}",
    )
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using Interop;

      public static class Consumer
      {
          public static int Use(Box box, Box.Crate crate, ISource<int> source, Counter counter)
          {
              int total = 0;
              if (box.TryOpen(out Toy? toy, out _)) total += toy.Name.Length;
              if (crate.TryOpen(out Toy? inherited, out _)) total += inherited.Name.Length;
              if (source.TryCount(out int count, out _)) total += count;
              if (counter.TryNext(out int next, out _)) total += next;
              return total + crate.Open().Name.Length;
          }
      }
      """.trimIndent(),
    )
  }

  /** The output compiles as C# with [consumer], and none of [names] is declared as a method. */
  private fun assertCompilesWithout(
    result: Tier1Result,
    names: List<String>,
    consumer: String = "public static class Consumer { }",
  ) {
    Tier1CSharpCompile.assertCompiles(result, consumer)
    assertTrue(
      result.compiledClean,
      "expected the generated exports to compile; got: ${result.compileErrors}",
    )
    val csharp: String = result.generatedCSharp
    names.forEach { name ->
      assertTrue(
        Regex("""bool $name\(""") !in csharp,
        "expected no `$name` twin; generatedCSharp=$csharp",
      )
    }
  }

  private fun assertWarned(result: Tier1Result, declarations: List<String>) {
    declarations.forEach { declaration ->
      assertTrue(
        result.kspWarnings.any {
          it.contains(ForwardDiagnosticKind.SKIPPED_RESULT_TRY_COLLISION.name) &&
              it.contains(declaration)
        },
        "expected a named warning for $declaration; kspWarnings=${result.kspWarnings}",
      )
    }
  }
}
