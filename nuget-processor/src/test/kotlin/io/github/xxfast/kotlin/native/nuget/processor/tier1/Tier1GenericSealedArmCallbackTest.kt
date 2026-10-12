package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-116's callback routes on an arm of a GENERIC sealed hierarchy (ADR-199): the per-call lambda
 * route (ADR-036), the stored-callback pair (ADR-037) and the interface-bridge pair (ADR-039).
 * Each spelled the arm by its bare name, the defect `Tier1GenericSealedFlowPropertyTest` pins for
 * the flow route. Kotlin read `asStableRef<Dish.Hot>()` ("One type argument expected"), and the
 * two pair routes baked `nameof(Hot)` into the subscribe body's disposed guard, CS0305 inside
 * `Hot<T>`; that half keys on the C# arm, so the phantom `Bare<T>` broke there with Kotlin that
 * compiled.
 *
 * `each` takes a `Char` on purpose: `(Int) -> Unit` is planned (ADR-160) and never reaches the
 * hand-written per-call route.
 */
class Tier1GenericSealedArmCallbackTest {

  private val members: String = """
    fun each(cb: (Char) -> Unit) = cb('a')
    fun addBell(listener: (Int) -> Unit) {}
    fun removeBell(listener: (Int) -> Unit) {}
    fun addPurr(listener: Purr) {}
    fun removePurr(listener: Purr) {}
  """.trimIndent().prependIndent("          ")

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      """
      |package tier1.pantry
      |
      |interface Purr { fun onPurr(volume: Int) }
      |interface Pet { val name: String }
      |
      |sealed class Dish<T> {
      |  class Hot<T>(val item: T) : Dish<T>() {
      |$members
      |  }
      |
      |  // The control: an arm that fixes the argument is not generic on either half.
      |  class Cold : Dish<Int>() {
      |$members
      |  }
      |}
      |
      |sealed class Bowl<T : Pet> {
      |  class Full<T : Pet>(val pet: T) : Bowl<T>() {
      |$members
      |  }
      |}
      |
      |sealed class Plate<out T> {
      |  // Not generic in Kotlin, `Bare<T>` in C#: the fixed variant argument is a phantom.
      |  class Bare(val id: Int) : Plate<Nothing>() {
      |$members
      |  }
      |}
      """.trimMargin(),
      processorOptions = mapOf("nuget.rootPackage" to "tier1"),
    )
  }

  /** The export body of [entryPoint], from its `@CName` to the next one. */
  private fun export(entryPoint: String): String {
    val generated: String = result.generated
    val start: Int = generated.indexOf("@CName(\"library_pantry__$entryPoint\")")
    assertTrue(start >= 0, "expected an export named $entryPoint; got: $generated")
    val end: Int = generated.indexOf("@CName(", start + 1).let { if (it < 0) generated.length else it }
    return generated.substring(start, end)
  }

  @Test
  fun `the generated Kotlin compiles`() {
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
  }

  @Test
  fun `every callback export of a generic arm reads the erased receiver`() {
    val receivers: Map<String, String> = mapOf(
      "dish_hot" to "tier1.pantry.Dish.Hot<Any?>",
      "bowl_full" to "tier1.pantry.Bowl.Full<tier1.pantry.Pet>",
      // Neither has a type parameter in Kotlin, so each keeps the bare name it has.
      "plate_bare" to "tier1.pantry.Plate.Bare",
      "dish_cold" to "tier1.pantry.Dish.Cold",
    )
    receivers.forEach { (prefix, receiver) ->
      listOf("each", "addBell", "addPurr").forEach { member ->
        assertContains(
          export("${prefix}_$member"),
          "handle.asStableRef<$receiver>().get()",
          message = "the receiver of ${prefix}_$member",
        )
      }
    }
    listOf("Dish.Hot", "Bowl.Full").forEach { arm ->
      assertFalse(
        "asStableRef<tier1.pantry.$arm>()" in result.generated,
        "no export may spell the generic arm $arm bare",
      )
    }
  }

  @Test
  fun `the arm carries the non-generic arm's C# surface and a consumer uses it`() {
    val cs: String = result.generatedCSharp
    listOf(
      "public void Each(Action<char> cb)",
      "public IDisposable AddBell(Action<int> listener)",
      "public IDisposable AddPurr(global::Interop.Pantry.IPurr listener)",
    ).forEach { member ->
      val declared: Int = cs.lines().count { line -> line.trim() == member }
      assertTrue(declared == 4, "expected `$member` on all four arms; got $declared")
    }
    // The subscribe body's disposed guard names the arm as the arm is declared.
    listOf("nameof(Hot<T>)", "nameof(Full<T>)", "nameof(Bare<T>)", "nameof(Cold)")
      .forEach { guard -> assertContains(cs, guard) }
    listOf("nameof(Hot)", "nameof(Full)", "nameof(Bare)").forEach { guard ->
      assertFalse(guard in cs, "$guard does not bind inside the generic arm")
    }
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using System;
      using Interop.Pantry;
      using Interop;

      namespace Consumer
      {
          public sealed class Ear : IPurr
          {
              public int Heard;
              public void OnPurr(int volume) => Heard += volume;
              public void Dispose() { }
          }

          public static class Probe
          {
              public static int Run()
              {
                  int seen = 0;
                  var ear = new Ear();
                  using var hot = new Dish.Hot<string>("tuna");
                  hot.Each(letter => seen += letter);
                  using (IDisposable bell = hot.AddBell(volume => seen += volume))
                  using (IDisposable purr = hot.AddPurr(ear))
                  {
                  }
                  using var cold = new Dish.Cold();
                  cold.Each(letter => seen += letter);
                  using var bare = new Plate.Bare<string>(3);
                  bare.Each(letter => seen += letter);
                  using (IDisposable bell = bare.AddBell(volume => seen += volume))
                  using (IDisposable purr = bare.AddPurr(ear))
                  {
                  }
                  return seen + ear.Heard;
              }
          }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }

  @Test
  fun `nothing on the three routes is skipped`() {
    assertTrue(
      result.kspWarnings.none { "SKIPPED" in it },
      "every member here binds; kspWarnings=${result.kspWarnings}",
    )
    assertTrue(result.kspErrors.isEmpty(), "kspErrors=${result.kspErrors}")
  }
}
