package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-231: a plain (non-suspend) lambda parameter on a `suspend` member, and on a member returning
 * a `Flow`, binds as a C# delegate on every owner those routes reach. C# registers the delegate's
 * context key and hands it over once the export returns; the launched (or collected) body releases
 * it through `NugetAsyncReleaseContext` in a `finally`, so the key is gone on completion, fault and
 * cancellation.
 *
 * The `object` owner is the static receiver ADR-229 added; this module also carries no suspend
 * lambda and no `Flow` parameter, so the release-context thunk is emitted for the plain lambda
 * alone.
 *
 * Oreo runs laps on the treadmill at 3am; Mylo watches.
 */
class Tier1SuspendPlainLambdaTest {

  private val options: Map<String, String> = mapOf(
    "nuget.namespace" to "TestLibrary",
    "nuget.rootPackage" to "tier1",
  )

  private val source: String = """
    package tier1.laps

    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.flow

    class Baton(val holder: String)

    object Sprint {
      suspend fun laps(count: Int, onLap: (Int) -> Unit): Int {
        repeat(count) { onLap(it) }
        return count
      }
      suspend fun recruit(name: String, make: (String) -> Baton): String = make(name).holder
      fun stream(count: Int, onLap: (Int) -> Unit): Flow<Int> = flow {
        repeat(count) { onLap(it); emit(it) }
      }
      suspend fun warmUp(count: Int, onLap: (Int) -> Unit): Flow<Int> = flow {
        repeat(count) { onLap(it); emit(it) }
      }
    }

    class Treadmill {
      suspend fun cheer(shout: (String) -> String): String = shout("Oreo")
    }

    interface Pacer {
      suspend fun pace(onStep: (Int) -> Unit): Int
    }

    class Metronome : Pacer {
      override suspend fun pace(onStep: (Int) -> Unit): Int { onStep(1); return 1 }
    }

    fun pacer(): Pacer = Metronome()
  """.trimIndent()

  private fun run(): Tier1Result = Tier1Harness.run(
    source,
    processorOptions = options,
    libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
  )

  @Test
  fun `an object's suspend member with a plain lambda binds and compiles on both halves`() {
    val result: Tier1Result = run()
    assertTrue(result.kspSucceeded, "ksp: ${result.kspErrors}")
    assertTrue(result.compiledClean, "generated Kotlin: ${result.compileErrors}")
    assertEquals(emptyList(), result.kspWarnings.filter { warning -> "Sprint." in warning })
    val csharp: String = result.generatedCSharp

    assertContains(
      csharp,
      "public static Task<int> LapsAsync(int count, Action<int> onLap, " +
        "CancellationToken cancellationToken = default)",
    )
    assertContains(csharp, "public static KotlinFlow<int> Stream(int count, Action<int> onLap)")
    assertContains(csharp, "NugetThunks.NugetAsyncReleaseContextPtr")
    assertContains(csharp, "internal static void NugetAsyncReleaseContextThunk(")
    assertContains(csharp, "onLap is null ? throw new ArgumentNullException(\"onLap\")")
    // An exported class from the delegate is an owned box Kotlin reads and releases.
    assertContains(csharp, "return NugetMarshal.WrapOwned<global::TestLibrary.Laps.Baton>(make(")
    assertContains(result.generated, "} finally {")
    assertContains(result.generated, ".invoke(onLapCtx, null)")
    // A returned flow takes the key back: Kotlin releases it only on a fault or a cancel, and the
    // `KotlinFlow`'s handle releases it when disposed.
    assertContains(result.generated, "} catch (nuget: Throwable) {")
    assertContains(csharp, ".Attach(new NugetContextRelease(onLapAsyncKeyHeld));")
    assertContains(csharp, "internal sealed class NugetContextRelease : IDisposable")
    // A Kotlin-backed interface reaches the same route through its dispatch export.
    assertContains(
      csharp,
      "Task<int> PaceAsync(Action<int> onStep, CancellationToken cancellationToken = default)",
    )
    Tier1CSharpCompile.assertCompiles(result, "class Consumer {}", allowUnsafe = true)
  }
}
