package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The hand-written per-call (ADR-036/102) and stored (ADR-037) callback routes carry what ADR-160's
 * plan declines. Neither had an admission test of its own, so a Kotlin builtin that is not a scalar
 * (`List`, `Set`, `Map`, `Any`, `Pair`, an array, `Duration`) reached them as a payload and either
 * aborted the KSP round inside the user-type C# speller (stored) or rendered `Action<List>`, which
 * no C# `using` resolves (per-call). A lambda RESULT neither route can marshal failed the generated
 * Kotlin compile. Each is now a named skip on both halves, class and sealed arm alike.
 *
 * The controls are the point of the denylist: `Char` stays on the per-call route (the payload it
 * exists for), a primitive payload keeps its plan or stored binding, and a stored
 * `(Boolean) -> Unit` now compiles instead of passing a `Byte` where the C# half reads a handle.
 */
class Tier1LegacyCallbackBuiltinPayloadTest {

  @Test
  fun `a builtin payload or result is a named skip on the per-call and stored routes`() {
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
    listOf("OnBatch(", "OnAny(", "MakeCat(", "AddBatch(", "AddCounter(").forEach { member ->
      assertFalse(member in cs, "$member must not bind")
    }
    assertFalse("Action<List>" in cs, "no unresolvable Action<List>")
    assertFalse("Action<Any>" in cs, "no unresolvable Action<Any>")
    listOf("OnInitial(Action<char>", "OnCount(", "AddTick(Action<int>").forEach { control ->
      assertTrue(control in cs, "expected the control `$control` to keep binding")
    }

    val kotlin: String = result.generated
    listOf("onBatch", "onAny", "makeCat", "addBatch", "removeBatch", "addCounter", "removeCounter")
      .forEach { member ->
        assertFalse(
          "export_walker_$member" in kotlin,
          "expected `$member` absent from the Kotlin half too, so the two halves agree",
        )
      }
    assertNamed(result, ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT, "onBatch", "List")
    assertNamed(result, ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT, "onAny", "Any")
    assertNamed(result, ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN, "makeCat", "Cat")
    assertNamed(result, ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT, "addBatch", "List")
    assertNamed(result, ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT, "removeBatch", "List")
    assertNamed(result, ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN, "addCounter", "Int")
    assertNamed(result, ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN, "removeCounter", "Int")
    listOf("onInitial", "onCount", "addTick", "removeTick").forEach { member ->
      assertFalse(
        result.kspWarnings.any { warning -> "Walker.$member" in warning },
        "a member that binds must not be reported; got: ${result.kspWarnings}",
      )
    }
  }

  /**
   * The rest of the builtin family, one payload per member, plus the stored mixed payload that
   * aborted the round even though its first component is a scalar.
   */
  @Test
  fun `every non-scalar builtin payload is refused on both routes`() {
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
      "OnSet(", "OnMap(", "OnPair(", "OnArray(", "OnElapsed(", "MakeList(", "MakeInitial(",
      "AddMixed(", "AddWindow(",
    ).forEach { member -> assertFalse(member in cs, "$member must not bind") }

    listOf("onSet", "onMap", "onPair", "onArray", "onElapsed", "addMixed", "removeMixed",
      "addWindow", "removeWindow").forEach { member ->
      assertNamed(result, ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT, member, null)
    }
    listOf("makeList", "makeInitial").forEach { member ->
      assertNamed(result, ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN, member, null)
    }
  }

  /**
   * The sealed-ARM twin: the arm's per-call and stored routes read `isArmCallbackRoutable`, which
   * the class walk never exercises. The per-call primitive sibling is the control: the refusal is
   * per member, not per arm.
   */
  @Test
  fun `a builtin payload or result is a named skip on a sealed arm too`() {
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
    listOf("OnBatch(", "AddBatch(", "AddCounter(").forEach { member ->
      assertFalse(member in cs, "$member must not bind on an arm")
    }
    listOf("OnCount(", "AddTick(Action<int>").forEach { control ->
      assertTrue(control in cs, "expected the arm control `$control` to keep binding")
    }
    listOf("onBatch", "addBatch", "removeBatch", "addCounter", "removeCounter").forEach { member ->
      assertFalse(
        "export_job_running_$member" in result.generated,
        "expected the arm's Kotlin half of `$member` gone too",
      )
    }
    assertNamed(result, ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT, "onBatch", "List")
    assertNamed(result, ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT, "addBatch", "List")
    assertNamed(result, ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN, "addCounter", "Int")
    assertNamed(result, ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN, "removeCounter", "Int")
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
   * A GENERIC owner drops every legacy-route member on both halves (ADR-147). The class walk still
   * reaches it, so each refused member must be named exactly once, not once per walk.
   */
  @Test
  fun `a non-Unit stored pair on a generic owner is named once`() {
    val result = Tier1Harness.run(
      """
      package tier1.legacybuiltingeneric

      class Cat(val name: String)

      class Crate<T>(val item: T) {
        fun addCounter(listener: () -> Int) {}
        fun removeCounter(listener: () -> Int) {}
        fun onBatch(cb: (List<Int>) -> Unit) = cb(listOf(1))
        fun makeCat(cb: () -> Cat) { cb() }
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected compilable Kotlin; got: ${result.compileErrors}")
    assertFalse("AddCounter(" in result.generatedCSharp, "a generic owner's pair must not bind")
    // The lambda is spelled as the author wrote it, not as the `FunctionN` it expands to.
    assertTrue(
      result.kspWarnings.any { "`cb: (List<Int>) -> Unit`" in it },
      "expected the lambda spelled in arrow syntax; got: ${result.kspWarnings}",
    )
    listOf("addCounter", "removeCounter", "onBatch", "makeCat").forEach { member ->
      val named: List<String> = result.kspWarnings.filter { warning -> "Crate.$member" in warning }
      assertEquals(1, named.size, "expected `$member` named exactly once; got: $named")
    }
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
