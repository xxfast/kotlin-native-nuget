package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Boundary nullability part A2: the per-call (ADR-036/102) and stored (ADR-037) callback routes.
 *
 * Two opposite edits, and they have to be read together, because before this change the tool got
 * each one exactly backwards:
 *  - a lambda whose PAYLOAD or RETURN is nullable bound with no diagnostic at all and then either
 *    aborted the author's `packNuget` inside generated Kotlin (`Int?` and handle payloads on the
 *    per-call route, every payload on the stored route) or crossed and killed the host process on a
 *    real null (`String?`, and a callback returning null at a generated `!!`). It is now a named
 *    skip, which replaces a broken build rather than removing a working member;
 *  - a lambda whose own TYPE is nullable (`listener: ((Int) -> Unit)?`) bound AND reported itself
 *    as skipped, on the very class that carried the member. It keeps binding, silently, with an
 *    `ArgumentNullException` guard in the wrapper.
 */
class Tier1NullableLambdaPayloadTest {

  @Test
  fun `a nullable lambda payload or return is a named skip on the per-call route`() {
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

    // Red before the refusal: three of these five shapes made the generated Kotlin uncompilable.
    assertTrue(
      result.compiledClean,
      "expected the refusal to leave compilable Kotlin; got: ${result.compileErrors}",
    )

    val cs: String = result.generatedCSharp
    assertFalse("EachCount(" in cs, "a nullable value payload must not bind")
    assertFalse("EachName(" in cs, "a nullable reference payload must not bind")
    assertFalse("EachCat(" in cs, "a nullable handle payload must not bind")
    assertFalse("Ask(" in cs, "a nullable lambda RETURN must not bind")
    // The non-null sibling is the control: this is a payload rule, not a lambda rule.
    assertTrue("EachPlainCount(" in cs, "a non-null payload must keep binding")

    val kotlin: String = result.generated
    listOf("eachCount", "eachName", "eachCat", "ask").forEach { member ->
      assertFalse(
        "export_walker_$member" in kotlin,
        "expected `$member` absent from the Kotlin half too, so the two halves agree",
      )
      assertTrue(
        result.kspWarnings.any { warning ->
          member in warning && "a callback parameter can carry" in warning
        },
        "expected a named callback-payload skip for `$member`; got: ${result.kspWarnings}",
      )
    }
  }

  @Test
  fun `a nullable lambda payload drops both halves of a stored callback pair`() {
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

    assertTrue(
      result.compiledClean,
      "expected the refusal to leave compilable Kotlin; got: ${result.compileErrors}",
    )

    // The pair goes together: a skip on the add alone would leave a cancel for a subscription
    // nobody can make, and a skip on the remove alone an uncancellable subscription.
    val cs: String = result.generatedCSharp
    assertFalse("AddRinger(" in cs, "the add half must not bind")
    assertFalse("RemoveRinger(" in cs, "the remove half must not bind")
    listOf("addRinger", "removeRinger").forEach { member ->
      assertTrue(
        result.kspWarnings.any { warning -> member in warning },
        "expected both halves of the pair named; got: ${result.kspWarnings}",
      )
    }
  }

  /**
   * The sealed-ARM twin. ADR-116's amendment re-keyed the per-call and stored callback routes onto
   * the arm, and the arm's routes read their own selector (`isArmCallbackRoutable`), so a refusal
   * applied to the class walk alone leaves the arm aborting the generated-Kotlin compile with no
   * diagnostic. Coverage found this refusal branch cold: the arm walk in
   * `warnRefusedLegacyRouteMembers` and the `isArmCallbackRoutable` clause were both entered by
   * every non-null arm fixture and never once taken.
   *
   * The non-null sibling on the same arm is the control: the refusal is per MEMBER, not per arm, so
   * one refused member must not take the arm's working callback member with it.
   */
  @Test
  fun `a nullable lambda payload is a named skip on a sealed arm too`() {
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

    assertTrue(
      result.compiledClean,
      "expected the arm refusal to leave compilable Kotlin; got: ${result.compileErrors}",
    )

    val cs: String = result.generatedCSharp
    assertFalse("EachCount(" in cs, "a nullable payload must not bind on an arm either")
    assertTrue("EachPlainCount(" in cs, "the arm's non-null sibling must keep binding")
    assertFalse(
      "export_job_running_eachCount" in result.generated,
      "expected the arm's Kotlin half gone too, so the two halves agree",
    )
    assertTrue(
      result.kspWarnings.any { warning ->
        "eachCount" in warning && "a callback parameter can carry" in warning
      },
      "expected the arm's member named by the sealed walk; got: ${result.kspWarnings}",
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
    // a null reaches a `[UnmanagedCallersOnly]` thunk that dereferences the registered ctx, a
    // fail-fast no `catch` can see. Rejected at the managed boundary instead, before any ctx is
    // registered for this call.
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
