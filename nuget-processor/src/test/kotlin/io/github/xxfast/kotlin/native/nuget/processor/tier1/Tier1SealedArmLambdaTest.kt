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
 * Three kinds have no arm route and must keep saying so, which is why cells 3 and 4 exist: a
 * generic method (no route on any owner), and a stored-callback / interface-bridge **pair**, whose
 * `CALLBACK_PROTOCOL` skip reason is the same constant the per-call route's skip carries. An
 * exemption written on the reason alone would drop a pair in silence, which is exactly the bug
 * ADR-116 was written to end.
 *
 * Oreo runs the hallway and gets relabelled; Mylo idles and is poked.
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
   * The exemption's origin split. A stored-callback `add`/`remove` pair (ADR-037) takes the
   * **same** `CALLBACK_PROTOCOL` reason the per-call route's skip takes, but no arm route emits
   * it, so it stays named. Exempting the reason wholesale would delete this diagnostic and put the
   * pair back into the silent absence ADR-116 fixed.
   */
  @Test
  fun `a stored-callback pair on an arm keeps its named skip`() {
    val result = Tier1Harness.run(
      """
      package tier1.armpair

      sealed class Feed {
        data class Live(val id: String) : Feed() {
          private val listeners: MutableList<(String) -> Unit> = mutableListOf()
          fun addTicker(listener: (String) -> Unit) { listeners += listener }
          fun removeTicker(listener: (String) -> Unit) { listeners -= listener }
        }
      }

      fun anyFeed(): Feed = Feed.Live("oreo")
      """.trimIndent(),
    )

    val unrouted: List<String> = unroutedWarnings(result)

    assertTrue(
      unrouted.any { it.contains("addTicker") } && unrouted.any { it.contains("removeTicker") },
      "expected both halves of a stored-callback pair on an arm to stay named; got: " +
          "${result.kspWarnings}",
    )
    assertTrue(
      unrouted.any { it.contains(ForwardPlanSkipReason.CALLBACK_PROTOCOL.name) },
      "expected the pair's skip to carry its own reason as the detail; got: $unrouted",
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

  private fun csharpLinesFor(result: Tier1Result, needle: String): List<String> =
    result.generatedCSharp.lines().filter { it.contains(needle) }
}
