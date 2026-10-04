package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The non-throwing `bool TryX(..., out T value, out Exception? failure)` twin beside ADR-108's
 * throwing `Result<T>` binding. One export carries both C# members: the Kotlin half writes a
 * `resultFailedOut` flag (zeroed on entry, set from `isFailure` before `getOrThrow()`), and the
 * Try reads it to tell a modelled `Result.failure` (returns `false`) from an exception the Kotlin
 * body threw (still throws).
 */
class Tier1ResultTryOverloadTest {

  /**
   * The ABI half: the flag slot sits directly before `errorOut`, every out slot is zeroed before
   * the `try`, the flag is written from the `Result` before it is unwrapped, and the C# Try shares
   * the throwing member's single extern.
   */
  @Test
  fun `a Result return carries the failed flag before the error slot and zeroes it on entry`() {
    val result = Tier1Harness.run(
      """
      package tier1.resulttryabi

      class Service {
        fun feed(name: String): Result<String> = Result.success(name)
        fun count(): Result<Int?> = Result.success(null)
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected the generated export to compile; got: ${result.compileErrors}",
    )
    val generated: String = result.generated.orEmpty()
    val feed: String = generated.exportBody("export_library_tier1_resulttryabi__service_feed")
    assertTrue(
      Regex("""resultFailedOut: COpaquePointer\?,\s*errorOut: COpaquePointer\?""") in feed,
      "expected resultFailedOut directly before errorOut; export=$feed",
    )
    val zeroFlag: Int =
      feed.indexOf("resultFailedOut.reinterpret<BooleanVar>().pointed.value = false")
    val zeroError: Int =
      feed.indexOf("errorOut.reinterpret<COpaquePointerVar>().pointed.value = null")
    val tryOpen: Int = feed.indexOf("try {")
    assertTrue(
      zeroFlag in 0 until tryOpen && zeroError in 0 until tryOpen,
      "expected every out slot zeroed before the try; export=$feed",
    )
    assertTrue(
      ".feed(name).also" in feed && "isFailure" in feed && ".getOrThrow()" in feed,
      "expected the flag written from the Result before getOrThrow; export=$feed",
    )
    val count: String = generated.exportBody("export_library_tier1_resulttryabi__service_count")
    assertTrue(
      "valueOut.reinterpret<IntVar>().pointed.value = 0" in count,
      "expected the nullable payload's valueOut zeroed on entry too; export=$count",
    )

    val csharp: String = result.generatedCSharp
    assertEquals(
      1,
      Regex("""static extern IntPtr Native_Feed\(""").findAll(csharp).count(),
      "expected the throwing member and its Try to share one extern; generatedCSharp=$csharp",
    )
    assertTrue(
      "[MarshalAs(UnmanagedType.I1)] out bool resultFailedOut, out IntPtr error)" in csharp,
      "expected the extern to declare the one-byte flag before the error slot; " +
          "generatedCSharp=$csharp",
    )
    assertTrue(
      "public string Feed(string name)" in csharp,
      "expected the throwing member unchanged; generatedCSharp=$csharp",
    )
    assertTrue(
      "public bool TryFeed(string name, $MAYBE_NULL out string value, $NOT_NULL out " +
          "global::System.Exception? failure)" in csharp,
      "expected the Try twin's signature; generatedCSharp=$csharp",
    )
    assertTrue(
      "public bool TryCount(out int? value, $NOT_NULL out global::System.Exception? failure)" in
          csharp,
      "expected a nullable payload to need no MaybeNullWhen; generatedCSharp=$csharp",
    )
    assertTrue(
      "if (!resultFailedOut) throw exception;" in csharp,
      "expected the Try to rethrow when Kotlin threw rather than returned a failure; " +
          "generatedCSharp=$csharp",
    )
  }

  /**
   * Every owner the throwing binding binds on gets its Try, and the result compiles as C# with
   * warnings as errors: class, override of an abstract and of an open member, interface (a
   * default interface method, so a C# implementer of `IFeeder` keeps compiling), object,
   * companion, sealed base and arm, top-level and extension. The consumer also proves the
   * nullable flow analysis the attributes buy, and the renamed out parameters.
   */
  @Test
  fun `every owner of a throwing Result binding gets a Try twin that compiles`() {
    val result = Tier1Harness.run(
      """
      package tier1.resulttryowners

      class Cat(val name: String)
      enum class Mood { HAPPY, SAD }

      interface Feeder {
        fun feed(name: String): Result<String>
        fun clean(): Result<Unit>
      }

      abstract class Base {
        abstract fun weigh(name: String): Result<Int>
        open fun nick(name: String): Result<String> = Result.success(name)
      }

      class Service : Base(), Feeder {
        override fun weigh(name: String): Result<Int> = Result.success(1)
        override fun nick(name: String): Result<String> = Result.success(name)
        override fun feed(name: String): Result<String> = Result.success(name)
        override fun clean(): Result<Unit> = Result.success(Unit)
        fun adopt(name: String): Result<Cat> = Result.success(Cat(name))
        fun run(): Result<Unit> = Result.success(Unit)
        fun count(): Result<Int?> = Result.success(null)
        fun mood(): Result<Mood> = Result.success(Mood.HAPPY)
        fun names(): Result<List<String>> = Result.success(listOf())
        fun tags(): Result<List<String>?> = Result.success(null)
        fun label(failure: String, exception: String): Result<String> =
          Result.success(failure + exception)
        companion object {
          fun make(): Result<Int> = Result.success(1)
        }
      }

      object Registry {
        fun lookup(name: String): Result<String> = Result.success(name)
      }

      sealed class Shape {
        abstract fun area(): Result<Double>
        class Square(val side: Double) : Shape() {
          override fun area(): Result<Double> = Result.success(side * side)
        }
      }

      fun adoptCat(cat: Cat): Result<Cat> = Result.success(cat)
      fun Cat.greet(): Result<String> = Result.success(name)
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected the generated exports to compile; got: ${result.compileErrors}",
    )
    val csharp: String = result.generatedCSharp
    listOf(
      "public abstract int Weigh(string name);",
      "public abstract bool TryWeigh(string name, out int value, $NOT_NULL out " +
          "global::System.Exception? failure);",
      "public override bool TryWeigh(string name, out int value",
      "public virtual bool TryNick(string name, $MAYBE_NULL out string value",
      "public override bool TryNick(string name, $MAYBE_NULL out string value",
      "public static bool TryMake(out int value",
      "public static bool TryLookup(string name, $MAYBE_NULL out string value",
      "public static bool TryGreet(this global::Interop.Cat receiver, $MAYBE_NULL out string value",
      "public bool TryLabel(string failure, string exception, $MAYBE_NULL out string value, " +
          "$NOT_NULL out global::System.Exception? failure_)",
    ).forEach { expected ->
      assertTrue(expected in csharp, "expected `$expected`; generatedCSharp=$csharp")
    }

    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using System;
      using System.Collections.Generic;
      using Interop;

      public sealed class Mine : IFeeder
      {
          public string Feed(string name) => name;
          public void Clean() { }
          public void Dispose() { }
      }

      public static class Consumer
      {
          public static int Use(Service service, IFeeder feeder, Base based, Shape shape, Cat cat)
          {
              int total = 0;
              if (service.TryFeed("a", out string? fed, out Exception? failure))
                  total += fed.Length;
              else total += failure.Message.Length;
              if (feeder.TryFeed("a", out string? viaInterface, out Exception? viaFailure))
                  total += viaInterface.Length;
              else total += viaFailure.Message.Length;
              IFeeder mine = new Mine();
              if (mine.TryFeed("a", out string? mined, out _)) total += mined.Length;
              if (!mine.TryClean(out Exception? cleanFailure)) total += cleanFailure.Message.Length;
              if (based.TryWeigh("a", out int weight, out _)) total += weight;
              if (based.TryNick("a", out string? nick, out _)) total += nick.Length;
              if (service.TryAdopt("a", out Cat? adopted, out _))
              {
                  using (adopted) total += adopted.Name.Length;
              }
              if (!service.TryRun(out Exception? runFailure)) total += runFailure.Message.Length;
              if (service.TryCount(out int? count, out _)) total += count ?? 0;
              if (service.TryMood(out Mood mood, out _)) total += (int)mood;
              if (service.TryNames(out IReadOnlyList<string>? names, out _)) total += names.Count;
              if (service.TryTags(out IReadOnlyList<string>? tags, out _))
                  total += tags?.Count ?? 0;
              if (service.TryLabel("x", "y", value: out string? label, failure_: out _))
                  total += label.Length;
              if (Service.TryMake(out int made, out _)) total += made;
              if (Registry.TryLookup("a", out string? found, out _)) total += found.Length;
              if (shape.TryArea(out double area, out _)) total += (int)area;
              if (Fixture.TryAdoptCat(cat, out Cat? again, out _)) total += again.Name.Length;
              if (cat.TryGreet(out string? greeting, out _)) total += greeting.Length;
              return total;
          }
      }
      """.trimIndent(),
    )
  }

  /**
   * A Try that would share its C# name with an authored property, or with its own type (CS0102,
   * CS0542), is dropped with a named warning; the throwing member still binds and the output
   * still compiles. The export keeps its throwing P/Invoke, so nothing is left unimported.
   */
  @Test
  fun `a Try twin that collides with an authored name is dropped with a named warning`() {
    val result = Tier1Harness.run(
      """
      package tier1.resulttrycollision

      class Service {
        val tryFeed: String = "x"
        fun feed(): Result<String> = Result.success("y")
      }

      class TryRun {
        fun run(): Result<Unit> = Result.success(Unit)
      }

      interface Pantry {
        val tryStock: String
        fun stock(): Result<String>
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected the generated exports to compile; got: ${result.compileErrors}",
    )
    val csharp: String = result.generatedCSharp
    assertTrue(
      "public string Feed()" in csharp && "public void Run()" in csharp,
      "expected the throwing members to survive; generatedCSharp=$csharp",
    )
    assertTrue(
      listOf("TryFeed", "TryRun", "TryStock").none { name -> Regex("""bool $name\(""") in csharp },
      "expected every colliding Try twin to be dropped; generatedCSharp=$csharp",
    )
    assertTrue(
      "string Stock();" in csharp,
      "expected the interface's throwing member to survive; generatedCSharp=$csharp",
    )
    listOf("TryFeed", "TryRun", "TryStock").forEach { name ->
      assertTrue(
        result.kspWarnings.any {
          it.contains(ForwardDiagnosticKind.SKIPPED_RESULT_TRY_COLLISION.name) && it.contains(name)
        },
        "expected a named warning for $name; kspWarnings=${result.kspWarnings}",
      )
    }
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using Interop;

      public static class Consumer
      {
          public static string Use(Service service) => service.TryFeed + service.Feed();
      }
      """.trimIndent(),
    )
  }

  private fun String.exportBody(name: String): String {
    val start: Int = indexOf("fun $name(")
    assertTrue(start >= 0, "expected export $name; generated=$this")
    val end: Int = indexOf("\n@CName", start).takeIf { it >= 0 } ?: length
    return substring(start, end)
  }

  private companion object {
    const val MAYBE_NULL: String = "[global::System.Diagnostics.CodeAnalysis.MaybeNullWhen(false)]"
    const val NOT_NULL: String = "[global::System.Diagnostics.CodeAnalysis.NotNullWhen(false)]"
  }
}
