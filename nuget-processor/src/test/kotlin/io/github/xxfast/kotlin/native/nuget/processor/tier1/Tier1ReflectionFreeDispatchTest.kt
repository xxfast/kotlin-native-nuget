package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-094: the generated C# dispatches erased generics without reflection. `INugetHandle` replaces
 * every `typeof(T).GetField("_handle", ...)` extraction and the generated `NugetMarshal.Factories`
 * registry replaces every `Activator.CreateInstance` materialisation, so the output works on
 * AOT-only runtimes (Mac Catalyst / iOS) where private-member reflection is not available.
 */
class Tier1ReflectionFreeDispatchTest {

  @Test
  fun `the handle interface and the factory registry are generated`() {
    val result = Tier1Harness.run(
      """
      package tier1.reflectionfree

      class Cat(val name: String)

      fun adopt(): Cat = Cat("Tom")
      """.trimIndent(),
      processorOptions = mapOf("nuget.namespace" to "Tier1"),
    )

    assertTrue(result.compiledClean, "expected the fixture to compile; got: ${result.compileErrors}")

    val cs: String = result.generatedCSharp
    assertContains(cs, "internal interface INugetHandle")
    assertContains(cs, "IntPtr Handle { get; }")
    assertContains(cs, "public class Cat : IDisposable, INugetHandle")
    assertContains(cs, "IntPtr INugetHandle.Handle => _handle;")
    assertContains(cs, "Dictionary<Type, Func<IntPtr, object>> Factories")
    assertContains(cs, "[typeof(global::Tier1.Cat)] = static handle => new global::Tier1.Cat(handle),")
    assertContains(cs, "internal static T Materialize<T>(IntPtr handle)")
  }

  @Test
  fun `no reflective dispatch survives anywhere in the generated bindings`() {
    val result = Tier1Harness.run(
      """
      package tier1.noreflection

      class Cat(val name: String)

      class Crate<T>(val value: T)

      fun <T> identity(value: T): T = value

      fun packInt(value: Int): Crate<Int> = Crate(value)

      fun describe(block: (Cat) -> String): String = block(Cat("Tom"))
      """.trimIndent(),
      processorOptions = mapOf("nuget.namespace" to "Tier1"),
    )

    assertTrue(result.compiledClean, "expected the fixture to compile; got: ${result.compileErrors}")

    val cs: String = result.generatedCSharp
    assertFalse(cs.contains("Activator.CreateInstance"), "no generated path may materialise reflectively")
    assertFalse(cs.contains("GetField(\"_handle\""), "no generated path may extract the handle reflectively")
  }

  @Test
  fun `a sealed subclass registers under its nested name`() {
    val result = Tier1Harness.run(
      """
      package tier1.reflectionfreesealed

      sealed class ApiResult {
        data class Success(val value: String) : ApiResult()
        data class Failure(val message: String) : ApiResult()
      }

      fun latest(): ApiResult = ApiResult.Success("ok")
      """.trimIndent(),
      processorOptions = mapOf("nuget.namespace" to "Tier1"),
    )

    assertTrue(result.compiledClean, "expected the sealed fixture to compile; got: ${result.compileErrors}")

    val cs: String = result.generatedCSharp
    assertContains(cs, "public abstract class ApiResult : IDisposable, INugetHandle")
    assertContains(
      cs,
      "[typeof(global::Tier1.ApiResult.Success)] = static handle => new global::Tier1.ApiResult.Success(handle),",
    )
    assertContains(
      cs,
      "[typeof(global::Tier1.ApiResult.Failure)] = static handle => new global::Tier1.ApiResult.Failure(handle),",
    )
    // Issue #40: the base is not constructible, but it IS the `T` an erased `StateFlow<Sealed>` or
    // `Flow<Sealed>` materialises, so it registers through its generated discriminator.
    assertContains(
      cs,
      "[typeof(global::Tier1.ApiResult)] = static handle => global::Tier1.ApiResult.FromHandle(handle),",
    )
  }

  @Test
  fun `enums, value classes and objects register no factory`() {
    val result = Tier1Harness.run(
      """
      package tier1.reflectionfreeplain

      enum class Mood { HAPPY, SAD }

      @JvmInline
      value class ChartId(val value: String)

      object Registry {
        fun size(): Int = 1
      }

      class Cat(val name: String)

      fun adopt(): Cat = Cat("Tom")
      """.trimIndent(),
      processorOptions = mapOf("nuget.namespace" to "Tier1"),
    )

    assertTrue(result.compiledClean, "expected the fixture to compile; got: ${result.compileErrors}")

    val cs: String = result.generatedCSharp
    assertContains(cs, "[typeof(global::Tier1.Cat)] = static handle => new global::Tier1.Cat(handle),")
    assertFalse(cs.contains("[typeof(global::Tier1.Mood)]"), "an enum has no handle constructor")
    assertFalse(cs.contains("[typeof(global::Tier1.ChartId)]"), "a value class round-trips as its underlying")
    assertFalse(cs.contains("[typeof(global::Tier1.Registry)]"), "an object is rendered without a handle")
  }
}
