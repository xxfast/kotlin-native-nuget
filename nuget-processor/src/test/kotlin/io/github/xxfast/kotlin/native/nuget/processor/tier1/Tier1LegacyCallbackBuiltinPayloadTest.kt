package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A Kotlin builtin that is not a scalar (`List`, `Set`, `Map`, `Duration`, ...) once reached the
 * hand-written per-call (ADR-036/102) and stored (ADR-037) callback routes as a payload, and either
 * aborted the KSP round inside the user-type C# speller (stored) or rendered `Action<List>`, which
 * no C# `using` resolves (per-call). Each was then a named skip on both halves.
 *
 * ADR-239 binds every erased type at both ends of a callback: a payload Kotlin boxes and C# reads
 * with `NugetMarshal.FromHandle<T>`, a result C# boxes and Kotlin reads back, and a stored pair
 * over a Kotlin lambda minted from the C# delegate. These cells pin that binding on a class, a
 * sealed arm and a generic owner, and keep the shapes no erased position carries (`Any`, `Pair`,
 * `IntArray`) as named skips on both halves.
 */
class Tier1LegacyCallbackBuiltinPayloadTest {

  @Test
  fun `a builtin payload or result binds on the per-call and stored routes`() {
    val result = Tier1Harness.run(
      """
      package tier1.legacybuiltinpayload

      class Cat(val name: String)

      class Walker {
        fun onBatch(cb: (List<Int>) -> Unit) = cb(listOf(1))
        fun onAny(cb: (Any) -> Unit) = cb(1)
        fun makeCat(cb: () -> Cat) { cb() }
        fun onInitial(cb: (Char) -> Unit) = cb('a')
        fun onCount(cb: (Int) -> Unit) = cb(1)
        fun addBatch(listener: (List<Int>) -> Unit) {}
        fun removeBatch(listener: (List<Int>) -> Unit) {}
        fun addCounter(listener: () -> Int) {}
        fun removeCounter(listener: () -> Int) {}
        fun addTick(listener: (Int) -> Unit) {}
        fun removeTick(listener: (Int) -> Unit) {}
      }
      """.trimIndent(),
    )

    assertEquals("OK", result.kspExitCode, "no generator abort expected: ${result.kspErrors}")
    assertTrue(result.compiledClean, "expected compilable Kotlin; got: ${result.compileErrors}")

    val cs: String = result.generatedCSharp
    listOf(
      "OnBatch(Action<IReadOnlyList<int>>", "MakeCat(", "OnInitial(Action<char>", "OnCount(",
      "AddBatch(Action<IReadOnlyList<int>>", "AddCounter(Func<int>", "AddTick(Action<int>",
    ).forEach { member -> assertTrue(member in cs, "expected `$member` to bind") }
    assertFalse("OnAny(" in cs, "OnAny must not bind")
    assertFalse("Action<Any>" in cs, "no unresolvable Action<Any>")
    assertFalse("export_walker_onAny" in result.generated, "the Kotlin half of onAny is gone too")
    assertNamed(result, ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT, "onAny", "Any")
    listOf(
      "onBatch", "makeCat", "onInitial", "onCount", "addBatch", "removeBatch", "addCounter",
      "removeCounter", "addTick", "removeTick",
    ).forEach { member ->
      assertFalse(
        result.kspWarnings.any { warning -> "Walker.$member" in warning },
        "a member that binds must not be reported; got: ${result.kspWarnings}",
      )
    }
  }

  /** The rest of the builtin family, one payload per member, plus a stored mixed payload. */
  @Test
  fun `the builtin family binds where an erased position carries it`() {
    val result = Tier1Harness.run(
      """
      package tier1.legacybuiltinfamily

      import kotlin.time.Duration

      class Walker {
        fun onSet(cb: (Set<String>) -> Unit) = cb(emptySet())
        fun onMap(cb: (Map<String, Int>) -> Unit) = cb(emptyMap())
        fun onPair(cb: (Pair<Int, Int>) -> Unit) = cb(1 to 2)
        fun onArray(cb: (IntArray) -> Unit) = cb(IntArray(0))
        fun onElapsed(cb: (Duration) -> Unit) = cb(Duration.ZERO)
        fun makeList(cb: () -> List<Int>) { cb() }
        fun makeInitial(cb: () -> Char) { cb() }
        fun addMixed(listener: (Int, List<Int>) -> Unit) {}
        fun removeMixed(listener: (Int, List<Int>) -> Unit) {}
        fun addWindow(listener: (Duration) -> Unit) {}
        fun removeWindow(listener: (Duration) -> Unit) {}
      }
      """.trimIndent(),
    )

    assertEquals("OK", result.kspExitCode, "no generator abort expected: ${result.kspErrors}")
    assertTrue(result.compiledClean, "expected compilable Kotlin; got: ${result.compileErrors}")

    val cs: String = result.generatedCSharp
    listOf(
      "OnSet(", "OnMap(", "OnElapsed(", "MakeList(", "MakeInitial(", "AddMixed(", "AddWindow(",
    ).forEach { member -> assertTrue(member in cs, "expected `$member` to bind") }
    listOf("OnPair(", "OnArray(").forEach { member ->
      assertFalse(member in cs, "$member must not bind")
    }
    listOf("onPair", "onArray").forEach { member ->
      assertNamed(result, ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT, member, null)
    }
  }

  /** The sealed-ARM twin: the arm's per-call and stored routes read `isArmCallbackRoutable`. */
  @Test
  fun `a builtin payload or result binds on a sealed arm too`() {
    val result = Tier1Harness.run(
      """
      package tier1.legacybuiltinarm

      sealed class Job {
        data class Running(val progress: Int) : Job() {
          fun onBatch(cb: (List<Int>) -> Unit) = cb(listOf(progress))
          fun onCount(cb: (Int) -> Unit) = cb(progress)
          fun addBatch(listener: (List<Int>) -> Unit) {}
          fun removeBatch(listener: (List<Int>) -> Unit) {}
          fun addCounter(listener: () -> Int) {}
          fun removeCounter(listener: () -> Int) {}
          fun addTick(listener: (Int) -> Unit) {}
          fun removeTick(listener: (Int) -> Unit) {}
        }

        data class Done(val code: Int) : Job()
      }

      class JobFactory {
        fun running(progress: Int): Job.Running = Job.Running(progress)
      }
      """.trimIndent(),
      fileName = "ArmJobSample.kt",
    )

    assertEquals("OK", result.kspExitCode, "no generator abort expected: ${result.kspErrors}")
    assertTrue(result.compiledClean, "expected compilable Kotlin; got: ${result.compileErrors}")

    val cs: String = result.generatedCSharp
    listOf("OnBatch(", "OnCount(", "AddBatch(", "AddCounter(Func<int>", "AddTick(Action<int>")
      .forEach { member -> assertTrue(member in cs, "expected `$member` to bind on the arm") }
    assertTrue(
      result.kspWarnings.none { warning -> "Job.Running." in warning },
      "a member that binds must not be reported; got: ${result.kspWarnings}",
    )
  }

  /**
   * A stored `(Boolean) -> Unit` declared its `CFunction` slot as a handle but passed a `Byte`, so
   * the generated Kotlin did not compile. It now rides the handle wire the C# half already reads
   * (`FromHandle<bool>`).
   */
  @Test
  fun `a stored Boolean listener compiles and passes a handle`() {
    val result = Tier1Harness.run(
      """
      package tier1.storedboolean

      class Switch {
        private var listeners: List<(Boolean) -> Unit> = emptyList()
        fun addToggle(listener: (Boolean) -> Unit) { listeners = listeners + listener }
        fun removeToggle(listener: (Boolean) -> Unit) { listeners = listeners - listener }
        fun flip(on: Boolean) = listeners.forEach { it(on) }
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected compilable Kotlin; got: ${result.compileErrors}")
    val cs: String = result.generatedCSharp
    assertTrue("AddToggle(Action<bool>" in cs, "expected the stored Boolean listener bound; cs=$cs")
    assertTrue("NugetMarshal.FromHandle<bool>(arg0Ptr)" in cs, "the C# half reads a handle")
    assertFalse(
      result.kspWarnings.any { warning -> "Toggle" in warning },
      "a binding pair must not be reported; got: ${result.kspWarnings}",
    )
  }

  /**
   * ADR-239: a GENERIC owner's stored pairs bind over the erased receiver on the managed-listener
   * route, which never spells the owner's `T`; its per-call members are on the plan.
   */
  @Test
  fun `stored pairs and per-call callbacks bind on a generic owner`() {
    val result = Tier1Harness.run(
      """
      package tier1.legacybuiltingeneric

      class Cat(val name: String)

      class Crate<T>(val item: T) {
        fun addCounter(listener: () -> Int) {}
        fun removeCounter(listener: () -> Int) {}
        fun addItem(listener: (T) -> Unit) {}
        fun removeItem(listener: (T) -> Unit) {}
        fun onBatch(cb: (List<Int>) -> Unit) = cb(listOf(1))
        fun makeCat(cb: () -> Cat) { cb() }
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected compilable Kotlin; got: ${result.compileErrors}")
    val cs: String = result.generatedCSharp
    listOf("AddCounter(Func<int>", "AddItem(Action<T>", "OnBatch(", "MakeCat(").forEach { member ->
      assertTrue(member in cs, "expected `$member` to bind on Crate<T>")
    }
    assertTrue("nameof(Crate<T>)" in cs, "the disposed-receiver check names the generic owner")
    assertTrue(
      result.kspWarnings.none { warning -> "Crate." in warning },
      "a member that binds must not be reported; got: ${result.kspWarnings}",
    )
  }

  private fun assertNamed(
    result: Tier1Result,
    kind: ForwardDiagnosticKind,
    member: String,
    mentions: String?,
  ) {
    assertTrue(
      result.kspWarnings.any { warning ->
        "[nuget:${kind.name}]" in warning && ".$member" in warning &&
            (mentions == null || mentions in warning)
      },
      "expected a ${kind.name} naming `$member`; got: ${result.kspWarnings}",
    )
  }
}
