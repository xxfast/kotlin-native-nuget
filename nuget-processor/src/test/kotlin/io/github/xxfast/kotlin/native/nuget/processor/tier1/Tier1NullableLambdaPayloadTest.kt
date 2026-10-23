package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Boundary nullability part A2: the per-call (ADR-036/102) and stored (ADR-037) callback routes.
 *
 * A lambda whose PAYLOAD or RETURN is nullable once bound with no diagnostic and then either
 * aborted the author's `packNuget` inside generated Kotlin or killed the host on a real null; it
 * then became a named skip. ADR-239 binds it: a nullable payload crosses as a box Kotlin retains
 * (none for null) and C# reads with `NugetMarshal.FromHandle<T>`, a nullable result as the owned
 * box C# mints (none for null), and a stored pair over a Kotlin lambda minted from the delegate.
 *
 * A lambda whose own TYPE is nullable (`listener: ((Int) -> Unit)?`) keeps binding, silently, with
 * an `ArgumentNullException` guard in the wrapper.
 */
class Tier1NullableLambdaPayloadTest {

  @Test
  fun `a nullable lambda payload or return binds on the per-call route`() {
    val result = Tier1Harness.run(
      """
      package tier1.nullablelambdapayload

      class Cat(val name: String)

      class Walker {
        fun eachCount(cb: (Int?) -> Unit) = cb(null)

        fun eachName(cb: (String?) -> Unit) = cb(null)

        fun eachCat(cb: (Cat?) -> Unit) = cb(null)

        fun ask(cb: (Int) -> String?): String? = cb(1)

        fun eachPlainCount(cb: (Int) -> Unit) = cb(1)
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected compilable Kotlin; got: ${result.compileErrors}")
    val cs: String = result.generatedCSharp
    listOf(
      "EachCount(Action<int?>", "EachName(Action<string?>", "EachCat(Action<", "Ask(",
      "EachPlainCount(",
    ).forEach { member -> assertTrue(member in cs, "expected `$member` to bind") }
    assertTrue(
      result.kspWarnings.none { warning -> "Walker." in warning },
      "a member that binds must not be reported; got: ${result.kspWarnings}",
    )
  }

  /** A member mixing a scalar parameter with a nullable-payload lambda binds whole. */
  @Test
  fun `a nullable payload beside another parameter binds and is named nowhere`() {
    val result = Tier1Harness.run(
      """
      package tier1.nullablelambdaonce

      class Quiz {
        fun ask(cb: (Int) -> String?): String? = cb(1)

        fun askPlain(cb: (Int) -> String?): String = cb(1) ?: ""

        fun mixed(x: Int, cb: (Int?) -> Unit) = cb(x)
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected compilable Kotlin; got: ${result.compileErrors}")
    listOf("Ask(", "AskPlain(", "Mixed(int x, Action<int?> cb)").forEach { member ->
      assertTrue(member in result.generatedCSharp, "expected `$member` to bind")
    }
    assertTrue(
      result.kspWarnings.none { warning -> "Quiz." in warning },
      "a member that binds must not be reported; got: ${result.kspWarnings}",
    )
  }

  @Test
  fun `a nullable lambda payload binds both halves of a stored callback pair`() {
    val result = Tier1Harness.run(
      """
      package tier1.nullablelambdastored

      class Bell {
        fun addRinger(listener: (String?) -> Unit) {
          ringers += listener
        }

        fun removeRinger(listener: (String?) -> Unit) {
          ringers -= listener
        }

        private var ringers: List<(String?) -> Unit> = emptyList()
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected compilable Kotlin; got: ${result.compileErrors}")
    assertTrue(
      "AddRinger(Action<string?>" in result.generatedCSharp,
      "the pair binds on the managed-listener route",
    )
    assertTrue(
      result.kspWarnings.none { warning -> "Ringer" in warning },
      "a pair that binds must not be reported; got: ${result.kspWarnings}",
    )
  }

  /** The sealed-ARM twin: the arm's routes read their own selector (`isArmCallbackRoutable`). */
  @Test
  fun `a nullable lambda payload binds on a sealed arm too`() {
    val result = Tier1Harness.run(
      """
      package tier1.nullablearmlambda

      sealed class Job {
        data class Running(val progress: Int) : Job() {
          fun eachCount(cb: (Int?) -> Unit) = cb(null)

          fun eachPlainCount(cb: (Int) -> Unit) = cb(progress)
        }

        data class Done(val code: Int) : Job()
      }

      class JobFactory {
        fun running(progress: Int): Job.Running = Job.Running(progress)
      }
      """.trimIndent(),
      fileName = "ArmJobSample.kt",
    )

    assertTrue(result.compiledClean, "expected compilable Kotlin; got: ${result.compileErrors}")
    val cs: String = result.generatedCSharp
    assertTrue("EachCount(Action<int?>" in cs, "a nullable payload binds on an arm")
    assertTrue("EachPlainCount(" in cs, "the arm's non-null sibling keeps binding")
    assertTrue(
      result.kspWarnings.none { warning -> "eachCount" in warning },
      "a member that binds must not be reported; got: ${result.kspWarnings}",
    )
  }

  @Test
  fun `a nullable lambda TYPE keeps binding, silently, and rejects a null delegate`() {
    val result = Tier1Harness.run(
      """
      package tier1.nullablelambdatype

      class Metronome(private val beats: Int) {
        fun onMaybeTick(listener: ((Int) -> Unit)?) = repeat(beats) { listener?.invoke(it + 1) }
      }
      """.trimIndent(),
    )

    assertTrue(
      result.compiledClean,
      "expected a nullable lambda type to keep binding; got: ${result.compileErrors}",
    )

    val cs: String = result.generatedCSharp
    assertTrue("OnMaybeTick(" in cs, "a nullable lambda type binds; only its payload matters")
    // The obligation that comes with binding it: the erased delegate slot cannot carry "absent", so
    // a null would be registered and invoked by the managed callback body on the first call,
    // surfacing as a `NullReferenceException` that never names the argument. Rejected at the
    // managed boundary instead, before any ctx is registered for this call (the guard is now
    // unconditional; Tier1CallbackNullGuardTest covers the non-null spellings).
    assertTrue(
      "ArgumentNullException.ThrowIfNull(listener);" in cs,
      "expected the wrapper to reject a null delegate up front; cs=$cs",
    )
    assertTrue(
      cs.indexOf("ArgumentNullException.ThrowIfNull(listener);") <
          cs.indexOf("NugetThunks.RegisterCtx(nativeCallback)"),
      "the guard must precede the ctx mint, which accepts null and defers the failure",
    )

    // And the half that used to contradict the other: the member exists, so nothing may report it
    // as absent.
    assertFalse(
      result.kspWarnings.any { warning -> "onMaybeTick" in warning },
      "a member that BINDS must not also be reported as skipped; got: ${result.kspWarnings}",
    )
    assertFalse(
      "Not generated from Kotlin `onMaybeTick`" in cs,
      "no XML remark may claim an absent member on the class that declares it",
    )
  }
}
