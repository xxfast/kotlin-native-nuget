package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * ADR-232: a C# class implementing a Kotlin interface whose members are `suspend` or return a
 * `Flow` crosses to Kotlin. The bridge factory carries a Begin/End pair per suspend member and a
 * key-returning slot per Flow member, and a `suspend` or Flow-returning member takes the
 * interface as a parameter.
 */
class Tier1AsyncInterfaceBridgeTest {

  private val unimplementable: String = ForwardDiagnosticKind.SKIPPED_UNIMPLEMENTABLE_INTERFACE.name

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      """
      package tier1.asyncbridge

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.toList

      enum class Mood { CALM, ZOOMIES }

      interface Pet {
        val name: String
        suspend fun fetch(toy: String): Int
        suspend fun nap()
        suspend fun mood(): Mood
        suspend fun treat(): String?
        fun treats(): Flow<String>
        val purrs: Flow<Int>
        suspend fun parcel(): Flow<String>
      }

      interface Alarm {
        suspend fun ring(): String
      }

      interface Shelf<T> {
        suspend fun take(slot: Int): T
        fun stock(): Flow<T>
      }

      suspend fun play(pet: Pet): String = "${'$'}{pet.name} fetched ${'$'}{pet.fetch("ball")}"

      suspend fun count(pet: Pet): Int = pet.treats().toList().size + pet.purrs.toList().sum()

      suspend fun <T> first(shelf: Shelf<T>): T = shelf.take(0)

      class Kennel {
        fun relay(pet: Pet): Flow<String> = pet.treats()
      }
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
  }

  @Test
  fun `a suspend and Flow interface plans a bridge factory on both halves`() {
    assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
    assertTrue(result.kspWarnings.none { it.contains(unimplementable) }, "${result.kspWarnings}")
    assertContains(result.generated, "pet_bridge_create")
    assertContains(result.generated, "override suspend fun fetch(toy: String): Int")
    assertContains(result.generated, "fetchPtr_end")
    assertContains(result.generated, "flowForKotlin(")
    val cs: String = result.generatedCSharp
    assertContains(cs, "NugetTasks.Begin(nugetToken => impl.FetchAsync(value0, nugetToken)")
    assertContains(cs, "NugetAsyncEnumeration.Enumerate(result)")
    assertContains(cs, "NugetTasks.BeginBoxed<T>(nugetToken => impl.TakeAsync(value0, nugetToken)")
    assertContains(result.generated, "override suspend fun parcel(): kotlinx.coroutines.flow.Flow<String>")
    // An interface no position reaches still declares its suspend member.
    assertContains(cs, "Task<string> RingAsync(")
  }

  @Test
  fun `a suspend or Flow-returning member takes the interface as a parameter`() {
    val skipped: List<String> = listOf("play", "count", "first", "relay")
      .filter { member -> result.kspWarnings.any { warning -> "$member:" in warning } }
    assertTrue(skipped.isEmpty(), "still skipped: $skipped; kspWarnings=${result.kspWarnings}")
  }

  @Test
  fun `the generated C# compiles against a C# implementation of each interface`() {
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using System.Collections.Generic;
      using System.Runtime.CompilerServices;
      using System.Threading;
      using System.Threading.Tasks;

      namespace Consumer
      {
          public sealed class Cat : global::Interop.IPet
          {
              public string Name => "Oreo";
              public Task<int> FetchAsync(string toy, CancellationToken cancellationToken = default) => Task.FromResult(toy.Length);
              public Task NapAsync(CancellationToken cancellationToken = default) => Task.CompletedTask;
              public Task<global::Interop.Mood> MoodAsync(CancellationToken cancellationToken = default) => Task.FromResult(global::Interop.Mood.Calm);
              public Task<string?> TreatAsync(CancellationToken cancellationToken = default) => Task.FromResult<string?>(null);
              public global::Interop.KotlinFlow<string> Treats() => global::Interop.KotlinFlow<string>.From(Names());
              public global::Interop.KotlinFlow<int> Purrs => global::Interop.KotlinFlow<int>.From(Counts());
              public async Task<global::Interop.KotlinFlow<string>> ParcelAsync(CancellationToken cancellationToken = default)
              {
                  await Task.Yield();
                  return Treats();
              }
              private static async IAsyncEnumerable<string> Names([EnumeratorCancellation] CancellationToken token = default)
              {
                  await Task.Yield();
                  yield return "tuna";
              }
              private static async IAsyncEnumerable<int> Counts([EnumeratorCancellation] CancellationToken token = default)
              {
                  await Task.Yield();
                  yield return 1;
              }
              public void Dispose() { }
              public ValueTask DisposeAsync() => default;
          }

          public sealed class Box<T>(T item) : global::Interop.IShelf<T>
          {
              public Task<T> TakeAsync(int slot, CancellationToken cancellationToken = default) => Task.FromResult(item);
              public global::Interop.KotlinFlow<T> Stock() => global::Interop.KotlinFlow<T>.From(Items());
              private async IAsyncEnumerable<T> Items([EnumeratorCancellation] CancellationToken token = default)
              {
                  await Task.Yield();
                  yield return item;
              }
              public void Dispose() { }
              public ValueTask DisposeAsync() => default;
          }

          public static class Probe
          {
              public static async Task<string> Run()
              {
                  var cat = new Cat();
                  int counted = await global::Interop.Fixture.CountAsync(cat);
                  string first = await global::Interop.Fixture.FirstAsync(new Box<string>("kibble"));
                  return await global::Interop.Fixture.PlayAsync(cat) + counted + first;
              }
          }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }

  /**
   * Hot flows at a C#-implemented interface: the slot hands back the handle of a Kotlin flow C#
   * created with `KotlinMutableStateFlow<T>.Create` / `KotlinMutableSharedFlow<T>.Create`.
   */
  @Test
  fun `hot flow members bridge through flows C# creates`() {
    val hot: Tier1Result = Tier1Harness.run(
      """
      package tier1.asyncbridgestate

      import kotlinx.coroutines.flow.MutableSharedFlow
      import kotlinx.coroutines.flow.MutableStateFlow
      import kotlinx.coroutines.flow.SharedFlow
      import kotlinx.coroutines.flow.StateFlow

      interface Pet {
        val mood: StateFlow<Int>
        val dial: MutableStateFlow<Int>
        fun news(): SharedFlow<String>
        fun bell(): MutableSharedFlow<String>
      }

      fun read(pet: Pet): Int = pet.mood.value
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
    assertTrue(hot.compiledClean, "compileErrors=${hot.compileErrors}")
    assertTrue(hot.kspWarnings.none { it.contains(unimplementable) }, "${hot.kspWarnings}")
    assertContains(hot.generated, "pet_bridge_create")
    assertContains(hot.generated, "__hotflow_state_create")
    assertContains(hot.generatedCSharp, "return NugetHotFlowNative.Retain(result);")
    Tier1CSharpCompile.assertCompiles(
      hot,
      """
      using System.Threading.Tasks;

      namespace Consumer
      {
          public sealed class Cat : global::Interop.IPet
          {
              public global::Interop.KotlinStateFlow<int> Mood { get; } = global::Interop.KotlinMutableStateFlow<int>.Create(1);
              public global::Interop.KotlinMutableStateFlow<int> Dial { get; } = global::Interop.KotlinMutableStateFlow<int>.Create(0);
              private readonly global::Interop.KotlinMutableSharedFlow<string> _news = global::Interop.KotlinMutableSharedFlow<string>.Create(replay: 1);
              public global::Interop.KotlinSharedFlow<string> News() => _news;
              public global::Interop.KotlinMutableSharedFlow<string> Bell() => _news;
              public void Dispose() { }
              public ValueTask DisposeAsync() => default;
          }

          public static class Probe
          {
              public static int Run() => global::Interop.Fixture.Read(new Cat());
          }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }
}
