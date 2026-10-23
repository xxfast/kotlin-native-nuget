package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-210 slice (a): a `suspend (T) -> R` PARAMETER binds as `Func<T, CancellationToken, Task<R>>`
 * on every host the fixture `TreatPrepSample.kt` crosses: a class `suspend fun` and a sealed-arm
 * `suspend fun` (the legacy suspend routes), a non-suspend member and a constructor that can only
 * store it (the ADR-062 plan), and a top-level `suspend fun`.
 *
 * `IntegrationTests/SuspendLambdaParameterTests.cs` proves the behaviour. This class pins what a
 * running test cannot see, each of which is a silent-wrong-output class if it drifts:
 *
 *  - the flat slot ORDER, and that the three release thunks cross once per export;
 *  - the Begin thunk's own body on a key MISS (which slots it frees, and which it must not);
 *  - the reverse shim's rules as copied into the forward `NugetTasks`;
 *  - the Kotlin-owned lifetime (the holder and its cleaner), and no bare `StableRef.create`.
 *
 * Oreo supervises the treat prep; Mylo eats it.
 */
class Tier1SuspendLambdaParameterTest {

  private val fixture: String = """
    package tier1.treatprep

    enum class Mood { CALM, HUNGRY }

    class TreatChef(val cat: String) {
      suspend fun plate(count: Int, prepare: suspend (Int) -> String): String = prepare(count)
      suspend fun serve(count: Int, bowl: suspend (Int) -> Unit): Int = count
      suspend fun announce(call: suspend () -> String): String = call()
      suspend fun mix(wet: Int, dry: Int, blend: suspend (Int, Int) -> Int): Int = blend(wet, dry)
      suspend fun label(tag: suspend (String) -> String): String = tag(cat)
      suspend fun both(first: suspend (Int) -> String, second: suspend (String) -> Int): Int =
        second(first(1))
      fun later(prepare: suspend (Int) -> String): Int = if (prepare === prepare) 0 else 1

      // A `String` AFTER the lambda, on each route: its null guard runs after the registration.
      suspend fun garnish(prepare: suspend (Int) -> String, topping: String): String =
        prepare(1) + topping
      fun stash(prepare: suspend (Int) -> String, topping: String): Int =
        if (prepare === prepare) topping.length else 0

      // Arity three, and every payload wire that is not a plain scalar: an enum by ordinal, an
      // exported class over a handle, a Boolean over a byte, and a Boolean result.
      suspend fun judge(rate: suspend (Mood, TreatChef, Boolean) -> Boolean): Boolean =
        rate(Mood.CALM, this, true)

      // Refused by name: arity four. (`maybe`, a nullable lambda, and `initial`, a `Char` result,
      // bind since ADR-239.)
      suspend fun maybe(prepare: (suspend (Int) -> String)?): Int = if (prepare == null) 0 else 1
      suspend fun initial(pick: suspend (Int) -> Char): Int = 0
      suspend fun crowd(pick: suspend (Int, Int, Int, Int) -> Int): Int = 0
    }

    sealed class Prep {
      class Batch(val size: Int) : Prep() {
        suspend fun cook(prepare: suspend (Int) -> String): String = prepare(size)
        fun keep(prepare: suspend (Int) -> String): Int = if (prepare === prepare) size else 0
      }

      data object Resting : Prep()
    }

    class TreatTimer(private val prepare: suspend (Int) -> String) {
      suspend fun ring(count: Int): String = prepare(count)
    }

    suspend fun prepOnce(seed: Int, prepare: suspend (Int) -> String): String = prepare(seed)
  """.trimIndent()

  private fun run(): Tier1Result = Tier1Harness.run(
    fixture,
    fileName = "TreatPrepSample.kt",
    libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
  )

  private val token: String = "global::System.Threading.CancellationToken"
  private val task: String = "global::System.Threading.Tasks.Task"

  /** The text of the C# member whose declaration line contains [signature], up to its close. */
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

  @Test
  fun `the generated bridge compiles and reports nothing for the bound members`() {
    val result: Tier1Result = run()

    assertTrue(result.kspErrors.isEmpty(), "kspErrors=${result.kspErrors}")
    assertTrue(result.compileErrors.isEmpty(), "compileErrors=${result.compileErrors}")
    listOf(
      "plate", "serve", "announce", "mix", "label", "both", "later", "judge", "garnish", "stash",
      "cook", "keep",
      "prepOnce",
      "TreatTimer",
    ).forEach { member ->
      assertFalse(
        result.kspWarnings.any { warning -> Regex("\\b$member\\b").containsMatchIn(warning) },
        "$member binds, so it must not be named; kspWarnings=${result.kspWarnings}",
      )
    }
  }

  @Test
  fun `every host binds the lambda as a Func over the token and a Task`() {
    val csharp: String = run().generatedCSharp

    listOf(
      // The legacy suspend route on a class: String, Unit, arity 0, arity 2, a String payload.
      "public Task<string> PlateAsync(int count, Func<int, $token, $task<string>> prepare, " +
          "CancellationToken cancellationToken = default)",
      "public Task<int> ServeAsync(int count, Func<int, $token, $task> bowl, " +
          "CancellationToken cancellationToken = default)",
      "public Task<string> AnnounceAsync(Func<$token, $task<string>> call, " +
          "CancellationToken cancellationToken = default)",
      "public Task<int> MixAsync(int wet, int dry, Func<int, int, $token, $task<int>> blend, " +
          "CancellationToken cancellationToken = default)",
      "public Task<string> LabelAsync(Func<string, $token, $task<string>> tag, " +
          "CancellationToken cancellationToken = default)",
      // The plan: a non-suspend class member, a non-suspend arm member and a constructor.
      "public int Later(Func<int, $token, $task<string>> prepare)",
      "public int Keep(Func<int, $token, $task<string>> prepare)",
      "public TreatTimer(Func<int, $token, $task<string>> prepare)",
      // ADR-118's arm suspend route, and the top-level suspend route.
      "public Task<string> CookAsync(Func<int, $token, $task<string>> prepare, " +
          "CancellationToken cancellationToken = default)",
      "public static Task<string> PrepOnceAsync(int seed, Func<int, $token, $task<string>> " +
          "prepare, CancellationToken cancellationToken = default)",
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
   * The ABI is positional, so the order is the contract. Per parameter: Begin, End, key. The three
   * release thunks do not depend on the parameter, so only the export's FIRST suspend lambda
   * declares them, right after its own three; the second declares three slots and reads the
   * first's.
   */
  @Test
  fun `the slots are flat and the release thunks cross once per export`() {
    val result: Tier1Result = run()

    assertEquals(
      listOf(
        "handle", "scopeHandle",
        "firstBegin", "firstEnd", "firstKey",
        "firstReleaseTask", "firstReleaseCancellation", "firstReleaseContext",
        "secondBegin", "secondEnd", "secondKey",
        "callbackPtr", "userData",
      ),
      result.exportParameters("treatchef_both_async"),
    )
    assertTrue(
      result.generatedCSharp.contains(
        "Native_BothAsync(NugetKotlinHandle handle, NugetKotlinHandle scopeHandle, " +
            "IntPtr firstBegin, IntPtr firstEnd, IntPtr firstKey, IntPtr firstReleaseTask, " +
            "IntPtr firstReleaseCancellation, IntPtr firstReleaseContext, IntPtr secondBegin, " +
            "IntPtr secondEnd, IntPtr secondKey, IntPtr callback, IntPtr userData);"
      ),
      "got=" + result.generatedCSharp.lines().filter { it.contains("Native_BothAsync(") },
    )
    // The plan route fans out the same six, ahead of its error slot.
    assertEquals(
      listOf(
        "handle", "prepareBegin", "prepareEnd", "prepareKey", "prepareReleaseTask",
        "prepareReleaseCancellation", "prepareReleaseContext", "errorOut",
      ),
      result.exportParameters("treatchef_later"),
    )
    // The second lambda releases through the FIRST one's thunks.
    val both: String = result.generated.substringAfter("treatchef_both_async")
      .substringBefore("@CName(")
    assertTrue(both.contains("NugetAsyncContext(firstReleaseContext, secondKey)"), both)
  }

  @Test
  fun `the call site passes link-time thunk addresses and the key it registered`() {
    val csharp: String = run().generatedCSharp
    val member: String = csharp.substringAfter("public Task<string> PlateAsync(")
      .substringBefore("private static extern")

    assertTrue(
      member.contains(
        "prepareAsyncKey = NugetTasks.Register(prepare, nameof(prepare), (NugetAsyncIntBegin)(" +
            "(a0, callback, awaitCtx) => NugetTasks.Begin(token => prepare(a0, token), " +
            "callback, awaitCtx)));"
      ),
      member,
    )
    assertTrue(
      member.contains(
        "count, NugetThunks.NugetAsyncIntBeginPtr, NugetThunks.NugetAsyncStringEndPtr, " +
            "prepareAsyncKey, NugetThunks.NugetAsyncReleaseTaskPtr, " +
            "NugetThunks.NugetAsyncReleaseCancellationPtr, " +
            "NugetThunks.NugetAsyncReleaseContextPtr, "
      ),
      member,
    )
    assertFalse(csharp.contains("GetFunctionPointerForDelegate"), "ADR-102: link-time only")
  }

  /**
   * The ownership rule, on both routes that host the parameter: C# owns a key from its registration
   * until the native call has RETURNED, and any exit in between unregisters it. `garnish` and
   * `stash` put a `String` after the lambda, so the string's null guard is evaluated after the
   * registration: without the guard a `null` topping left the key, and the consumer's closure,
   * in the context table for the life of the process.
   *
   * Pinned as an order: declare the key zero, register inside the `try`, make the call (the later
   * argument's guard is in its argument list), zero the key as the very next statement, and
   * unregister in the `finally`, where a zero key is a no-op. The Kotlin half builds the holder
   * that takes the key before anything else in the export can throw.
   */
  @Test
  fun `a key registered before a later argument fails is unregistered on both routes`() {
    val result: Tier1Result = run()
    val csharp: String = result.generatedCSharp

    fun assertGuarded(member: String, call: String) {
      val order: List<String> = listOf(
        "IntPtr prepareAsyncKey = IntPtr.Zero;",
        "try",
        "prepareAsyncKey = NugetTasks.Register(prepare, nameof(prepare), ",
        call,
        "prepareAsyncKey = IntPtr.Zero;",
        "finally",
        "NugetThunks.UnregisterCtx(prepareAsyncKey);",
      )
      var from = 0
      order.forEach { text ->
        val at: Int = member.indexOf(text, from)
        assertTrue(at >= 0, "expected `$text` after offset $from in:\n$member")
        from = at + text.length
      }
      // The later argument's guard sits in the call's own argument list, after the registration.
      val callLine: String = member.lines().first { line -> line.contains(call) }
      assertTrue(callLine.contains("prepareAsyncKey, "), callLine)
      assertTrue(callLine.contains("topping ?? throw"), callLine)
      // The hand-over is the statement immediately after the call, ahead of any error check.
      val lines: List<String> = member.lines()
      assertEquals(
        "prepareAsyncKey = IntPtr.Zero;",
        lines[lines.indexOf(callLine) + 1].trim(),
        member,
      )
    }

    // The legacy suspend route.
    assertGuarded(
      csharp.substringAfter("public Task<string> GarnishAsync(")
        .substringBefore("private static extern"),
      "= Native_GarnishAsync(",
    )
    // ...where one closure object holds every lambda of the method, so the completion callback's
    // GCHandle must go too, or it roots the consumer's lambda after the key is gone.
    val garnishAsync: String = csharp.substringAfter("public Task<string> GarnishAsync(")
      .substringBefore("private static extern")
    val unregistered: Int = garnishAsync.indexOf("NugetThunks.UnregisterCtx(prepareAsyncKey);")
    val freed: Int =
      garnishAsync.indexOf("if (callbackHandle.IsAllocated) callbackHandle.Free();", unregistered)
    assertTrue(freed > unregistered, garnishAsync)
    assertTrue(garnishAsync.indexOf("throw;", freed) > freed, garnishAsync)
    // The ADR-062 plan route.
    assertGuarded(
      csharp.substringAfter("public int Stash(").substringBefore("private static extern"),
      "= Native_Stash(",
    )

    // Kotlin, plan route: the holder is bound ahead of the `try`, so ahead of the receiver read
    // and of every other argument's lowering.
    val stash: String = result.generated.substringAfter("treatchef_stash\")")
      .substringBefore("@CName(")
    val holder = "val prepareHolder = NugetAsyncContext(prepareReleaseContext, prepareKey)"
    assertTrue(stash.indexOf(holder) in 0 until stash.indexOf("try {"), stash)
    assertEquals(1, stash.split("NugetAsyncContext(").size - 1, stash)
    // Kotlin, legacy route: the lambda lowering leads the prelude and builds its holder there.
    val garnish: String = result.generated.substringAfter("treatchef_garnish_async\")")
      .substringBefore("@CName(")
    assertTrue(
      garnish.indexOf("NugetAsyncContext(prepareReleaseContext, prepareKey)") in
          0 until garnish.indexOf("launchForCSharp("),
      garnish,
    )
  }

  /**
   * The ADR's per-slot ownership statement for a key miss. A handle-passed payload is freed here
   * because the delegate read that would have freed it never runs. `callback` is a function
   * pointer and `awaitCtx` is the pending record `awaitForKotlin` still owns and releases itself
   * when `begin` throws: disposing either is a crash and a double free, which is exactly what the
   * shared ctx-dispatch shell would do with them.
   */
  @Test
  fun `a Begin thunk has its own miss branch and frees payload handles only`() {
    val result: Tier1Result = run()
    val string: String = result.thunk("NugetAsyncStringBegin")
    val int: String = result.thunk("NugetAsyncIntBegin")

    assertTrue(
      string.contains(
        "NugetAsyncStringBeginThunk(IntPtr a0, IntPtr callback, IntPtr awaitCtx, IntPtr key, " +
            "IntPtr* errOut)"
      ),
      string,
    )
    assertTrue(string.contains("object? target = LookupCtx(key);"), string)
    assertTrue(string.contains("if (a0 != IntPtr.Zero) NugetMarshal.Dispose(a0);"), string)
    assertTrue(
      string.contains("throw new ObjectDisposedException(\"NugetAsyncStringBegin\");"), string,
    )
    assertTrue(
      string.contains("return ((NugetAsyncStringBegin)target)(a0, callback, awaitCtx);"), string,
    )
    assertTrue(string.contains("*errOut = NugetErrorNative.CreateManagedError(ex);"), string)
    listOf(string, int).forEach { thunk ->
      assertFalse(thunk.contains("Dispose(callback)"), thunk)
      assertFalse(thunk.contains("Dispose(awaitCtx)"), thunk)
      assertFalse(thunk.contains("Dispose(key)"), thunk)
    }
    // A by-value payload needs nothing on a miss.
    assertFalse(int.contains("NugetMarshal.Dispose("), int)
    assertTrue(
      result.generatedCSharp.contains(
        "(IntPtr)(delegate* unmanaged[Cdecl]<int, IntPtr, IntPtr, IntPtr, IntPtr*, IntPtr>)" +
            "&NugetAsyncIntBeginThunk;"
      ),
    )
  }

  /**
   * The payload wires beyond a plain scalar: the enum crosses as its ordinal, the class as a handle
   * C# reads (and so owns), the Boolean as a byte. On a miss only the handle slot is freed.
   */
  @Test
  fun `enum class and Boolean payloads cross on their callback wires`() {
    val result: Tier1Result = run()
    val csharp: String = result.generatedCSharp

    assertTrue(
      Regex("""public Task<bool> JudgeAsync\(Func<\S*Mood, \S*TreatChef, bool, """)
        .containsMatchIn(csharp),
      "got=" + csharp.lines().filter { it.contains("JudgeAsync(") },
    )
    assertTrue(
      csharp.contains(
        "internal delegate IntPtr NugetAsyncIntObjectBoolBegin(int a0, IntPtr a1, byte a2, " +
            "IntPtr callback, IntPtr awaitCtx);"
      ),
      "got=" + csharp.lines().filter { it.contains("internal delegate IntPtr NugetAsync") },
    )
    val begin: String = result.thunk("NugetAsyncIntObjectBoolBegin")
    assertTrue(begin.contains("if (a1 != IntPtr.Zero) NugetMarshal.Dispose(a1);"), begin)
    assertEquals(1, Regex("""NugetMarshal\.Dispose\(""").findAll(begin).count(), begin)
    assertTrue(csharp.contains("return NugetTasks.End<bool>(a0) ? (byte)1 : (byte)0;"))
    val call: String = csharp.lines().first { line -> line.contains("rateAsyncKey = NugetTasks.") }
    val adapted = Regex(
      """rate\(\(\S*Mood\)a0, NugetMarshal\.FromHandle<\S*TreatChef>\(a1\), a2 != 0, token\)"""
    )
    assertTrue(adapted.containsMatchIn(call), call)
    val judge: String = result.generated.substringAfter("treatchef_judge_async")
      .substringBefore("@CName(")
    assertTrue(
      judge.contains(
        ".invoke(rateArg0.ordinal, NugetHandles.retain(rateArg1), " +
            "if (rateArg2) 1.toByte() else 0.toByte(), nugetCallback, nugetCtx, rateHolder.key, " +
            "nugetErr)"
      ),
      judge,
    )
    assertTrue(judge.contains("} != 0.toByte()"), judge)
  }

  @Test
  fun `one Begin per payload wire shape and one End per result type`() {
    val csharp: String = run().generatedCSharp

    listOf(
      "internal delegate IntPtr NugetAsyncBegin(IntPtr callback, IntPtr awaitCtx);",
      "internal delegate IntPtr NugetAsyncIntBegin(int a0, IntPtr callback, IntPtr awaitCtx);",
      "internal delegate IntPtr NugetAsyncIntIntBegin(int a0, int a1, IntPtr callback, " +
          "IntPtr awaitCtx);",
      "internal delegate IntPtr NugetAsyncStringBegin(IntPtr a0, IntPtr callback, " +
          "IntPtr awaitCtx);",
      // No adapting closure converts a result: a String is boxed in End, after the read.
      "return NugetMarshal.WrapString(NugetTasks.End<string>(a0));",
      "return NugetTasks.End<int>(a0);",
      "NugetTasks.End(a0);",
    ).forEach { text -> assertTrue(csharp.contains(text), "expected `$text`") }
    listOf("NugetAsyncIntBeginThunk(", "NugetAsyncStringEndThunk(", "NugetAsyncVoidEndThunk(")
      .forEach { thunk ->
        assertEquals(1, Regex(Regex.escape(" $thunk")).findAll(csharp).count(), thunk)
      }
  }

  @Test
  fun `the forward NugetTasks copies the reverse shim's rules`() {
    val csharp: String = run().generatedCSharp
    val tasks: String = csharp.substringAfter("internal static unsafe class NugetTasks")
      .substringBefore("internal static unsafe partial class NugetThunks")

    // Attach: None on the default scheduler, and the task handle freed here if attaching throws.
    assertTrue(tasks.contains("global::System.Threading.Tasks.TaskContinuationOptions.None,"))
    assertTrue(tasks.contains("global::System.Threading.Tasks.TaskScheduler.Default);"))
    val attach: String = tasks.substringAfter("static void Attach(").substringBefore("Begin(")
    assertTrue(
      attach.contains("GCHandle.FromIntPtr(handle).Free();\n                throw;"),
      attach,
    )
    // Begin: the source is minted before the user call, and its handle is allocated last.
    val begin: String = tasks.substringAfter("static IntPtr Begin(").substringBefore("void End(")
    val minted: Int = begin.indexOf("cts = new global::System.Threading.CancellationTokenSource()")
    val called: Int = begin.indexOf("start(cts.Token)")
    val attached: Int = begin.indexOf("Attach(task, callback, ctx);")
    val allocated: Int = begin.indexOf("GCHandle.Alloc(cts)")
    assertTrue(minted in 0 until called, begin)
    assertTrue(called < attached && attached < allocated, begin)
    // End: GetResult, with the task handle freed in `finally` on every path.
    val end: String = tasks.substringAfter("static T End<T>(")
      .substringBefore("ReleaseCancellation")
    assertTrue(end.contains(".GetAwaiter().GetResult();"), end)
    assertTrue(end.contains("finally\n            {\n                handle.Free();"), end)
    // Release cancellation: free, then dispose, or queue the cancel off this thread.
    assertTrue(tasks.contains("ThreadPool.UnsafeQueueUserWorkItem("), tasks)
    // The release thunks are not user code, so task and cancellation carry no error slot.
    assertTrue(csharp.contains("static void NugetAsyncReleaseTaskThunk(IntPtr a0)"))
    assertTrue(csharp.contains("static void NugetAsyncReleaseCancellationThunk(IntPtr a0, int a1)"))
    assertTrue(
      csharp.contains("static void NugetAsyncReleaseContextThunk(IntPtr a0, IntPtr* errOut)")
    )
  }

  @Test
  fun `the Kotlin lambda awaits through the runtime seam and its holder owns the key`() {
    val kotlin: String = run().generated

    // The holder: a cleaner over the release thunk and the key, capturing nothing else.
    assertTrue(kotlin.contains("private class NugetAsyncContext("), "the holder class is emitted")
    assertTrue(kotlin.contains("kotlin.native.ref.createCleaner("), kotlin.takeLast(800))
    assertTrue(kotlin.contains(") { (fn, ctx) -> fn.invoke(ctx, null) }"), kotlin.takeLast(800))
    assertEquals(1, Regex("class NugetAsyncContext\\(").findAll(kotlin).count())
    val plate: String = kotlin.substringAfter("treatchef_plate_async").substringBefore("@CName(")
    // Built before the coroutine launches, so Kotlin owns the key on the calling thread.
    assertTrue(
      plate.indexOf("NugetAsyncContext(prepareReleaseContext, prepareKey)") in
          0 until plate.indexOf("launchForCSharp("),
      plate,
    )
    assertTrue(
      plate.contains("val prepareArg: suspend (kotlin.Int) -> kotlin.String = run {"),
      plate,
    )
    assertTrue(
      plate.contains("io.github.xxfast.kotlin.native.nuget.runtime.awaitForKotlin("),
      plate,
    )
    // Both user-code crossings go through the ADR-161 error channel, and the key rides last.
    assertEquals(2, Regex("nugetCallbackCall \\{ nugetErr ->").findAll(plate).count(), plate)
    assertTrue(
      plate.contains(".invoke(prepareArg0, nugetCallback, nugetCtx, prepareHolder.key, nugetErr)"),
      plate,
    )
    assertTrue(plate.contains("invoke(nugetSource, if (nugetCancelled) 1 else 0)"), plate)
    // The String result box is read, then released through the counted registry.
    assertTrue(plate.contains("NugetHandles.release(prepareBox)"), plate)
    // Every Kotlin-side handle on this route goes through `NugetHandles`.
    assertFalse(kotlin.contains("StableRef.create("), "a bare StableRef.create is uncounted")
  }

  @Test
  fun `the shapes outside the v1 set stay refused by name`() {
    val result: Tier1Result = run()

    listOf("crowd").forEach { member ->
      assertEquals(
        1,
        result.kspWarnings.count { warning -> warning.contains("TreatChef.$member:") },
        "$member is refused and must be named once; kspWarnings=${result.kspWarnings}",
      )
    }
    listOf("CrowdAsync(").forEach { member ->
      assertFalse(result.generatedCSharp.contains(member), "$member must not be declared")
    }
    // ADR-239: a `Char` result is in the erased vocabulary, and a nullable suspend lambda binds
    // with its `null` as a zero key, so `initial` and `maybe` bind.
    assertTrue(result.generatedCSharp.contains("MaybeAsync("), "MaybeAsync binds")
    assertTrue(result.kspWarnings.none { it.contains("TreatChef.maybe:") }, "${result.kspWarnings}")
    assertTrue(result.generatedCSharp.contains("InitialAsync("), "InitialAsync binds")
    val warnings: List<String> = result.kspWarnings
    assertTrue(warnings.none { it.contains("TreatChef.initial:") }, "$warnings")
  }

  @Test
  fun `a module with no suspend lambda parameter gains none of the route's helpers`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.treatprepfree

      class Bowl {
        suspend fun fill(grams: Int): Int = grams
        fun tip(listener: (Int) -> Unit) { listener(1) }
      }
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertFalse(result.generatedCSharp.contains("class NugetTasks"))
    assertFalse(result.generatedCSharp.contains("NugetAsyncReleaseContext"))
    assertFalse(result.generated.contains("NugetAsyncContext"))
  }

  /**
   * The same guard on every other plan-route owner: each renders its body through its own call
   * shape, and a registration whose key local was never declared would not compile for a consumer.
   */
  @Test
  fun `the key guard reaches the top-level object and extension owners`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.treatprepowners

      class Chef(val cat: String)

      object Pantry {
        fun shelve(prepare: suspend (Int) -> String, topping: String): Int = topping.length
      }

      fun topStash(prepare: suspend (Int) -> String, topping: String): Int = topping.length

      fun Chef.extStash(prepare: suspend (Int) -> String, topping: String): Int = topping.length
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(result.kspErrors.isEmpty(), "kspErrors=${result.kspErrors}")
    assertTrue(result.compileErrors.isEmpty(), "compileErrors=${result.compileErrors}")
    listOf("int Shelve(", "int TopStash(", "int ExtStash(").forEach { signature ->
      val member: String = result.generatedCSharp.substringAfter(signature)
        .substringBefore("private static extern")
      listOf(
        "IntPtr prepareAsyncKey = IntPtr.Zero;",
        "prepareAsyncKey = NugetTasks.Register(prepare, nameof(prepare), ",
        "prepareAsyncKey = IntPtr.Zero;",
        "NugetThunks.UnregisterCtx(prepareAsyncKey);",
      ).fold(0) { from, text ->
        val at: Int = member.indexOf(text, from)
        assertTrue(at >= 0, "expected `$text` after offset $from in `$signature`:\n$member")
        at + text.length
      }
    }
  }
}
