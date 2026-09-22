package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-161, part C: a Kotlin invocation that lands after C# disposed the subscription, or after the
 * per-call frame returned, must never read a freed `GCHandle`.
 *
 * A freed `GCHandle` cannot be validated. Its slots come off a LIFO free list, so the next
 * `GCHandle.Alloc` deterministically takes the slot the disposed subscription released and the stale
 * ctx resolves to a LIVE delegate belonging to somebody else: the memo's spike (b) measured
 * `invokedWrongListener=5000` of 5000, with nothing thrown for the thunk to catch. So the ctx is a
 * never-reused key into a table, and a late call is a lookup MISS the thunk can answer honestly:
 * dropped for a `void` shape (what-question 4, approved), reported as `ObjectDisposedException`
 * through part B's `errOut` channel where a value must be returned.
 *
 * These are text cells (ADR-060 tier 1) because the defect is the ABSENCE of a lookup: a thunk that
 * kept dispatching through `GCHandle.FromIntPtr(ctx).Target` has the same signature, the same arity
 * and the same `DllImport`s as one that does not, so no structural assertion can see the difference.
 *
 * The bridge cells are not decoration: the ADR-084 slot delegates are merged into the one shared
 * thunk shell, so if `_pins` had stayed `GCHandle`s every slot call would MISS the table and report
 * `ObjectDisposedException` instead of calling the C# implementation. Either both move or neither
 * does.
 *
 * Oreo unsubscribes. Whatever arrives for him afterwards is not delivered to Mylo by mistake.
 */
class Tier1CallbackLateInvocationTest {

  private val source: String = """
    package tier1.late

    class Toy(val name: String)

    interface Greeter {
      fun greet(name: String): String
      fun tick(count: Int)
    }

    interface CatEventListener {
      fun onMeow(message: String)
      fun onPurr()
    }

    class Cat(val name: String) {
      private val watchers: MutableList<(String) -> Unit> = mutableListOf()
      private val ticks: MutableList<(Int) -> Unit> = mutableListOf()

      // Per-call lambda, the ADR-062 plan route (ADR-160): value-returning and void.
      fun describeWith(format: (String) -> String): String = format(name)
      fun forEachToy(action: (Toy) -> Unit) = action(Toy("Mouse"))

      // Stored callback, ADR-037: the subscription owns the ctx and drops it on Dispose().
      fun addWatcher(watcher: (String) -> Unit) { watchers.add(watcher) }
      fun removeWatcher(watcher: (String) -> Unit) { watchers.remove(watcher) }
      fun addTicker(ticker: (Int) -> Unit) { ticks.add(ticker) }
      fun removeTicker(ticker: (Int) -> Unit) { ticks.remove(ticker) }

      // ADR-084 interface bridge slots.
      fun greetVia(greeter: Greeter): String = greeter.greet(name)
    }

    // ADR-039 listener bridge: an add/remove pair taking an interface.
    class CatEventSource(val name: String) {
      private val listeners: MutableList<CatEventListener> = mutableListOf()
      fun addListener(listener: CatEventListener) { listeners.add(listener) }
      fun removeListener(listener: CatEventListener) { listeners.remove(listener) }
      fun trigger() = listeners.forEach { it.onMeow("meow") }
    }
  """.trimIndent()

  private fun run(): Tier1Result = Tier1Harness.run(source)

  /** The table itself: concurrent, monotonic, and it never hands out `IntPtr.Zero`. */
  @Test
  fun `the thunk class owns a never-reused key table`() {
    val result = run()

    val missing: List<String> = listOf(
      "private static readonly System.Collections.Concurrent" +
          ".ConcurrentDictionary<IntPtr, object> _ctxTable = new();",
      "private static long _ctxNextKey;",
      "IntPtr key = (IntPtr)System.Threading.Interlocked.Increment(ref _ctxNextKey);",
      "_ctxTable[key] = target;",
      "if (key != IntPtr.Zero) _ctxTable.TryRemove(key, out _);",
      "_ctxTable.TryGetValue(key, out object? target);",
    ).filterNot { needle -> result.generatedCSharp.contains(needle) }

    assertTrue(
      missing.isEmpty(),
      "expected the ADR-161 key table on NugetThunks; missing: $missing; got: " +
          "${csharpLinesFor(result, "_ctx")}",
    )
    // `TryRemove` and not "leave the entry behind": the table is the only strong reference to the
    // delegate once the GCHandle is gone, so an entry that outlived its subscription would be a
    // leak per subscription -- the "never free" alternative the memo rejected.
    assertTrue(
      result.generatedCSharp.contains("TryRemove"),
      "expected removal to drop the table's strong reference",
    )
  }

  /**
   * Every user-code thunk resolves its ctx through the table, and NONE of them dispatches through a
   * `GCHandle` any more. The second half is the load-bearing one: one emitter left on
   * `GCHandle.FromIntPtr(ctx).Target` is exactly the silent wrong-listener bug.
   */
  @Test
  fun `every user-code thunk dispatches through a table lookup`() {
    val result = run()

    val thunks: Int = occurrences(result.generatedCSharp, ", IntPtr* errOut)")
    val lookups: Int = occurrences(result.generatedCSharp, "object? target = LookupCtx(")

    assertTrue(thunks >= 4, "expected the fixture to reach at least four user-code thunk shapes")
    assertEquals(
      thunks,
      lookups,
      "expected every user-code thunk (the ones carrying part B's error slot) to resolve its ctx " +
          "through the key table; a thunk left on GCHandle.FromIntPtr reads a slot the next " +
          "allocation reuses and silently invokes a foreign delegate",
    )
    assertTrue(
      result.generatedCSharp.lines().none { line -> line.contains("GCHandle.FromIntPtr(a") },
      "expected no thunk to dispatch through a GCHandle ctx parameter; got: " +
          "${csharpLinesFor(result, "GCHandle.FromIntPtr(a")}",
    )
    // The GCHandle reads that REMAIN are deliberate and are not thunk ctxs: ADR-084's identity
    // token, read back by the shared probe in `NugetMarshal`. The Flow and async families keep
    // their own GCHandle ctx too, but this fixture reaches neither, so they are pinned by
    // Tier1CallbackFaultContainmentTest instead.
    assertEquals(
      listOf("original = (T)GCHandle.FromIntPtr(token).Target!;"),
      csharpLinesFor(result, "GCHandle.FromIntPtr"),
      "expected the only surviving GCHandle read to be the ADR-084 identity probe",
    )
  }

  /** The two miss branches: drop a `void` shape, report a value-returning one. */
  @Test
  fun `a lookup miss drops a void callback and reports a value-returning one`() {
    val result = run()

    val voidThunk: String = thunkBody(result, "NugetObjectVoidCallbackThunk")
    assertTrue(
      voidThunk.contains("if (target is null)") && voidThunk.contains("return;"),
      "expected a void shape's miss to be DROPPED: the consumer unsubscribed, and reporting would " +
          "surface a KotlinException on whatever thread happened to emit; got: $voidThunk",
    )
    assertTrue(
      !voidThunk.contains("ObjectDisposedException"),
      "expected a void shape NOT to report the miss; got: $voidThunk",
    )

    val stringThunk: String = thunkBody(result, "NugetStringStringCallbackThunk")
    assertTrue(
      stringThunk.contains("throw new ObjectDisposedException(\"NugetStringStringCallback\");"),
      "expected a value-returning shape's miss to throw ObjectDisposedException, which the shell's " +
          "catch reports through part B's errOut slot: there is no value to invent, and a default " +
          "would become an NPE at the Kotlin call site; got: $stringThunk",
    )
    // Inside the `try`, so the throw is contained by the same catch that contains a user throw.
    assertTrue(
      stringThunk.indexOf("try") < stringThunk.indexOf("ObjectDisposedException"),
      "expected the miss throw to sit inside the thunk's try; an exception escaping an " +
          "[UnmanagedCallersOnly] frame is runtime-dependent and never an error channel; got: " +
          stringThunk,
    )
  }

  /** The three ctx OWNERS: per-call frame, subscription, bridge state. */
  @Test
  fun `every ctx owner registers a key and removes it`() {
    val result = run()

    val missing: List<String> = listOf(
      // Per-call (ADR-160): registered in the prelude, removed in the `finally`.
      "IntPtr formatCtx = IntPtr.Zero;",
      "formatCtx = NugetThunks.RegisterCtx(formatNative);",
      "if (formatCtx != IntPtr.Zero) NugetThunks.UnregisterCtx(formatCtx);",
      // Stored callback (ADR-037): removed by the subscription, after the native remove.
      "IntPtr cbKey = NugetThunks.RegisterCtx(nativeCallback);",
      "NugetThunks.UnregisterCtx(cbKey); });",
      // ADR-039 listener bridge: one key per method, all removed together.
      "IntPtr k0 = NugetThunks.RegisterCtx(onMeowCb);",
      // ADR-084 bridge slots, through the shared `Pin`.
      "IntPtr key = NugetThunks.RegisterCtx(value);",
      "NugetThunks.UnregisterCtx(pin);",
    ).filterNot { needle -> result.generatedCSharp.contains(needle) }

    assertTrue(
      missing.isEmpty(),
      "expected every ctx owner to hand out a table key and to remove it on its own release path; " +
          "missing: $missing; got: ${csharpLinesFor(result, "RegisterCtx")}",
    )
  }

  /**
   * The subscribe failure path. The old code freed nothing when `error != IntPtr.Zero`, so the
   * delegate stayed rooted for the life of the process; the key removal there is the same edit and
   * the same bug.
   */
  @Test
  fun `a failed subscribe removes the key it registered`() {
    val result = run()

    assertTrue(
      result.generatedCSharp.contains(
        "if (error != IntPtr.Zero) { NugetThunks.UnregisterCtx(cbKey); " +
            "throw NugetErrorNative.BuildException(error); }"
      ),
      "expected a subscribe that reported an error to drop its own key; got: " +
          "${csharpLinesFor(result, "BuildException(error); }")}",
    )
  }

  /** The Kotlin half still compiles: the ctx slot is still one `IntPtr`, so no ABI moved. */
  @Test
  fun `the generated Kotlin still compiles and the ctx slot is unchanged`() {
    val result = run()

    assertTrue(
      result.compiledClean,
      "expected the callback exports to compile; got: ${result.compileErrors}",
    )
  }

  /**
   * The text of one named thunk. The rendered file is one `[UnmanagedCallersOnly]` block per thunk
   * followed by its pointer getter, so the chunk that declares [thunkName] and stops at the getter
   * is exactly that thunk's own body and cannot pick up a neighbour's miss branch.
   */
  private fun thunkBody(result: Tier1Result, thunkName: String): String {
    val chunk: String? = result.generatedCSharp.split("[UnmanagedCallersOnly")
      .firstOrNull { part -> part.contains("$thunkName(") }
    assertTrue(chunk != null, "expected the fixture to emit $thunkName")
    return chunk!!.substringBefore("Ptr =>")
  }

  private fun occurrences(text: String, needle: String): Int = text.split(needle).size - 1

  private fun csharpLinesFor(result: Tier1Result, needle: String): List<String> =
    result.generatedCSharp.lines().filter { it.contains(needle) }.map(String::trim)
}
