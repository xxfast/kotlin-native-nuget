package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #114. A Unit-returning lambda renders its return as the type argument `void`:
 *
 * ```kotlin
 * val onTick: () -> Unit
 * ```
 * ```csharp
 * public KotlinFunc<void> OnTick => new KotlinFunc<void>(Native_Get_onTick(_handle, out _));
 * ```
 * ```
 * Interop.cs(11642,27): error CS1547: Keyword 'void' cannot be used in this context
 * ```
 *
 * `void` is a legal C# *return* type and never a legal *type argument*, and the lambda routes do
 * not distinguish the two positions. The suspend arm right next door already does: it narrows a
 * `void` last argument to `KotlinSuspendAction` / `KotlinSuspendAction<T...>` rather than
 * spelling `KotlinSuspendFunc<..., void>`. The plain arm has no such branch, so
 * `suspend () -> Unit` compiles and `() -> Unit` does not.
 *
 * The fix therefore has two halves, and a run that only did one is still broken:
 *  - the **branch**, at every route that spells a lambda's C# type, and
 *  - the **type**, because `KotlinAction` is rendered nowhere today (only `KotlinFunc<TResult>`
 *    and `KotlinFunc<T1..Tn, TResult>` exist). Emitting the name without declaring the class
 *    swaps CS1547 for CS0246.
 *
 * **Every route gets its own cell**, for the reason issue #111 already established here: each is
 * a separate hand-written copy of the same expression, so a fix applied to one is invisible to
 * the others.
 *  - `CirClassTranslator.kt:372` ordinary class property
 *  - `CirClassTranslator.kt:1168` sealed subclass property
 *  - `CirFunctionTranslator.kt:158` top-level function, lambda return
 *
 * [onName] and [onCleanup] are the controls: the non-Unit lambda must keep its `KotlinFunc`
 * spelling, and the suspend Unit lambda (which was always correct) must not regress.
 *
 * `packNuget` is green with the defect in it. Only compiling the generated `Interop.cs` fails,
 * which is why this is a structural Tier 1 cell (ADR-060 rejected compiling C# in Tier 1) backed
 * by the real fixture in `test-library` that `scripts/verify.sh` does compile.
 *
 * Oreo naps through every tick. Mylo is unmoved.
 */
class Tier1UnitLambdaPropertyTest {

  private val source: String = """
    package tier1.ticker

    class Ticker(val label: String) {
      val onTick: () -> Unit = {}
      val onCount: (Int) -> Unit = {}
      val onName: () -> String = { label }
      val onCleanup: suspend () -> Unit = {}
    }

    sealed class Feed {
      data class Live(val label: String) : Feed() {
        val onTick: () -> Unit = {}
      }
    }

    fun ticker(): () -> Unit = {}
  """.trimIndent()

  private fun run(): Tier1Result = Tier1Harness.run(source)

  /**
   * The whole-file invariant, and the one that fails today. `void` is a return type, never a
   * generic type argument, so it must not appear between the angle brackets of one anywhere in
   * the generated bindings.
   *
   * A `delegate* unmanaged[Cdecl]<..., void>` is exempt and not an oversight: a function pointer's
   * trailing type argument *is* its return type, so `void` there is both legal and required. The
   * distinction is the whole bug: the same token is fine in a return position and CS1547 in a
   * generic one, and the lambda routes did not tell the two apart.
   */
  @Test
  fun `void is never spelled as a generic type argument`() {
    val result = run()

    val offending: List<String> = result.generatedCSharp.lines()
      .filter { it.contains("void>") || it.contains("<void,") || it.contains(", void,") }
      .filterNot { it.contains("delegate*") }

    assertTrue(
      offending.isEmpty(),
      "`void` cannot be used as a C# type argument (CS1547); got: $offending",
    )
  }

  /** The reported repro, at the ordinary-class property arm (`:372`). */
  @Test
  fun `a zero-arg Unit lambda property renders KotlinAction`() {
    val result = run()

    val onTick: List<String> = memberLines(result, "OnTick")
    assertTrue(
      onTick.isNotEmpty(),
      "expected an OnTick member on both Ticker and Feed.Live; got none",
    )
    onTick.forEach { line ->
      assertTrue(
        line.contains("KotlinAction") && !line.contains("KotlinAction<"),
        "expected the zero-arg Unit lambda to render the non-generic `KotlinAction`; " +
            "got: ${line.trim()}",
      )
    }
  }

  /**
   * The arity-N facet. The issue only observed the zero-arg shape, but the same line joins every
   * argument unconditionally, so `(Int) -> Unit` renders `KotlinFunc<int, void>` and is the same
   * CS1547. The Unit return is dropped and the parameters stay: `KotlinAction<int>`.
   */
  @Test
  fun `an arity-N Unit lambda property renders KotlinAction of its parameters`() {
    val result = run()

    val onCount: List<String> = memberLines(result, "OnCount")
    assertTrue(onCount.isNotEmpty(), "expected an OnCount member; got none")
    onCount.forEach { line ->
      assertTrue(
        line.contains("KotlinAction<int>"),
        "expected `(Int) -> Unit` to render `KotlinAction<int>`; got: ${line.trim()}",
      )
    }
  }

  /** The sealed subclass arm (`:1168`), which is a separate copy of the same expression. */
  @Test
  fun `a Unit lambda property on a sealed subclass renders KotlinAction`() {
    val result = run()

    val sealedGetters: List<String> = memberLines(result, "OnTick").filter(::isSealedGetter)
    assertTrue(
      sealedGetters.isNotEmpty(),
      "expected Feed.Live to keep its OnTick member; got: ${memberLines(result, "OnTick")}",
    )
    sealedGetters.forEach { line ->
      assertFalse(
        line.contains("void"),
        "expected the sealed subclass arm to narrow Unit too; got: ${line.trim()}",
      )
    }
  }

  /** The top-level function arm (`CirFunctionTranslator.kt:158`), a return position. */
  @Test
  fun `a function returning a Unit lambda renders KotlinAction`() {
    val result = run()

    val ticker: List<String> = result.generatedCSharp.lines().filter { it.contains("Ticker()") }
    assertTrue(ticker.isNotEmpty(), "expected a Ticker() function; got none")
    assertTrue(
      ticker.any { it.contains("KotlinAction") },
      "expected the lambda-returning function to narrow Unit; got: $ticker",
    )
  }

  /**
   * The type half. Naming `KotlinAction` without declaring it trades CS1547 for CS0246, so the
   * class itself must be rendered, at both the arities the fixture uses.
   */
  @Test
  fun `the KotlinAction runtime type is declared at every used arity`() {
    val result = run()

    assertTrue(
      result.generatedCSharp.contains("public class KotlinAction :"),
      "expected the non-generic KotlinAction to be declared",
    )
    assertTrue(
      result.generatedCSharp.contains("public class KotlinAction<T1> :"),
      "expected the arity-1 KotlinAction<T1> to be declared",
    )
  }

  /** Control: a non-Unit lambda keeps `KotlinFunc`, and the suspend Unit arm does not regress. */
  @Test
  fun `non-Unit and suspend lambdas are untouched`() {
    val result = run()

    assertTrue(
      memberLines(result, "OnName").any { it.contains("KotlinFunc<string>") },
      "control: `() -> String` must keep rendering KotlinFunc<string>; " +
          "got: ${memberLines(result, "OnName")}",
    )
    assertTrue(
      memberLines(result, "OnCleanup").any { it.contains("KotlinSuspendAction") },
      "control: `suspend () -> Unit` was already correct and must stay so; " +
          "got: ${memberLines(result, "OnCleanup")}",
    )
  }

  private fun memberLines(result: Tier1Result, member: String): List<String> =
    result.generatedCSharp.lines().filter { it.contains(" $member ") || it.contains(" $member=>") }

  private fun isSealedGetter(line: String): Boolean = line.contains("out _")
}
