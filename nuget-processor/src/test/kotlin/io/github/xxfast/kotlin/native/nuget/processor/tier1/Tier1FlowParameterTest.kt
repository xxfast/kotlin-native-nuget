package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-210 slice (b): a Kotlin `Flow<T>` PARAMETER binds as C# `IAsyncEnumerable<T>` on every host
 * the fixture `TrickleSample.kt` crosses: a class and a sealed-arm `suspend fun`, a Flow-returning
 * member (the legacy Flow-return route as host), a non-suspend arm member and a constructor that
 * can only store it (the ADR-062 plan), and a top-level `suspend fun`.
 *
 * `IntegrationTests/FlowParameterTests.cs` proves the behaviour. This class pins what a running
 * test cannot see, each a silent-wrong-output class if it drifts: the flat slot order with the
 * shared thunks crossing once per export, the Enumerate thunk's own key-miss body, the reverse
 * shim's Dispose ORDER as copied (a wrong order leaks a running iterator and reports nothing), the
 * guarded registration span on all three call shapes, and the `StateFlow` refusal's own sentence.
 *
 * Mylo drinks from the fountain one drop at a time; Oreo watches the level.
 */
class Tier1FlowParameterTest {

  private val fixture: String = """
    package tier1.trickle

    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.StateFlow
    import kotlinx.coroutines.flow.SharedFlow
    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.map
    import kotlinx.coroutines.flow.count

    class WaterFountain(val cat: String) {
      suspend fun totalDrops(drops: Flow<Int>): Int = drops.count()
      suspend fun describe(sips: Flow<String>): String = cat
      suspend fun flags(bits: Flow<Boolean>): Int = bits.count()
      fun doubled(source: Flow<Int>): Flow<Int> = source.map { drop -> drop * 2 }
      suspend fun both(prepare: suspend (Int) -> String, drops: Flow<Int>): Int = drops.count()
      suspend fun flowFirst(drops: Flow<Int>, prepare: suspend (Int) -> String): Int = drops.count()
      suspend fun topped(drops: Flow<Int>, topping: String): Int = drops.count()
      fun stash(drops: Flow<Int>, topping: String): Int = topping.length

      // Refused, each by name: the three hot flows, on both routes, then a nullable flow and an
      // element type no Current thunk casts.
      fun level(gauge: StateFlow<Int>): Int = gauge.value
      fun shared(gauge: SharedFlow<Int>): Int = 0
      fun mutable(gauge: MutableStateFlow<Int>): Int = gauge.value
      suspend fun levelLater(gauge: StateFlow<Int>): Int = gauge.value
      suspend fun maybe(drops: Flow<Int>?): Int = 0
      suspend fun letters(drops: Flow<Char>): Int = 0
      suspend fun mishaps(faults: Flow<Throwable>): Int = 0
      fun mishapsNow(faults: Flow<Throwable>): Int = 0
    }

    sealed class Bowl {
      class Ceramic(val capacity: Int) : Bowl() {
        suspend fun fill(drops: Flow<Int>): Int = drops.count()
        fun attach(drops: Flow<Int>): Int = capacity
        fun gauge(level: StateFlow<Int>): Int = level.value
        fun spill(faults: Flow<Throwable>): Int = capacity
      }

      data object Upturned : Bowl()
    }

    class Dripper(private val drops: Flow<Int>) {
      suspend fun drain(): Int = drops.count()
    }

    suspend fun countDrops(drops: Flow<Int>): Int = drops.count()
  """.trimIndent()

  private fun run(): Tier1Result = Tier1Harness.run(
    fixture,
    fileName = "TrickleSample.kt",
    libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
  )

  private val flow: String = "global::System.Collections.Generic.IAsyncEnumerable"

  /** The text of the C# thunk named [name], from its declaration to its closing brace. */
  private fun Tier1Result.thunk(name: String): String {
    val lines: List<String> = generatedCSharp.lines()
    val start: Int = lines.indexOfFirst { line -> line.contains(" ${name}Thunk(") }
    assertTrue(start >= 0, "expected a thunk named ${name}Thunk in Interop.cs")
    val end: Int = (start until lines.size).first { index -> lines[index] == "        }" }
    return lines.subList(start, end + 1).joinToString("\n")
  }

  /** The parameter names of the Kotlin export whose `@CName` ends with [suffix], in order. */
  private fun Tier1Result.exportParameters(suffix: String): List<String> {
    val lines: List<String> = generated.lines()
    val start: Int = lines.indexOfFirst { line ->
      line.startsWith("@CName(") && line.contains("$suffix\")")
    }
    assertTrue(start >= 0, "expected a Kotlin export ending in $suffix")
    return lines.drop(start + 2).takeWhile { line -> !line.startsWith(")") }
      .map { line -> line.trim().substringBefore(":") }
  }

  /** The body of the C# member whose declaration contains [signature]. */
  private fun Tier1Result.member(signature: String): String {
    assertTrue(generatedCSharp.contains(signature), "expected `$signature` in Interop.cs")
    return generatedCSharp.substringAfter(signature).substringBefore("private static extern")
  }

  private fun assertInOrder(text: String, vararg parts: String) {
    parts.fold(0) { from, part ->
      val at: Int = text.indexOf(part, from)
      assertTrue(at >= 0, "expected `$part` after offset $from in:\n$text")
      at + part.length
    }
  }

  @Test
  fun `the generated bridge compiles and reports nothing for the bound members`() {
    val result: Tier1Result = run()

    assertTrue(result.kspErrors.isEmpty(), "kspErrors=${result.kspErrors}")
    assertTrue(result.compileErrors.isEmpty(), "compileErrors=${result.compileErrors}")
    listOf(
      "totalDrops", "describe", "flags", "doubled", "both", "flowFirst", "topped", "stash",
      "fill", "attach", "Dripper", "countDrops",
    ).forEach { member ->
      assertFalse(
        result.kspWarnings.any { warning -> Regex("\\b$member\\b").containsMatchIn(warning) },
        "$member binds, so it must not be named; kspWarnings=${result.kspWarnings}",
      )
    }
  }

  @Test
  fun `every host binds the flow as an IAsyncEnumerable`() {
    val csharp: String = run().generatedCSharp

    listOf(
      // The legacy suspend route on a class: Int, String and Boolean elements.
      "public Task<int> TotalDropsAsync($flow<int> drops, " +
          "CancellationToken cancellationToken = default)",
      "public Task<string> DescribeAsync($flow<string> sips, " +
          "CancellationToken cancellationToken = default)",
      "public Task<int> FlagsAsync($flow<bool> bits, " +
          "CancellationToken cancellationToken = default)",
      // The legacy Flow-return route as host.
      "public KotlinFlow<int> Doubled($flow<int> source)",
      // The plan: a non-suspend class member, the arm member that was a silent drop, a constructor.
      "public int Stash($flow<int> drops, string topping)",
      "public int Attach($flow<int> drops)",
      "public Dripper($flow<int> drops)",
      // ADR-118's arm suspend route, and the top-level suspend route.
      "public Task<int> FillAsync($flow<int> drops, CancellationToken cancellationToken = default)",
      "public static Task<int> CountDropsAsync($flow<int> drops, " +
          "CancellationToken cancellationToken = default)",
    ).forEach { signature ->
      assertTrue(
        csharp.contains(signature),
        "expected `$signature`; got=" + csharp.lines().filter { line ->
          line.contains(signature.substringBefore("(").substringAfterLast(" ") + "(")
        },
      )
    }
  }

  /**
   * The ABI is positional. A `Flow` declares Enumerate, Current and its key; the thunks that do not
   * depend on the parameter cross once per export, declared by whichever parameter needs each
   * first: release task and release context by the first asynchronous parameter, the three
   * enumeration thunks by the first `Flow`, release cancellation by the first suspend lambda. So
   * one `Flow` is 8 slots and one of each is 12, in either order.
   */
  @Test
  fun `the slots are flat and every shared thunk crosses once per export`() {
    val result: Tier1Result = run()

    assertEquals(
      listOf(
        "handle", "scopeHandle",
        "dropsEnumerate", "dropsCurrent", "dropsKey",
        "dropsReleaseTask", "dropsMoveNextBegin", "dropsMoveNextEnd", "dropsDisposeEnumeration",
        "dropsReleaseContext",
        "callbackPtr", "userData",
      ),
      result.exportParameters("waterfountain_totalDrops_async"),
    )
    assertEquals(
      listOf(
        "handle", "scopeHandle",
        "prepareBegin", "prepareEnd", "prepareKey",
        "prepareReleaseTask", "prepareReleaseCancellation", "prepareReleaseContext",
        "dropsEnumerate", "dropsCurrent", "dropsKey",
        "dropsMoveNextBegin", "dropsMoveNextEnd", "dropsDisposeEnumeration",
        "callbackPtr", "userData",
      ),
      result.exportParameters("waterfountain_both_async"),
    )
    assertEquals(
      listOf(
        "handle", "scopeHandle",
        "dropsEnumerate", "dropsCurrent", "dropsKey",
        "dropsReleaseTask", "dropsMoveNextBegin", "dropsMoveNextEnd", "dropsDisposeEnumeration",
        "dropsReleaseContext",
        "prepareBegin", "prepareEnd", "prepareKey", "prepareReleaseCancellation",
        "callbackPtr", "userData",
      ),
      result.exportParameters("waterfountain_flowFirst_async"),
    )
    // The C# import declares the same slots, and the call passes the matching thunks.
    assertTrue(
      result.generatedCSharp.contains(
        "Native_TotalDropsAsync(NugetKotlinHandle handle, NugetKotlinHandle scopeHandle, " +
            "IntPtr dropsEnumerate, IntPtr dropsCurrent, IntPtr dropsKey, " +
            "IntPtr dropsReleaseTask, IntPtr dropsMoveNextBegin, IntPtr dropsMoveNextEnd, " +
            "IntPtr dropsDisposeEnumeration, IntPtr dropsReleaseContext, " +
            "IntPtr callback, IntPtr userData);"
      ),
      "got=" + result.generatedCSharp.lines().filter { it.contains("Native_TotalDropsAsync(") },
    )
    assertTrue(
      result.generatedCSharp.contains(
        "NugetThunks.NugetAsyncEnumeratePtr, NugetThunks.NugetAsyncIntCurrentPtr, dropsAsyncKey, " +
            "NugetThunks.NugetAsyncReleaseTaskPtr, NugetThunks.NugetAsyncMoveNextBeginPtr, " +
            "NugetThunks.NugetAsyncMoveNextEndPtr, NugetThunks.NugetAsyncDisposeEnumerationPtr, " +
            "NugetThunks.NugetAsyncReleaseContextPtr, "
      ),
    )
    // The plan route fans out the same eight, ahead of its error slot.
    assertEquals(
      listOf(
        "handle", "dropsEnumerate", "dropsCurrent", "dropsKey", "dropsReleaseTask",
        "dropsMoveNextBegin", "dropsMoveNextEnd", "dropsDisposeEnumeration",
        "dropsReleaseContext", "errorOut",
      ),
      result.exportParameters("bowl_ceramic_attach"),
    )
  }

  /**
   * Enumerate is the only keyed thunk. On a miss it mints nothing (no enumeration handle, no
   * `CancellationTokenSource`), disposes nothing (it has no payload), and reports through `errOut`.
   * Never the shared ctx-dispatch shell.
   */
  @Test
  fun `the Enumerate thunk has its own miss branch and Current is per element type`() {
    val result: Tier1Result = run()
    val csharp: String = result.generatedCSharp
    val enumerate: String = result.thunk("NugetAsyncEnumerate")

    assertTrue(
      enumerate.contains("NugetAsyncEnumerateThunk(IntPtr key, IntPtr* errOut)"),
      enumerate,
    )
    assertInOrder(
      enumerate,
      "object? target = LookupCtx(key);",
      "if (target is null)",
      "throw new ObjectDisposedException(\"NugetAsyncEnumerate\");",
      "return ((Func<IntPtr>)target)();",
      "*errOut = NugetErrorNative.CreateManagedError(ex);",
    )
    assertFalse(enumerate.contains("NugetMarshal.Dispose("), enumerate)
    // One Current per element type; a String is boxed on the read, a Boolean rides a byte.
    listOf(
      "return NugetAsyncEnumeration.Current<int>(a0);",
      "return NugetMarshal.WrapString(NugetAsyncEnumeration.Current<string>(a0));",
      "return NugetAsyncEnumeration.Current<bool>(a0) ? (byte)1 : (byte)0;",
    ).forEach { text -> assertTrue(csharp.contains(text), "expected `$text`") }
    // The two step halves and the dispose are shared: one thunk each, whatever the element.
    listOf(
      "NugetAsyncEnumerateThunk(", "NugetAsyncIntCurrentThunk(", "NugetAsyncMoveNextBeginThunk(",
      "NugetAsyncMoveNextEndThunk(", "NugetAsyncDisposeEnumerationThunk(",
    ).forEach { thunk ->
      assertEquals(1, csharp.split(" $thunk").size - 1, thunk)
    }
    // User-code thunks carry the error slot; the dispose is plumbing and does not.
    assertTrue(
      csharp.contains(
        "NugetAsyncMoveNextBeginThunk(IntPtr a0, IntPtr a1, IntPtr a2, IntPtr* errOut)"
      )
    )
    assertTrue(csharp.contains("static int NugetAsyncMoveNextEndThunk(IntPtr a0, IntPtr* errOut)"))
    assertTrue(csharp.contains("return NugetTasks.End<bool>(a0) ? 1 : 0;"))
    assertTrue(csharp.contains("static void NugetAsyncDisposeEnumerationThunk(IntPtr a0, int a1)"))
  }

  /**
   * The reverse shim's enumeration rules, copied. The Dispose ORDER is the one whose failure is
   * silent: `DisposeAsync` during a pending `MoveNextAsync` throws `NotSupportedException` and
   * leaves the iterator running, so the pending step is awaited first, after the cancel.
   */
  @Test
  fun `the forward enumeration copies the reverse shim's rules`() {
    val csharp: String = run().generatedCSharp
    val enumeration: String = csharp.substringAfter("internal abstract class NugetAsyncEnumeration")
      .substringBefore("internal sealed class NugetAsyncEnumeration<T>")

    // Enumerate: the source is minted first and its token reaches the enumerator, so an
    // `[EnumeratorCancellation]` iterator sees Kotlin cancellation; the handle is allocated last.
    assertInOrder(
      enumeration.substringAfter("static IntPtr Enumerate<T>(").substringBefore("static T Current"),
      "cts = new global::System.Threading.CancellationTokenSource();",
      "source.GetAsyncEnumerator(cts.Token);",
      "GCHandle.Alloc(",
      "cts.Dispose();",
      "throw;",
    )
    // MoveNextBegin: the pending step is remembered BEFORE it is attached, for Dispose to await.
    val moveNextBegin: String = enumeration.substringAfter("static void MoveNextBegin(")
      .substringBefore("DisposeEnumeration")
    assertInOrder(
      moveNextBegin,
      "pending = n.MoveNext().AsTask();",
      "n.Pending = pending;",
      "NugetTasks.Attach(pending, callback, ctx);",
    )
    val dispose: String = enumeration.substringAfter("static void DisposeEnumeration(")
    assertInOrder(
      dispose,
      "h.Free();",
      "global::System.Threading.Tasks.Task.Run(async () =>",
      "if (cancelled != 0)",
      "try { n.Cts.Cancel(); } catch (AggregateException) { }",
      "try { await n.Pending; } catch (Exception) { }",
      "try { await n.Dispose(); } catch (Exception) { }",
      "n.Cts.Dispose();",
      "catch (Exception)",
    )
    assertFalse(csharp.contains("async void"), "an escaping exception would abort the host")
    // `await` is not allowed in an unsafe context, so these members are not on `NugetTasks`.
    val tasks: String = csharp.substringAfter("internal static unsafe class NugetTasks")
      .substringBefore("internal abstract class NugetAsyncEnumeration")
    assertFalse(tasks.contains("await "), tasks)
  }

  /**
   * Slice (a)'s ownership rule, on the three call shapes that host a `Flow`: C# owns the key from
   * its registration until the native call has returned, and any exit in between unregisters it.
   * `topped` and `stash` put a `String` after the flow, so its null guard runs after the
   * registration. The Flow-return host registers once per C# collect and returns the call's
   * result, so it holds the result across the hand-over.
   */
  @Test
  fun `the key is guarded on the suspend plan and Flow-return call shapes`() {
    val result: Tier1Result = run()
    val register = "dropsAsyncKey = NugetTasks.Register(drops, nameof(drops), " +
        "(Func<IntPtr>)(() => NugetAsyncEnumeration.Enumerate(drops)));"

    listOf(
      result.member("public Task<int> ToppedAsync(") to "= Native_ToppedAsync(",
      result.member("public int Stash(") to "= Native_Stash(",
    ).forEach { (member, call) ->
      assertInOrder(
        member,
        "IntPtr dropsAsyncKey = IntPtr.Zero;",
        "try",
        register,
        call,
        "dropsAsyncKey = IntPtr.Zero;",
        "finally",
        "NugetThunks.UnregisterCtx(dropsAsyncKey);",
      )
      val lines: List<String> = member.lines()
      val callLine: Int = lines.indexOfFirst { line -> line.contains(call) }
      assertTrue(lines[callLine].contains("topping ?? throw"), lines[callLine])
      assertEquals("dropsAsyncKey = IntPtr.Zero;", lines[callLine + 1].trim(), member)
    }
    assertInOrder(
      result.member("public KotlinFlow<int> Doubled("),
      "IntPtr sourceAsyncKey = IntPtr.Zero;",
      "try",
      "sourceAsyncKey = NugetTasks.Register(source, nameof(source), ",
      "var nugetResult = Native_DoubledCollect(",
      "sourceAsyncKey = IntPtr.Zero;",
      "return nugetResult;",
      "finally",
      "NugetThunks.UnregisterCtx(sourceAsyncKey);",
    )
  }

  @Test
  fun `the Kotlin flow is the runtime's pull seam and its holder owns the key`() {
    val kotlin: String = run().generated
    val total: String = kotlin.substringAfter("waterfountain_totalDrops_async\")")
      .substringBefore("@CName(")

    // Built before the coroutine launches, so Kotlin owns the key on the calling thread.
    assertInOrder(
      total,
      "val dropsArg: kotlinx.coroutines.flow.Flow<kotlin.Int> = run {",
      "val dropsHolder = NugetAsyncContext(dropsReleaseContext, dropsKey)",
      "io.github.xxfast.kotlin.native.nuget.runtime.flowForKotlin(",
      "release = { nugetTask -> dropsReleaseTask.",
      "enumerate = { nugetCallbackCall { nugetErr -> dropsEnumerate.",
      ".invoke(dropsHolder.key, nugetErr) }!! },",
      "moveNextBegin = { nugetEnumeration, nugetCallback, nugetCtx -> nugetCallbackCall {",
      "moveNextEnd = { nugetTask -> nugetCallbackCall {",
      "current = { nugetEnumeration -> nugetCallbackCall {",
      "dispose = { nugetEnumeration, nugetCancelled -> dropsDisposeEnumeration.",
      ".invoke(nugetEnumeration, if (nugetCancelled) 1 else 0) },",
      "launchForCSharp(",
    )
    // Enumerate, both halves of a step and Current run user C#: four error-channel crossings.
    assertEquals(4, total.split("nugetCallbackCall { nugetErr ->").size - 1, total)
    // A String element's box is read, then released through the counted registry.
    val describe: String = kotlin.substringAfter("waterfountain_describe_async\")")
      .substringBefore("@CName(")
    assertInOrder(
      describe,
      "val sipsBox = nugetCallbackCall {",
      "val sipsValue = sipsBox.asStableRef<String>().get()",
      "NugetHandles.release(sipsBox)",
    )
    // The plan route: the holder is the export's first statement, ahead of the `try`.
    val attach: String = kotlin.substringAfter("bowl_ceramic_attach\")").substringBefore("@CName(")
    val holder = "val dropsHolder = NugetAsyncContext(dropsReleaseContext, dropsKey)"
    assertTrue(attach.indexOf(holder) in 0 until attach.indexOf("try {"), attach)
    assertEquals(1, attach.split("NugetAsyncContext(").size - 1, attach)
    // Every Kotlin-side handle on this route goes through `NugetHandles`.
    assertFalse(kotlin.contains("StableRef.create("), "a bare StableRef.create is uncounted")
  }

  /**
   * A `StateFlow<T>`, a `SharedFlow<T>` and a `MutableStateFlow<T>` at a parameter stay unbound,
   * under the existing `SKIPPED_UNSUPPORTED_INPUT` code with their own sentence: C# has nothing to
   * make a synchronous current value from. The hint is the remedy that works, on the plan route
   * and on a `suspend` host alike.
   */
  @Test
  fun `a StateFlow parameter is refused with its own sentence and the stateIn hint`() {
    val result: Tier1Result = run()

    listOf("level", "shared", "mutable", "levelLater").forEach { member ->
      val named: List<String> =
        result.kspWarnings.filter { warning -> warning.contains("WaterFountain.$member:") }
      assertEquals(1, named.size, "$member must be named once; kspWarnings=${result.kspWarnings}")
      val warning: String = named.single()
      assertTrue(
        warning.contains("[nuget:${ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT.name}]"),
        warning,
      )
      assertTrue(
        warning.contains(
          "a StateFlow, SharedFlow or MutableStateFlow cannot be passed in: C# has no source " +
              "for a synchronous current value or a replay cache"
        ),
        warning,
      )
      assertTrue(
        warning.contains("declare the parameter as `Flow<T>` and call `stateIn(scope)`"),
        warning,
      )
    }
    listOf("Level(", "Shared(", "Mutable(", "LevelLaterAsync(", "Gauge(").forEach { member ->
      assertFalse(result.generatedCSharp.contains(" $member"), "$member must not be declared")
    }
    // On a sealed arm the refusal is named too: this position used to be dropped in silence.
    assertEquals(
      1,
      result.kspWarnings.count { warning -> warning.contains("Bowl.Ceramic.gauge:") },
      "kspWarnings=${result.kspWarnings}",
    )
  }

  @Test
  fun `an element outside the v1 set stays refused by name and a nullable flow binds`() {
    val result: Tier1Result = run()

    assertEquals(
      1,
      result.kspWarnings.count { warning -> warning.contains("WaterFountain.letters:") },
      "letters is refused and must be named once; kspWarnings=${result.kspWarnings}",
    )
    assertFalse(result.generatedCSharp.contains("LettersAsync("), "LettersAsync must not be declared")
    // ADR-210 amendment: a `Flow<T>?` parameter is an `IAsyncEnumerable<T>?`.
    assertTrue(
      result.kspWarnings.none { warning -> warning.contains("WaterFountain.maybe:") },
      "kspWarnings=${result.kspWarnings}",
    )
    assertTrue(
      result.generatedCSharp.contains(
        "MaybeAsync(global::System.Collections.Generic.IAsyncEnumerable<int>? drops",
      ),
      result.generatedCSharp.lines().filter { "MaybeAsync" in it }.toString(),
    )
  }

  /**
   * ADR-210 leaves a `Throwable` element open ("v1 may refuse it by name"). v1 refuses it: the
   * Current thunk casts the element type it names, and nothing proves a `Throwable` rides the
   * `String` thunk. Named once at every position, the sealed arm's non-suspend one included.
   */
  @Test
  fun `a Throwable element is refused by name at every position`() {
    val result: Tier1Result = run()

    // Each route words its own refusal: the legacy suspend gate spells the parameter, the plan
    // names the element set a `Flow` parameter admits, the arm names the missing route.
    val refusals: Map<String, Pair<ForwardDiagnosticKind, String>> = mapOf(
      "WaterFountain.mishaps:" to
          (ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT to "but not faults: Flow<Throwable>"),
      "WaterFountain.mishapsNow:" to
          (ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT to
              "a `Flow<T>` at a parameter"),
      "Bowl.Ceramic.spill:" to
          (ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_COMBINATION to
              "a FLOW_PROTOCOL member of a sealed subclass"),
    )
    refusals.forEach { (member, expected) ->
      val (kind, sentence) = expected
      val named: List<String> = result.kspWarnings.filter { warning -> warning.contains(member) }
      assertEquals(1, named.size, "$member must be named once; kspWarnings=${result.kspWarnings}")
      val warning: String = named.single()
      assertTrue(warning.contains("[nuget:${kind.name}]"), warning)
      assertTrue(warning.contains(sentence), warning)
    }
    listOf("MishapsAsync(", " MishapsNow(", " Spill(").forEach { member ->
      assertFalse(result.generatedCSharp.contains(member), "$member must not be declared")
    }
    listOf("mishaps", "mishapsNow", "spill").forEach { member ->
      assertFalse(result.generated.contains("_$member"), "$member must not be exported")
    }
    // No Current thunk was minted for it: the three the fixture's bound members need, no more.
    val currents: Set<String> = Regex("""NugetAsync(\w+)CurrentThunk\(""")
      .findAll(result.generatedCSharp)
      .map { match -> match.groupValues[1] }
      .toSet()
    assertEquals(setOf("Int", "String", "Bool"), currents)
  }

  @Test
  fun `a module with no Flow parameter gains none of the enumeration helpers`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.tricklefree

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.flowOf

      class Bowl {
        suspend fun fill(prepare: suspend (Int) -> String): String = prepare(1)
        fun drops(): Flow<Int> = flowOf(1)
      }
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    // The suspend lambda parameter keeps slice (a)'s helpers and gains nothing of slice (b)'s.
    assertTrue(result.generatedCSharp.contains("class NugetTasks"))
    assertFalse(result.generatedCSharp.contains("NugetAsyncEnumeration"))
    assertFalse(result.generatedCSharp.contains("NugetAsyncEnumerate"))
    assertFalse(result.generatedCSharp.contains("NugetAsyncMoveNextBegin"))
    assertFalse(result.generated.contains("flowForKotlin"))
  }

  /**
   * The same binding and the same key guard on every other owner, mirroring slice (a)'s cell: each
   * renders its body through its own call shape, and a registration whose key local was never
   * declared would not compile for a consumer.
   */
  @Test
  fun `the flow binds and its key is guarded on the top-level object and extension owners`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.trickleowners

      import kotlinx.coroutines.flow.Flow

      class Fountain(val cat: String)

      object Cistern {
        fun shelve(drops: Flow<Int>, topping: String): Int = topping.length
      }

      fun topStash(drops: Flow<Int>, topping: String): Int = topping.length

      fun Fountain.extStash(drops: Flow<Int>, topping: String): Int = topping.length
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(result.kspErrors.isEmpty(), "kspErrors=${result.kspErrors}")
    assertTrue(result.compileErrors.isEmpty(), "compileErrors=${result.compileErrors}")
    assertEquals(emptyList(), result.kspWarnings.filter { it.contains("[nuget:SKIPPED") })
    listOf("int Shelve(", "int TopStash(", "int ExtStash(").forEach { signature ->
      assertInOrder(
        result.member(signature),
        "IntPtr dropsAsyncKey = IntPtr.Zero;",
        "dropsAsyncKey = NugetTasks.Register(drops, nameof(drops), ",
        "dropsAsyncKey = IntPtr.Zero;",
        "NugetThunks.UnregisterCtx(dropsAsyncKey);",
      )
    }
  }
}
