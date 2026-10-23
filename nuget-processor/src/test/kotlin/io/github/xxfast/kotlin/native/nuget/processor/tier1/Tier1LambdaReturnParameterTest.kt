package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ROADMAP line 51, ADR-160 amendment: a top-level function that takes ordinary value parameters
 * and returns a lambda binds as `KotlinFunc<...>` for every parameter type an ordinary top-level
 * function accepts. Before the fix the legacy generic-return gate refused interface, class and
 * collection parameters (`SKIPPED_UNSUPPORTED_RETURN`, with a sentence about the lambda), failed
 * the build on an enum parameter, and narrowed a nullable primitive to its non-null form.
 *
 * Oreo hands Rex to the supplier; the supplier hands Rex back.
 */
class Tier1LambdaReturnParameterTest {

  private val source: String =
    """
    package tier1.lambdareturn

    interface Pet {
      val name: String
    }

    class Cat(override val name: String) : Pet

    fun petRelay(): (Pet) -> Pet = { it }

    fun petSupplier(pet: Pet): () -> Pet = { pet }

    fun catSupplier(cat: Cat): () -> Cat = { cat }

    fun listSupplier(xs: List<Int>): () -> Int = { xs.sum() }

    fun nullableSupplier(n: Int?): () -> Int = { n ?: -1 }

    fun adder(n: Int): (Int) -> Int = { it + n }

    fun greeter(greeting: String): (String) -> String = { "${'$'}greeting, ${'$'}it" }
    """.trimIndent()

  // Kept in its own run: today this shape is a KSP ERROR, which would mask every other assertion.
  private val enumSource: String =
    """
    package tier1.lambdareturnenum

    enum class Mood { HAPPY, SLEEPY, GRUMPY }

    fun moodSupplier(m: Mood): () -> Int = { m.ordinal }
    """.trimIndent()

  private val names: List<String> =
    listOf("petSupplier", "catSupplier", "listSupplier", "nullableSupplier", "adder")

  private fun signature(cs: String, name: String): String =
    cs.lines().firstOrNull { line -> "public static" in line && " $name(" in line }
      ?: error("no public static $name( in the generated C#")

  @Test
  fun `no lambda return with a value parameter is skipped`() {
    val result = Tier1Harness.run(source)
    assertTrue(result.kspErrors.isEmpty(), "expected no KSP errors; got: ${result.kspErrors}")
    names.forEach { name ->
      val warnings: List<String> = result.kspWarnings.filter { "lambdareturn.$name" in it }
      assertTrue(warnings.isEmpty(), "$name must bind, not be skipped; got: $warnings")
    }
    assertTrue(
      result.kspWarnings.none { "SKIPPED_UNSUPPORTED_RETURN" in it },
      "no SKIPPED_UNSUPPORTED_RETURN expected; got: ${result.kspWarnings}",
    )
  }

  @Test
  fun `an interface parameter binds and returns a Func of the projected interface`() {
    val cs: String = Tier1Harness.run(source).generatedCSharp
    val line: String = signature(cs, "PetSupplier")
    assertTrue(
      Regex("""Func<(global::[\w.]+\.)?IPet> PetSupplier\((global::[\w.]+\.)?IPet pet\)""")
        .containsMatchIn(line),
      "got: $line",
    )
  }

  @Test
  fun `an exported class parameter binds`() {
    val cs: String = Tier1Harness.run(source).generatedCSharp
    val line: String = signature(cs, "CatSupplier")
    assertTrue(
      Regex("""Func<(global::[\w.]+\.)?Cat> CatSupplier\((global::[\w.]+\.)?Cat cat\)""")
        .containsMatchIn(line),
      "got: $line",
    )
  }

  @Test
  fun `a collection parameter binds`() {
    val cs: String = Tier1Harness.run(source).generatedCSharp
    val line: String = signature(cs, "ListSupplier")
    assertTrue(
      Regex(
        """Func<int> ListSupplier\((global::System\.Collections\.Generic\.)?""" +
            """IReadOnlyList<int> xs\)""",
      )
        .containsMatchIn(line),
      "got: $line",
    )
  }

  @Test
  fun `a nullable primitive parameter keeps its null`() {
    val cs: String = Tier1Harness.run(source).generatedCSharp
    val line: String = signature(cs, "NullableSupplier")
    assertTrue("Func<int> NullableSupplier(int? n)" in line, "got: $line")
  }

  @Test
  fun `an arity-1 lambda return with a value parameter binds`() {
    val cs: String = Tier1Harness.run(source).generatedCSharp
    val line: String = signature(cs, "Adder")
    assertTrue("Func<int, int> Adder(int n)" in line, "got: $line")
  }

  @Test
  fun `the parameterless relay and the primitive-parameter greeter keep binding`() {
    val cs: String = Tier1Harness.run(source).generatedCSharp
    assertTrue(
      Regex("""Func<(global::[\w.]+\.)?IPet, (global::[\w.]+\.)?IPet> PetRelay\(\)""")
        .containsMatchIn(signature(cs, "PetRelay")),
    )
    assertTrue("Func<string, string> Greeter(string greeting)" in signature(cs, "Greeter"))
  }

  @Test
  fun `an enum parameter binds instead of failing the build`() {
    val result = Tier1Harness.run(enumSource)
    assertTrue(result.kspErrors.isEmpty(), "expected no KSP errors; got: ${result.kspErrors}")
    val line: String = signature(result.generatedCSharp, "MoodSupplier")
    assertTrue(
      Regex("""Func<int> MoodSupplier\((global::[\w.]+\.)?Mood m\)""").containsMatchIn(line),
      "got: $line",
    )
  }

  /**
   * The Kotlin half: one owned handle minted through the generated `NugetHandles.retain`, the same
   * mint every other plan result uses, never a bare `StableRef.create`.
   */
  @Test
  fun `the returned lambda crosses as one retained handle`() {
    val result = Tier1Harness.run(source)
    assertTrue(
      result.compiledClean,
      "expected clean generated Kotlin; got: ${result.compileErrors}",
    )
    val export: String = result.generated.lines()
      .dropWhile { line -> "__petSupplier(" !in line }
      .take(4)
      .joinToString("\n")
    assertTrue("NugetHandles.retain(" in export, "got: $export")
    assertTrue("StableRef.create(" !in result.generated, "a bare StableRef.create leaked in")
  }

  /**
   * ADR-239: a collection type argument is in the erased vocabulary, so `() -> List<Int>` binds as
   * `Func<IReadOnlyList<int>>` (the list read through `NugetMarshal.FromHandle<T>`'s collection
   * table) and is named nowhere.
   */
  @Test
  fun `a collection type argument binds`() {
    val result = Tier1Harness.run(
      """
      package tier1.lambdareturnlist

      fun treatBag(n: Int): () -> List<Int> = { List(n) { it } }
      """.trimIndent(),
    )
    assertTrue(result.kspWarnings.none { "treatBag" in it }, "got: ${result.kspWarnings}")
    assertTrue(
      Regex("""Func<IReadOnlyList<int>> TreatBag\(int n\)""")
        .containsMatchIn(result.generatedCSharp),
      "got: ${signature(result.generatedCSharp, "TreatBag")}",
    )
  }

  /**
   * Measured on main before the route moved: a sealed interface and a sealed class type argument
   * both bound (`KotlinFunc<global::Interop.Treat>`, `Func` since ADR-239), read back through the
   * ADR-009 `FromHandle` factory. The plan route keeps that, with and without a value parameter.
   */
  @Test
  fun `a sealed type argument keeps binding`() {
    val result = Tier1Harness.run(
      """
      package tier1.lambdareturnsealed

      sealed interface Treat {
        class Kibble(val grams: Int) : Treat
        object Tuna : Treat
      }

      sealed class Toy {
        class Mouse(val squeaks: Int) : Toy()
      }

      fun treatSupplier(): () -> Treat = { Treat.Tuna }

      fun toySupplier(): () -> Toy = { Toy.Mouse(3) }

      fun kibbleFor(grams: Int): () -> Treat = { Treat.Kibble(grams) }
      """.trimIndent(),
    )
    assertTrue(result.compiledClean, "got: ${result.compileErrors}")
    assertTrue(
      result.kspWarnings.none { "lambdareturnsealed" in it },
      "expected no skips; got: ${result.kspWarnings}",
    )
    val cs: String = result.generatedCSharp
    assertTrue(
      "public static Func<global::Interop.Treat> TreatSupplier()" in cs,
      signature(cs, "TreatSupplier"),
    )
    assertTrue(
      "public static Func<global::Interop.Toy> ToySupplier()" in cs,
      signature(cs, "ToySupplier"),
    )
    assertTrue(
      "public static Func<global::Interop.Treat> KibbleFor(int grams)" in cs,
      signature(cs, "KibbleFor"),
    )
  }

  /**
   * ADR-239: a `suspend` lambda return binds as the ADR-210 delegate shape
   * (`Func<CancellationToken, Task<int>>`) on both halves, with no orphan export.
   */
  @Test
  fun `a suspend lambda return binds as an async delegate`() {
    val result = Tier1Harness.run(
      """
      package tier1.lambdareturnsuspend

      fun napper(): suspend () -> Int = { 3 }

      fun napFor(minutes: Int): suspend () -> Int = { minutes }
      """.trimIndent(),
    )
    assertTrue(result.kspErrors.isEmpty(), "expected no KSP errors; got: ${result.kspErrors}")
    assertTrue(result.compiledClean, "got: ${result.compileErrors}")
    val warnings: List<String> = result.kspWarnings
    assertTrue(warnings.none { "lambdareturnsuspend" in it }, "got: $warnings")
    val cs: String = result.generatedCSharp
    val task: String = "Func<global::System.Threading.CancellationToken, " +
        "global::System.Threading.Tasks.Task<int>>"
    assertTrue("$task Napper()" in cs, "got: ${signature(cs, "Napper")}")
    assertTrue(
      "$task NapFor(int minutes)" in cs,
      "got: ${signature(cs, "NapFor")}",
    )
  }
}
