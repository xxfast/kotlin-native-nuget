package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-118 / ROADMAP line 54: the legacy suspend route composed `${prefix}_${name}_async` off the
 * **bare** method name at three sites (`SuspendFunctionExports`, and both C# projections), so two
 * `suspend` overloads asked for one C symbol and the round failed on a duplicate entry point. Both
 * halves now read `ForwardCallablePlanCatalog.overloadSuffix`, exactly as the Flow route does since
 * issue #97 ([Tier1FlowMethodOverloadTest]).
 *
 * The suffix has to reach **three** fields, not two: the `[DllImport]`'s `EntryPoint` (the C
 * symbol), the import's C# name and `CirMethod.nativeName` (the extern the rendered body calls).
 * The second and third are what the `Native_..._2Async` assertions below pin: an implementation
 * that numbers only the entry point still compiles whenever the two externs agree on argument
 * types, and the second overload's body silently calls the first overload's extern.
 *
 * The end-to-end half lives in `AsyncCatService`/`AsyncCatSitter`/`CatMoodTracker`, the top-level
 * `AsyncFunctions.kt` (ROADMAP line 29) and `SuspendMethodOverloadTests.cs`.
 */
class Tier1SuspendMethodOverloadTest {

  @Test
  fun `suspend overloads on an ordinary class get numbered entry points and externs`() {
    val result = Tier1Harness.run(
      """
      package tier1.suspendoverload

      class Cat(val name: String)

      class Shelter {
        suspend fun adopt(name: String): Int = name.length
        suspend fun adopt(name: String, lives: Int): Int = name.length + lives
        suspend fun rehome(cats: List<Int>): String = cats.joinToString()
        suspend fun rehome(names: Set<String>): String = names.joinToString()
      }
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(
      result.compiledClean,
      "expected both overload pairs to compile; got: ${result.compileErrors} ${result.kspErrors}",
    )

    val kotlin: String = result.generated
    listOf(
      "library_tier1_suspendoverload__shelter_adopt_async", "library_tier1_suspendoverload__shelter_adopt_2_async",
      "library_tier1_suspendoverload__shelter_rehome_async", "library_tier1_suspendoverload__shelter_rehome_2_async",
    ).forEach { entryPoint ->
      assertContains(kotlin, "@CName(\"$entryPoint\")")
      assertEquals(
        1,
        Regex("EntryPoint = \"$entryPoint\"").findAll(result.generatedCSharp).count(),
        "expected exactly one C# import for $entryPoint; generatedCSharp=${result.generatedCSharp}",
      )
    }

    // The extern *name* half. `rehome`'s pair is the load-bearing one: both parameters cross as a
    // single IntPtr to a boxed wire container, so an unnumbered `nativeName` would have bound the
    // second body to the first extern rather than failing to compile.
    listOf(
      "Native_AdoptAsync", "Native_Adopt_2Async",
      "Native_RehomeAsync", "Native_Rehome_2Async",
    ).forEach { externName ->
      assertContains(result.generatedCSharp, "$externName(")
    }
  }

  /**
   * ADR-118's other half on one fixture: a `suspend fun` **declared on a sealed arm** binds under
   * the arm's own export prefix, and the arm's overload pair is numbered by the planner's separate
   * `sealedSubclassEntries` counter (whose numbered symbol the sealed post-process copies).
   *
   * Deliberately a sealed-only module: no ordinary class with a `suspend fun`, no top-level
   * `suspend fun`, no Flow. The coroutine imports and the shared `nuget_scope_*`/`nuget_job_*`
   * exports are gated on those, so only a module like this one proves that ADR-118 widened both
   * gates to arms. Were either gate still `classes`-only, the generated file would not compile
   * (missing imports) or would call a `nuget_scope_create` that does not exist.
   */
  @Test
  fun `suspend members on a sealed arm export under the arm prefix and number their overloads`() {
    val result = Tier1Harness.run(
      """
      package tier1.suspendarm

      sealed class Job {
        open suspend fun rest(): Int = 0

        data class Running(val progress: Int) : Job() {
          suspend fun pause(): Int = progress
          suspend fun pause(millis: Int): Int = progress + millis
          suspend fun resume(prefix: String): String = "${'$'}prefix${'$'}progress"
        }

        data object Idle : Job() {
          suspend fun nap(): String = "napping"
        }

        data class Done(val code: Int) : Job()
      }
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(
      result.compiledClean,
      "expected the sealed arms' suspend exports to compile; got: ${result.compileErrors} " +
          "${result.kspErrors}",
    )

    val kotlin: String = result.generated
    listOf(
      "library_tier1_suspendarm__job_running_pause_async", "library_tier1_suspendarm__job_running_pause_2_async",
      "library_tier1_suspendarm__job_running_resume_async", "library_tier1_suspendarm__job_idle_nap_async",
    ).forEach { entryPoint ->
      assertContains(kotlin, "@CName(\"$entryPoint\")")
      assertEquals(
        1,
        Regex("EntryPoint = \"$entryPoint\"").findAll(result.generatedCSharp).count(),
        "expected exactly one C# import for $entryPoint; generatedCSharp=${result.generatedCSharp}",
      )
    }

    // ADR-127: the shared `nuget_*` exports ship unconditionally from the `:nuget-runtime`
    // klib, so the generated file no longer declares them. `scripts/verify-runtime-exports.sh`
    // checks them on the linked binary instead.

    // The declared-only rule: `rest` is declared on the base and overridden by no arm, so no arm
    // may export it and no arm may render it.
    listOf("job_running_rest_async", "job_idle_rest_async", "job_done_rest_async")
      .forEach { entryPoint ->
        assertTrue(
          !kotlin.contains(entryPoint),
          "a base-declared `open suspend fun` must not export under an arm prefix; found " +
              "$entryPoint in $kotlin",
        )
      }
    assertTrue(
      !result.generatedCSharp.contains("RestAsync"),
      "a base-declared `open suspend fun` must not render on any arm; " +
          "generatedCSharp=${result.generatedCSharp}",
    )

    val csharp: String = result.generatedCSharp
    listOf("Native_PauseAsync", "Native_Pause_2Async", "Native_ResumeAsync", "Native_NapAsync")
      .forEach { externName -> assertContains(csharp, "$externName(") }

    // ADR-118's per-arm scope: a suspending arm is IAsyncDisposable and owns the scope; the base
    // and a non-suspending arm are unchanged.
    assertContains(csharp, "public sealed class Running : Job, IAsyncDisposable")
    assertContains(csharp, "public sealed class Idle : Job, IAsyncDisposable")
    assertContains(csharp, "public sealed class Done : Job\n")
    assertContains(csharp, "public abstract class Job : IDisposable, INugetHandle")
  }

  /**
   * ROADMAP line 29, the top-level twin of the first cell: `addSuspendFunctionExports` and
   * `translateSuspendFunction` composed `${cname}_async` with no overload suffix, so this pair
   * failed the round with ERROR_C_ENTRY_POINT_COLLISION. Arity cell: the externs differ in
   * parameter count, so only the C symbol needed the number.
   */
  @Test
  fun `top-level suspend overloads of different arity get numbered entry points`() {
    val result = Tier1Harness.run(
      """
      package tier1.suspendtoplevelarity

      suspend fun fetch(name: String): Int = name.length
      suspend fun fetch(name: String, lives: Int): Int = name.length + lives
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(
      result.compiledClean,
      "expected the top-level arity pair to compile; got: ${result.compileErrors} " +
          "${result.kspErrors}",
    )
    assertEntryPointsOnBothHalves(
      result,
      "library_tier1_suspendtoplevelarity__fetch_async",
      "library_tier1_suspendtoplevelarity__fetch_2_async",
    )
    listOf("FetchAsync_native", "Fetch_2Async_native").forEach { externName ->
      assertExternDeclaredAndCalled(result.generatedCSharp, externName)
    }
    // The public name carries no number: one natural C# overload set.
    assertEquals(
      2,
      Regex("Task<int> FetchAsync\\(").findAll(result.generatedCSharp).count(),
      "generatedCSharp=${result.generatedCSharp}",
    )
  }

  /**
   * The load-bearing top-level cell: both parameters cross as one IntPtr to a boxed wire container,
   * so the two externs have identical native parameters. Numbering the entry point alone is CS0111
   * on the externs; numbering the extern but not the wrapper's call site binds the second body to
   * the first overload silently. Hence the declared-and-called count on each extern.
   */
  @Test
  fun `top-level same-wire suspend overloads number the extern and its call site`() {
    val result = Tier1Harness.run(
      """
      package tier1.suspendtoplevelwire

      suspend fun tally(ids: List<Int>): String = "list"
      suspend fun tally(ids: Set<String>): String = "set"
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(
      result.compiledClean,
      "expected the top-level same-wire pair to compile; got: ${result.compileErrors} " +
          "${result.kspErrors}",
    )
    assertEntryPointsOnBothHalves(
      result,
      "library_tier1_suspendtoplevelwire__tally_async",
      "library_tier1_suspendtoplevelwire__tally_2_async",
    )
    listOf("TallyAsync_native", "Tally_2Async_native").forEach { externName ->
      assertExternDeclaredAndCalled(result.generatedCSharp, externName)
    }
  }

  /**
   * The shared-counter decision (ADR-095 parity with the class route, human decision 2026-09-26):
   * an ordinary `ping()` declared first keeps `ping`, the `suspend ping(Int)` takes number 2 on its
   * `_async` symbol, and the ordinary `ping(String)` after it takes 3. A suspend-only counter would
   * have given `ping` / `ping_2` / `ping_async`, which is asserted absent.
   */
  @Test
  fun `top-level suspend and ordinary namesakes share one declaration-order counter`() {
    val result = Tier1Harness.run(
      """
      package tier1.suspendtoplevelmixed

      fun ping(): Int = 1
      suspend fun ping(x: Int): Int = x
      fun ping(x: String): Int = 3
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(
      result.compiledClean,
      "expected the mixed namesakes to compile; got: ${result.compileErrors} ${result.kspErrors}",
    )
    assertEntryPointsOnBothHalves(
      result,
      "library_tier1_suspendtoplevelmixed__ping",
      "library_tier1_suspendtoplevelmixed__ping_2_async",
      "library_tier1_suspendtoplevelmixed__ping_3",
    )
    listOf(
      "library_tier1_suspendtoplevelmixed__ping_2",
      "library_tier1_suspendtoplevelmixed__ping_async",
    ).forEach { absent ->
      assertTrue(
        "@CName(\"$absent\")" !in result.generated &&
            "EntryPoint = \"$absent\"" !in result.generatedCSharp,
        "expected no $absent symbol under the shared counter",
      )
    }
    assertExternDeclaredAndCalled(result.generatedCSharp, "Ping_2Async_native")
  }

  private fun assertEntryPointsOnBothHalves(result: Tier1Result, vararg entryPoints: String) {
    entryPoints.forEach { entryPoint ->
      assertContains(result.generated, "@CName(\"$entryPoint\")")
      assertEquals(
        1,
        Regex("EntryPoint = \"$entryPoint\"").findAll(result.generatedCSharp).count(),
        "expected exactly one C# import for $entryPoint; generatedCSharp=${result.generatedCSharp}",
      )
    }
  }

  /** Declared once as the extern and called once from its wrapper body. */
  private fun assertExternDeclaredAndCalled(csharp: String, externName: String) {
    assertEquals(
      2,
      Regex("\\b$externName\\(").findAll(csharp).count(),
      "expected $externName declared and called once each; generatedCSharp=$csharp",
    )
  }

  /**
   * ADR-159: two `suspend` overloads that render **one** C# signature must fail the round with the
   * ADR-034 kind, not reach the generated file as CS0111. C# cannot overload on reference
   * nullability, and the `CancellationToken` tail every async method grows at render time cannot
   * separate the two either, so `play(String)` / `play(String?)` both spell `PlayAsync(string)`.
   *
   * The guard existed; it ran before the async and Flow members were projected, so it never saw
   * them. ADR-118 gives the two overloads distinct C symbols and externs, which is why the failure
   * was purely the public C# signature and KSP was silent.
   *
   * `@JvmName` is a harness artefact: Tier 1 compiles the fixture for the JVM, where the two
   * `play`s clash. Kotlin/Native does not need it, so it is not in `test-library`. Diagnostic cell,
   * in-process only: its correct outcome is a failed build.
   */
  @Test
  fun `suspend overloads that render one C# signature fire ERROR_CSHARP_SIGNATURE_COLLISION`() {
    val result = Tier1Harness.run(
      """
      package tier1.suspendoverloadcollision

      class Piano(val keys: Int) {
        suspend fun play(note: String): Int = note.length

        @JvmName("playNullable")
        suspend fun play(note: String?): Int = note?.length ?: keys
      }
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(
      result.kspErrors.any {
        it.contains(ForwardDiagnosticKind.ERROR_CSHARP_SIGNATURE_COLLISION.name)
      },
      "expected the async overload collision to fail generation rather than emit CS0111; " +
          "kspErrors=${result.kspErrors}",
    )
  }
}
