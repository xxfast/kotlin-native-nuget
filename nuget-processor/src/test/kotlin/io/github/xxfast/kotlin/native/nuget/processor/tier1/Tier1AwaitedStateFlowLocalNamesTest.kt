package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * The awaited-`StateFlow` branch of `CirConcurrencyRenderer.renderAsyncMethod` spells its locals
 * (`flowHandle`, `collectScope`, the write lambda's `error`) and lambda parameters
 * (`flowOnNext`..`flowUserData`, `v`) literally, where the acquired-`Flow` branch mints them
 * through `freshName`. They are all declared inside the completion closure, a lambda, so a method
 * parameter of the same name is shadowed there, not redeclared. LangVersion 14, the version every
 * generated `Interop.cs` builds at (net10.0 and up, `NugetCompileInteropTask`), accepts that.
 * Inside the closure no user parameter is read, so the shadowing is also the intended binding.
 * The CS0136 the ROADMAP inferred does not occur; this pins it.
 *
 * Every awaited holder shape is one cell: plain, nullable member, nullable element, collection
 * element (ADR-068), `MutableStateFlow` (ADR-071 write and compare-and-set lambdas) and a static
 * top-level member. The proof is a real `dotnet build` of the generated C#.
 */
class Tier1AwaitedStateFlowLocalNamesTest {

  private val fixture: String = """
    package tier1.awaitedlocals

    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.StateFlow

    class Gauge {
      suspend fun watch(flowHandle: Int, collectScope: String): StateFlow<Int> =
        MutableStateFlow(flowHandle)

      suspend fun maybe(flowHandle: Int, collectScope: String): StateFlow<Int>? = null

      suspend fun blanks(flowHandle: Int, collectScope: String): StateFlow<String?> =
        MutableStateFlow(null)

      suspend fun lists(flowHandle: Int, collectScope: String): StateFlow<List<Int>> =
        MutableStateFlow(listOf(flowHandle))

      suspend fun held(
        flowHandle: Int,
        collectScope: String,
        flowOnNext: Int,
        flowOnComplete: Int,
        flowOnError: Int,
        flowUserData: Int,
        v: Int,
      ): MutableStateFlow<Int> = MutableStateFlow(v)

      suspend fun heldText(
        flowHandle: Int,
        collectScope: String,
        v: String,
        error: Int,
      ): MutableStateFlow<String> = MutableStateFlow(v)
    }

    suspend fun gauge(flowHandle: Int, collectScope: String): StateFlow<Int> =
      MutableStateFlow(flowHandle)
  """.trimIndent()

  @Test
  fun `an awaited StateFlow member with parameters named after its locals compiles in C#`() {
    val result: Tier1Result = Tier1Harness.run(
      fixture,
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
    assertTrue(result.kspSucceeded, "kspErrors=${result.kspErrors}")
    assertTrue(result.compiledClean, "generated Kotlin must compile; got: ${result.compileErrors}")
    // Each cell must reach the awaited-StateFlow branch, or the build below proves nothing.
    val csharp: String = result.generatedCSharp
    listOf(
      "Task<KotlinStateFlow<int>> WatchAsync(int flowHandle, string collectScope,",
      "Task<KotlinStateFlow<int>?> MaybeAsync(int flowHandle, string collectScope,",
      "Task<KotlinStateFlow<string?>> BlanksAsync(int flowHandle, string collectScope,",
      "Task<KotlinStateFlow<IReadOnlyList<int>>> ListsAsync(int flowHandle, string collectScope,",
      "Task<KotlinMutableStateFlow<int>> HeldAsync(int flowHandle, string collectScope, " +
        "int flowOnNext, int flowOnComplete, int flowOnError, int flowUserData, int v,",
      "Task<KotlinMutableStateFlow<string>> HeldTextAsync(int flowHandle, string collectScope, " +
        "string v,",
      "Task<KotlinStateFlow<int>> GaugeAsync(int flowHandle, string collectScope,",
    ).forEach { signature -> assertContains(csharp, signature) }
    assertContains(csharp, "var flowHandle = new NugetKotlinHandle(resultPtr);")
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      namespace Consumer
      {
          public static class Probe
          {
              public static object Run(global::Interop.Gauge gauge) => gauge;
          }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }
}
