package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-208 part E: a `Flow<E>` / `StateFlow<E>` type argument of an exported generic class
 * (`Box<Flow<Int>>`) binds wherever the instantiation does. `box.Value` is a `KotlinFlow<E>` /
 * `KotlinStateFlow<E>` materialised through one generated, handle-keyed collect export per closed
 * flow instantiation, which projects each element exactly as a `Flow` member's own export does.
 * The materialised wrapper owns its collection scope and cancels it on `Dispose`.
 *
 * Read-only: nothing here passes a flow into Kotlin, and a `MutableStateFlow` argument binds as a
 * read-only `KotlinStateFlow`.
 *
 * Oreo's box hums. Mylo listens to it until somebody closes the lid.
 */
class Tier1FlowTypeArgumentTest {

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      mapOf(
        "Boxes.kt" to """
          package tier1.boxes

          import kotlin.jvm.JvmInline
          import kotlinx.coroutines.flow.Flow
          import kotlinx.coroutines.flow.MutableSharedFlow
          import kotlinx.coroutines.flow.MutableStateFlow
          import kotlinx.coroutines.flow.SharedFlow
          import kotlinx.coroutines.flow.StateFlow
          import kotlinx.coroutines.flow.flowOf

          class Box<T>(val value: T)

          class Cat(val name: String)

          enum class Mood { CALM, GRUMPY }

          @JvmInline
          value class Paw(val size: Int)

          class Kennel {
            fun ticks(): Box<Flow<Int>> = Box(flowOf(1, 2))
            val moods: Box<Flow<Mood>> = Box(flowOf(Mood.CALM))
            fun names(): Box<StateFlow<String>> = Box(MutableStateFlow("Oreo"))
            fun paws(): Box<StateFlow<Paw>> = Box(MutableStateFlow(Paw(4)))
            fun cats(): Box<Flow<Cat>> = Box(flowOf(Cat("Mylo")))
            fun lists(): Box<Flow<List<Mood>>> = Box(flowOf(listOf(Mood.CALM)))
            fun maybes(): Box<Flow<String?>> = Box(flowOf(null))
            fun shared(): Box<SharedFlow<Int>> = Box(MutableSharedFlow())
            fun cell(): Box<MutableStateFlow<Int>> = Box(MutableStateFlow(1))
            fun nested(): Box<Box<Flow<Int>>> = Box(Box(flowOf(1)))
            fun absent(): Box<Flow<Int>?> = Box(null)
            fun maybeBox(): Box<Flow<Int>>? = null
            fun take(box: Box<Flow<Int>>): Int = 0
            suspend fun later(): Box<Flow<Int>> = Box(flowOf(1))

            fun flowOfFlow(): Box<Flow<Flow<Int>>> = Box(flowOf(flowOf(1)))
            fun flowOfLambda(): Box<Flow<(Int) -> Int>> = Box(flowOf({ it }))
          }

          class Pile<T>(val top: T) {
            fun streamed(): Box<Flow<T>> = Box(flowOf(top))
          }

          fun crateOfFlow(): Box<Flow<Int>> = Box(flowOf(1))
        """.trimIndent(),
      ),
      processorOptions = mapOf("nuget.rootPackage" to "tier1"),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
  }

  private val box = "global::Interop.Boxes.Box"
  private val flow = "global::Interop.KotlinFlow"
  private val state = "global::Interop.KotlinStateFlow"

  @Test
  fun `a flow type argument binds at every position on both halves`() {
    assertTrue(result.kspErrors.isEmpty(), "expected no KSP errors; got: ${result.kspErrors}")
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    val cs: String = result.generatedCSharp.withoutDocComments()
    listOf(
      "public $box<$flow<int>> Ticks()",
      "public $box<$flow<global::Interop.Boxes.Mood>> Moods",
      "public $box<$state<string>> Names()",
      "public $box<$state<global::Interop.Boxes.Paw>> Paws()",
      "public $box<$flow<global::Interop.Boxes.Cat>> Cats()",
      "public $box<$flow<IReadOnlyList<global::Interop.Boxes.Mood>>> Lists()",
      "public $box<$flow<string?>> Maybes()",
      // ADR-205: a SharedFlow argument is the plain `KotlinFlow<T>`, through the same collect.
      "public $box<$flow<int>> Shared()",
      "public $box<$box<$flow<int>>> Nested()",
      "public $box<$flow<int>?> Absent()",
      "public $box<$flow<int>>? MaybeBox()",
      "public int Take($box<$flow<int>> box)",
      "Task<$box<$flow<int>>> LaterAsync(",
      "public static $box<$flow<int>> CrateOfFlow()",
    ).forEach { signature ->
      assertContains(cs, signature, message = "expected `$signature` in Interop.cs")
    }
    listOf(
      "ticks", "moods", "names", "paws", "cats", "lists", "maybes", "shared", "nested", "absent",
      "maybeBox", "take", "later", "crateOfFlow",
    ).forEach { member ->
      assertTrue(
        result.kspWarnings.none { it.contains("[nuget:") && it.contains("$member: ") },
        "expected no diagnostic for $member; kspWarnings=${result.kspWarnings}",
      )
    }
  }

  /**
   * One export per closed flow instantiation, named by its element, however many members mention
   * it: `Flow<Int>` appears at seven positions (and as a `SharedFlow<Int>`) and has one collect.
   */
  @Test
  fun `each closed flow instantiation has one generated collect export and one factory`() {
    val kotlin: String = result.generated
    val cs: String = result.generatedCSharp.withoutDocComments()
    listOf(
      "library_flowarg_flow_kotlin_Int_collect",
      "library_flowarg_flow_tier1_boxes_Mood_collect",
      "library_flowarg_flow_tier1_boxes_Cat_collect",
      "library_flowarg_flow_kotlin_String_nullable_collect",
      "library_flowarg_stateflow_kotlin_String_collect",
      "library_flowarg_stateflow_kotlin_String_value",
      "library_flowarg_stateflow_tier1_boxes_Paw_collect",
      "library_flowarg_stateflow_tier1_boxes_Paw_value",
    ).forEach { export ->
      assertEquals(
        1,
        Regex("@CName\\(\"$export\"\\)").findAll(kotlin).count(),
        "expected exactly one Kotlin export $export",
      )
      assertEquals(
        1,
        Regex("EntryPoint = \"$export\"").findAll(cs).count(),
        "expected exactly one C# import of $export",
      )
    }
    assertContains(kotlin, "flowHandle.asStableRef<Flow<Int>>().get()")
    assertContains(kotlin, "flowHandle.asStableRef<StateFlow<String>>().get()")
    // The element is projected as a Flow member's own export projects it.
    assertContains(kotlin, "if (value != null) NugetHandles.retain(value) else null")
    listOf(
      "$flow<int>",
      "$flow<global::Interop.Boxes.Mood>",
      "$state<string>",
      "$state<global::Interop.Boxes.Paw>",
      "$flow<IReadOnlyList<global::Interop.Boxes.Mood>>",
    ).forEach { type ->
      assertEquals(
        1,
        Regex(Regex.escape("[typeof($type)] = static handle =>")).findAll(cs).count(),
        "expected exactly one Factories line for $type",
      )
    }
    // No runtime StateFlow export is involved: the pair is the instantiation's own.
    assertFalse(
      cs.contains("NugetStateFlowNative"),
      "a boxed StateFlow reads through its generated pair, not the runtime's shared one",
    )
  }

  /** The wrapper owns the scope its collections run on, and its `Dispose` cancels it. */
  @Test
  fun `the materialised wrapper owns and disposes its own collection scope`() {
    val cs: String = result.generatedCSharp.withoutDocComments()
    assertContains(cs, "var scope = new NugetScopeHandle(NugetScopeNative.Create());")
    assertContains(cs, "ownedHandle: handle, ownedScope: scope);")
    assertContains(cs, "if (handle.IsClosed || scope.IsClosed)")
    val dispose: String = cs.substringAfter("public class KotlinFlow<T>")
      .substringAfter("public void Dispose()")
      .substringBefore("internal class KotlinFlowEnumerator<T>")
    assertTrue(
      dispose.indexOf("_ownedScope") in 0 until dispose.indexOf("_ownedHandle"),
      "expected Dispose to cancel the scope before releasing the flow handle; got: $dispose",
    )
  }

  /**
   * ADR-071's write seam is keyed on a member; a boxed `MutableStateFlow` has none, so it binds as
   * the read-only `KotlinStateFlow<T>` and the dropped setter is named once per instantiation.
   */
  @Test
  fun `a MutableStateFlow argument binds read-only, named`() {
    val cs: String = result.generatedCSharp.withoutDocComments()
    assertContains(cs, "public $box<$state<int>> Cell()")
    assertFalse(
      cs.contains("KotlinMutableStateFlow<int>"),
      "a boxed MutableStateFlow has no settable Value",
    )
    val named: List<String> = result.kspWarnings.filter {
      it.contains("[nuget:${ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT.name}]") &&
        it.contains("MutableStateFlow<kotlin.Int>")
    }
    assertEquals(1, named.size, "expected one named setter drop; kspWarnings=${result.kspWarnings}")
    assertContains(named.single(), "read-only KotlinStateFlow")
  }

  @Test
  fun `a flow argument nothing can materialise stays a named skip`() {
    val cs: String = result.generatedCSharp.withoutDocComments()
    mapOf(
      "flowOfFlow" to "Flow<Flow<Int>>",
      "flowOfLambda" to "Flow<Function1<Int, Int>>",
      "streamed" to "Flow<T>",
    ).forEach { (member, argument) ->
      val csName: String = member.replaceFirstChar { it.uppercase() }
      assertTrue(
        cs.lines().none { line -> line.trimStart().startsWith("public") && " $csName(" in line },
        "expected no $csName declaration; got: ${cs.lines().filter { " $csName(" in it }}",
      )
      val diagnostic: String? = result.kspWarnings.firstOrNull {
        it.contains("[nuget:${ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_TYPE.name}]") &&
          it.contains("$member: ")
      }
      assertTrue(
        diagnostic != null && diagnostic.contains("`$argument`"),
        "expected a SKIPPED_UNSUPPORTED_TYPE naming $member and $argument; " +
          "kspWarnings=${result.kspWarnings}",
      )
    }
  }

  @Test
  fun `the consumer compiles against a boxed flow at every element kind`() {
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using System.Collections.Generic;
      using System.Threading.Tasks;
      using Interop;
      using Interop.Boxes;

      namespace Consumer
      {
          public static class Probe
          {
              public static async Task<string> Run(Kennel kennel)
              {
                  string seen = "";
                  using Box<KotlinFlow<int>> ticks = kennel.Ticks();
                  using (KotlinFlow<int> flow = ticks.Value)
                  {
                      await foreach (int tick in flow) seen += tick;
                  }
                  using Box<KotlinFlow<Mood>> moods = kennel.Moods;
                  using (KotlinFlow<Mood> flow = moods.Value)
                  {
                      await foreach (Mood mood in flow) seen += mood;
                  }
                  using Box<KotlinStateFlow<string>> names = kennel.Names();
                  using (KotlinStateFlow<string> flow = names.Value)
                  {
                      seen += flow.Value;
                  }
                  using Box<KotlinStateFlow<Paw>> paws = kennel.Paws();
                  using (KotlinStateFlow<Paw> flow = paws.Value)
                  {
                      Paw paw = flow.Value;
                      seen += paw;
                  }
                  using Box<KotlinFlow<Cat>> cats = kennel.Cats();
                  using (KotlinFlow<Cat> flow = cats.Value)
                  {
                      await foreach (Cat cat in flow)
                      {
                          using (cat) seen += cat.Name;
                      }
                  }
                  using Box<KotlinFlow<IReadOnlyList<Mood>>> lists = kennel.Lists();
                  using (KotlinFlow<IReadOnlyList<Mood>> flow = lists.Value)
                  {
                      await foreach (IReadOnlyList<Mood> list in flow) seen += list.Count;
                  }
                  using Box<KotlinFlow<string?>> maybes = kennel.Maybes();
                  using (KotlinFlow<string?> flow = maybes.Value)
                  {
                      await foreach (string? maybe in flow) seen += maybe ?? "none";
                  }
                  using Box<KotlinStateFlow<int>> cell = kennel.Cell();
                  using (KotlinStateFlow<int> flow = cell.Value)
                  {
                      seen += flow.Value;
                  }
                  using Box<Box<KotlinFlow<int>>> nested = kennel.Nested();
                  using Box<KotlinFlow<int>> inner = nested.Value;
                  using Box<KotlinFlow<int>?> absent = kennel.Absent();
                  using KotlinFlow<int>? none = absent.Value;
                  using Box<KotlinFlow<int>>? maybeBox = kennel.MaybeBox();
                  using Box<KotlinFlow<int>> later = await kennel.LaterAsync();
                  using Box<KotlinFlow<int>> top = Boxes.CrateOfFlow();
                  return seen + kennel.Take(ticks) + (none == null);
              }
          }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }

  /** The same reachability for an interface reached only as a boxed flow's element. */
  @Test
  fun `an interface reached only as a boxed flow element is materialisable`() {
    val reached: Tier1Result = Tier1Harness.run(
      """
      package tier1.reach

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.emptyFlow

      class Box<T>(val value: T)

      interface Sighting { val name: String }

      class Kennel {
        fun seen(): Box<Flow<Sighting>> = Box(emptyFlow())
      }
      """.trimIndent(),
      processorOptions = mapOf("nuget.rootPackage" to "tier1"),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
    val cs: String = reached.generatedCSharp.withoutDocComments()
    assertContains(cs, "global::Interop.Reach.Box<$flow<global::Interop.Reach.ISighting>> Seen()")
    assertContains(cs, "public sealed class Sighting : ISighting")
    Tier1CSharpCompile.assertCompiles(
      reached,
      """
      using System.Threading.Tasks;
      using Interop;
      using Interop.Reach;

      namespace Consumer
      {
          public static class Probe
          {
              public static async Task<string> Run(Kennel kennel)
              {
                  string seen = "";
                  using Box<KotlinFlow<ISighting>> box = kennel.Seen();
                  using KotlinFlow<ISighting> flow = box.Value;
                  await foreach (ISighting sighting in flow) seen += sighting.Name;
                  return seen;
              }
          }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }

  /**
   * The two elements that read through their own helper rather than `FromHandle<T>`: a bare
   * `ByteArray` (`NugetMarshal.ReadBytes`) and a `Throwable` (its ADR-107 envelope). And the two
   * flows nothing can read: a star projection and an element with no bridge shape.
   */
  @Test
  fun `bytes and throwable elements bind, an unreadable or projected flow does not`() {
    val edge: Tier1Result = Tier1Harness.run(
      mapOf(
        "Edges.kt" to """
          package tier1.edges

          import kotlinx.coroutines.flow.Flow
          import kotlinx.coroutines.flow.MutableStateFlow
          import kotlinx.coroutines.flow.StateFlow
          import kotlinx.coroutines.flow.flowOf

          class Box<T>(val value: T)

          class Kennel {
            fun chunks(): Box<Flow<ByteArray>> = Box(flowOf(byteArrayOf(1)))
            fun faults(): Box<StateFlow<Throwable?>> = Box(MutableStateFlow(null))
            fun nothings(): Box<Flow<Unit>> = Box(flowOf(Unit))
            fun anything(box: Box<Flow<*>>): Int = 0
          }
        """.trimIndent(),
      ),
      processorOptions = mapOf("nuget.rootPackage" to "tier1"),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
    assertTrue(edge.kspErrors.isEmpty(), "expected no KSP errors; got: ${edge.kspErrors}")
    assertTrue(edge.compiledClean, "expected a clean compile; got: ${edge.compileErrors}")
    val cs: String = edge.generatedCSharp.withoutDocComments()
    assertContains(cs, "global::Interop.Edges.Box<$flow<byte[]>> Chunks()")
    assertContains(cs, "global::Interop.Edges.Box<$state<global::System.Exception?>> Faults()")
    assertContains(cs, "read: static h => NugetMarshal.ReadBytes(h),")
    assertContains(
      cs,
      "read: static h => h == IntPtr.Zero ? null : NugetErrorNative.BuildException(h),",
    )
    assertContains(edge.generated, "NugetHandles.retain(buildError(value, ::nugetMappedType))")
    listOf("nothings", "anything").forEach { member ->
      val csName: String = member.replaceFirstChar { it.uppercase() }
      assertTrue(
        cs.lines().none { line -> line.trimStart().startsWith("public") && " $csName(" in line },
        "expected no $csName declaration; got: ${cs.lines().filter { " $csName(" in it }}",
      )
      assertTrue(
        edge.kspWarnings.any {
          it.contains("[nuget:${ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_TYPE.name}]") &&
            it.contains("$member: ")
        },
        "expected a SKIPPED_UNSUPPORTED_TYPE naming $member; kspWarnings=${edge.kspWarnings}",
      )
    }
    Tier1CSharpCompile.assertCompiles(
      edge,
      """
      using System;
      using System.Threading.Tasks;
      using Interop;
      using Interop.Edges;

      namespace Consumer
      {
          public static class Probe
          {
              public static async Task<int> Run(Kennel kennel)
              {
                  int total = 0;
                  using Box<KotlinFlow<byte[]>> chunks = kennel.Chunks();
                  using (KotlinFlow<byte[]> flow = chunks.Value)
                  {
                      await foreach (byte[] chunk in flow) total += chunk.Length;
                  }
                  using Box<KotlinStateFlow<Exception?>> faults = kennel.Faults();
                  using KotlinStateFlow<Exception?> fault = faults.Value;
                  return total + (fault.Value == null ? 0 : 1);
              }
          }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }
}
