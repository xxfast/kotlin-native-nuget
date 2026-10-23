package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * A `MutableStateFlow` or `MutableSharedFlow` of a nullable collection or `ByteArray` element
 * (`MutableStateFlow<List<Int>?>`, `MutableStateFlow<ByteArray?>`,
 * `MutableSharedFlow<Set<String>?>`)
 * binds settable on every owner the held route reaches: a class, an `object` and the top level.
 * Null crosses as a null handle both ways, the encoding a nullable `ByteArray` element already
 * used: Kotlin boxes a null emission or `.value` as a null pointer, C# guards the read on
 * `IntPtr.Zero` before `ReadList`, and the C# write passes `IntPtr.Zero` for null instead of
 * minting a container.
 *
 * The end-to-end half lives in `NullableCollectionFlowTests.cs`.
 */
class Tier1MutableFlowNullableCollectionElementTest {

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      """
      package tier1.nullablecollectionflow

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.MutableSharedFlow
      import kotlinx.coroutines.flow.MutableStateFlow
      import kotlinx.coroutines.flow.StateFlow
      import kotlinx.coroutines.flow.flowOf

      enum class Mood { CALM, WILD }

      class Basket {
        val counts: MutableStateFlow<List<Int>?> = MutableStateFlow(null)
        val photo: MutableStateFlow<ByteArray?> = MutableStateFlow(null)
        val tags: MutableSharedFlow<Set<String>?> = MutableSharedFlow(replay = 1)
        val moods: MutableStateFlow<List<Mood>?> = MutableStateFlow(null)
        val plain: Flow<Map<String, Int>?> = flowOf(null, mapOf("oreo" to 1))
        fun countsJar(): MutableStateFlow<List<Int>?> = counts
        suspend fun awaitCounts(): MutableStateFlow<List<Int>?> = counts
        suspend fun awaitPhoto(): MutableStateFlow<ByteArray?> = photo
        suspend fun awaitRead(): StateFlow<List<Int>?> = counts
      }

      object Shelf {
        val counts: MutableStateFlow<List<Int>?> = MutableStateFlow(null)
        val tags: MutableSharedFlow<Set<String>?> = MutableSharedFlow(replay = 1)
      }

      val looseCounts: MutableStateFlow<List<Int>?> = MutableStateFlow(null)
      val looseTags: MutableSharedFlow<Set<String>?> = MutableSharedFlow(replay = 1)
      fun looseJar(): MutableStateFlow<List<Int>?> = looseCounts
      suspend fun awaitLoose(): MutableStateFlow<List<Int>?> = looseCounts
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
  }

  @Test
  fun `every owner binds the nullable element settable, with no diagnostic`() {
    assertTrue(result.compiledClean, "got: ${result.compileErrors} ${result.kspErrors}")
    val cs: String = result.generatedCSharp
    listOf(
      "public KotlinMutableStateFlow<IReadOnlyList<int>?> Counts",
      "public KotlinMutableStateFlow<byte[]?> Photo",
      "public KotlinMutableSharedFlow<IReadOnlySet<string>?> Tags",
      "public KotlinMutableStateFlow<IReadOnlyList<global::Interop.Mood>?> Moods",
      "public KotlinFlow<IReadOnlyDictionary<string, int>?> Plain",
      "public KotlinMutableStateFlow<IReadOnlyList<int>?> CountsJar()",
      "public Task<KotlinMutableStateFlow<IReadOnlyList<int>?>> AwaitCountsAsync(",
      "public Task<KotlinMutableStateFlow<byte[]?>> AwaitPhotoAsync(",
      "public Task<KotlinStateFlow<IReadOnlyList<int>?>> AwaitReadAsync(",
      "public static KotlinMutableStateFlow<IReadOnlyList<int>?> Counts",
      "public static KotlinMutableSharedFlow<IReadOnlySet<string>?> Tags",
      "public static KotlinMutableStateFlow<IReadOnlyList<int>?> LooseCounts",
      "public static KotlinMutableSharedFlow<IReadOnlySet<string>?> LooseTags",
      "public static KotlinMutableStateFlow<IReadOnlyList<int>?> LooseJar()",
      "public static Task<KotlinMutableStateFlow<IReadOnlyList<int>?>> AwaitLooseAsync(",
    ).forEach { signature -> assertContains(cs, signature) }
    assertTrue(
      result.kspWarnings.none { it.contains("[nuget:") },
      "expected no diagnostic; kspWarnings=${result.kspWarnings}",
    )
  }

  @Test
  fun `null crosses as a null handle on both halves`() {
    val kotlin: String = result.generated
    val cs: String = result.generatedCSharp
    // Kotlin: a null emission and a null `.value` leave as a null pointer, a component that
    // projects (an enum to its ordinal) projected behind the guard.
    assertContains(
      kotlin,
      "if (value != null) NugetHandles.retain(value.map { it.ordinal }) else null",
    )
    assertContains(
      kotlin,
      "return if (v != null) NugetHandles.retain(v.map { it.ordinal }) else null",
    )
    // Kotlin: a null write slot is a null element.
    assertContains(kotlin, ".counts.value = value?.asStableRef<MutableList<Any?>>()?.get()?.map {")
    // C#: the read is guarded before the container helper touches the pointer.
    assertContains(cs, "read: static h => h == IntPtr.Zero ? null : NugetMarshal.ReadList<int>(")
    assertContains(cs, "read: static h => h == IntPtr.Zero ? null : NugetMarshal.ReadSet<string>(")
    // C#: a null write mints nothing.
    assertContains(cs, "IntPtr vBox = v != null ? NugetMarshal.CreateList(v) : IntPtr.Zero;")
  }

  @Test
  fun `the consumer reads and writes null and a collection on every owner`() {
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using System.Collections.Generic;
      using System.Threading.Tasks;
      using Interop;

      namespace Consumer
      {
          public static class Probe
          {
              public static async Task<bool> Run(Basket basket)
              {
                  KotlinMutableStateFlow<IReadOnlyList<int>?> counts = basket.Counts;
                  counts.Value = null;
                  counts.Value = new List<int> { 1, 2 };
                  bool swapped = counts.CompareAndSet(counts.Value, null);
                  basket.Photo.Value = null;
                  basket.Photo.Value = new byte[] { 1 };
                  await basket.Tags.EmitAsync(null);
                  bool emitted = basket.Tags.TryEmit(new HashSet<string> { "oreo" });
                  IReadOnlyList<IReadOnlySet<string>?> replay = basket.Tags.ReplayCache;
                  using KotlinMutableStateFlow<IReadOnlyList<int>?> awaited = await basket.AwaitCountsAsync();
                  awaited.Value = null;
                  Shelf.Counts.Value = null;
                  await Shelf.Tags.EmitAsync(null);
                  using KotlinMutableStateFlow<IReadOnlyList<int>?> loose = Fixture.LooseCounts;
                  loose.Value = null;
                  await foreach (IReadOnlyDictionary<string, int>? map in basket.Plain) { if (map is null) break; }
                  return swapped && emitted && replay.Count == 1 && counts.Value is null;
              }
          }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }
}
