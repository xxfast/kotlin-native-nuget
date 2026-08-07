package io.github.xxfast.kotlin.native.nuget.processor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ADR-078: the legacy helper protocols emit their `DllImport` declarations as raw renderer text,
 * so the contract check collects them from the rendered `Interop.cs` rather than from CIR nodes.
 */
class ForwardAbiLegacyImportTest {
  @Test
  fun `collects primitive parameters and returns`() {
    val rendered: String = declaration(
      "nuget_list_size",
      "private static extern int nuget_list_size(IntPtr handle, bool deep, double weight);",
    )

    assertEquals(
      "nuget_list_size(in pointer, in bool, in double) -> int",
      ForwardAbiContract.csharpLegacy(rendered, emptySet()).canonicalText(),
    )
  }

  @Test
  fun `collects a void return and an IntPtr return`() {
    val rendered: String = declaration(
      "nuget_dispose",
      "private static extern void nuget_dispose(IntPtr handle);",
    ) + declaration(
      "nuget_wrap_object",
      "private static extern IntPtr nuget_wrap_object(IntPtr value);",
    )

    assertEquals(
      """
      nuget_dispose(in pointer) -> void
      nuget_wrap_object(in pointer) -> pointer
      """.trimIndent(),
      ForwardAbiContract.csharpLegacy(rendered, emptySet()).canonicalText(),
    )
  }

  @Test
  fun `treats string as a pointer result and a string parameter`() {
    val rendered: String = declaration(
      "nuget_map_get",
      "private static extern string? nuget_map_get(IntPtr handle, string key);",
    )

    assertEquals(
      "nuget_map_get(in pointer, in string) -> pointer",
      ForwardAbiContract.csharpLegacy(rendered, emptySet()).canonicalText(),
    )
  }

  @Test
  fun `collects the error out slot`() {
    val rendered: String = declaration(
      "nuget_suspend_func1_invoke",
      "private static extern IntPtr nuget_suspend_func1_invoke(IntPtr handle, out IntPtr error);",
    )

    assertEquals(
      "nuget_suspend_func1_invoke(in pointer, out pointer) -> pointer",
      ForwardAbiContract.csharpLegacy(rendered, emptySet()).canonicalText(),
    )
  }

  @Test
  fun `collects a marshalled boolean out parameter`() {
    val rendered: String = declaration(
      "nuget_flow_try_next",
      "private static extern bool nuget_flow_try_next(IntPtr handle, " +
          "[MarshalAs(UnmanagedType.I1)] out bool valueOut, out IntPtr error);",
    )

    assertEquals(
      "nuget_flow_try_next(in pointer, out pointer, out pointer) -> bool",
      ForwardAbiContract.csharpLegacy(rendered, emptySet()).canonicalText(),
    )
  }

  @Test
  fun `treats a delegate parameter as a pointer`() {
    val rendered: String = declaration(
      "nuget_async_await",
      "private static extern void nuget_async_await(IntPtr handle, NugetAsyncCallback callback);",
    )

    assertEquals(
      "nuget_async_await(in pointer, in pointer) -> void",
      ForwardAbiContract.csharpLegacy(rendered, emptySet()).canonicalText(),
    )
  }

  @Test
  fun `accepts a return marshalling attribute between the attribute and the declaration`() {
    val rendered: String = """
      |        [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "nuget_flow_done")]
      |        [return: MarshalAs(UnmanagedType.I1)]
      |        private static extern bool nuget_flow_done(IntPtr handle);
      |
    """.trimMargin()

    assertEquals(
      "nuget_flow_done(in pointer) -> bool",
      ForwardAbiContract.csharpLegacy(rendered, emptySet()).canonicalText(),
    )
  }

  @Test
  fun `collapses identical duplicate imports of one entry point`() {
    val rendered: String = declaration(
      "nuget_dispose",
      "private static extern void nuget_dispose(IntPtr handle);",
    ) + declaration(
      "nuget_dispose",
      "private static extern void Native_Dispose(IntPtr handle);",
    )

    assertEquals(
      "nuget_dispose(in pointer) -> void",
      ForwardAbiContract.csharpLegacy(rendered, emptySet()).canonicalText(),
    )
  }

  @Test
  fun `reports conflicting duplicate imports of one entry point`() {
    val rendered: String = declaration(
      "nuget_dispose",
      "private static extern void nuget_dispose(IntPtr handle);",
    ) + declaration(
      "nuget_dispose",
      "private static extern void nuget_dispose(IntPtr handle, out IntPtr error);",
    )

    val error: IllegalArgumentException = assertFailsWith {
      ForwardAbiContract.csharpLegacy(rendered, emptySet())
    }

    assertTrue(error.message!!.contains("nuget_dispose"))
    assertTrue(error.message!!.contains("out pointer"))
  }

  @Test
  fun `fails fast on an entry point without a parsable declaration`() {
    val rendered: String = """
      |        [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "nuget_broken")]
      |        public static int NugetBroken(IntPtr handle) => 0;
      |
    """.trimMargin()

    val error: IllegalStateException = assertFailsWith {
      ForwardAbiContract.csharpLegacy(rendered, emptySet())
    }

    assertTrue(error.message!!.contains("nuget_broken"))
    assertTrue(error.message!!.contains("NugetBroken"))
  }

  @Test
  fun `excludes entry points already covered by the ordinary collector`() {
    val rendered: String = declaration(
      "device_get_id",
      "private static extern IntPtr Native_Get_id(IntPtr handle, out IntPtr error);",
    ) + declaration(
      "nuget_list_get",
      "private static extern IntPtr nuget_list_get(IntPtr handle, int index);",
    )

    assertEquals(
      "nuget_list_get(in pointer, in int) -> pointer",
      ForwardAbiContract.csharpLegacy(rendered, setOf("device_get_id")).canonicalText(),
    )
  }

  @Test
  fun `reports a legacy pair whose Kotlin export drops the error slot`() {
    val rendered: String = declaration(
      "nuget_stateflow_collect",
      "private static extern void nuget_stateflow_collect(IntPtr handle, " +
          "NugetAsyncCallback callback, out IntPtr error);",
    )
    val legacy: List<ForwardAbiSignature> = ForwardAbiContract.csharpLegacy(rendered, emptySet())

    val error: IllegalArgumentException = assertFailsWith {
      ForwardAbiContract.assertMatches(
        csharp = legacy,
        kotlin = listOf(
          ForwardAbiSignature(
            "nuget_stateflow_collect",
            ForwardAbiType.VOID,
            listOf(
              ForwardAbiParameter(ForwardAbiType.POINTER),
              ForwardAbiParameter(ForwardAbiType.POINTER),
            ),
          ),
        ),
      )
    }

    assertTrue(error.message!!.contains("mismatch for nuget_stateflow_collect"))
  }

  private fun declaration(entryPoint: String, extern: String): String = """
    |        [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "$entryPoint")]
    |        $extern
    |
  """.trimMargin()
}
