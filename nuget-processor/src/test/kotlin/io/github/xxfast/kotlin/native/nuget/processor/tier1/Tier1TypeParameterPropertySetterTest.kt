package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-218: a `var` whose type is a class's own type parameter binds `{ get; set; }`. The C# setter
 * boxes the value with `Wrap<T>` and disposes only a box it minted; the Kotlin setter reads the box
 * back through the callable route's bounded read. A trampolined (ADR-198) or multi-bound `T` has
 * no erased argument the receiver could take the value at, so its setter assigns inside the
 * trampoline. An interface-owned `T` (`IHolder<T>`) declares the setter too.
 */
class Tier1TypeParameterPropertySetterTest {

  private val options: Map<String, String> = mapOf(
    "nuget.namespace" to "TestLibrary",
    "nuget.rootPackage" to "tier1",
  )

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      """
      package tier1.tpset

      interface Pet { val name: String }
      interface Trainable { fun train(): Int }

      class Shelf<T>(first: T) {
        var current: T = first
        var maybe: T? = null
        var items: List<T> = listOf(first)
        var tags: Map<String, T> = mapOf("first" to first)
      }

      class Kennel<T : Pet>(first: T) {
        var current: T = first
      }

      class Crate<T : Any>(first: T) {
        var current: T = first
        var maybe: T? = null
      }

      enum class Medal { BRONZE, SILVER, GOLD }

      class Rosette<T : Enum<T>>(first: T) {
        var current: T = first
        var runnerUp: T? = null
      }

      class Arena<T>(first: T) where T : Pet, T : Trainable {
        var champ: T = first
      }

      interface Holder<T> {
        var item: T
      }

      class Locket<T>(first: T) : Holder<T> {
        override var item: T = first
      }

      class Tabby(override val name: String) : Pet

      fun rosette(): Rosette<Medal> = Rosette(Medal.SILVER)
      """.trimIndent(),
      processorOptions = options,
    )
  }

  @Test
  fun `the generated Kotlin compiles and no setter is refused`() {
    assertTrue(result.kspSucceeded, "ksp: ${result.kspErrors}")
    assertTrue(result.compiledClean, "generated Kotlin: ${result.compileErrors}")
    assertFalse(
      result.kspWarnings.any { "binds read-only" in it },
      result.kspWarnings.joinToString("\n"),
    )
  }

  @Test
  fun `a bare T setter boxes in C# and disposes only an owned box`() {
    val csharp: String = result.generatedCSharp
    assertContains(csharp, "public T Current\n")
    assertContains(csharp, "valueBox = NugetMarshal.Wrap<T>(value!, out valueOwned);")
    assertContains(
      csharp,
      "if (valueOwned && valueBox != IntPtr.Zero) { NugetMarshal.Dispose(valueBox); } " +
          "if (!valueOwned) GC.KeepAlive(value);",
    )
    // ADR-198: under `struct`, `T?` is `Nullable<T>` and boxes as itself.
    assertContains(csharp, "valueBox = NugetMarshal.Wrap<T?>(value, out valueOwned);")
  }

  @Test
  fun `the Kotlin setter reads the box through the bound`() {
    val kotlin: String = result.generated
    assertContains(
      kotlin,
      "handle.asStableRef<tier1.tpset.Shelf<Any?>>().get().current = " +
          "value?.asStableRef<Any>()?.get()",
    )
    assertContains(
      kotlin,
      "handle.asStableRef<tier1.tpset.Kennel<tier1.tpset.Pet>>().get().current = " +
          "(value.asStableRef<Any>().get() as tier1.tpset.Pet)",
    )
    assertContains(
      kotlin,
      "handle.asStableRef<tier1.tpset.Crate<Any>>().get().current = value.asStableRef<Any>().get()",
    )
  }

  @Test
  fun `a trampolined or multi-bound T setter assigns inside the trampoline`() {
    val kotlin: String = result.generated
    assertContains(
      kotlin,
      "(handle.asStableRef<Any>().get() as tier1.tpset.Rosette<T>).current = " +
          "value.asStableRef<Any>().get().let { bounded -> " +
          "bounded as kotlin.Enum<*>; bounded as T }",
    )
    assertContains(
      kotlin,
      "(handle.asStableRef<Any>().get() as tier1.tpset.Rosette<T>).runnerUp = " +
          "value?.asStableRef<Any>()?.get().let { bounded -> " +
          "bounded as kotlin.Enum<*>?; bounded as T? }",
    )
    assertContains(
      kotlin,
      "(handle.asStableRef<Any>().get() as tier1.tpset.Arena<T>).champ = " +
          "value.asStableRef<Any>().get().let { bounded -> bounded as tier1.tpset.Pet; " +
          "bounded as tier1.tpset.Trainable; bounded as T }",
    )
    assertContains(kotlin, "nugetTrampoline<Nothing>()")
  }

  @Test
  fun `an interface-owned T declares its setter`() {
    val csharp: String = result.generatedCSharp
    val start: Int = csharp.indexOf("public interface IHolder<T>")
    assertTrue(start >= 0, csharp)
    val holder: String = csharp.substring(start, csharp.indexOf("\n    }", start))
    assertContains(holder, "T Item { get; set; }")
  }

  @Test
  fun `a consumer assigns every T setter`() {
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using TestLibrary.Tpset;

      public static class Probe
      {
          public static void Run(Tabby tabby)
          {
              using var shelf = new Shelf<int>(1);
              shelf.Current = 3;
              shelf.Items = new[] { 1 };
              using var names = new Shelf<string?>("Oreo");
              names.Current = null;
              names.Maybe = null;
              using var kennel = new Kennel<Tabby>(tabby);
              kennel.Current = tabby;
              using var crate = new Crate<string>("Oreo");
              crate.Current = "Mylo";
              crate.Maybe = null;
              using var rosette = Fixture.Rosette();
              rosette.Current = Medal.Gold;
              rosette.RunnerUp = null;
              using var locket = new Locket<string>("Oreo");
              locket.Item = "Mylo";
              IHolder<string> holder = locket;
              holder.Item = "Oreo";
          }
      }
      """.trimIndent(),
    )
  }
}
