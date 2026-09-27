package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-068 (2026-09-27 amendment): a TOP-LEVEL `suspend fun` returning `StateFlow<T>` awaits to the
 * same handle-owning `KotlinStateFlow<T>` the class-method route builds. It has no parent class
 * scope to collect on, so it passes a null scope and `nuget_stateflow_collect` launches on an
 * ad-hoc `CoroutineScope(Dispatchers.Default)`, the same scope the top-level suspend route's own
 * call launches on. The shipped output was `Task<StateFlow>`, an undefined C# type.
 *
 * The end-to-end half lives in `CatWatch` / `TopLevelSuspendStateFlowTests.cs`, the ownership half
 * in `LeakTests/LiveHandleTests.cs` (`TopLevelSuspendStateFlow_...`).
 */
class Tier1TopLevelSuspendStateFlowTest {

  @Test
  fun `a top-level suspend StateFlow return awaits to a KotlinStateFlow with no parent scope`() {
    val result = Tier1Harness.run(
      """
      package tier1.toplevelsuspendstateflow

      import kotlinx.coroutines.flow.MutableStateFlow
      import kotlinx.coroutines.flow.StateFlow

      class Kitten(val name: String)

      private val count = MutableStateFlow(0)
      private val kitten = MutableStateFlow(Kitten("Oreo"))

      suspend fun watchCount(): StateFlow<Int> = count
      suspend fun watchKitten(): StateFlow<Kitten> = kitten
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(
      result.compiledClean,
      "expected the top-level route to compile; got: ${result.compileErrors} ${result.kspErrors}",
    )

    // The Kotlin half is the plain top-level suspend export: the awaited flow is minted through
    // the handle table, never a bare `StableRef.create`.
    val kotlin: String = result.generated
    assertFalse(
      kotlin.contains("StableRef.create("),
      "expected no bare StableRef.create; generated=$kotlin",
    )

    val csharp: String = result.generatedCSharp

    assertContains(csharp, "public static Task<KotlinStateFlow<int>> WatchCountAsync(")
    assertContains(csharp, "public static Task<KotlinStateFlow<")
    assertContains(csharp, "Kitten>> WatchKittenAsync(")
    assertFalse(
      csharp.contains("Task<StateFlow>"),
      "expected no undefined-type Task<StateFlow>; generatedCSharp=$csharp",
    )
    // No parent scope on a static member: the collect launches on the runtime's ad-hoc scope.
    assertContains(csharp, "IntPtr collectScope = IntPtr.Zero;")
    assertFalse(
      csharp.contains("GetOrCreateScope()"),
      "a static member has no GetOrCreateScope(); generatedCSharp=$csharp",
    )
    assertContains(csharp, "NugetStateFlowNative.Collect(flowHandle, collectScope,")
    assertContains(csharp, "internal static class NugetStateFlowNative")
    assertContains(csharp, "public class KotlinStateFlow<T>")
  }

  @Test
  fun `a top-level suspend StateFlow of a nullable element is refused by name`() {
    val result = Tier1Harness.run(
      """
      package tier1.toplevelsuspendstateflownullable

      import kotlinx.coroutines.flow.MutableStateFlow
      import kotlinx.coroutines.flow.StateFlow

      private val nickname = MutableStateFlow<String?>(null)

      suspend fun watchNickname(): StateFlow<String?> = nickname
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(
      result.compiledClean,
      "expected the refusal to compile; got: ${result.compileErrors} ${result.kspErrors}",
    )
    assertFalse(
      result.generatedCSharp.contains("WatchNicknameAsync"),
      "expected no C# for the refused member; generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      result.kspWarnings.any {
        it.contains("[nuget:${ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN.name}]") &&
            it.contains("watchNickname")
      },
      "expected a SKIPPED_UNSUPPORTED_RETURN naming watchNickname; kspWarnings=${result.kspWarnings}",
    )
  }
}
