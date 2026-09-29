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
  fun `an interface parameter binds and returns KotlinFunc of the projected interface`() {
    val cs: String = Tier1Harness.run(source).generatedCSharp
    val line: String = signature(cs, "PetSupplier")
    assertTrue(
      Regex("""KotlinFunc<(global::[\w.]+\.)?IPet> PetSupplier\((global::[\w.]+\.)?IPet pet\)""")
        .containsMatchIn(line),
      "got: $line",
    )
  }

  @Test
  fun `an exported class parameter binds`() {
    val cs: String = Tier1Harness.run(source).generatedCSharp
    val line: String = signature(cs, "CatSupplier")
    assertTrue(
      Regex("""KotlinFunc<(global::[\w.]+\.)?Cat> CatSupplier\((global::[\w.]+\.)?Cat cat\)""")
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
        """KotlinFunc<int> ListSupplier\((global::System\.Collections\.Generic\.)?""" +
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
    assertTrue("KotlinFunc<int> NullableSupplier(int? n)" in line, "got: $line")
  }

  @Test
  fun `an arity-1 lambda return with a value parameter binds`() {
    val cs: String = Tier1Harness.run(source).generatedCSharp
    val line: String = signature(cs, "Adder")
    assertTrue("KotlinFunc<int, int> Adder(int n)" in line, "got: $line")
  }

  @Test
  fun `the parameterless relay and the primitive-parameter greeter keep binding`() {
    val cs: String = Tier1Harness.run(source).generatedCSharp
    assertTrue(
      Regex("""KotlinFunc<(global::[\w.]+\.)?IPet, (global::[\w.]+\.)?IPet> PetRelay\(\)""")
        .containsMatchIn(signature(cs, "PetRelay")),
    )
    assertTrue("KotlinFunc<string, string> Greeter(string greeting)" in signature(cs, "Greeter"))
  }

  @Test
  fun `an enum parameter binds instead of failing the build`() {
    val result = Tier1Harness.run(enumSource)
    assertTrue(result.kspErrors.isEmpty(), "expected no KSP errors; got: ${result.kspErrors}")
    val line: String = signature(result.generatedCSharp, "MoodSupplier")
    assertTrue(
      Regex("""KotlinFunc<int> MoodSupplier\((global::[\w.]+\.)?Mood m\)""").containsMatchIn(line),
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
   * The issue #111 rule on the plan: a type argument C# cannot spell skips the function named,
   * naming the argument, with no half-emitted export on either side.
   */
  @Test
  fun `an unspellable type argument skips the function naming the argument`() {
    val result = Tier1Harness.run(
      """
      package tier1.lambdareturnlist

      fun treatBag(n: Int): () -> List<Int> = { List(n) { it } }
      """.trimIndent(),
    )
    val warning: String? = result.kspWarnings
      .firstOrNull { "SKIPPED_UNSUPPORTED_RETURN" in it && "treatBag" in it }
    assertTrue(warning != null, "expected a named skip; got: ${result.kspWarnings}")
    assertTrue("kotlin.collections.List" in warning, "got: $warning")
    assertTrue("TreatBag" !in result.generatedCSharp)
    assertTrue("treatBag" !in result.generated, "no orphan Kotlin export expected")
  }

  /**
   * Measured on main before the route moved: a sealed interface and a sealed class type argument
   * both bound (`KotlinFunc<global::Interop.Treat>`), read back through the ADR-009 `FromHandle`
   * factory. The plan route keeps that, with and without a value parameter.
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
      "public static KotlinFunc<global::Interop.Treat> TreatSupplier()" in cs,
      signature(cs, "TreatSupplier"),
    )
    assertTrue(
      "public static KotlinFunc<global::Interop.Toy> ToySupplier()" in cs,
      signature(cs, "ToySupplier"),
    )
    assertTrue(
      "public static KotlinFunc<global::Interop.Treat> KibbleFor(int grams)" in cs,
      signature(cs, "KibbleFor"),
    )
  }

  /**
   * Fold: a `suspend` lambda return used to pass the legacy generic-return gate, so the Kotlin
   * half exported it while the C# half had no arm for it: an orphan export, no C# method, no
   * diagnostic. The gate refuses it now, and the planner names the skip.
   */
  @Test
  fun `a suspend lambda return is a named skip with no orphan export`() {
    val result = Tier1Harness.run(
      """
      package tier1.lambdareturnsuspend

      fun napper(): suspend () -> Int = { 3 }

      fun napFor(minutes: Int): suspend () -> Int = { minutes }
      """.trimIndent(),
    )
    assertTrue(result.kspErrors.isEmpty(), "expected no KSP errors; got: ${result.kspErrors}")
    listOf("napper", "napFor").forEach { name ->
      assertTrue(
        result.kspWarnings.any { warning ->
          "SKIPPED_UNSUPPORTED_RETURN" in warning && "lambdareturnsuspend.$name" in warning
        },
        "expected a named skip for $name; got: ${result.kspWarnings}",
      )
      assertTrue("__$name(" !in result.generated, "orphan Kotlin export for $name")
    }
    assertTrue("Napper" !in result.generatedCSharp && "NapFor" !in result.generatedCSharp)
    // The sentence says where a suspend lambda DOES bind, not that it binds nowhere.
    val napper: String = result.kspWarnings.first { "lambdareturnsuspend.napper" in it }
    assertTrue(
      "a `suspend` lambda binds as a class property (`KotlinSuspendFunc<...>`), but not as a " +
          "parameter or as a function return" in napper,
      "got: $napper",
    )
    assertTrue("not bridged at any position" !in napper, "got: $napper")
  }
}
