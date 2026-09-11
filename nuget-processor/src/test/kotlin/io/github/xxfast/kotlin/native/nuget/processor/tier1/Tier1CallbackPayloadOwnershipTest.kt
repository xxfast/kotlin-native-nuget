package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ADR-036 amendment (2026-09-11) · **who frees a handle-passed callback payload**.
 *
 * The per-call lambda-parameter route minted a `StableRef` for every non-by-value argument, handed
 * the handle to the C# thunk, and released it again the moment the thunk returned. The C# side
 * frees the same handle on both payload kinds it supports, so every crossing freed once too often
 * and `LeakTests`' tracked count fell *below* baseline (`-1` per call, Rows 8g/8h/8i):
 *
 *  - a **`String`** payload is read by `NugetMarshal.FromHandle<string>`, whose `string` branch
 *    calls `Native_dispose(handle)` right after `Marshal.PtrToStringUTF8`, so the handle is
 *    already gone when Kotlin's release runs,
 *  - an **exported object** payload falls through to `NugetMarshal.Materialize<T>`, which hands
 *    the raw handle to the wrapper's `new T(handle)` constructor. The wrapper owns it from then
 *    on and frees it in `Dispose()`, which is what ADR-036's documented `using var t = toy;` in a
 *    callback body does. Kotlin's release freed the handle out from under a live wrapper.
 *
 * The rule this cell pins: **on a handle-passed payload, the C# side owns the free.** Kotlin
 * retains for the duration of the crossing and never releases. A by-value primitive is unchanged
 * because it never had a handle (see [Tier1PrimitiveLambdaParameterTest]), and the callback's
 * *return* box stays Kotlin's, since nothing on the C# side ever frees it.
 *
 * The residual is named in the ADR: a generated wrapper has a `Dispose()` and no finalizer, so a
 * consumer who never disposes an object payload leaks that one handle. Leaking beats freeing
 * twice, which is a use-after-free.
 *
 * Oreo's toys are the object payload. Mylo's name is the `String`.
 */
class Tier1CallbackPayloadOwnershipTest {

  private val source: String = """
    package tier1.owners

    class Toy(val name: String)

    class Cat(val name: String) {
      private val toys: List<Toy> = listOf(Toy("Mouse"), Toy("Ball"))
      private val watchers: MutableList<(String) -> Unit> = mutableListOf()

      fun describeWith(format: (String) -> String): String = format(name)
      fun forEachToy(action: (Toy) -> Unit) = toys.forEach(action)
      fun onTick(listener: (Int) -> Unit) = listener(1)

      fun addWatcher(watcher: (String) -> Unit) { watchers.add(watcher) }
      fun removeWatcher(watcher: (String) -> Unit) { watchers.remove(watcher) }
    }

    interface CatEventListener {
      fun onMeow(message: String)
    }

    class CatEventSource(val name: String) {
      private val listeners: MutableList<CatEventListener> = mutableListOf()
      fun addListener(listener: CatEventListener) { listeners.add(listener) }
      fun removeListener(listener: CatEventListener) { listeners.remove(listener) }
      fun trigger() = listeners.forEach { it.onMeow("${'$'}name says meow!") }
    }
  """.trimIndent()

  private fun run(): Tier1Result = Tier1Harness.run(source)

  /** The whole file still compiles: dropping a release must not orphan a binding. */
  @Test
  fun `the generated Kotlin exports compile`() {
    val result = run()

    assertTrue(
      result.compiledClean,
      "expected the callback-payload exports to compile; got: ${result.compileErrors}",
    )
  }

  /** The defect's own line: no handle-passed payload is released after the invoke. */
  @Test
  fun `no handle-passed payload is released after the invoke`() {
    val result = run()

    val offending: List<String> = result.generated.lines()
      .map { it.trim() }
      .filter { it.startsWith("NugetHandles.release(arg") }
    assertTrue(
      offending.isEmpty(),
      "the C# side frees a handle-passed callback payload (FromHandle<string> disposes; " +
          "Materialize hands the handle to the wrapper), so Kotlin must not release it too; " +
          "got: $offending",
    )
  }

  /** The retain stays on both handle kinds: the C# thunk needs a handle to read at all. */
  @Test
  fun `both handle-passed payloads are still retained for the crossing`() {
    val result = run()

    assertTrue(
      result.generated.contains("val arg0Ref = NugetHandles.retain(it0 as Any)"),
      "the String payload still crosses as a retained handle; got: ${payloadLines(result)}",
    )
    assertTrue(
      result.generated.contains("val arg0Ref = NugetHandles.retain(it0)"),
      "the object payload still crosses as a retained handle; got: ${payloadLines(result)}",
    )
  }

  /**
   * The other half of the crossing is unchanged. Nothing on the C# side frees the box a callback
   * *returns* (`WrapString` hands back a fresh handle the caller reads), so that release stays.
   */
  @Test
  fun `the callback result box is still released by Kotlin`() {
    val result = run()

    assertTrue(
      result.generated.contains("NugetHandles.release(resultRef)"),
      "the callback's return box has no C# owner; Kotlin still frees it",
    )
  }

  /** Control: the by-value primitive shape is untouched by an ownership rule about handles. */
  @Test
  fun `the by-value primitive payload is unchanged`() {
    val result = run()

    assertTrue(
      result.generated.contains("CFunction<(Int, COpaquePointer) -> Unit>"),
      "control: an Int payload still crosses by value; got: ${payloadLines(result)}",
    )
    assertTrue(
      result.generated.contains("listenerFn.invoke(it0, listenerUserData)"),
      "control: a by-value payload is handed over verbatim; got: ${payloadLines(result)}",
    )
  }

  /**
   * The stored-callback (ADR-037) and interface-bridge routes carried the same defect with an
   * extra turn of the screw: their C# thunk spells an explicit `NugetMarshal.Dispose(argPtr)`
   * right after `FromHandle`, which already disposed a `string`. Three frees of one handle,
   * measured at -2 live handles per `onMeow` crossing before this fix (retain +1, three
   * releases). Exactly one owner now: `FromHandle`.
   */
  @Test
  fun `the stored and interface routes do not dispose the payload a second time`() {
    val result = run()

    val offending: List<String> = result.generatedCSharp.lines()
      .filter { it.contains("NugetMarshal.Dispose(arg") }
      .map { it.trim() }
    assertTrue(
      offending.isEmpty(),
      "`FromHandle` is the owner of a handle-passed callback argument on every route; " +
          "got: $offending",
    )
    // Not a vacuous pass: both routes have to be in the file for the assertion above to mean
    // anything, so the fixture's `addWatcher`/`removeWatcher` pair and its `CatEventListener`
    // bridge are named here.
    listOf(
      "string arg0 = NugetMarshal.FromHandle<string>(arg0Ptr); listener(arg0);",
      "string arg0 = NugetMarshal.FromHandle<string>(arg0Ptr); listener.OnMeow(arg0);",
    ).forEach { body ->
      assertTrue(
        result.generatedCSharp.contains(body),
        "control: the stored and interface routes must both be generated here; expected `$body`",
      )
    }
  }

  /** Control: the C# side still reads both payloads, i.e. ownership moved rather than vanished. */
  @Test
  fun `the C sharp thunks still unmarshal both payloads`() {
    val result = run()

    listOf("string arg0 = NugetMarshal.FromHandle<string>(arg0Ptr);", "FromHandle<Toy>(arg0Ptr);")
      .forEach { line ->
        assertTrue(
          result.generatedCSharp.contains(line),
          "control: the C# thunk is the owner now, so it must still read the handle; " +
              "expected `$line`",
        )
      }
  }

  private fun payloadLines(result: Tier1Result): List<String> = result.generated.lines()
    .filter { it.contains("NugetHandles.") || it.contains("reinterpret<CFunction<") }
    .map { it.trim() }
}
