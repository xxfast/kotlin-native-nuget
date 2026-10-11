package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * ADR-205: a declared `SharedFlow<T>` collects through the shipped per-member `_collect` export, at
 * a property, a method return and a `suspend` return, on every owner the plain `Flow<T>` route
 * reaches. ADR-209 respells it `KotlinSharedFlow<T>` (a `KotlinFlow<T>`), and a declared
 * `MutableSharedFlow<T>` `KotlinMutableSharedFlow<T>`; `Tier1SharedFlowSurfaceTest` owns the
 * members that adds.
 *
 * Replay is Kotlin-side (`SharedFlow.collect` replays the cache, then never completes), so the
 * collect seam is the one a `Flow<T>` member gets: each cell asserts that same export.
 *
 * `libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore)` is load-bearing: without it every
 * member here is skipped as an unsupported type and the assertions pass vacuously.
 */
class Tier1SharedFlowTest {

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      """
      package tier1.sharedflow

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.MutableSharedFlow
      import kotlinx.coroutines.flow.SharedFlow
      import kotlinx.coroutines.flow.asSharedFlow

      class Cat(val name: String)

      class Bulletin {
        private val _headlines: MutableSharedFlow<String> = MutableSharedFlow(replay = 1)
        val headlines: SharedFlow<String> = _headlines.asSharedFlow()
        val editions: SharedFlow<Int> = MutableSharedFlow(replay = 1)
        val cats: SharedFlow<Cat> = MutableSharedFlow(replay = 1)
        val extras: MutableSharedFlow<Int> = MutableSharedFlow(replay = 1)
        val maybe: SharedFlow<String?> = MutableSharedFlow(replay = 1)
        fun editionReport(): SharedFlow<Int> = editions
        suspend fun latest(): SharedFlow<Int> = editions
      }

      interface Feed {
        val pings: SharedFlow<Int>
        fun pingReport(): SharedFlow<Int>
      }

      class LoudFeed : Feed {
        override val pings: SharedFlow<Int> = MutableSharedFlow(replay = 1)
        override fun pingReport(): SharedFlow<Int> = pings
      }

      fun feed(): Feed = LoudFeed()

      sealed class Nap {
        class Loaf : Nap() {
          val purrs: SharedFlow<Int> = MutableSharedFlow(replay = 1)
          fun purrReport(): SharedFlow<Int> = purrs
        }
      }

      suspend fun bulletinWire(): SharedFlow<Int> = MutableSharedFlow(replay = 1)
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
  }

  @Test
  fun `class property, method and suspend return bind as KotlinSharedFlow`() {
    assertTrue(result.compiledClean, result.compileErrors.toString())
    val cs: String = result.generatedCSharp
    assertContains(cs, "public KotlinSharedFlow<string> Headlines")
    assertContains(cs, "public KotlinSharedFlow<int> Editions")
    assertContains(cs, "public KotlinSharedFlow<global::Interop.Cat> Cats")
    assertContains(cs, "public KotlinSharedFlow<int> EditionReport()")
    assertContains(cs, "public Task<KotlinSharedFlow<int>> LatestAsync(")
    val kt: String = result.generated
    assertContains(kt, "obj.headlines.collect")
    assertContains(kt, "obj.editionReport().collect")
    assertContains(kt, "flowHandle.asStableRef<Flow<Int>>()")
  }

  @Test
  fun `a declared MutableSharedFlow binds as KotlinMutableSharedFlow over the same collect`() {
    val cs: String = result.generatedCSharp
    assertContains(cs, "public KotlinMutableSharedFlow<int> Extras")
    assertContains(result.generated, "obj.extras.collect")
  }

  @Test
  fun `a nullable SharedFlow element follows the nullable Flow element widening`() {
    assertContains(result.generatedCSharp, "public KotlinSharedFlow<string?> Maybe")
  }

  @Test
  fun `interface, sealed-arm and top-level suspend owners reach the shared route`() {
    val cs: String = result.generatedCSharp
    assertContains(cs, "KotlinSharedFlow<int> Pings")
    assertContains(cs, "KotlinSharedFlow<int> PingReport()")
    assertContains(cs, "public KotlinSharedFlow<int> Purrs")
    assertContains(cs, "public KotlinSharedFlow<int> PurrReport()")
    assertContains(cs, "Task<KotlinSharedFlow<int>> BulletinWireAsync(")
    val unnamed: List<String> = listOf(
      "headlines", "editions", "cats", "extras", "maybe", "editionReport", "latest", "pings",
      "pingReport", "purrs", "purrReport", "bulletinWire",
    ).filter { member -> result.kspWarnings.any { warning -> ".$member:" in warning } }
    assertTrue(unnamed.isEmpty(), "still skipped: $unnamed; kspWarnings=${result.kspWarnings}")
  }

  @Test
  fun `the generated C# compiles against a consumer of every shared member and owner`() {
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using System.Collections.Generic;
      using System.Threading.Tasks;

      namespace Consumer
      {
          public static class Probe
          {
              public static async Task<int> Run(
                  global::Interop.Bulletin bulletin,
                  global::Interop.IFeed feed,
                  global::Interop.Nap.Loaf loaf)
              {
                  IAsyncEnumerable<int> pings = feed.Pings;
                  IAsyncEnumerable<int> pingReport = feed.PingReport();
                  IAsyncEnumerable<int> purrs = loaf.Purrs;
                  IAsyncEnumerable<int> purrReport = loaf.PurrReport();
                  IAsyncEnumerable<int> wire = await global::Interop.Fixture.BulletinWireAsync();
                  IAsyncEnumerable<string> headlines = bulletin.Headlines;
                  IAsyncEnumerable<int> extras = bulletin.Extras;
                  IAsyncEnumerable<int> report = bulletin.EditionReport();
                  await foreach (int edition in await bulletin.LatestAsync()) { return edition; }
                  return 0;
              }
          }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }
}
