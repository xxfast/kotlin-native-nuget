package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * ADR-099, the read arms the `test-library` corpus does not reach. `WardBoard` returns a nested
 * `List` (`grid`) and a nested `List` in a `Map` *value* (`runsByPatient`), so `ReadList` is warm
 * at runtime; nothing anywhere returns a nested `Set`, a nested `Map`, or a nullable leaf *at a
 * return position*, which leaves `componentCollectionRead`'s `ReadSet`/`ReadMap` arms and the
 * block-bodied read lambda (ADR-083's `CirComponentRead.declaration` under a nesting level)
 * entirely unexecuted.
 *
 * These are generated-text cells rather than fixture cells because each one costs a native member
 * and a round trip to observe at runtime, and what is in doubt is the projection, not the ABI: the
 * component slot is the same `COpaquePointer` at every kind and depth.
 */
class Tier1NestedCollectionReadTest {

  @Test
  fun `a nested Set and a nested Map return read back through their own helpers`() {
    val result = Tier1Harness.run(
      """
      package tier1.nestedreads

      class Ward {
        fun groups(): List<Set<String>> = listOf(setOf("oreo"))

        fun table(): Map<String, Map<String, Int>> = mapOf("oreo" to mapOf("naps" to 3))
      }
      """.trimIndent(),
    )

    assertTrue(
      result.compiledClean,
      "expected the nested reads to leave compilable source; got: ${result.compileErrors}",
    )

    val cs: String = result.generatedCSharp
    assertContains(
      cs,
      "NugetMarshal.ReadSet<string>(NugetListNative.Get(listHandle, i), " +
          "static h1 => NugetMarshal.FromHandle<string>(h1))",
    )
    assertContains(
      cs,
      "NugetMarshal.ReadMap<string, int>(NugetMapNative.ValueAt(mapHandle, i), " +
          "static k1 => NugetMarshal.FromHandle<string>(k1), " +
          "static v1 => NugetMarshal.FromHandle<int>(v1))",
    )
    // The helpers themselves have to be emitted alongside, gated per kind.
    assertContains(cs, "public static HashSet<T> ReadSet<T>(IntPtr handle, Func<IntPtr, T> read)")
    assertContains(cs, "public static Dictionary<TKey, TValue> ReadMap<TKey, TValue>(")
  }

  /**
   * The write half at depth 3, which is where the per-level lambda naming is load-bearing: C#
   * rejects a lambda parameter that shadows an enclosing one (CS0136), so `x`/`x1`/`x2` is the
   * difference between generated code that compiles and generated code that does not. The
   * `test-library` corpus compiles this shape (`WardBoard.logCages`) but nothing pins the spelling.
   */
  @Test
  fun `a nested input recurses through the same factory at every level`() {
    val result = Tier1Harness.run(
      """
      package tier1.nestedwrites

      class Ward {
        fun logCages(cages: List<List<Set<String>>>): Int = cages.size
      }
      """.trimIndent(),
    )

    assertTrue(
      result.compiledClean,
      "expected the nested input to leave compilable source; got: ${result.compileErrors}",
    )

    assertContains(
      result.generatedCSharp,
      "NugetMarshal.CreateList(global::System.Linq.Enumerable.Select(cages, " +
          "x => NugetMarshal.CreateList(global::System.Linq.Enumerable.Select(x, " +
          "x1 => NugetMarshal.CreateSet(x1)))))",
    )
    // The Kotlin half casts to the WIRE container at every level, not to the declared element
    // type, and names each lambda parameter by depth so no nested implicit `it` shadows its
    // enclosing one.
    assertContains(
      result.generated,
      "cages.asStableRef<MutableList<Any?>>().get()" +
          ".map { (it as MutableList<*>).map { e2 -> (e2 as MutableSet<*>)" +
          ".mapTo(mutableSetOf()) { e3 -> e3 as kotlin.String } } }",
    )
  }

  @Test
  fun `a nullable leaf under a nesting level reads through a block-bodied lambda`() {
    val result = Tier1Harness.run(
      """
      package tier1.nestednullableleaf

      class Ward {
        fun trail(): List<List<String?>> = listOf(listOf("oreo", null))
      }
      """.trimIndent(),
    )

    assertTrue(
      result.compiledClean,
      "expected the nullable-leaf read to leave compilable source; got: ${result.compileErrors}",
    )

    // ADR-083's read carries a declaration (the handle has to be tested against IntPtr.Zero and
    // then read, and the Get call cannot be repeated), which no expression-bodied lambda can hold.
    assertContains(
      result.generatedCSharp,
      "NugetMarshal.ReadList<string?>(NugetListNative.Get(listHandle, i), " +
          "static h1 => { IntPtr elementHandle1 = h1; " +
          "return elementHandle1 == IntPtr.Zero ? (string?)null : " +
          "NugetMarshal.FromHandle<string>(elementHandle1); })",
    )
  }
}
