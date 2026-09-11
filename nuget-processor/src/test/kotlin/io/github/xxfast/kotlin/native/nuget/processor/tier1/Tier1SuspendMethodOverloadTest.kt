package io.github.xxfast.kotlin.native.nuget.processor.tier1

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
 * The end-to-end half lives in `AsyncCatService`/`AsyncCatSitter`/`CatMoodTracker` and
 * `SuspendMethodOverloadTests.cs`.
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
      "shelter_adopt_async", "shelter_adopt_2_async",
      "shelter_rehome_async", "shelter_rehome_2_async",
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
      "job_running_pause_async", "job_running_pause_2_async",
      "job_running_resume_async", "job_idle_nap_async",
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
}
