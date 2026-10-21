package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-216: a function's own type parameter inside an exported generic class (`Box<T>`, a generic
 * sealed `Outcome<T>`) at a parameter or a return, and inside any `Result` payload the same gate
 * admits (`List<T>`, `Map<K, V>`, `List<List<T>>`, `Box<T>`). C# spells each one exactly as the
 * class-`T` route already does, and the Kotlin read reinterprets the handle at the argument's
 * erased bound (ADR-208).
 *
 * Still refused by name: a `Box<List<T>>` the erased wire cannot read (ADR-208's closed-argument
 * rule). A multi-bound `T` witnessed only inside a generic class binds through ADR-217.
 *
 * Oreo goes in the box; Mylo is the one who knocks it over.
 */
class Tier1OwnTypeParameterInGenericClassTest {

  private val options: Map<String, String> = mapOf(
    "nuget.namespace" to "TestLibrary",
    "nuget.rootPackage" to "tier1",
  )

  private val source: String = """
    package tier1.boxedown

    interface Furry { val name: String }
    interface Tricky { val tricks: Int }

    class Cat(override val name: String, override val tricks: Int) : Furry, Tricky

    class Box<T>(val item: T) {
      fun rebox(): Box<Box<T>> = Box(this)
    }

    class Pair2<A, B>(val first: A, val second: B)

    sealed class Outcome<out T> {
      data class Done<T>(val value: T) : Outcome<T>()
      data class Failed(val reason: String) : Outcome<Nothing>()
    }

    class Depot {
      fun <T> wrap(value: T): Box<T> = Box(value)
      fun <T> unwrap(box: Box<T>): T = box.item
      fun <T : Furry> groom(box: Box<T>): T = box.item
      fun <T> maybe(box: Box<T>?): Box<T>? = box
      fun <T> nest(box: Box<Box<T>>): Box<T> = box.item
      fun <T> boxes(list: List<Box<T>>): List<Box<T>> = list
      fun <T> listed(list: List<T>): Result<List<T>> = Result.success(list)
      fun <K : Any, V> tally(map: Map<K, V>): Result<Map<K, V>> = Result.success(map)
      fun <T> nested(list: List<List<T>>): Result<List<List<T>>> = Result.success(list)
      fun <T> boxedResult(value: T): Result<Box<T>> = Result.success(Box(value))
      fun <T> settle(value: T): Outcome<T> = Outcome.Done(value)
      fun <T> pairUp(value: T): Pair2<T, String> = Pair2(value, "Oreo")
      fun <T : Any> opt(box: Box<T?>): T? = box.item
      fun <T> rank(box: Box<T>): T where T : Furry, T : Tricky = box.item
      fun <T> deep(box: Box<List<T>>): Int = box.item.size
    }

    fun <T> unwrapTop(box: Box<T>): T = box.item
  """.trimIndent()

  private fun run(): Tier1Result =
    Tier1Harness.run(source, fileName = "Boxes.kt", processorOptions = options)

  private fun assertKotlinCompiles(result: Tier1Result) {
    assertTrue(result.kspSucceeded, "ksp: ${result.kspErrors}")
    assertTrue(result.compiledClean, "generated Kotlin: ${result.compileErrors}")
  }

  @Test
  fun `a member's own T binds inside a generic class and a Result payload`() {
    val result: Tier1Result = run()
    assertKotlinCompiles(result)

    val ns = "global::TestLibrary.Boxedown"
    listOf(
      "public $ns.Box<T> Wrap<T>(T value)",
      "public T Unwrap<T>($ns.Box<T> box)",
      "public T Groom<T>($ns.Box<T> box) where T : $ns.IFurry",
      "public $ns.Box<T>? Maybe<T>($ns.Box<T>? box)",
      "public $ns.Box<T> Nest<T>($ns.Box<$ns.Box<T>> box)",
      "public IReadOnlyList<$ns.Box<T>> Boxes<T>(IReadOnlyList<$ns.Box<T>> list)",
      "public IReadOnlyList<T> Listed<T>(IReadOnlyList<T> list)",
      "public bool TryListed<T>(IReadOnlyList<T> list, ",
      "public IReadOnlyDictionary<K, V> Tally<K, V>(IReadOnlyDictionary<K, V> map) " +
        "where K : notnull",
      "public IReadOnlyList<IReadOnlyList<T>> Nested<T>(IReadOnlyList<IReadOnlyList<T>> list)",
      "public $ns.Box<T> BoxedResult<T>(T value)",
      "public $ns.Outcome<T> Settle<T>(T value)",
      "public $ns.Pair2<T, string> PairUp<T>(T value)",
      "public static T UnwrapTop<T>($ns.Box<T> box)",
    ).forEach { signature ->
      assertTrue(
        result.generatedCSharp.contains(signature),
        "expected `$signature`; got: ${result.generatedCSharp.lines().filter { "public" in it }}",
      )
    }

    // The Kotlin read reinterprets the handle at the argument's erased bound (ADR-208).
    listOf(
      ".unwrap<Any?>(box.asStableRef<tier1.boxedown.Box<Any?>>().get())",
      ".groom<tier1.boxedown.Furry>(box.asStableRef<tier1.boxedown.Box<tier1.boxedown.Furry>>()",
    ).forEach { fragment -> assertContains(result.generated, fragment) }
  }

  /**
   * ADR-216 amendment: an erased read of an open instantiation finds a factory. Every concrete
   * generic class fills its ADR-199 `NugetFactory<T>` slot from its static constructor, and a
   * member whose result nests an open instantiation fills that slot before the read, since the
   * consumer may never have touched it (`Nest(Wrap(b))`, a `List<Box<T>>` element).
   */
  @Test
  fun `an open nested instantiation fills its factory slot`() {
    val result: Tier1Result = run()
    assertKotlinCompiles(result)
    val cs: String = result.generatedCSharp
    val box = "global::TestLibrary.Boxedown.Box"
    listOf(
      "static Box()",
      "NugetFactory<Box<T>>.Create = static handle => new Box<T>(handle, out _);",
      "internal static class NugetFactory<T>",
      "NugetFactory<$box<T>>.Create ??= static handle => new $box<T>(handle, out _);",
    ).forEach { fragment -> assertContains(cs, fragment) }
    // Once per member that needs it: the class-T `Rebox`, `Boxes`, but not a bare `Wrap`.
    val fills: Int = Regex(Regex.escape("NugetFactory<$box<T>>.Create ??=")).findAll(cs).count()
    assertTrue(fills >= 2, "expected the class-T and list members to fill the slot; got $fills")
    val wrap: String = cs
      .substringAfter("public $box<T> Wrap<T>(T value)")
      .substringBefore("public ")
    assertFalse(wrap.contains("??="), "a bare Box<T> return reads directly, without a fill")
  }

  /**
   * ADR-216 decision 4: `Box<T?>` under a non-null bound keeps ADR-197's bare-`T?` semantics. C#
   * reads `T?` as an annotation for a value-type `T`, so `Opt<int>` takes a `Box<int>`.
   */
  @Test
  fun `a nullable T inside a box under a non-null bound spells the bare T semantics`() {
    val result: Tier1Result = run()
    assertKotlinCompiles(result)
    assertContains(
      result.generatedCSharp,
      "public T? Opt<T>(global::TestLibrary.Boxedown.Box<T?> box) where T : notnull",
    )
  }

  @Test
  fun `a shape the gate admits but the bridge cannot carry is refused by name`() {
    val result: Tier1Result = run()
    assertKotlinCompiles(result)

    mapOf(
      "tier1.boxedown.Depot.deep" to
        (ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_TYPE to "List<T>"),
    ).forEach { (member, expected) ->
      val (kind: ForwardDiagnosticKind, refusal: String) = expected
      val named: List<String> = result.kspWarnings.filter { warning ->
        Regex("\\b${Regex.escape(member)}\\b").containsMatchIn(warning)
      }
      assertEquals(1, named.size, "expected exactly one warning naming $member; got: $named")
      assertContains(named.single(), kind.name)
      assertContains(named.single(), refusal)
    }
    val declarations: List<String> = result.generatedCSharp.lines()
      .map(String::trim)
      .filterNot { line -> line.startsWith("//") }
    // ADR-217: a multi-bound `T` witnessed only inside `Box<T>` binds through the trampoline.
    assertTrue(
      declarations.any { line -> line.startsWith("public T Rank<T>(") },
      "expected the ADR-217 Rank binding",
    )
    listOf("Deep").forEach { member ->
      assertFalse(
        declarations.any { line -> Regex("\\b$member\\s*[<(]").containsMatchIn(line) },
        "expected no C# member $member",
      )
    }
  }

  @Test
  fun `the generated own-T-in-a-box C# compiles against a consumer`() {
    val result: Tier1Result = run()

    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using System;
      using System.Collections.Generic;
      using TestLibrary.Boxedown;

      public static class Consumer
      {
          public static int Use(Depot depot, Cat oreo)
          {
              Box<int> b = depot.Wrap(3);
              int three = depot.Unwrap(b);
              Cat groomed = depot.Groom(depot.Wrap(oreo));
              Box<string>? none = depot.Maybe<string>(null);
              Box<int> inner = depot.Nest(depot.Wrap(b));
              IReadOnlyList<Box<int>> boxes = depot.Boxes(new[] { b });
              IReadOnlyList<string> listed = depot.Listed(new[] { "Oreo", "Mylo" });
              bool ok = depot.TryListed(new[] { 1 }, out IReadOnlyList<int>? value,
                  out Exception? failure);
              IReadOnlyDictionary<string, int> tally =
                  depot.Tally(new Dictionary<string, int> { ["Oreo"] = 1 });
              IReadOnlyList<IReadOnlyList<int>> nested = depot.Nested(new[] { new[] { 1 } });
              Box<long> boxed = depot.BoxedResult(1L);
              Outcome<string> settled = depot.Settle("Mylo");
              Pair2<int, string> paired = depot.PairUp(1);
              int? opt = depot.Opt(b);
              int top = Boxes.UnwrapTop(b);
              return three + groomed.Tricks + inner.Item + boxes.Count + listed.Count
                  + (ok ? value!.Count : 0) + (failure == null ? 0 : 1) + tally.Count
                  + nested.Count + (int)boxed.Item + paired.First + (opt ?? 0) + top
                  + (none == null ? 0 : 1) + (settled == null ? 0 : 1);
          }
      }
      """.trimIndent(),
    )
  }
}
