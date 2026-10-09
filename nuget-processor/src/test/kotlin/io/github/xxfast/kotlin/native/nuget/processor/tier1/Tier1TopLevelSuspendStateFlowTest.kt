package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.NUGET_RUNTIME_EXPORTS
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
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
    assertContains(csharp, "NugetKotlinHandle collectScope = NugetKotlinHandle.Null;")
    assertFalse(
      csharp.contains("GetOrCreateScope()"),
      "a static member has no GetOrCreateScope(); generatedCSharp=$csharp",
    )
    assertContains(csharp, "NugetStateFlowNative.Collect(flowHandle, collectScope,")
    assertContains(csharp, "internal static class NugetStateFlowNative")
    assertContains(csharp, "public class KotlinStateFlow<T>")
  }


  /**
   * ROADMAP Phase 6 (nullable suspend StateFlow), element half: `StateFlow<T?>` awaits to
   * `KotlinStateFlow<T?>`. Every `.Value` reads through the runtime's
   * `nuget_stateflow_value_or_null`,
   * which hands back a null handle for a null value instead of throwing out of a `@CName` export;
   * `FromHandle<T?>` already reads that as null. A non-null element keeps the shipped
   * `nuget_stateflow_value` read byte for byte.
   */
  @Test
  fun `a top-level suspend StateFlow of a nullable element binds a nullable KotlinStateFlow`() {
    val result: Tier1Result = nullableResult

    assertTrue(
      result.compiledClean,
      "expected the nullable route to compile; got: ${result.compileErrors} ${result.kspErrors}",
    )
    val csharp: String = result.generatedCSharp

    assertContains(csharp, "public static Task<KotlinStateFlow<int?>> WatchStreakAsync(")
    assertContains(csharp, "public static Task<KotlinStateFlow<string?>> WatchNicknameAsync(")
    assertContains(csharp, "Kitten?>> WatchStrayAsync(")
    assertEquals(
      "() => NugetStateFlowNative.ValueOrNull(flowHandle),",
      asyncBody(csharp, "WatchStreakAsync(").lines().map { it.trim() }
        .single { it.contains("NugetStateFlowNative.Value") },
    )
    assertContains(asyncBody(csharp, "WatchNicknameAsync("), VALUE_OR_NULL)
    assertContains(asyncBody(csharp, "WatchStrayAsync("), VALUE_OR_NULL)
    // The non-null twin keeps the shipped read.
    assertEquals(
      "() => NugetStateFlowNative.Value(flowHandle),",
      asyncBody(csharp, "WatchCountAsync(").lines().map { it.trim() }
        .single { it.contains("NugetStateFlowNative.Value") },
    )
    assertContains(
      csharp,
      "EntryPoint = \"nuget_stateflow_value_or_null\")]",
    )
    assertContains(
      csharp,
      "internal static extern IntPtr ValueOrNull(NugetKotlinHandle flowHandle);",
    )
    assertTrue(
      "nuget_stateflow_value_or_null" in NUGET_RUNTIME_EXPORTS,
      "the new runtime export must be pinned beside its pair",
    )
    // The runtime owns the export; the generated Kotlin never declares it.
    assertFalse(
      result.generated.contains("nuget_stateflow_value_or_null"),
      "expected no regenerated runtime export; generated=${result.generated}",
    )
    assertFalse(
      result.kspWarnings.any { it.contains("watchStreak") || it.contains("watchNickname") },
      "expected no skip for a bound nullable element; kspWarnings=${result.kspWarnings}",
    )
  }

  /**
   * Member half: `StateFlow<T>?` awaits to `KotlinStateFlow<T>?`. The Kotlin export already sends a
   * null flow as a zero `resultPtr`; the C# completion must test it before wrapping, or a null
   * Kotlin return awaits to a live holder over a zero handle whose first `.Value` dereferences
   * null.
   * Composes with the element half: `StateFlow<String?>?` is `KotlinStateFlow<string?>?`.
   */
  @Test
  fun `a top-level suspend nullable StateFlow awaits to a nullable holder guarded on the wire`() {
    val result: Tier1Result = nullableResult

    assertTrue(
      result.compiledClean,
      "expected the nullable route to compile; got: ${result.compileErrors} ${result.kspErrors}",
    )
    val csharp: String = result.generatedCSharp

    assertContains(csharp, "public static Task<KotlinStateFlow<int>?> WatchMaybeAsync(")
    assertContains(csharp, "Kitten>?> WatchDenAsync(")
    assertContains(csharp, "public static Task<KotlinStateFlow<string?>?> WatchEitherAsync(")
    listOf("WatchMaybeAsync(", "WatchDenAsync(", "WatchEitherAsync(").forEach { member ->
      val body: String = asyncBody(csharp, member)
      val guard: Int = body.indexOf("if (resultPtr == IntPtr.Zero)")
      assertTrue(guard >= 0, "expected a null-wire guard in $member; body=$body")
      assertTrue(
        guard < body.indexOf("new NugetKotlinHandle(resultPtr)"),
        "expected the guard before the handle is owned in $member; body=$body",
      )
      assertContains(body, "t.SetResult(null);")
      assertFalse(body.contains(">?("), "a nullable type cannot be constructed; body=$body")
    }
    assertContains(asyncBody(csharp, "WatchEitherAsync("), VALUE_OR_NULL)
    assertContains(asyncBody(csharp, "WatchMaybeAsync("), "NugetStateFlowNative.Value(flowHandle)")
    // The non-null member keeps its unguarded shipped completion.
    assertFalse(
      asyncBody(csharp, "WatchCountAsync(").contains("resultPtr == IntPtr.Zero"),
      "expected the non-null member unchanged",
    )
  }

  /**
   * What stays refused on this bucket after the nullable element is admitted: a nullable
   * COLLECTION element has no handle-keyed reader through the runtime pair, so it is still named.
   */
  @Test
  fun `a top-level suspend StateFlow of a nullable collection element is still refused by name`() {
    val result: Tier1Result = nullableResult

    assertFalse(
      result.generatedCSharp.contains("WatchRosterAsync"),
      "expected no C# for the refused member; generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      result.kspWarnings.any {
        it.contains("[nuget:${ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN.name}]") &&
            it.contains("watchRoster")
      },
      "expected a SKIPPED_UNSUPPORTED_RETURN naming watchRoster; kspWarnings=${result.kspWarnings}",
    )
  }

  private companion object {
    const val VALUE_OR_NULL: String = "NugetStateFlowNative.ValueOrNull(flowHandle)"

    // Shared by the three nullable cells: one KSP and compile pass, not three.
    val nullableResult: Tier1Result by lazy {
      Tier1Harness.run(
        """
        package tier1.toplevelsuspendstateflownullable

        import kotlinx.coroutines.flow.MutableStateFlow
        import kotlinx.coroutines.flow.StateFlow

        class Kitten(val name: String)

        private val count = MutableStateFlow(0)
        private val streak = MutableStateFlow<Int?>(null)
        private val nickname = MutableStateFlow<String?>(null)
        private val stray = MutableStateFlow<Kitten?>(null)
        private val roster = MutableStateFlow<List<Int>?>(null)

        suspend fun watchCount(): StateFlow<Int> = count
        suspend fun watchStreak(): StateFlow<Int?> = streak
        suspend fun watchNickname(): StateFlow<String?> = nickname
        suspend fun watchStray(): StateFlow<Kitten?> = stray
        suspend fun watchMaybe(): StateFlow<Int>? = null
        suspend fun watchDen(): StateFlow<Kitten>? = null
        suspend fun watchEither(): StateFlow<String?>? = nickname
        suspend fun watchRoster(): StateFlow<List<Int>?> = roster
        """.trimIndent(),
        libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
      )
    }
  }

  /** The rendered body of one async member, from its declaration to the next declaration. */
  private fun asyncBody(csharp: String, member: String): String {
    val start: Int = csharp.indexOf(member)
    assertTrue(start >= 0, "expected $member; generatedCSharp=$csharp")
    val end: Int = csharp.indexOf("\n        public ", start + member.length)
      .let { if (it < 0) csharp.length else it }
    return csharp.substring(start, end)
  }
}
