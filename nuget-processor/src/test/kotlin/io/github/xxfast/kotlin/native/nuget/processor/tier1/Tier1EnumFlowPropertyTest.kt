package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * ADR-230: a `Flow`, `StateFlow`, `SharedFlow`, `MutableStateFlow` or `MutableSharedFlow` property
 * declared on an enum (in the constructor, in the class body, or abstract and overridden in each
 * entry's body) binds as a C# extension property on `{Enum}Extensions`, the entry crossing as its
 * ordinal through the static flow route. A held mutable flow returns the holder of that entry's
 * own flow, and a nullable collection element rides the same route.
 *
 * The end-to-end half lives in `EnumFlowPropertyTests.cs`.
 */
class Tier1EnumFlowPropertyTest {

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      """
      package tier1.enumflowproperty

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.MutableSharedFlow
      import kotlinx.coroutines.flow.MutableStateFlow
      import kotlinx.coroutines.flow.SharedFlow
      import kotlinx.coroutines.flow.StateFlow
      import kotlinx.coroutines.flow.flowOf

      enum class Tail(val swishes: MutableStateFlow<Int>) {
        OREO(MutableStateFlow(1)) { override val mood: Flow<String> get() = flowOf("flick") },
        MYLO(MutableStateFlow(2)) { override val mood: Flow<String> get() = flowOf("thump") };

        abstract val mood: Flow<String>
        val height: MutableStateFlow<Int> = MutableStateFlow(ordinal)
        val twitches: MutableSharedFlow<String> = MutableSharedFlow(replay = 1)
        val level: StateFlow<Int> get() = height
        val echoes: SharedFlow<String> get() = twitches
        val wags: Flow<Int> get() = flowOf(ordinal)
        val socks: MutableStateFlow<List<Int>?> = MutableStateFlow(null)
      }
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
  }

  @Test
  fun `every flow kind binds as an extension property with the entry as its ordinal`() {
    assertTrue(result.compiledClean, "got: ${result.compileErrors} ${result.kspErrors}")
    val cs: String = result.generatedCSharp
    assertContains(cs, "public static partial class TailExtensions")
    listOf(
      "public KotlinMutableStateFlow<int> Swishes",
      "public KotlinFlow<string> Mood",
      "public KotlinMutableStateFlow<int> Height",
      "public KotlinMutableSharedFlow<string> Twitches",
      "public KotlinStateFlow<int> Level",
      "public KotlinSharedFlow<string> Echoes",
      "public KotlinFlow<int> Wags",
      "public KotlinMutableStateFlow<IReadOnlyList<int>?> Socks",
    ).forEach { signature -> assertContains(cs, signature) }
    // The held acquire reads the entry by its ordinal, through the entry's own override.
    assertContains(result.generated, "Tail.entries[receiver].height")
    assertContains(result.generated, "Tail.entries[receiver].mood")
    assertTrue(
      result.kspWarnings.none { it.contains("[nuget:") },
      "expected no diagnostic; kspWarnings=${result.kspWarnings}",
    )
  }

  @Test
  fun `the consumer reads and writes each entry's own flows`() {
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
              public static async Task<bool> Run()
              {
                  using KotlinMutableStateFlow<int> height = Tail.Oreo.Height;
                  height.Value = 7;
                  using KotlinMutableSharedFlow<string> twitches = Tail.Mylo.Twitches;
                  bool emitted = twitches.TryEmit("thump");
                  await twitches.EmitAsync("thump");
                  using KotlinMutableStateFlow<int> swishes = Tail.Mylo.Swishes;
                  swishes.Value = 3;
                  using KotlinStateFlow<int> level = Tail.Oreo.Level;
                  using KotlinSharedFlow<string> echoes = Tail.Mylo.Echoes;
                  using KotlinMutableStateFlow<IReadOnlyList<int>?> socks = Tail.Oreo.Socks;
                  socks.Value = null;
                  await foreach (string mood in Tail.Oreo.Mood) { }
                  await foreach (int wag in Tail.Mylo.Wags) { }
                  return emitted && level.Value == 7 && echoes.ReplayCache.Count == 1;
              }
          }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }
}
