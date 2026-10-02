package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Stored-callback pair null guard (ADR-037 / ADR-039 amendments, 2026-09-28). Every generated C#
 * entry point that takes a delegate or a listener rejects `null` with
 * `ArgumentNullException.ThrowIfNull(<param>)` as its first statement, whatever the Kotlin
 * nullability and whatever the route, so a rejected call mints no ctx key and no Kotlin handle
 * (which is why no LeakTests row accompanies it).
 */
class Tier1CallbackNullGuardTest {

  private val source: String =
    """
    package tier1.callbacknullguard

    enum class Mood { HAPPY, GRUMPY }

    interface CatListener {
      fun onMeow(message: String)
    }

    class Cat(val name: String) {
      private val moods = mutableListOf<(Mood) -> Unit>()
      private val pounces = mutableListOf<(Int) -> Unit>()
      private val listeners = mutableListOf<CatListener>()
      private val maybes = mutableListOf<CatListener>()

      fun addMoodListener(listener: (Mood) -> Unit) { moods.add(listener) }
      fun removeMoodListener(listener: (Mood) -> Unit) { moods.remove(listener) }

      fun addPounceListener(listener: ((Int) -> Unit)?) { if (listener != null) pounces.add(listener) }
      fun removePounceListener(listener: ((Int) -> Unit)?) { if (listener != null) pounces.remove(listener) }

      fun addListener(listener: CatListener) { listeners.add(listener) }
      fun removeListener(listener: CatListener) { listeners.remove(listener) }

      fun addMaybeListener(listener: CatListener?) { if (listener != null) maybes.add(listener) }
      fun removeMaybeListener(listener: CatListener?) { if (listener != null) maybes.remove(listener) }

      // ADR-062 plan route, with a collection parameter (a prelude handle mint) ahead of the lambda.
      fun countPounces(labels: List<String>, pouncer: (Int) -> Unit): Int { pouncer(labels.size); return 1 }

      // Legacy per-call route, nullable Kotlin spelling.
      fun onMaybePounce(pouncer: ((Int) -> Unit)?) { pouncer?.invoke(1) }

      // Legacy per-call route, NON-null Kotlin spelling: a payload the plan's callback lowering
      // declines (a `Char` payload), so the member stays on the hand-written route.
      fun eachLetter(chooser: (Char) -> Unit) { chooser('c') }
    }

    sealed class Job {
      data class Running(val percent: Int) : Job() {
        private val tickers = mutableListOf<(String) -> Unit>()
        private val watchers = mutableListOf<CatListener>()

        fun addTicker(listener: (String) -> Unit) { tickers.add(listener) }
        fun removeTicker(listener: (String) -> Unit) { tickers.remove(listener) }

        fun addMaybeTicker(listener: ((String) -> Unit)?) { if (listener != null) tickers.add(listener) }
        fun removeMaybeTicker(listener: ((String) -> Unit)?) { if (listener != null) tickers.remove(listener) }

        fun addWatcher(listener: CatListener) { watchers.add(listener) }
        fun removeWatcher(listener: CatListener) { watchers.remove(listener) }

        fun addMaybeWatcher(listener: CatListener?) { if (listener != null) watchers.add(listener) }
        fun removeMaybeWatcher(listener: CatListener?) { if (listener != null) watchers.remove(listener) }

        fun countTicks(listener: (Int) -> Unit): Int { listener(1); return 1 }
      }

      data object Idle : Job()
    }

    fun tallyTicks(listener: (Int) -> Unit): Int { listener(1); return 1 }

    fun Cat.nudge(pouncer: (Int) -> Unit): Int { pouncer(1); return 1 }
    """.trimIndent()

  /** The body of the public C# member named [member], up to its closing brace at member indent. */
  private fun String.body(member: String): String {
    val match: MatchResult? = Regex("""public [^\n(=]*\b$member\(""").find(this)
    assertTrue(match != null, "expected a public `$member` in the generated C#; cs=$this")
    val start: Int = match.range.first
    val end: Int = indexOf("\n        }", start)
    return substring(start, end)
  }

  private fun assertGuardFirst(
    cs: String, member: String, param: String, owner: String?, registers: String = "nativeCallback",
  ) {
    val body: String = cs.body(member)
    val guard = "ArgumentNullException.ThrowIfNull($param);"
    val guardAt: Int = body.indexOf(guard)
    assertTrue(guardAt >= 0, "expected `$guard` in `$member`; body=$body")
    val firstStatement: String = body.substringAfter("{").trimStart().lineSequence().first()
    assertTrue(firstStatement == guard, "the guard must be the first statement of `$member`; body=$body")
    val registerAt: Int = body.indexOf("NugetThunks.RegisterCtx(")
    if (owner == null) {
      // Pins the route: the legacy wrapper registers `nativeCallback`, the plan `<local>Native`.
      assertTrue("NugetThunks.RegisterCtx($registers)" in body, "expected `$member` on the route registering `$registers`; body=$body")
    }
    assertTrue(registerAt < 0 || guardAt < registerAt, "guard must precede RegisterCtx in `$member`; body=$body")
    val nativeAt: Int = body.indexOf("Native_")
    assertTrue(nativeAt < 0 || guardAt < nativeAt, "guard must precede the native call in `$member`; body=$body")
    if (owner != null) {
      val disposed = "if (_handle.IsInvalid) throw new ObjectDisposedException(nameof($owner));"
      val disposedAt: Int = body.indexOf(disposed)
      assertTrue(disposedAt > guardAt, "expected `$disposed` right after the guard in `$member`; body=$body")
      assertTrue(registerAt < 0 || disposedAt < registerAt, "disposed check must precede RegisterCtx; body=$body")
    }
  }

  @Test
  fun `every callback entry point rejects null first, on every route and owner`() {
    val result = Tier1Harness.run(source)
    assertTrue(result.compiledClean, "expected clean Kotlin; got: ${result.compileErrors}")
    val cs: String = result.generatedCSharp

    // ADR-037 lambda pair and ADR-039 interface pair, both nullabilities, on a class.
    assertGuardFirst(cs, "AddMoodListener", "listener", owner = "Cat")
    assertGuardFirst(cs, "AddPounceListener", "listener", owner = "Cat")
    assertGuardFirst(cs, "AddListener", "listener", owner = "Cat")
    assertGuardFirst(cs, "AddMaybeListener", "listener", owner = "Cat")

    // The same four on a sealed arm; the disposed check names the arm, not the base.
    assertGuardFirst(cs, "AddTicker", "listener", owner = "Running")
    assertGuardFirst(cs, "AddMaybeTicker", "listener", owner = "Running")
    assertGuardFirst(cs, "AddWatcher", "listener", owner = "Running")
    assertGuardFirst(cs, "AddMaybeWatcher", "listener", owner = "Running")

    // Legacy per-call route: nullable spelling, and the non-null spelling test-library no longer
    // reaches (its every non-null per-call lambda plans).
    assertGuardFirst(cs, "OnMaybePounce", "pouncer", owner = null)
    assertGuardFirst(cs, "EachLetter", "chooser", owner = null)

    // ADR-062 plan route: class member (the guard also precedes the list handle mint),
    // sealed arm, top-level, extension.
    assertGuardFirst(cs, "CountPounces", "pouncer", owner = null, registers = "pouncerNative")
    assertGuardFirst(cs, "CountTicks", "listener", owner = null, registers = "listenerNative")
    assertGuardFirst(cs, "TallyTicks", "listener", owner = null, registers = "listenerNative")
    assertGuardFirst(cs, "Nudge", "pouncer", owner = null, registers = "pouncerNative")
  }
}
