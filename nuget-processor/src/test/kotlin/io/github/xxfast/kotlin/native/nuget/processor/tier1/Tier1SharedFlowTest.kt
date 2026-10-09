package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * ADR-205: a declared `SharedFlow<T>` (and a declared `MutableSharedFlow<T>`, as the read-only
 * view) binds as `KotlinFlow<T>` through the shipped per-member `_collect` export, at a property,
 * a method return and a `suspend` return, on every owner the plain `Flow<T>` route reaches.
 *
 * Replay is Kotlin-side (`SharedFlow.collect` replays the cache, then never completes), so there
 * is no new export shape: each cell asserts the same collect export a `Flow<T>` member gets.
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
  fun `class property, method and suspend return bind as KotlinFlow`() {
    assertTrue(result.compiledClean, result.compileErrors.toString())
    val cs: String = result.generatedCSharp
    assertContains(cs, "public KotlinFlow<string> Headlines")
    assertContains(cs, "public KotlinFlow<int> Editions")
    assertContains(cs, "public KotlinFlow<global::Interop.Cat> Cats")
    assertContains(cs, "public KotlinFlow<int> EditionReport()")
    assertContains(cs, "public Task<KotlinFlow<int>> LatestAsync(")
    val kt: String = result.generated
    assertContains(kt, "obj.headlines.collect")
    assertContains(kt, "obj.editionReport().collect")
    assertContains(kt, "flowHandle.asStableRef<Flow<Int>>()")
  }

  @Test
  fun `a declared MutableSharedFlow binds as the read-only view`() {
    val cs: String = result.generatedCSharp
    assertContains(cs, "public KotlinFlow<int> Extras")
    assertContains(result.generated, "obj.extras.collect")
  }

  @Test
  fun `a nullable SharedFlow element follows the nullable Flow element widening`() {
    assertContains(result.generatedCSharp, "public KotlinFlow<string?> Maybe")
  }

  @Test
  fun `interface, sealed-arm and top-level suspend owners reach the shared route`() {
    val cs: String = result.generatedCSharp
    assertContains(cs, "KotlinFlow<int> Pings")
    assertContains(cs, "KotlinFlow<int> PingReport()")
    assertContains(cs, "public KotlinFlow<int> Purrs")
    assertContains(cs, "public KotlinFlow<int> PurrReport()")
    assertContains(cs, "Task<KotlinFlow<int>> BulletinWireAsync(")
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
