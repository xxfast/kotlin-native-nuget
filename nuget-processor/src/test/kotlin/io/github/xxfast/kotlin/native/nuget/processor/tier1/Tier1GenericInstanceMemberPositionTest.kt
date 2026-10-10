package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-208 part D: a closed instantiation of an exported generic class (`Box<String>`, `Box<Cat>`,
 * `Box<Box<Int>>`, `Box<String>?`) binds at every member position through the ADR-199 seam, as an
 * `ObjectHandle` spelled with the use site's arguments. Before it, every position but a top-level
 * function return was `UNROUTED_POSITION`.
 *
 * A returned box is a fresh wrapper the consumer owns; a passed box is a borrow. Shapes C# has no
 * erased reader for, or no spelling of, stay named skips.
 *
 * Oreo keeps her toys in boxes, and Mylo keeps trying to take them out.
 */
class Tier1GenericInstanceMemberPositionTest {

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      mapOf(
        "Boxes.kt" to """
          package tier1.boxes

          import kotlin.jvm.JvmInline

          class Box<T>(val value: T)

          class Cat(val name: String)

          enum class Mood { CALM, GRUMPY }

          @JvmInline
          value class Paw(val size: Int)

          class Shelter {
            val names: Box<String> = Box("Oreo")
            var count: Box<Int> = Box(1)
            fun cat(): Box<Cat> = Box(Cat("Mylo"))
            fun take(box: Box<String>): String = box.value
            fun nested(): Box<Box<Int>> = Box(Box(2))
            fun maybe(): Box<String>? = null
            fun takeMaybe(box: Box<String>?): Int = if (box == null) 0 else 1
            fun mood(): Box<Mood> = Box(Mood.CALM)
            fun paw(): Box<Paw> = Box(Paw(3))
            fun nullableArg(): Box<String?> = Box(null)

            companion object {
              fun make(): Box<Int> = Box(3)
            }
          }

          object Registry {
            fun boxed(): Box<String> = Box("toy")
            fun put(box: Box<Cat>) {}
          }

          val topBox: Box<String> = Box("top")

          fun takeTop(box: Box<Int>): Int = box.value

          fun topReturn(): Box<Cat> = Box(Cat("Top"))

          fun <T> crateOf(item: T): Box<T> = Box(item)

          val Box<Int>.label: String get() = "n=" + value

          class Pile<T>(val top: T) {
            fun push(item: T): Pile<T> = Pile(item)
            fun sameTop(other: Pile<T>): Boolean = other.top == top
            fun boxed(): Box<T> = Box(top)
          }
        """.trimIndent(),
        "Declined.kt" to """
          package tier1.declined

          import tier1.boxes.Box
          import tier1.boxes.Cat

          interface Shelf<T> {
            fun top(): T
          }

          class Tin<T>(val lid: T) {
            inner class Latch(val turns: Int)
            inner class Infuser<U>(val herb: U)
          }

          class Refusals {
            fun list(): Box<List<Int>> = Box(listOf(1))
            fun any(): Box<Any> = Box("toy")
            fun bytes(): Box<ByteArray> = Box(byteArrayOf(1))
            fun lambda(): Box<(Int) -> Int> = Box { it + 1 }
            fun star(box: Box<*>): Int = 0
            fun projected(box: Box<out Cat>): Int = 0
            fun shelf(shelf: Shelf<String>): Int = 0
            fun latchOf(): Tin<Int>.Latch = Tin(1).Latch(2)
          }

          object Depot

          fun pairOf(): Pair<Int, Int> = 1 to 2

          fun shelfOf(): Shelf<String> = object : Shelf<String> {
            override fun top(): String = "top"
          }

          fun infuserOf(): Tin<Int>.Infuser<String> = Tin(1).Infuser("mint")

          fun boxOfDepot(): Box<Depot> = Box(Depot)

          fun boxOfUnit(): Box<Unit> = Box(Unit)
        """.trimIndent(),
      ),
      processorOptions = mapOf("nuget.rootPackage" to "tier1"),
    )
  }

  @Test
  fun `a closed instantiation binds at every member position on both halves`() {
    assertTrue(result.kspErrors.isEmpty(), "expected no KSP errors; got: ${result.kspErrors}")
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    val cs: String = result.generatedCSharp.withoutDocComments()
    val box = "global::Interop.Boxes.Box"
    listOf(
      "public $box<string> Names",
      "public $box<int> Count",
      "public $box<global::Interop.Boxes.Cat> Cat()",
      "public string Take($box<string> box)",
      "public $box<$box<int>> Nested()",
      "public $box<string>? Maybe()",
      "public int TakeMaybe($box<string>? box)",
      "public $box<global::Interop.Boxes.Mood> Mood()",
      "public $box<global::Interop.Boxes.Paw> Paw()",
      "public $box<string?> NullableArg()",
      "public static $box<int> Make()",
      "public static $box<string> Boxed()",
      "public static void Put($box<global::Interop.Boxes.Cat> box)",
      "public static $box<string> TopBox",
      "public static int TakeTop($box<int> box)",
      "public static $box<global::Interop.Boxes.Cat> TopReturn()",
    ).forEach { signature ->
      assertContains(cs, signature, message = "expected `$signature` in Interop.cs")
    }
    // The setter is a borrow: the wrapper's handle crosses, nothing is released.
    assertContains(cs, "Native_Set_count(_handle, value._handle")
    // A nested instantiation materialises through its own Factories line.
    assertContains(
      cs,
      "[typeof($box<$box<int>>)] = static handle => new $box<$box<int>>(handle, out _)",
    )
    val kotlin: String = result.generated
    assertContains(kotlin, "asStableRef<tier1.boxes.Box<kotlin.Int>>().get()")
    assertContains(kotlin, "box.asStableRef<tier1.boxes.Box<kotlin.String>>().get()")
    assertContains(kotlin, "box?.asStableRef<tier1.boxes.Box<kotlin.String>>()?.get()")
    assertContains(kotlin, "box.asStableRef<tier1.boxes.Box<tier1.boxes.Cat>>().get()")
  }

  @Test
  fun `no member typed with a closed instantiation is skipped`() {
    val boxed: List<String> = listOf(
      "names", "count", "cat", "take", "nested", "maybe", "takeMaybe", "mood", "paw",
      "nullableArg", "make", "boxed", "put", "topBox", "takeTop", "topReturn", "crateOf", "label",
      "push", "sameTop",
    )
    boxed.forEach { member ->
      assertTrue(
        result.kspWarnings.none { it.contains("[nuget:") && it.contains("$member: ") },
        "expected no diagnostic for $member; kspWarnings=${result.kspWarnings}",
      )
    }
  }

  /**
   * An open instantiation has no `Factories` line (nothing closed to key it on). Inside a generic
   * owner (`Pile<T>.push(): Pile<T>`, `boxed(): Box<T>`) it is new on the plan route with ADR-208;
   * over a top-level function's own `T` (`fun <T> crateOf(item: T): Box<T>`) it is the ADR-197
   * generic-function route's, unchanged, and this cell is the first `dotnet build` of either.
   * The extension receiver `Box<Int>.label` binds as a C# extension on `Box<int>`.
   */
  @Test
  fun `the consumer compiles against every bound position`() {
    val cs: String = result.generatedCSharp.withoutDocComments()
    assertFalse(
      cs.contains("[typeof(global::Interop.Boxes.Box<T>)]"),
      "an open instantiation has no Factories line",
    )
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using Interop.Boxes;
      using Kotlin.Native.Interop;

      namespace Consumer
      {
          public static class Probe
          {
              public static string Run(Shelter shelter, Box<string> mine, Box<Cat> cat)
              {
                  using Box<string> names = shelter.Names;
                  using Box<int> count = shelter.Count;
                  shelter.Count = count;
                  using Box<Cat> held = shelter.Cat();
                  using Box<Box<int>> nested = shelter.Nested();
                  using Box<int> inner = nested.Value;
                  using Box<string>? maybe = shelter.Maybe();
                  using Box<Mood> mood = shelter.Mood();
                  using Box<Paw> paw = shelter.Paw();
                  using Box<string?> nullable = shelter.NullableArg();
                  using Box<int> made = Shelter.Make();
                  using Box<string> boxed = Registry.Boxed();
                  Registry.Put(cat);
                  using Box<string> top = Boxes.TopBox;
                  using Box<Cat> topCat = Boxes.TopReturn();
                  using Box<string> open = Boxes.CrateOf<string>("open");
                  string label = count.Label;
                  using var pile = new Pile<string>("a");
                  using Pile<string> pushed = pile.Push("b");
                  using Box<string> piled = pushed.Boxed();
                  bool same = pile.SameTop(pushed);
                  return names.Value + shelter.Take(mine) + shelter.TakeMaybe(null) + inner.Value +
                      Boxes.TakeTop(count) + label + open.Value + piled.Value + same;
              }
          }
      }
      """.trimIndent(),
    )
  }

  @Test
  fun `each declined shape stays a named skip and absent from Interop cs`() {
    val cs: String = result.generatedCSharp.withoutDocComments()
    mapOf(
      "list" to ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_TYPE,
      "any" to ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_TYPE,
      "bytes" to ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_TYPE,
      "lambda" to ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_TYPE,
      "star" to ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_TYPE,
      "projected" to ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_TYPE,
      "shelf" to ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT,
      "latchOf" to ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN,
      "pairOf" to ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_TYPE,
      // The four shapes the deleted legacy generic-return route still rendered, each as C# that
      // does not compile (CS0234, a misspelled inner class, CS0718, `Box<void>`).
      "shelfOf" to ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN,
      "infuserOf" to ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN,
      "boxOfDepot" to ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_TYPE,
      "boxOfUnit" to ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_TYPE,
    ).forEach { (member, kind) ->
      val csName: String = member.replaceFirstChar { it.uppercase() }
      assertTrue(
        cs.lines().none { line -> line.trimStart().startsWith("public") && " $csName(" in line },
        "expected no $csName declaration; got: ${cs.lines().filter { " $csName(" in it }}",
      )
      val diagnostic: String? = result.kspWarnings.firstOrNull {
        it.contains("[nuget:${kind.name}]") && it.contains("$member: ")
      }
      assertTrue(
        diagnostic != null,
        "expected a ${kind.name} naming $member; kspWarnings=${result.kspWarnings}",
      )
      assertFalse(
        diagnostic.contains("sealed type"),
        "a plain generic class is not a sealed type; got: $diagnostic",
      )
      // Skip means absent on both halves: no orphan export is left behind either.
      assertFalse(
        result.generated.contains("_$member\""),
        "expected no Kotlin export for the skipped $member",
      )
    }
  }

  /** The refusal names the class and why its use site has no C# spelling, not a sealed type. */
  @Test
  fun `a refused instantiation says why the use site has no spelling`() {
    mapOf(
      "list" to "the erased wire cannot read its type argument `List<Int>`",
      "any" to "the erased wire cannot read its type argument `Any`",
      "star" to "is a use-site projection, and C# has no projection of a generic class",
    ).forEach { (member, why) ->
      val diagnostic: String = result.kspWarnings.single { it.contains("$member: ") }
      assertContains(
        diagnostic,
        "its generic class `tier1.boxes.Box` has no C# spelling here: ",
        message = "expected the plain-class sentence for $member",
      )
      assertContains(diagnostic, why, message = "expected the reason for $member")
      assertContains(diagnostic, "type the member with a closed instantiation C# can spell")
    }
  }

  private val async: Tier1Result by lazy {
    Tier1Harness.run(
      mapOf("Boxes.kt" to """
      package tier1.boxes

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.MutableStateFlow
      import kotlinx.coroutines.flow.StateFlow
      import kotlinx.coroutines.flow.flowOf

      class Box<T>(val value: T)

      class Den {
        suspend fun later(): Box<String> = Box("later")
        suspend fun maybeLater(): Box<String>? = null
        suspend fun laterWith(box: Box<String>, spare: Box<Int>?): String = box.value
        val stream: Flow<Box<String>> = flowOf(Box("a"))
        val state: StateFlow<Box<Int>> = MutableStateFlow(Box(1))
        val mutable: MutableStateFlow<Box<String>> = MutableStateFlow(Box("m"))
        fun streamOf(box: Box<String>): Flow<Box<String>> = flowOf(box)
      }

      suspend fun topLater(box: Box<String>): Box<String> = box
      """.trimIndent()),
      processorOptions = mapOf("nuget.rootPackage" to "tier1"),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
  }

  /**
   * Decision 5: the suspend and Flow routes refused every generic result, element and parameter
   * by name. A closed instantiation is an ordinary handle there too: awaited as a caller-owned
   * `Box<string>`, collected as `KotlinFlow<Box<string>>` elements, passed as a borrow.
   */
  @Test
  fun `a suspend result and parameter and a Flow element bind a closed instantiation`() {
    assertTrue(async.kspErrors.isEmpty(), "expected no KSP errors; got: ${async.kspErrors}")
    assertTrue(async.compiledClean, "expected a clean compile; got: ${async.compileErrors}")
    val cs: String = async.generatedCSharp.withoutDocComments()
    val box = "global::Interop.Boxes.Box"
    listOf(
      "Task<$box<string>> LaterAsync(",
      "Task<$box<string>?> MaybeLaterAsync(",
      "Task<string> LaterWithAsync($box<string> box, $box<int>? spare",
      "KotlinFlow<$box<string>> Stream",
      "KotlinStateFlow<$box<int>> State",
      // No write arm for a generic element (ADR-071 seam): read-only, as a collection element is.
      "KotlinStateFlow<$box<string>> Mutable",
      "KotlinFlow<$box<string>> StreamOf($box<string> box)",
      "Task<$box<string>> TopLaterAsync($box<string> box",
    ).forEach { signature ->
      assertContains(cs, signature, message = "expected `$signature` in Interop.cs")
    }
    val kotlin: String = async.generated
    assertContains(kotlin, "box.asStableRef<tier1.boxes.Box<kotlin.String>>().get()")
    assertContains(kotlin, "spare?.asStableRef<tier1.boxes.Box<kotlin.Int>>()?.get()")
    listOf("later", "maybeLater", "laterWith", "stream", "state", "mutable", "streamOf", "topLater")
      .forEach { member ->
        assertTrue(
          async.kspWarnings.none { it.contains("[nuget:") && it.contains("$member: ") },
          "expected no diagnostic for $member; kspWarnings=${async.kspWarnings}",
        )
      }
  }

  @Test
  fun `the consumer compiles against the suspend and Flow positions`() {
    Tier1CSharpCompile.assertCompiles(
      async,
      """
      using System.Threading.Tasks;
      using Interop.Boxes;
      using Kotlin.Native.Interop;

      namespace Consumer
      {
          public static class Probe
          {
              public static async Task<string> Run(Den den, Box<string> mine)
              {
                  using Box<string> later = await den.LaterAsync();
                  using Box<string>? maybe = await den.MaybeLaterAsync();
                  string with = await den.LaterWithAsync(mine, null);
                  using Box<string> top = await Boxes.TopLaterAsync(mine);
                  string seen = "";
                  await foreach (Box<string> item in den.Stream)
                  {
                      using (item) seen += item.Value;
                  }
                  await foreach (Box<string> item in den.StreamOf(mine))
                  {
                      using (item) seen += item.Value;
                  }
                  using Box<int> now = den.State.Value;
                  using Box<string> held = den.Mutable.Value;
                  return later.Value + with + top.Value + seen + now.Value;
              }
          }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }

  private val crates: Tier1Result by lazy {
    Tier1Harness.run(
      mapOf("Crates.kt" to """
      package tier1.crates

      import kotlin.jvm.JvmInline
      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.MutableStateFlow
      import kotlinx.coroutines.flow.StateFlow
      import kotlinx.coroutines.flow.flowOf

      class Box<T>(val value: T)

      @JvmInline
      value class Crate<T>(val item: T)

      class Depot {
        val held: Crate<Int> = Crate(1)
        var spare: Crate<String> = Crate("toy")
        fun fetch(): Crate<Int> = Crate(2)
        fun stow(crate: Crate<Int>): Int = crate.item
        fun maybeFetch(): Crate<Int>? = null
        fun maybeStow(crate: Crate<Int>?): Int = 0
        fun boxed(): Box<Crate<Int>> = Box(Crate(3))
        fun listed(): List<Crate<Int>> = listOf(Crate(4))
        suspend fun later(): Crate<Int> = Crate(5)
        suspend fun laterWith(crate: Crate<Int>): Int = crate.item
        val stream: Flow<Crate<Int>> = flowOf(Crate(6))
        val state: StateFlow<Crate<Int>> = MutableStateFlow(Crate(7))
        val mutable: MutableStateFlow<Crate<Int>> = MutableStateFlow(Crate(8))
        fun streamOf(): Flow<Crate<Int>> = flowOf(Crate(9))
        fun dial(): MutableStateFlow<Crate<Int>> = MutableStateFlow(Crate(10))
      }

      val topCrate: Crate<Int> = Crate(11)

      fun topFetch(): Crate<Int> = Crate(12)

      fun topStow(crate: Crate<Int>): Int = crate.item

      val Crate<Int>.label: String get() = "n=" + item
      """.trimIndent()),
      processorOptions = mapOf("nuget.rootPackage" to "tier1"),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
  }


  /** Every member of the [crates] fixture, with the named skip its position gives it. */
  private val crateMembers: Map<String, ForwardDiagnosticKind> = mapOf(
    "held" to ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY,
    "spare" to ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY,
    "fetch" to ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_TYPE,
    "stow" to ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_TYPE,
    "maybeFetch" to ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN,
    "maybeStow" to ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_TYPE,
    "boxed" to ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_TYPE,
    "listed" to ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_TYPE,
    "later" to ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN,
    "laterWith" to ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT,
    "stream" to ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY,
    "state" to ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY,
    "mutable" to ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY,
    "streamOf" to ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN,
    "dial" to ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN,
    "topCrate" to ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY,
    "topFetch" to ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_TYPE,
    "topStow" to ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_TYPE,
    "label" to ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY,
  )

  /**
   * A generic VALUE class (`@JvmInline value class Crate<T>`) is not a handle-carrying object, so
   * an instantiation of it is not an ADR-208 handle at any position: it has no `_handle`, no
   * `asStableRef` read and no `Factories` line. Every member typed with one stays a named skip,
   * absent on both halves, and what is left of `Interop.cs` compiles.
   */
  @Test
  fun `a generic value class instantiation is never a handle and stays a named skip`() {
    assertTrue(crates.kspErrors.isEmpty(), "expected no KSP errors; got: ${crates.kspErrors}")
    assertTrue(crates.compiledClean, "expected a clean compile; got: ${crates.compileErrors}")
    val cs: String = crates.generatedCSharp.withoutDocComments()
    val mentions: List<String> = cs.lines().filter { Regex("""\bCrate\b""").containsMatchIn(it) }
    assertTrue(mentions.isEmpty(), "expected no C# code naming Crate; got: $mentions")
    assertFalse(
      "tier1.crates.Crate" in crates.generated,
      "expected no Kotlin export reading or minting a Crate",
    )
    assertFalse(
      Regex("""@CName\("[^"]*depot_(?!create"|dispose")""").containsMatchIn(crates.generated),
      "expected no Depot member export; generated=${crates.generated}",
    )
    val declarations: List<String> = cs.lines().filter { it.trimStart().startsWith("public") }
    crateMembers.forEach { (member, kind) ->
      val csName: String = member.replaceFirstChar { it.uppercase() }
      val declared: List<String> =
        declarations.filter { Regex("""\b$csName(Async)?\b""").containsMatchIn(it) }
      assertTrue(declared.isEmpty(), "expected no $csName declaration; got: $declared")
      assertTrue(
        crates.kspWarnings.any {
          it.contains("[nuget:${kind.name}]") && it.contains(".$member: ")
        },
        "expected a ${kind.name} naming $member; kspWarnings=${crates.kspWarnings}",
      )
    }
    // The ADR-208 refusal sentence belongs to a generic CLASS. Only `Box<Crate<Int>>` gets it, for
    // its argument; the value class itself never reaches that seam.
    assertFalse(
      crates.kspWarnings.any { it.contains("generic class `tier1.crates.Crate`") },
      "a generic value class is not a generic class reference; kspWarnings=${crates.kspWarnings}",
    )
    assertContains(
      crates.kspWarnings.single { it.contains(".boxed: ") },
      "its generic class `tier1.crates.Box` has no C# spelling here: the erased wire cannot " +
        "read its type argument `Crate<Int>`",
    )
    Tier1CSharpCompile.assertCompiles(
      crates,
      """
      using Interop.Crates;

      namespace Consumer
      {
          public static class Probe
          {
              public static void Run()
              {
                  using var depot = new Depot();
              }
          }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }

  /**
   * ADR-208's read-only gate on a generic `MutableStateFlow` element is for a generic CLASS, whose
   * refusal is silent because the member still binds. A generic value class element keeps
   * ADR-071's named write refusal, on a property and on a returned holder.
   */
  @Test
  fun `a MutableStateFlow of a generic value class keeps its named write refusal`() {
    listOf("mutable", "dial").forEach { member ->
      assertTrue(
        crates.kspWarnings.any {
          it.contains("[nuget:${ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT.name}]") &&
            it.contains(".$member: ") && it.contains("value class tier1.crates.Crate") &&
            it.contains("has no write arm")
        },
        "expected $member refused naming its value class; kspWarnings=${crates.kspWarnings}",
      )
    }
    assertFalse(
      Regex("""depot_set_\w+_value""").containsMatchIn(crates.generated),
      "expected no Kotlin setter for a generic value class element",
    )
  }

  /**
   * An interface reached ONLY as the type argument of a member-position instantiation is spelled
   * `ISighting` and read through `FromHandle<ISighting>`, so it needs the backing wrapper and
   * `Factories` key a planned position gives it (ADR-173's erased-position walk, which covered a
   * top-level return and a property only).
   */
  @Test
  fun `an interface reached only inside a member-position instantiation is materialisable`() {
    val reached: Tier1Result = Tier1Harness.run(
      """
      package tier1.reach

      class Box<T>(val value: T)

      interface Sighting { val name: String }

      interface Nest { val size: Int }

      interface Burrow { val depth: Int }

      class Kennel {
        fun seen(): Box<Sighting> = TODO()
        fun put(box: Box<Nest>): Int = 0
        fun deep(): Box<Box<Burrow>> = TODO()
      }
      """.trimIndent(),
      processorOptions = mapOf("nuget.rootPackage" to "tier1"),
    )
    val cs: String = reached.generatedCSharp.withoutDocComments()
    listOf("Sighting", "Nest", "Burrow").forEach { name ->
      assertContains(cs, "public sealed class $name : I$name", message = "no wrapper for $name")
      assertContains(
        cs,
        "[typeof(global::Interop.Reach.I$name)] = " +
          "static handle => new global::Interop.Reach.$name(handle, out _)",
        message = "no I$name factory key",
      )
    }
  }
}
