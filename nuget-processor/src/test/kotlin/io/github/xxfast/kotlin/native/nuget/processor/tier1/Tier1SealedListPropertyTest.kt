package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * ROADMAP:64 / issue #39 — a `List<T>` property on a *sealed subclass* renders as a `get { ... }`
 * block, not as an expression-bodied getter wrapping a statement block (which the C# compiler
 * rejects with CS1002/CS1519/CS8124), and carries the `out IntPtr error` slot on both halves of
 * the ABI. A scalar getter on the same subclass stays expression-bodied; since issue #38 every
 * sealed-subclass getter carries the error slot, so the scalar one declares it and passes `out _`.
 */
class Tier1SealedListPropertyTest {

  private val source: String = """
    package tier1.sealedlistproperty

    data class Item(val name: String)

    sealed class State {
      data class Loaded(val items: List<Item>, val refreshing: Boolean) : State()

      data object Loading : State()
    }

    fun loaded(): State = State.Loaded(listOf(Item("Oreo")), refreshing = true)
  """.trimIndent()

  @Test
  fun `list property on a sealed subclass renders a block-bodied getter`() {
    val result = Tier1Harness.run(source)

    assertTrue(
      result.compiledClean,
      "expected the sealed-subclass List property to compile; got: ${result.compileErrors}",
    )

    assertContains(
      result.generatedCSharp,
      """
      |            public IReadOnlyList<global::Interop.Item> Items
      |            {
      |                get
      |                {
      |                    IntPtr listHandle = Native_Get_items(_handle, out IntPtr error);
      |                    if (error != IntPtr.Zero)
      |                    {
      |                        throw NugetErrorNative.BuildException(error);
      |                    }
      """.trimMargin(),
    )
    assertContains(result.generatedCSharp, "                    return result.AsReadOnly();")
  }

  @Test
  fun `the list getter's DllImport declares the error slot the Kotlin export takes`() {
    val result = Tier1Harness.run(source)

    assertContains(
      result.generatedCSharp,
      "private static extern IntPtr Native_Get_items(IntPtr handle, out IntPtr error);",
    )
    assertContains(
      result.generated,
      "fun export_state_loaded_get_items(handle: COpaquePointer, errorOut: COpaquePointer?)",
    )
  }

  @Test
  fun `a scalar getter on the same subclass keeps the expression body`() {
    val result = Tier1Harness.run(source)

    assertContains(
      result.generatedCSharp,
      "            public bool Refreshing => Native_Get_refreshing(_handle, out _);",
    )
    assertContains(
      result.generatedCSharp,
      "private static extern bool Native_Get_refreshing(IntPtr handle, out IntPtr error);",
    )
  }
}
