package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-198: a type parameter whose bound has no closed Kotlin spelling. `T : Enum<T>` is invariant
 * and self-referencing, so no single type argument is within it (`Enum<Any?>` is not an
 * `Enum<Enum<Any?>>`) and the erased owner spelling every other generic export uses does not
 * compile. Such an export delegates to a local generic function that re-declares the bound and is
 * called at `Nothing`; inside it a box is read as `Any`, checked against each bound's
 * star-projected class, and cast to `T`.
 *
 * The C# half is a real constraint, `where T : struct, global::System.Enum`: only an enum can be
 * the type argument, and a generated enum crosses through its ADR-094 box.
 */
class Tier1EnumSelfBoundTest {

  private val options: Map<String, String> = mapOf(
    "nuget.namespace" to "TestLibrary",
    "nuget.rootPackage" to "tier1",
  )

  private val fixture: String =
    """
    package tier1.enumbound

    enum class Medal { BRONZE, SILVER, GOLD }

    class Pet(val name: String)

    class Podium<T : Enum<T>>(val value: T) {
      val runnerUp: T? = null
      fun outranks(other: T): Boolean = value > other
      fun pick(other: T?): T? = other ?: runnerUp
      fun label(): String = value.name
    }

    fun <T : Enum<T>> champion(value: T): T = value
    """.trimIndent()

  private fun droppedBounds(result: Tier1Result): List<String> =
    result.kspWarnings.filter { it.contains(ForwardDiagnosticKind.INFO_DROPPED_BOUND.name) }

  /** `(line, code)` of every C# compile error in a build log, once each. */
  private fun errors(log: String): Set<Pair<Int, String>> =
    Regex("""Consumer\.cs\((\d+),\d+\): error (CS\d+)""").findAll(log)
      .map { match -> match.groupValues[1].toInt() to match.groupValues[2] }
      .toSet()

  @Test
  fun `an Enum self-bound binds as struct Enum on both routes and the Kotlin compiles`() {
    val result: Tier1Result = Tier1Harness.run(fixture, processorOptions = options)

    assertTrue(result.kspSucceeded, "ksp: ${result.kspErrors}")
    assertTrue(result.compiledClean, "generated Kotlin: ${result.compileErrors}")
    assertContains(
      result.generatedCSharp,
      "public class Podium<T> : IDisposable, INugetHandle where T : struct, global::System.Enum",
    )
    assertContains(
      result.generatedCSharp,
      "public static T Champion<T>(T value) where T : struct, global::System.Enum",
    )
    assertEquals(0, droppedBounds(result).size, "${droppedBounds(result)}")
    // Under `struct`, `T?` is `Nullable<T>`: it boxes as itself, and a null result reads back as
    // null rather than `default(T)` (the first entry).
    assertContains(result.generatedCSharp, "NugetMarshal.Wrap<T?>(other, out")
    assertContains(result.generatedCSharp, "NugetMarshal.FromHandle<T?>(nativeResult)")

    // The trampoline: the export calls a local generic function at `Nothing`, which re-declares
    // the bound; a box is checked against the star-projected bound class, then cast to `T`.
    val kotlin: String = result.generated
    assertContains(kotlin, "where T : kotlin.Enum<T>")
    assertContains(kotlin, "nugetTrampoline<Nothing>()")
    assertContains(
      kotlin,
      "other.asStableRef<Any>().get().let { bounded -> bounded as kotlin.Enum<*>; bounded as T }",
    )
    assertContains(kotlin, "(handle.asStableRef<Any>().get() as tier1.enumbound.Podium<T>)")
    assertContains(kotlin, "tier1.enumbound.Podium<T>(")
    // The function route reads its argument the same way.
    assertContains(
      kotlin,
      "tier1.enumbound.champion(value.asStableRef<Any>().get().let { bounded -> " +
          "bounded as kotlin.Enum<*>; bounded as T })",
    )
    // A member that takes no `T` keeps the erased, star-projected read and no trampoline.
    assertContains(kotlin, "handle.asStableRef<tier1.enumbound.Podium<*>>().get().label()")
    // No unchecked-cast warning reaches the consumer's build: the casts to `T` and `Podium<T>`
    // sit under the trampoline's own suppression, the checks are star-projected.
    assertTrue(
      result.compileWarnings.none { it.contains("nchecked cast") },
      "${result.compileWarnings}",
    )

    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using TestLibrary.Enumbound;

      public static class Probe
      {
          public static string Run()
          {
              using var podium = new Podium<Medal>(Medal.Gold);
              bool beats = podium.Outranks(Medal.Silver);
              Medal? none = podium.Pick(null);
              Medal? some = podium.Pick(Medal.Bronze);
              Medal? runner = podium.RunnerUp;
              Medal champion = Fixture.Champion(Medal.Gold);
              return podium.Label() + beats + none + some + runner + champion + podium.Value;
          }
      }
      """.trimIndent(),
    )
  }

  @Test
  fun `the C# constraint rejects every type argument that is not an enum`() {
    val result: Tier1Result = Tier1Harness.run(fixture, processorOptions = options)
    assertTrue(result.compiledClean, "generated Kotlin: ${result.compileErrors}")

    val build: Tier1CSharpBuild = Tier1CSharpCompile.compile(
      result,
      """
      using TestLibrary.Enumbound;

      public static class Bad
      {
          public static object A() => new Podium<int>(1);
          public static object B() => new Podium<string>("Oreo");
          public static object C() => new Podium<Pet>(new Pet("Mylo"));
          public static object D() => new Podium<Medal?>(Medal.Gold);
          public static object E() => Fixture.Champion<int>(1);
      }
      """.trimIndent(),
    )

    assertFalse(build.succeeded, build.log)
    val errors: Set<Pair<Int, String>> = errors(build.log)
    // `int` is a value type with no boxing conversion to `System.Enum`; the other three are not
    // non-nullable value types at all.
    assertTrue(errors.contains(5 to "CS0315"), "$errors\n${build.log}")
    assertTrue(errors.contains(6 to "CS0453"), "$errors")
    assertTrue(errors.contains(7 to "CS0453"), "$errors")
    assertTrue(errors.contains(8 to "CS0453"), "$errors")
    assertTrue(errors.contains(9 to "CS0315"), "$errors")
  }

  /**
   * A user type named `Enum` in an enclosing namespace shadows `System.Enum`, which a `using
   * System;` directive only imports; the constraint is qualified, so it still names the BCL type.
   */
  @Test
  fun `the constraint names System Enum when a user type named Enum is in scope`() {
    val result: Tier1Result = Tier1Harness.run(
      mapOf(
        "Shadow.kt" to "package tier1\n\nclass Enum(val note: String)\n",
        "Fixture.kt" to fixture,
      ),
      processorOptions = options,
    )

    assertTrue(result.compiledClean, "generated Kotlin: ${result.compileErrors}")
    assertContains(result.generatedCSharp, "public class Enum")
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using TestLibrary.Enumbound;

      public static class Probe
      {
          public static Medal Run() => Fixture.Champion(Medal.Silver);
      }
      """.trimIndent(),
    )
  }

  /**
   * A `reified` type parameter cannot be supplied by the trampoline's `T` (it is not reified) nor
   * by `Nothing`, and an `enumValues<T>()` body needs exactly that. Named, never emitted.
   */
  @Test
  fun `a reified Enum-bound function is a named skip on both halves`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.enumreified

      enum class Medal { BRONZE, GOLD }

      inline fun <reified T : Enum<T>> next(value: T): T =
        enumValues<T>()[(value.ordinal + 1) % enumValues<T>().size]

      fun <T : Enum<T>> keep(value: T): T = value
      """.trimIndent(),
      processorOptions = options,
    )

    assertTrue(result.kspSucceeded, "ksp: ${result.kspErrors}")
    assertTrue(result.compiledClean, "generated Kotlin: ${result.compileErrors}")
    assertFalse(result.generated.contains("enumreified.next("), "the reified call was emitted")
    assertFalse(
      result.generatedCSharp.lines().any { it.contains("public static T Next<T>(") },
      "the C# half declared Next",
    )
    assertContains(result.generatedCSharp, "public static T Keep<T>(T value)")
    val skips: List<String> = result.kspWarnings.filter { warning ->
      warning.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_COMBINATION.name) &&
          warning.contains("next")
    }
    assertEquals(1, skips.size, "${result.kspWarnings}")
    assertContains(skips.single(), "reified")
  }

  /**
   * `struct` must lead a C# constraint list (CS0449), whichever order the Kotlin bounds were
   * declared in, and an interface bound beside the enum one stays.
   */
  @Test
  fun `the enum constraint leads the list beside an interface bound`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.enumorder

      interface Pet { fun purr(): String }

      enum class Cat : Pet {
        OREO, MYLO;

        override fun purr(): String = name
      }

      class Basket<T>(val value: T) where T : Pet, T : Enum<T> {
        fun swap(other: T): T = other
      }

      fun <T> pick(value: T): T where T : Pet, T : Enum<T> = value
      """.trimIndent(),
      processorOptions = options,
    )

    assertTrue(result.kspSucceeded, "ksp: ${result.kspErrors}")
    assertTrue(result.compiledClean, "generated Kotlin: ${result.compileErrors}")
    assertContains(
      result.generatedCSharp,
      "public class Basket<T> : IDisposable, INugetHandle where T : " +
          "struct, global::System.Enum, global::TestLibrary.Enumorder.IPet",
    )
    assertContains(
      result.generatedCSharp,
      "public static T Pick<T>(T value) where T : " +
          "struct, global::System.Enum, global::TestLibrary.Enumorder.IPet",
    )
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using TestLibrary.Enumorder;

      public static class Probe
      {
          public static T Run<T>(T value) where T : struct, System.Enum, IPet
          {
              using var basket = new Basket<T>(value);
              return Fixture.Pick(basket.Swap(value));
          }
      }
      """.trimIndent(),
    )
  }

  /**
   * ADR-198 decision 4: a `suspend` or `Flow` member on such an owner takes the generic-class
   * route's existing named refusal (ADR-147), and the rest of the owner still compiles.
   */
  @Test
  fun `suspend and Flow members on an enum-bound owner keep the generic-owner refusal`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.enumasync

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.flowOf

      class Podium<T : Enum<T>>(val value: T) {
        suspend fun settle(other: T): T = other
        fun replay(): Flow<T> = flowOf(value)
        fun outranks(other: T): Boolean = value > other
      }
      """.trimIndent(),
      processorOptions = options,
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(result.kspSucceeded, "ksp: ${result.kspErrors}")
    assertTrue(result.compiledClean, "generated Kotlin: ${result.compileErrors}")
    assertTrue(
      result.kspWarnings.any { it.contains("settle") && it.contains("SKIPPED_") },
      "${result.kspWarnings}",
    )
    assertTrue(
      result.kspWarnings.any { it.contains("replay") && it.contains("SKIPPED_") },
      "${result.kspWarnings}",
    )
    assertFalse(result.generatedCSharp.lines().any { it.contains(" SettleAsync(") })
    assertContains(result.generatedCSharp, "public bool Outranks(T other)")
  }

  /**
   * A data class under the bound: `copy` takes a `T`, so it rides the trampoline; equals,
   * hashCode and toString take none and keep the star-projected receiver.
   */
  @Test
  fun `a data class under the bound copies through the trampoline`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.enumdata

      enum class Medal { BRONZE, GOLD }

      data class Award<T : Enum<T>>(val medal: T, val note: String)
      """.trimIndent(),
      processorOptions = options,
    )

    assertTrue(result.kspSucceeded, "ksp: ${result.kspErrors}")
    assertTrue(result.compiledClean, "generated Kotlin: ${result.compileErrors}")
    assertContains(result.generated, "as tier1.enumdata.Award<T>).copy(")
    assertContains(
      result.generated,
      "handle.asStableRef<tier1.enumdata.Award<*>>().get().hashCode()",
    )
  }
}
