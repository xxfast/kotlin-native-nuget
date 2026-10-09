package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * ADR-071 Alternative 4: every declared `MutableStateFlow<T>` member gains an atomic
 * `CompareAndSet(expect, update)` on `KotlinMutableStateFlow<T>`, backed by one
 * `_compare_and_set` export per member on each settable route (property: owner-handle keyed;
 * held function return and awaited suspend return: flow-handle keyed). Both parameters cross
 * through the setter's own write slot, the export returns a `Boolean` and carries `errorOut`, so
 * a throwing `equals` surfaces as a `KotlinException` instead of reading as a missed swap.
 * `Update` / `UpdateAndGet` / `GetAndUpdate` are C# retry loops over it with no export.
 */
class Tier1MutableStateFlowCompareAndSetTest {

  private val source: String =
    """
    package tier1.cas

    import kotlinx.coroutines.flow.MutableStateFlow

    class Toy(val name: String)

    class Tracker {
      val count: MutableStateFlow<Int> = MutableStateFlow(0)
      val label: MutableStateFlow<String> = MutableStateFlow("a")
      val toy: MutableStateFlow<Toy> = MutableStateFlow(Toy("mouse"))
      val spare: MutableStateFlow<Toy?> = MutableStateFlow(null)
      val weight: MutableStateFlow<Int?> = MutableStateFlow(null)
      val maybe: MutableStateFlow<String>? = null
      fun level(): MutableStateFlow<Int> = MutableStateFlow(3)
      fun level(scale: Int): MutableStateFlow<Int> = MutableStateFlow(scale)
      fun pick(expect: Int, update: Int): MutableStateFlow<Toy> = MutableStateFlow(Toy("ball"))
      suspend fun awaitCount(): MutableStateFlow<Int> = count
    }
    """.trimIndent()

  @Test
  fun `the property route exports an owner-keyed compare-and-set per member`() {
    val result = Tier1Harness.run(
      source, libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
    assertTrue(result.compiledClean, "got: ${result.compileErrors} ${result.kspErrors}")
    val kotlin: String = result.generated
    val csharp: String = result.generatedCSharp

    // Scalar element: both slots by value, Boolean return, errorOut, false on a throw.
    assertContains(kotlin, "@CName(\"library_tier1_cas__tracker_compare_and_set_count_value\")")
    assertContains(
      kotlin,
      "handle: COpaquePointer,\n  `expect`: Int,\n  update: Int,\n  errorOut: COpaquePointer?,\n" +
          "): Boolean = try {",
    )
    assertContains(
      kotlin,
      "handle.asStableRef<tier1.cas.Tracker>().get().count.compareAndSet(expect, update)",
    )
    // Object element: both slots unwrapped through the setter's own `asStableRef`.
    assertContains(
      kotlin,
      "handle.asStableRef<tier1.cas.Tracker>().get().toy.compareAndSet(" +
          "expect.asStableRef<tier1.cas.Toy>().get(), update.asStableRef<tier1.cas.Toy>().get())",
    )
    // Nullable object element: null-safe unwrap on both.
    assertContains(
      kotlin,
      "spare.compareAndSet(expect?.asStableRef<tier1.cas.Toy>()?.get(), " +
          "update?.asStableRef<tier1.cas.Toy>()?.get())",
    )
    // Nullable scalar element: the has-value pair, twice.
    assertContains(
      kotlin,
      "weight.compareAndSet(if (expectHasValue) expect else null, " +
          "if (updateHasValue) update else null)",
    )
    // Nullable member: an absent member throws through errorOut, like the setter.
    assertContains(
      kotlin,
      "(handle.asStableRef<tier1.cas.Tracker>().get().maybe ?: throw " +
          "IllegalStateException(\"maybe is null\")).compareAndSet(expect, update)",
    )

    assertContains(
      csharp,
      "EntryPoint = \"library_tier1_cas__tracker_compare_and_set_count_value\")]\n" +
          "        [return: MarshalAs(UnmanagedType.I1)]\n" +
          "        private static extern bool Native_CompareAndSetCountValue(" +
          "NugetKotlinHandle handle, int expect, int update, out IntPtr error);",
    )
    assertContains(
      csharp,
      "private static extern bool Native_CompareAndSetLabelValue(NugetKotlinHandle handle, " +
          "[MarshalAs(UnmanagedType.LPUTF8Str)] string expect, " +
          "[MarshalAs(UnmanagedType.LPUTF8Str)] string update, out IntPtr error);",
    )
    assertContains(
      csharp,
      "private static extern bool Native_CompareAndSetWeightValue(NugetKotlinHandle handle, " +
          "[MarshalAs(UnmanagedType.I1)] bool expectHasValue, int expect, " +
          "[MarshalAs(UnmanagedType.I1)] bool updateHasValue, int update, out IntPtr error);",
    )
    assertContains(
      csharp,
      "return NugetErrorNative.Check(Native_CompareAndSetCountValue(_handle, expect, update, " +
          "out IntPtr error), error);",
    )
    assertContains(
      csharp,
      "Native_CompareAndSetWeightValue(_handle, expect.HasValue, expect.GetValueOrDefault(), " +
          "update.HasValue, update.GetValueOrDefault(), out IntPtr error)",
    )
    assertContains(csharp, "if (expect is null) throw new ArgumentNullException(nameof(expect));")
    assertContains(csharp, "if (update is null) throw new ArgumentNullException(nameof(update));")
    assertContains(csharp, "Native_CompareAndSetToyValue(_handle, expect._handle, update._handle")
    assertContains(
      csharp,
      "Native_CompareAndSetSpareValue(_handle, expect?._handle ?? NugetKotlinHandle.Null, " +
          "update?._handle ?? NugetKotlinHandle.Null",
    )

    // The helper: a required compare-and-set delegate and the three C#-only retry loops.
    assertContains(csharp, "            Func<T, T, bool> compareAndSet,\n")
    assertContains(csharp, "public bool CompareAndSet(T expect, T update)")
    assertContains(csharp, "public void Update(Func<T, T> transform)")
    assertContains(csharp, "public T UpdateAndGet(Func<T, T> transform)")
    assertContains(csharp, "public T GetAndUpdate(Func<T, T> transform)")
  }

  @Test
  fun `the held and suspend routes export a flow-keyed compare-and-set`() {
    val result = Tier1Harness.run(
      source, libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
    assertTrue(result.compiledClean, "got: ${result.compileErrors} ${result.kspErrors}")
    val kotlin: String = result.generated
    val csharp: String = result.generatedCSharp

    // Held, both overloads carry their own suffix so the two do not collide on one C symbol.
    assertContains(kotlin, "@CName(\"library_tier1_cas__tracker_level_compare_and_set\")")
    assertContains(kotlin, "@CName(\"library_tier1_cas__tracker_level_2_compare_and_set\")")
    assertContains(
      kotlin,
      "flowHandle.asStableRef<kotlinx.coroutines.flow.MutableStateFlow<kotlin.Int>>().get()" +
          ".compareAndSet(expect, update)",
    )
    // Suspend: the awaited flow's own handle.
    assertContains(kotlin, "@CName(\"library_tier1_cas__tracker_awaitCount_compare_and_set\")")

    assertContains(
      csharp,
      "EntryPoint = \"library_tier1_cas__tracker_level_compare_and_set\")]\n" +
          "        [return: MarshalAs(UnmanagedType.I1)]\n" +
          "        private static extern bool Native_LevelCompareAndSet(" +
          "NugetKotlinHandle flowHandle, int expect, int update, out IntPtr error);",
    )
    assertContains(
      csharp,
      "private static extern bool Native_AwaitCountAsyncCompareAndSet(" +
          "NugetKotlinHandle flowHandle, int expect, int update, out IntPtr error);",
    )
    assertContains(
      csharp,
      "return NugetErrorNative.Check(Native_AwaitCountAsyncCompareAndSet(flowHandle, " +
          "expect, update, out IntPtr error), error);",
    )
    // A user parameter spelled `expect` / `update` moves the lambda's own names off it.
    assertContains(csharp, "(expect_, update_) =>")
  }
}
