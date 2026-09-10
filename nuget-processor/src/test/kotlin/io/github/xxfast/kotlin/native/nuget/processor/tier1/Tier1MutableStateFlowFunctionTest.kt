package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-071 (2026-09-11): a `MutableStateFlow<T>` returned from a **function** is the flow that call
 * returned, not a fresh one per access. The shipped shape built the C# wrapper out of three lambdas
 * that each re-invoked the Kotlin function, so a `.Value` write landed in one throwaway flow and
 * the next `.Value` read built another; a fresh-per-call body (nothing shipped had one) lost every
 * write. The route now mints the flow's own handle once per call and keys reads on ADR-068's shared
 * `nuget_stateflow_collect` / `nuget_stateflow_value` and the write on a flow-handle-keyed
 * `_set_value`.
 *
 * The end-to-end half lives in `CatSnackDispenser` / `MutableStateFlowFunctionTests.cs`, and the
 * ownership half in `LeakTests/LiveHandleTests.cs` row 8f.
 */
class Tier1MutableStateFlowFunctionTest {

  @Test
  fun `a MutableStateFlow function return crosses as a held flow handle`() {
    val result = Tier1Harness.run(
      """
      package tier1.mutablestateflowfunction

      import kotlinx.coroutines.flow.MutableStateFlow

      class Dispenser {
        private var latest: MutableStateFlow<Int>? = null
        fun level(): MutableStateFlow<Int> = MutableStateFlow(3).also { latest = it }
        fun lastLevel(): Int = latest?.value ?: -1
      }
      """.trimIndent(),
      // `MutableStateFlow` must resolve for the flow route to be taken at all; with only
      // kotlin-stdlib on the KSP libraries path the member is skipped as an unsupported type.
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(
      result.compiledClean,
      "expected the held-flow route to compile; got: ${result.compileErrors} ${result.kspErrors}",
    )

    val kotlin: String = result.generated

    // One acquire export per member, minting the flow's own StableRef through the generated
    // handle table (never a bare `StableRef.create`, which would bypass ADR-120's live-handle
    // accounting).
    assertContains(kotlin, "@CName(\"dispenser_level\")")
    assertContains(kotlin, "NugetHandles.retain(obj.level() as Any)")

    // The write is keyed on the flow handle, so it lands in the flow the caller was handed
    // rather than in whatever a fresh invocation of `level()` builds.
    assertContains(
      kotlin,
      "flowHandle.asStableRef<kotlinx.coroutines.flow.MutableStateFlow<kotlin.Int>>()",
    )
    assertContains(kotlin, "@CName(\"dispenser_level_set_value\")")

    // The per-member reads are dead on this route: reads go through ADR-068's shared
    // handle-keyed exports, generated once per module.
    assertFalse(
      kotlin.contains("dispenser_level_collect"),
      "expected no per-member _collect for a held flow; generated=$kotlin",
    )
    assertFalse(
      kotlin.contains("dispenser_level_value"),
      "expected no per-member _value for a held flow; generated=$kotlin",
    )
    assertContains(kotlin, "@CName(\"nuget_stateflow_collect\")")
    assertContains(kotlin, "@CName(\"nuget_stateflow_value\")")

    val csharp: String = result.generatedCSharp

    assertContains(csharp, "EntryPoint = \"dispenser_level\"")
    assertContains(csharp, "EntryPoint = \"dispenser_level_set_value\"")
    assertFalse(
      csharp.contains("dispenser_level_collect"),
      "expected no per-member _collect import for a held flow; generatedCSharp=$csharp",
    )
    assertFalse(
      csharp.contains("dispenser_level_value"),
      "expected no per-member _value import for a held flow; generatedCSharp=$csharp",
    )

    // The flow is acquired once per call, and all three seams key off that one handle: the
    // shared collect/value exports for reads, the flow-keyed setter for writes, and the
    // wrapper's own `ownedHandle` slot for disposal.
    assertContains(csharp, "IntPtr flow = Native_Level(_handle);")
    assertContains(csharp, "NugetStateFlowNative.Collect(flow,")
    assertContains(csharp, "() => NugetStateFlowNative.Value(flow),")
    assertContains(csharp, "Native_LevelSetValue(flow, v, out IntPtr error);")
    assertContains(csharp, "                flow);")
  }
}
