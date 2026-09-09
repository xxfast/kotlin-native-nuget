package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * ROADMAP:64 / issue #39 — a `List<T>` property on a *sealed subclass* renders as a `get { ... }`
 * block, not as an expression-bodied getter wrapping a statement block (which the C# compiler
 * rejects with CS1002/CS1519/CS8124), and carries the `out IntPtr error` slot on both halves of
 * the ABI. ADR-111 moved these getters onto the shared property plan, so every one of them now
 * reads the slot back and throws, and a `bool` return carries its `[return: MarshalAs]`.
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
      |                {                IntPtr nativeResult = Native_Get_items(_handle, out IntPtr error);
      |                if (error != IntPtr.Zero)
      |                {
      |                    throw NugetErrorNative.BuildException(error);
      |                }
      """.trimMargin(),
    )
    assertContains(
      result.generatedCSharp,
      "static h1 => NugetMarshal.FromHandle<global::Interop.Item>(h1)).AsReadOnly();",
    )
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

  /**
   * ADR-111: the scalar getter is planned like any class property now, so it reads the error slot
   * and throws instead of discarding it with `out _`, and its import carries the ADR-069
   * `[return: MarshalAs(UnmanagedType.I1)]` a `bool` return needs (ROADMAP:28).
   */
  @Test
  fun `a bool getter on the same subclass checks the error slot and marshals its return`() {
    val result = Tier1Harness.run(source)

    assertContains(
      result.generatedCSharp,
      """
      |            public bool Refreshing
      |            {
      |                get
      |                {                bool nativeResult = Native_Get_refreshing(_handle, out IntPtr error);
      |                if (error != IntPtr.Zero)
      |                {
      |                    throw NugetErrorNative.BuildException(error);
      |                }
      |                return nativeResult;
      """.trimMargin(),
    )
    assertContains(
      result.generatedCSharp,
      """
      |            [return: MarshalAs(UnmanagedType.I1)]
      |            private static extern bool Native_Get_refreshing(IntPtr handle, out IntPtr error);
      """.trimMargin(),
    )
  }
}
