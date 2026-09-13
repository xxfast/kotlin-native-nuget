package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardPlanSkipReason
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #115 / ADR-116, the lambda-parameter row. A method taking a function parameter (ADR-036)
 * and **declared on a sealed arm** binds on that arm, off the arm's own export prefix
 * (`job_running_relabel`), through the same thunk and `GCHandle` protocol `Cat.describeWith` uses.
 * ADR-118 re-keyed the suspend route onto the arms and ADR-124 the Flow route; this is the third
 * row, site for site.
 *
 * The module here declares **no `suspend` and no `Flow` anywhere**, deliberately. `invoke`,
 * `CFunction` and `COpaquePointer` also arrive from the suspend/flow import branch, so a fixture
 * carrying either of those would import them for the wrong reason and let a broken
 * `hasLambdaParamMethods` gate (which walks `classes` only, and sealed classes are not in
 * `classes`, ADR-009) pass unnoticed. [compiledClean] is the assertion that catches it: the
 * generated wrapper calls `fn.invoke(...)` on a reinterpreted `CFunction`, which does not compile
 * without those imports.
 *
 * ADR-116's 2026-09-13 amendment adds the fourth row, cells 4 and 5 here: a stored-callback
 * (ADR-037) and an interface-bridge (ADR-039) `addX`/`removeX` **pair** on an arm binds too, as
 * the `IDisposable AddX(...)` subscription an ordinary class already gets. Each pair gets its
 * **own** module, deliberately: the two Kotlin import gates (`hasStoredCallbackMethods`,
 * `hasInterfaceBridgeMethods`) walk `classes` only and pull the same three cinterop names, so one
 * combined module would let a fixed stored-callback gate mask a still-broken bridge gate and
 * compile for the wrong reason. Both modules are **pair-only**: no `suspend`, no `Flow`, no
 * per-call lambda anywhere, so nothing else can supply those imports.
 *
 * A generic method still has no route on any owner and must keep saying so (cell 3): an exemption
 * written on the reason alone is what ADR-116 was written to end.
 *
 * Oreo runs the hallway and gets relabelled and ticked; Mylo idles, is poked, and is watched.
 */
class Tier1SealedArmLambdaTest {

  private val fixture: String = """
    package tier1.armlambda

    sealed class Job {
      // The Func cell: a (String) -> String parameter with a String outer return, so the UTF8
      // pair crosses on the callback argument and on the outer return at once.
      data class Running(val progress: Int) : Job() {
        fun relabel(transform: (String) -> String): String = transform("running-${'$'}progress")

        // No arm route at all, and none is claimed by this change: still named.
        fun <T> pick(value: T): T = value
      }

      // The Action cell: a (String) -> Unit parameter and a Unit outer return, on a data object
      // arm, so the void branch of the renderer and the object arm's handle receiver ride one
      // member.
      data object Idle : Job() {
        fun pokeWith(action: (String) -> Unit) = action("idle")
      }

      // The control: an arm with no callback member at all keeps generating as it does today.
      data class Done(val code: Int) : Job()
    }

    class JobFactory {
      fun running(progress: Int): Job.Running = Job.Running(progress)
      fun idle(): Job.Idle = Job.Idle
    }
  """.trimIndent()

  private fun run(): Tier1Result = Tier1Harness.run(fixture, fileName = "JobSample.kt")

  /**
   * The Kotlin half. `compiledClean` is load-bearing twice over: the callback wrapper only
   * compiles if the cinterop imports reached a module whose only callback owner is a sealed arm,
   * and the exports only exist if the arm loop runs at all.
   */
  @Test
  fun `an arm's lambda-parameter methods export under the arm's own prefix`() {
    val result = run()

    assertTrue(
      result.compiledClean,
      "expected the arm's callback exports to compile; got: " +
          "${result.compileErrors} ${result.kspErrors}",
    )

    val missing: List<String> = listOf(
      "@CName(\"job_running_relabel\")",
      "@CName(\"job_idle_pokeWith\")",
    ).filterNot(result.generated::contains)

    assertTrue(missing.isEmpty(), "expected the arm-prefixed callback exports; missing: $missing")

    val leaked: List<String> = listOf(
      // The member is declared on one arm; the sealed base carries no callback route and must not
      // be handed one, and the control arm must gain nothing.
      "job_relabel",
      "job_pokeWith",
      "job_done_relabel",
    ).filter { entryPoint -> result.generated.contains("@CName(\"$entryPoint\")") }

    assertTrue(leaked.isEmpty(), "expected no base-prefixed or control-arm export; got: $leaked")
  }

  /**
   * The C# half, asserted **inside the arm's own class body**: a `Func`/`Action` member rendered
   * at namespace scope, or onto the base, compiles and still cannot be called the way the issue
   * asks for.
   */
  @Test
  fun `an arm's lambda-parameter methods render as Func and Action members of the arm`() {
    val result = run()

    val running: String = armBody(result.generatedCSharp, "Running")
    val idle: String = armBody(result.generatedCSharp, "Idle")

    assertTrue(
      running.contains("public string Relabel(Func<string, string> transform)"),
      "expected the Func member on the Running arm; got: ${csharpLinesFor(result, "Relabel")}",
    )
    assertTrue(
      running.contains("EntryPoint = \"job_running_relabel\""),
      "expected the arm-prefixed extern on the Running arm; got: " +
          "${csharpLinesFor(result, "job_running_relabel")}",
    )
    assertTrue(
      idle.contains("public void PokeWith(Action<string> action)"),
      "expected the Action member on the Idle arm; got: ${csharpLinesFor(result, "PokeWith")}",
    )
    assertTrue(
      idle.contains("EntryPoint = \"job_idle_pokeWith\""),
      "expected the arm-prefixed extern on the Idle arm; got: " +
          "${csharpLinesFor(result, "job_idle_pokeWith")}",
    )
  }

  /**
   * The diagnostic pair. A per-call callback member on an arm is routed now and must not be named
   * a drop; a generic one has no arm route and must keep saying so.
   */
  @Test
  fun `a lambda-parameter arm member is no longer named unrouted while a generic one still is`() {
    val unrouted: List<String> = unroutedWarnings(run())

    assertFalse(
      unrouted.any { it.contains("relabel") || it.contains("pokeWith") },
      "expected a lambda-parameter arm member to be routed, not named a drop; got: $unrouted",
    )
    assertTrue(
      unrouted.any { it.contains("pick") },
      "expected a generic arm member to keep its named skip; got: $unrouted",
    )
  }

  /**
   * Cell 4, ADR-116's 2026-09-13 amendment: a stored-callback `add`/`remove` pair (ADR-037) on an
   * arm binds on the arm, off the arm's own export prefix, with parity to `Cat.addMoodListener`.
   *
   * The module is **pair-only** on purpose. `hasStoredCallbackMethods` walks `classes` only and
   * sealed classes are not in `classes` (ADR-009), so a module whose single callback owner is an
   * arm gets no `invoke` / `CFunction` / `COpaquePointer` import unless that gate learns the arm
   * walk too; nothing else here can supply them, and [Tier1Result.compiledClean] is what says so.
   */
  private val storedCallbackPairFixture: String = """
    package tier1.armpair

    sealed class Feed {
      data class Live(val id: String) : Feed() {
        private val tickers: MutableList<(String) -> Unit> = mutableListOf()
        fun addTicker(listener: (String) -> Unit) { tickers += listener }
        fun removeTicker(listener: (String) -> Unit) { tickers -= listener }
        fun tick() { tickers.forEach { it("tick:${'$'}id") } }
      }
    }

    fun anyFeed(): Feed = Feed.Live("oreo")
  """.trimIndent()

  @Test
  fun `a stored-callback pair on an arm binds under the arm's own prefix`() {
    val result: Tier1Result =
      Tier1Harness.run(storedCallbackPairFixture, fileName = "FeedSample.kt")

    assertTrue(
      result.compiledClean,
      "expected a pair-only arm module to compile, which needs the cinterop imports no other " +
          "member here can supply; got: ${result.compileErrors} ${result.kspErrors}",
    )

    val missing: List<String> = listOf(
      "@CName(\"feed_live_addTicker\")",
      "@CName(\"feed_live_removeTicker\")",
    ).filterNot(result.generated::contains)

    assertTrue(missing.isEmpty(), "expected the arm-prefixed pair exports; missing: $missing")

    val live: String = armBody(result.generatedCSharp, "Live")

    assertTrue(
      live.contains("public IDisposable AddTicker(Action<string> listener)"),
      "expected the subscription member on the Live arm; got: " +
          "${csharpLinesFor(result, "AddTicker")}",
    )
    assertTrue(
      live.contains("EntryPoint = \"feed_live_addTicker\"") &&
          live.contains("EntryPoint = \"feed_live_removeTicker\""),
      "expected both arm-prefixed externs on the Live arm; got: " +
          "${csharpLinesFor(result, "feed_live_")}",
    )

    val unrouted: List<String> = unroutedWarnings(result)
    assertFalse(
      unrouted.any { it.contains("addTicker") || it.contains("removeTicker") },
      "expected a routed pair not to be named a drop; got: $unrouted",
    )
  }

  /**
   * Cell 5, the interface-bridge half (ADR-039) on a `data object` arm, in its own module for the
   * reason cell 4 states. The listener is void-only: the bridge route overrides every method as
   * `Unit`, so a non-`Unit` member would break the build for an unrelated reason.
   *
   * The `nameof` assertion is the silent-wrong one. `translateInterfaceBridgeMethod`'s `className`
   * lands in `ObjectDisposedException(nameof(...))`; the *base*'s name compiles there (the arm is
   * nested inside the base) and misnames the owner, so the guard must read the arm's own name.
   */
  private val interfaceBridgePairFixture: String = """
    package tier1.armbridge

    interface FeedWatcher {
      fun onWake(reason: String)
    }

    sealed class Feed {
      data object Idle : Feed() {
        private val watchers: MutableList<FeedWatcher> = mutableListOf()
        fun addWatcher(w: FeedWatcher) { watchers += w }
        fun removeWatcher(w: FeedWatcher) { watchers -= w }
        fun wake(reason: String) { watchers.forEach { it.onWake(reason) } }
      }
    }

    fun anyFeed(): Feed = Feed.Idle
  """.trimIndent()

  @Test
  fun `an interface-bridge pair on an object arm binds and guards under the arm's own name`() {
    val result: Tier1Result =
      Tier1Harness.run(interfaceBridgePairFixture, fileName = "FeedSample.kt")

    assertTrue(
      result.compiledClean,
      "expected a bridge-pair-only arm module to compile, which needs its own import gate walk; " +
          "got: ${result.compileErrors} ${result.kspErrors}",
    )

    val missing: List<String> = listOf(
      "@CName(\"feed_idle_addWatcher\")",
      "@CName(\"feed_idle_removeWatcher\")",
    ).filterNot(result.generated::contains)

    assertTrue(missing.isEmpty(), "expected the arm-prefixed bridge exports; missing: $missing")

    val idle: String = armBody(result.generatedCSharp, "Idle")

    assertTrue(
      idle.contains("public IDisposable AddWatcher(IFeedWatcher listener)"),
      "expected the bridge subscription member on the Idle arm; got: " +
          "${csharpLinesFor(result, "AddWatcher")}",
    )

    val guard: String = subscribeBody(idle, "public IDisposable AddWatcher(")
    assertTrue(
      guard.contains("nameof(Idle)"),
      "expected the disposed guard to name the arm that owns the member; got: $guard",
    )
    assertFalse(
      guard.contains("nameof(Feed)"),
      "expected the disposed guard not to name the sealed base, which compiles and misnames the " +
          "owner; got: $guard",
    )

    val unrouted: List<String> = unroutedWarnings(result)
    assertFalse(
      unrouted.any { it.contains("addWatcher") || it.contains("removeWatcher") },
      "expected a routed bridge pair not to be named a drop; got: $unrouted",
    )
  }

  private fun unroutedWarnings(result: Tier1Result): List<String> = result.kspWarnings
    .filter { warning ->
      warning.contains(ForwardPlanSkipReason.SEALED_SUBCLASS_UNROUTED.name) ||
          warning.contains("sealed subclass")
    }

  /** The generated C# between one arm's class header and the next arm's, base members excluded. */
  private fun armBody(csharp: String, arm: String): String {
    val header: String = "public sealed class $arm"
    val start: Int = csharp.indexOf(header)
    check(start >= 0) { "no `$header` in the generated C#" }
    val next: Int = csharp.indexOf("public sealed class ", start + header.length)
    return if (next < 0) csharp.substring(start) else csharp.substring(start, next)
  }

  /**
   * One rendered member's body, from its signature to the next `public ` line. The arm's other
   * routes print `nameof(...)` guards of their own, so the owner-name assertion has to read the
   * subscribe member alone rather than the whole arm body.
   */
  private fun subscribeBody(armBody: String, signature: String): String {
    val start: Int = armBody.indexOf(signature)
    check(start >= 0) { "no `$signature` in the arm body" }
    val next: Int = armBody.indexOf("public ", start + signature.length)
    return if (next < 0) armBody.substring(start) else armBody.substring(start, next)
  }

  private fun csharpLinesFor(result: Tier1Result, needle: String): List<String> =
    result.generatedCSharp.lines().filter { it.contains(needle) }
}
