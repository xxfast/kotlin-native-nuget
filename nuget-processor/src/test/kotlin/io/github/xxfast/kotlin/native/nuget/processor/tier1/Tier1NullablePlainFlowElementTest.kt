package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-067 widened to plain `Flow`: a `Flow<T?>` element is nullable on BOTH halves.
 *
 * Until 2026-09-20 only `StateFlow` computed element nullability (`isStateFlowType && ...`), so a
 * plain `Flow<Pet?>` bound as a non-null `KotlinFlow<IPet>` and the Kotlin half boxed
 * `NugetHandles.retain(value as Any)`. That is the worst of the three possible outcomes: not a
 * refusal, not a binding -- a binding that compiles and then **faults the stream** on the first
 * null emission (measured in `IntegrationTests`: `KotlinException` out of
 * `KotlinFlowEnumerator.MoveNextAsync`, so the consumer sees the flow die rather than yield null).
 *
 * The gap was per ROUTE, not per element type, so the cells below cover three element families on
 * both positions the route has: an interface (the `read:` delegate arm), a nullable reference
 * scalar and a nullable value scalar (`int?` instantiates `T` as `Nullable<int>`, which
 * `FromHandle<T>`'s ADR-067 underlying-type dispatch already reads).
 *
 * Oreo naps by the window and counts whoever goes past; sometimes it is nobody at all.
 */
class Tier1NullablePlainFlowElementTest {

  private val source: String = """
    package tier1.window

    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.flowOf

    interface Pet {
      fun speak(): String
    }

    fun strayPet(): Pet = object : Pet {
      override fun speak(): String = "Mrrp?"
    }

    // No Kotlin superclass: a Flow member on a class that has one does not generate a usable scope
    // handle today, which is a separate defect from the element nullability this test is about.
    class PassersBy {
      fun take(pet: Pet) {}

      // The PROPERTY position of the route.
      val pets: Flow<Pet?> = flowOf(strayPet(), null)
      val remarks: Flow<String?> = flowOf("a tail", null)
      val naps: Flow<Int?> = flowOf(1, null)

      // ...and the METHOD position, which computes its own nullability in a separate copy of the
      // same code on each half.
      fun petsPassingBy(): Flow<Pet?> = pets
      fun remarksPassingBy(): Flow<String?> = remarks
      fun napsPassingBy(): Flow<Int?> = naps
    }
  """.trimIndent()

  /** The C# half: the declared element carries `?` at both positions, for all three families. */
  @Test
  fun `a plain Flow of a nullable element declares the nullable element type`() {
    val result = Tier1Harness.run(
      source,
      fileName = "PassersBy.kt",
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val csharp: String = result.generatedCSharp

    listOf(
      "KotlinFlow<global::Interop.IPet?> Pets",
      "KotlinFlow<string?> Remarks",
      "KotlinFlow<int?> Naps",
      "KotlinFlow<global::Interop.IPet?> PetsPassingBy()",
      "KotlinFlow<string?> RemarksPassingBy()",
      "KotlinFlow<int?> NapsPassingBy()",
    ).forEach { expected ->
      assertContains(
        csharp,
        expected,
        message = "expected a nullable plain-Flow element; csharp=" +
            "${csharp.lines().filter { it.contains("KotlinFlow<") }}",
      )
    }
  }

  /**
   * The interface element additionally needs the ADR-136 nullable `read:` arm: the wire pointer is
   * tested BEFORE the bridge-token probe, so a null emission never asks C# to resolve
   * `IntPtr.Zero` and never hands out a live wrapper over a null handle.
   */
  @Test
  fun `a plain Flow of a nullable interface element reads the null pointer before resolving`() {
    val result = Tier1Harness.run(
      source,
      fileName = "PassersBy.kt",
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    val csharp: String = result.generatedCSharp
    val nullableRead =
      "read: static h => h == IntPtr.Zero ? null : (NugetMarshal.TryResolveCSharp(h, out " +
          "global::Interop.IPet csharpOriginal) ? csharpOriginal : new global::Interop.Pet(h, out _))"

    assertContains(
      csharp,
      nullableRead,
      message = "expected the nullable element read arm; csharp=" +
          "${csharp.lines().filter { it.contains("read: static h") }}",
    )
  }

  /**
   * The Kotlin half, which no C# assertion can see: every emission on these six members is boxed
   * through the null-guarded expression, and the shipped unguarded `value as Any` -- the box that
   * threw on a null emission -- is gone from the file entirely.
   */
  @Test
  fun `a plain Flow of a nullable element boxes each emission null-guarded`() {
    val result = Tier1Harness.run(
      source,
      fileName = "PassersBy.kt",
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    val kotlin: String = result.generated

    assertContains(
      kotlin,
      "val itemRef = if (value != null) NugetHandles.retain(value) else null",
      message = "expected the null-guarded emission box; kotlin=" +
          "${kotlin.lines().filter { it.contains("itemRef") }}",
    )
    assertEquals(
      6,
      Regex(Regex.escape("if (value != null) NugetHandles.retain(value) else null"))
        .findAll(kotlin).count(),
      "expected all six flow members (three properties, three methods) null-guarded",
    )
    assertEquals(
      0,
      Regex(Regex.escape("NugetHandles.retain(value as Any)")).findAll(kotlin).count(),
      "expected no unguarded `value as Any` box left on a nullable-element flow; kotlin=" +
          "${kotlin.lines().filter { it.contains("value as Any") }}",
    )
  }

  /**
   * The suspend route, `suspend fun (): StateFlow<T?>`, which reads every element through the
   * runtime's shared pair rather than per-member exports. Until 2026-10-08 it skipped named,
   * because `nuget_stateflow_value` boxes `flow.value as Any` with no null arm. It now reads
   * through the runtime's `nuget_stateflow_value_or_null` instead, and the class-method twin of
   * the top-level cells (`Tier1TopLevelSuspendStateFlowTest`) binds with the same spelling,
   * collecting on the owner's scope. A nullable MEMBER on the same route is guarded on the wire
   * pointer.
   */
  @Test
  fun `a suspend fun returning a nullable-element StateFlow binds through the null-aware read`() {
    val result = Tier1Harness.run(
      """
      package tier1.window

      import kotlinx.coroutines.flow.MutableStateFlow
      import kotlinx.coroutines.flow.StateFlow
      import kotlinx.coroutines.flow.asStateFlow

      class Window {
        private val onWatch: MutableStateFlow<String?> = MutableStateFlow(null)

        suspend fun awaitWatch(): StateFlow<String?> = onWatch.asStateFlow()

        suspend fun awaitShutters(): StateFlow<Int>? = null

        // The non-null twin on the same route keeps the shipped read.
        suspend fun awaitLead(): StateFlow<String> = MutableStateFlow("Mylo").asStateFlow()
      }
      """.trimIndent(),
      fileName = "Window.kt",
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(
      result.compiledClean,
      "expected the class-method route to compile; " +
          "got: ${result.compileErrors} ${result.kspErrors}",
    )
    val csharp: String = result.generatedCSharp
    assertContains(csharp, "public Task<KotlinStateFlow<string?>> AwaitWatchAsync(")
    assertContains(csharp, "public Task<KotlinStateFlow<int>?> AwaitShuttersAsync(")
    assertContains(csharp, "public Task<KotlinStateFlow<string>> AwaitLeadAsync(")
    assertEquals(
      1,
      Regex(Regex.escape("NugetStateFlowNative.ValueOrNull(flowHandle)")).findAll(csharp).count(),
      "expected exactly the nullable-element member on the null-aware read; csharp=" +
          "${csharp.lines().filter { it.contains("NugetStateFlowNative.Value") }}",
    )
    assertEquals(
      2,
      Regex(Regex.escape("NugetStateFlowNative.Value(flowHandle)")).findAll(csharp).count(),
      "expected the two non-null elements on the shipped read; csharp=" +
          "${csharp.lines().filter { it.contains("NugetStateFlowNative.Value") }}",
    )
    assertEquals(
      1,
      Regex(Regex.escape("if (resultPtr == IntPtr.Zero)")).findAll(csharp).count(),
      "expected only the nullable member guarded on the wire; csharp=$csharp",
    )
    assertTrue(
      result.kspWarnings.none { it.contains("awaitWatch") || it.contains("awaitShutters") },
      "expected no skip for either nullable shape; kspWarnings=${result.kspWarnings}",
    )
  }
}
