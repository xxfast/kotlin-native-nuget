package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.NUGET_RUNTIME_EXPORTS
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-207: a Kotlin `Flow` collected from C# is credit-gated. The runtime's `collectForCSharp`
 * parks the producer inside its next `emit` until the C# reader returns the credit, and the
 * generated `KotlinFlowEnumerator.MoveNextAsync` returns it through the runtime-fixed
 * `nuget_flow_resume` after each item it hands out.
 *
 * The C# half is all that changes in generated output: the Kotlin per-member exports already call
 * `emit` from inside a `collect { }` suspend lambda, which is what lets `emit` become `suspend`.
 *
 * Oreo works the treat conveyor and does not start the next treat until the last one is eaten.
 */
class Tier1FlowBackpressureTest {

  private val source: String = """
    package tier1.conveyor

    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.flow

    class TreatConveyor {
      val belt: Flow<Int> = flow { repeat(3) { emit(it) } }
      fun crates(): Flow<List<String>> = flow { emit(listOf("treat")) }
    }
  """.trimIndent()

  @Test
  fun `MoveNextAsync returns the credit after it hands out an item`() {
    val result = Tier1Harness.run(
      source,
      fileName = "TreatConveyor.kt",
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val csharp: String = result.generatedCSharp
    val moveNext: String = csharp
      .substringAfter("public async ValueTask<bool> MoveNextAsync()")
      .substringBefore("public ValueTask DisposeAsync()")

    // The credit goes back only once `Current` holds the item, and through the published handle.
    val handout: String = "Current = item;\n" +
        "                        IntPtr job = Volatile.Read(ref _jobHandle);\n" +
        "                        if (job != IntPtr.Zero) NugetJobNative.Resume(job);\n" +
        "                        return true;"
    assertContains(
      moveNext.replace("\r\n", "\n"),
      handout,
      message =
        "expected the resume between the hand-out and `return true`; MoveNextAsync:\n$moveNext",
    )
    assertEquals(
      1,
      Regex("""NugetJobNative\.Resume\(""").findAll(moveNext).count(),
      "expected exactly one resume per item handed out; MoveNextAsync:\n$moveNext",
    )
  }

  @Test
  fun `the resume binds the runtime-fixed nuget_flow_resume export`() {
    val result = Tier1Harness.run(
      source,
      fileName = "TreatConveyor.kt",
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    val csharp: String = result.generatedCSharp
    val jobNative: String = csharp
      .substringAfter("internal static class NugetJobNative")
      .substringBefore("internal sealed class NugetJobCell")
    assertContains(
      jobNative.replace("\r\n", "\n"),
      "EntryPoint = \"nuget_flow_resume\")]\n" +
          "        internal static extern void Resume(IntPtr handle);",
      message = "expected the resume import beside Cancel and Dispose; NugetJobNative:\n$jobNative",
    )
    // Runtime-fixed: the generated Kotlin must not declare it, or it collides at link time.
    assertTrue("nuget_flow_resume" in NUGET_RUNTIME_EXPORTS)
    assertTrue(
      "\"nuget_flow_resume\"" !in result.generated,
      "expected no regenerated nuget_flow_resume; generatedKotlin:\n${result.generated}",
    )
  }
}
