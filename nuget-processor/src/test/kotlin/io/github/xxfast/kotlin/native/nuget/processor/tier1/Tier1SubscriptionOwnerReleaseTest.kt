package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ROADMAP: a discarded `AddXxx` subscription's token `StableRef` was never freed, even after its
 * owner was disposed. The token is the Kotlin unregister closure, which captures the owner, so the
 * leak was the whole owner graph. The owner's `NugetKotlinHandle` now keeps every subscription
 * still attached to it and unregisters them, through the same remove export `Dispose()` calls,
 * before it releases itself, on the explicit and the finalizer path alike. A live owner keeps
 * delivering to a discarded subscription exactly as before (ADR-187's 2026-10-02 gate).
 *
 * Text cells (ADR-060 tier 1); `LeakTests` rows 16i and 16m measure the counts.
 *
 * Oreo leaves the room; nobody keeps listening for her meows.
 */
class Tier1SubscriptionOwnerReleaseTest {

  private val source: String = """
    package tier1.subscriptions

    interface CatEventListener {
      fun onMeow(message: String)
      fun onPurr()
    }

    class Cat(val name: String) {
      private val watchers: MutableList<(String) -> Unit> = mutableListOf()
      private val listeners: MutableList<CatEventListener> = mutableListOf()

      // Stored callback, ADR-037.
      fun addWatcher(watcher: (String) -> Unit) { watchers.add(watcher) }
      fun removeWatcher(watcher: (String) -> Unit) { watchers.remove(watcher) }

      // ADR-039 listener bridge.
      fun addListener(listener: CatEventListener) { listeners.add(listener) }
      fun removeListener(listener: CatEventListener) { listeners.remove(listener) }
    }
  """.trimIndent()

  private fun run(): Tier1Result = Tier1Harness.run(source)

  /** The owner side: attach, detach, and a release that drains before it frees the handle. */
  @Test
  fun `the owner handle drains its attached subscriptions before it releases itself`() {
    val csharp: String = run().generatedCSharp

    val missing: List<String> = listOf(
      "internal void Attach(IDisposable subscription)",
      "internal void Detach(IDisposable subscription)",
    ).filterNot(csharp::contains)
    assertTrue(missing.isEmpty(), "expected the attach/detach pair; missing: $missing")

    val release: String = csharp.substringAfter("protected override bool ReleaseHandle()")
      .substringBefore("return true;")
    assertTrue(
      release.contains("_released = true;") &&
          release.contains(".Dispose();") &&
          release.indexOf(".Dispose();") < release.indexOf("NugetMarshal.Dispose(handle);"),
      "expected ReleaseHandle to mark itself released and unregister every attached subscription " +
          "BEFORE nuget_dispose frees the owner; got: $release",
    )
    // The drain snapshots under the lock and disposes outside it: the unregister calls into Kotlin.
    assertTrue(
      release.indexOf("lock (") < release.indexOf("_released = true;") &&
          release.indexOf("_attached = null;") < release.indexOf("foreach"),
      "expected a snapshot taken under the lock and iterated outside it; got: $release",
    )
  }

  /** Every subscription site attaches what it returns, to the owner it subscribed on. */
  @Test
  fun `every AddX attaches its subscription to the owner handle`() {
    val csharp: String = run().generatedCSharp

    val created: Int = occurrences(csharp, "new NugetSubscription(")
    val attached: Int = occurrences(csharp, "owner.Attach(subscription);")
    assertEquals(2, created, "expected the fixture's two subscription sites")
    assertEquals(
      created,
      attached,
      "expected every NugetSubscription to be attached to its owner; got: " +
          csharp.lines().filter { it.contains("NugetSubscription(") || it.contains("Attach(") },
    )
    assertTrue(
      csharp.contains("NugetKotlinHandle owner = _handle;") && csharp.contains("}, owner);"),
      "expected the subscription to carry the owner it detaches from",
    )
    // The unregister must capture nothing of the owner: it shares its closure object with the
    // listener delegate the thunk key table holds, so a `_handle` read there roots the wrapper and
    // its finalizer, and with it this whole release, never runs.
    val unregisters: List<String> = csharp.lines().filter { it.contains("new NugetSubscription(") }
    assertTrue(
      unregisters.all { it.contains("(IntPtr.Zero, sub);") && !it.contains("_handle") },
      "expected every unregister to pass a zero receiver and read no owner state; got: " +
          unregisters.map(String::trim),
    )
  }

  /**
   * The subscription side: the `Interlocked.Exchange` stays the single gate, so a consumer
   * `Dispose()` after the owner's drain is a silent no-op, and a disposal detaches itself so a
   * long-lived owner does not accumulate disposed subscriptions.
   */
  @Test
  fun `a subscription disposes once and detaches itself`() {
    val csharp: String = run().generatedCSharp
    val dispose: String = csharp.substringAfter("internal sealed class NugetSubscription")
      .substringAfter("public void Dispose()").substringBefore("\n    }\n")

    assertTrue(
      dispose.contains("Interlocked.Exchange(ref _disposeAction, null);") &&
          dispose.contains("if (action is null) return;") &&
          dispose.contains("_owner?.Detach(this);"),
      "expected a single-shot dispose that detaches from its owner; got: $dispose",
    )
  }

  private fun occurrences(text: String, needle: String): Int = text.split(needle).size - 1
}
