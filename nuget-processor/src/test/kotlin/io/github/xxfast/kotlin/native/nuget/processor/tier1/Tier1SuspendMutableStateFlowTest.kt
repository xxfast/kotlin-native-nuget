package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-071 held-route amendment, cross-noted on ADR-068: a class `suspend fun` returning
 * `MutableStateFlow<T>` awaits to `Task<KotlinMutableStateFlow<T>>`. The `_async` export is
 * unchanged (it already hands the awaited flow back as its own handle); the write goes through a
 * generated flow-handle-keyed `_set_value` sibling, the same shape the held function-return route
 * emits, never a runtime export.
 *
 * `suspend fun (): MutableStateFlow<T?>` and the top-level route stay read-only
 * (`KotlinStateFlow<T>`). The end-to-end half lives in `SuspendStateFlowTests.cs` and the
 * ownership half in `LeakTests/LiveHandleTests.cs`.
 */
class Tier1SuspendMutableStateFlowTest {

  @Test
  fun `a suspend MutableStateFlow return awaits to a settable holder`() {
    val result = Tier1Harness.run(
      """
      package tier1.suspendmutable

      import kotlinx.coroutines.flow.MutableStateFlow

      class Toy(val name: String)

      class Jar {
        val treats: MutableStateFlow<Int> = MutableStateFlow(0)
        val toy: MutableStateFlow<Toy> = MutableStateFlow(Toy("mouse"))
        val tag: MutableStateFlow<String?> = MutableStateFlow(null)
        suspend fun awaitTreats(): MutableStateFlow<Int> = treats
        suspend fun awaitToy(): MutableStateFlow<Toy> = toy
        suspend fun awaitTag(): MutableStateFlow<String?> = tag
        suspend fun awaitSpareJar(): MutableStateFlow<Int>? = null
      }

      suspend fun awaitLooseTreats(): MutableStateFlow<Int> = MutableStateFlow(1)
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(result.compiledClean, "got: ${result.compileErrors} ${result.kspErrors}")

    val kotlin: String = result.generated
    val csharp: String = result.generatedCSharp

    // The flow-keyed setter beside the unchanged `_async` export.
    assertContains(kotlin, "@CName(\"library_tier1_suspendmutable__jar_awaitTreats_async\")")
    assertContains(kotlin, "@CName(\"library_tier1_suspendmutable__jar_awaitTreats_set_value\")")
    assertContains(
      kotlin,
      "flowHandle.asStableRef<kotlinx.coroutines.flow.MutableStateFlow<kotlin.Int>>().get()" +
          ".value = value",
    )
    assertContains(
      kotlin,
      "flowHandle.asStableRef<kotlinx.coroutines.flow.MutableStateFlow<tier1.suspendmutable.Toy>>()" +
          ".get().value = value.asStableRef<tier1.suspendmutable.Toy>().get()",
    )

    assertContains(csharp, "public Task<KotlinMutableStateFlow<int>> AwaitTreatsAsync(")
    assertContains(
      csharp,
      "EntryPoint = \"library_tier1_suspendmutable__jar_awaitTreats_set_value\"",
    )
    assertContains(
      csharp,
      "private static extern void Native_AwaitTreatsAsyncSetValue(NugetKotlinHandle flowHandle, " +
          "int value, out IntPtr error);",
    )
    assertContains(csharp, "t.SetResult(new KotlinMutableStateFlow<int>(")
    assertContains(
      csharp,
      "Native_AwaitTreatsAsyncSetValue(flowHandle, v, out IntPtr error);",
    )
    assertContains(
      csharp,
      "Native_AwaitToyAsyncSetValue(flowHandle, v._handle, out IntPtr error);",
    )

    // A nullable element keeps S1's read-only spelling; its write side stays deferred.
    assertContains(csharp, "public Task<KotlinStateFlow<string?>> AwaitTagAsync(")
    assertFalse(
      kotlin.contains("jar_awaitTag_set_value"),
      "expected no setter for a suspend MutableStateFlow<T?>; generated=$kotlin",
    )
    // So does a nullable member: S1's `KotlinStateFlow<T>?`, read-only.
    assertContains(csharp, "public Task<KotlinStateFlow<int>?> AwaitSpareJarAsync(")
    assertFalse(
      kotlin.contains("jar_awaitSpareJar_set_value"),
      "expected no setter for a suspend MutableStateFlow<T>?; generated=$kotlin",
    )
    // The top-level route stays read-only in v1.
    assertContains(csharp, "Task<KotlinStateFlow<int>> AwaitLooseTreatsAsync(")
    assertFalse(
      kotlin.contains("awaitLooseTreats_set_value"),
      "expected no setter on the top-level suspend route; generated=$kotlin",
    )
  }
}
