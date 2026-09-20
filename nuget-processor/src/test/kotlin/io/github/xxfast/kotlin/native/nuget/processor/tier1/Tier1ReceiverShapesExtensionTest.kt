package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-132 (receiver shapes): pins the contract now that an extension receiver lowers exactly like
 * a parameter, one slot to the left, for every admitted shape beyond handle/non-null value class.
 * A bare INTERFACE receiver takes the ADR-084 stage-3 interface argument's own lifecycle —
 * `HandleOf(receiver, out receiverOwned)` on the C# side and a borrowed `StableRef` read on the
 * Kotlin side, with a `Dispose` in the `finally` when the handle was minted for a C#-implemented
 * pet — and a NULLABLE VALUE-CLASS receiver re-wraps ADR-077 sub-item 3's nullable underlying
 * wire. A has-value fan-out receiver is the one admitted shape that cannot take this path, and is
 * instead dropped as the named `RECEIVER_FAN_OUT` skip, pinned in the last test below.
 *
 * Only the compile and the two public signatures are pinned here; the exact re-wrap spelling on
 * the Kotlin side and the argument expression on the C# side are the implementer's to choose.
 */
class Tier1ReceiverShapesExtensionTest {

  private val source: String = """
    package tier1.receivershapes

    interface Pet {
      val name: String
      val legs: Int
      fun speak(): String
    }

    class Cat(val title: String) : Pet {
      override val name: String = title
      override val legs: Int = 4
      override fun speak(): String = "Meow"
    }

    @JvmInline
    value class CatId(val id: String)

    fun Pet.describe(): String = "${'$'}name has ${'$'}legs legs and says ${'$'}{speak()}"

    fun CatId?.orAnonymous(): String = this?.id ?: "anonymous"
  """.trimIndent()

  @Test
  fun `both receiver shapes produce a Kotlin export that compiles`() {
    val result = Tier1Harness.run(source)

    assertTrue(
      result.compiledClean,
      "expected the interface and nullable value-class receivers to compile; " +
          "got: ${result.compileErrors}",
    )
    // The interface receiver is a borrowed StableRef, exactly like an interface *parameter*: the
    // ADR-084 bridge object backing a C#-implemented pet is itself a Kotlin `Pet`, so one read
    // serves both implementations.
    assertContains(
      result.generated,
      "receiver.asStableRef<tier1.receivershapes.Pet>().get().describe()",
    )
  }

  @Test
  fun `an interface receiver binds as a C# extension on the interface and disposes its transfer handle`() {
    val result = Tier1Harness.run(source)
    val cs: String = result.generatedCSharp

    assertContains(cs, "public static string Describe(this global::Interop.IPet receiver)")
    // Same prelude/cleanup pair the interface argument position already emits
    // (`Tier1InterfaceBridgeFactoryTest`: `petHandle = NugetMarshal.HandleOf(pet, out petOwned);`).
    assertContains(cs, "NugetMarshal.HandleOf(receiver, out receiverOwned)")
    assertContains(
      cs,
      "if (receiverOwned && receiverHandle != IntPtr.Zero) { NugetMarshal.Dispose(receiverHandle); }",
    )
  }

  @Test
  fun `a nullable value-class receiver binds as a C# extension on the nullable struct`() {
    val result = Tier1Harness.run(source)
    val cs: String = result.generatedCSharp

    assertContains(cs, "public static string OrAnonymous(this global::Interop.CatId? receiver)")
  }

  /**
   * ADR-132's control: the one admitted shape that is now a NAMED skip instead. A receiver whose
   * wire is the ADR-079/080 adjacent `receiverHasValue` + `receiver` pair cannot be a plan — the
   * model allows a single RECEIVER-role slot and it must come first, and `nativeInputParameters`
   * marks only the value half of a fan-out with the caller's role, so such a plan fails
   * `validateRoles` with an exception out of the processor rather than a diagnostic. The skip has
   * to be a warning plus no export, never a crash and never a half-rendered one.
   */
  @Test
  fun `a has-value fan-out receiver is a named skip, not a crash and not an export`() {
    val result = Tier1Harness.run(
      """
      package tier1.receiverfanout

      fun Int?.orZero(): Int = this ?: 0
      """.trimIndent(),
    )

    assertTrue(result.kspErrors.isEmpty(), "expected no KSP error; got: ${result.kspErrors}")
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    assertFalse(
      result.generated.contains("orZero"),
      "a fan-out receiver must not render an export at all",
    )
    assertFalse(
      // Word-bounded, accessor prefixes included (the same shape at the property position renders
      // `GetOrZero`): the marshal helper is declared even for a module that exports nothing
      // (ADR-129 amendment), and it declares `HandleOfOrZero`, which a bare `contains` now matches.
      Regex("\\b(Get|Set)?OrZero\\b").containsMatchIn(result.generatedCSharp),
      "a fan-out receiver must not render a C# binding at all",
    )
    assertTrue(
      result.kspWarnings.any { warning -> warning.contains("orZero") },
      "the drop must name itself; got: ${result.kspWarnings}",
    )
  }

  /**
   * ROADMAP Phase 4 / ADR-064 amendment: the fan-out drop owns its sentence and its hint under the
   * shipped `SKIPPED_UNSUPPORTED_INPUT` kind. The generic pair it used to print named a reason
   * constant (`its RECEIVER_FAN_OUT type combination is not supported`), never named the receiver
   * type at all (an extension symbol does not carry its receiver), and sent the author to
   * "supported parameter/return shapes" for a type that IS supported at a parameter. Pinned whole,
   * both halves, because the remedy is the point of the message.
   */
  @Test
  fun `a fan-out receiver names the receiver type, its shape, and both remedies`() {
    val result = Tier1Harness.run(
      """
      package tier1.receiverfanoutmessage

      fun Int?.orZero(): Int = this ?: 0
      """.trimIndent(),
    )

    val warning: String = result.kspWarnings.single { it.contains("orZero") }
    assertContains(
      warning,
      "[nuget:SKIPPED_UNSUPPORTED_INPUT] Skipping tier1.receiverfanoutmessage.orZero: " +
          "its extension receiver `Int?` crosses the bridge as a has-value flag plus a value " +
          "(two slots), and an extension receiver can carry only one (RECEIVER_FAN_OUT). " +
          "`Int?` binds as an ordinary parameter, so declare a top-level function that takes it " +
          "as a parameter instead of as the receiver; or declare the extension on the non-null " +
          "receiver `Int`",
    )
    // The two strings the shipped generic pair printed, gone: the sentence blamed a reason
    // constant and the hint blamed the parameter/return shapes.
    assertFalse(
      warning.contains("type combination is not supported"),
      "the fan-out sentence must not fall back to the generic one; got: $warning",
    )
    assertFalse(
      warning.contains("expose a bridgeable adapter"),
      "the fan-out hint must not fall back to the generic one; got: $warning",
    )
  }

  /**
   * The non-primitive spellings of the same shape, proving the receiver name in both the sentence
   * and the non-null clause is rendered from the receiver's own type rather than hardcoded: an
   * `enum class` (its `int` ordinal wire has no spare null) and a value class over a primitive
   * (ADR-079's re-wrapped underlying).
   */
  @Test
  fun `a fan-out enum and value-class receiver each name themselves`() {
    val result = Tier1Harness.run(
      """
      package tier1.receiverfanoutkinds

      enum class Mood { HAPPY, SAD }

      @JvmInline
      value class Dosage(val mg: Int)

      fun Mood?.loud(): String = if (this == Mood.HAPPY) "!" else "."

      fun Dosage?.orZero(): Int = this?.mg ?: 0
      """.trimIndent(),
    )

    val mood: String = result.kspWarnings.single { it.contains("loud") }
    assertContains(
      mood,
      "its extension receiver `Mood?` crosses the bridge as a has-value flag plus a value " +
          "(two slots), and an extension receiver can carry only one (RECEIVER_FAN_OUT). " +
          "`Mood?` binds as an ordinary parameter, so declare a top-level function that takes it " +
          "as a parameter instead of as the receiver; or declare the extension on the non-null " +
          "receiver `Mood`",
    )

    val dosage: String = result.kspWarnings.single { it.contains("orZero") }
    assertContains(
      dosage,
      "its extension receiver `Dosage?` crosses the bridge as a has-value flag plus a value " +
          "(two slots), and an extension receiver can carry only one (RECEIVER_FAN_OUT). " +
          "`Dosage?` binds as an ordinary parameter, so declare a top-level function that takes " +
          "it as a parameter instead of as the receiver; or declare the extension on the " +
          "non-null receiver `Dosage`",
    )
  }

  /**
   * One declaration, one warning. ADR-096 synthesizes an omitting overload per trailing defaulted
   * parameter, and a `Skipped` passes through `synthesized()` unchanged, so a fan-out receiver
   * with a defaulted parameter used to report the drop once per overload (`...withDefault` and
   * `...withDefault_2`) for a single thing the author wrote. The receiver is never truncated, so
   * every omitting overload drops for the identical reason and only the declared entry is worth
   * reporting.
   */
  @Test
  fun `a fan-out receiver with a defaulted parameter warns once, not once per omitting overload`() {
    val result = Tier1Harness.run(
      """
      package tier1.receiverfanoutdefaults

      fun Int?.withDefault(a: Int = 0): Int = (this ?: 0) + a
      """.trimIndent(),
    )

    val warnings: List<String> = result.kspWarnings.filter { it.contains("withDefault") }
    assertTrue(
      warnings.size == 1,
      "one declaration must print one warning; got ${warnings.size}: $warnings",
    )
    assertContains(warnings.single(), "tier1.receiverfanoutdefaults.withDefault:")
    assertFalse(
      warnings.single().contains("withDefault_2"),
      "the synthesized overload must not be reported as a declaration of its own; got: $warnings",
    )
  }
}
