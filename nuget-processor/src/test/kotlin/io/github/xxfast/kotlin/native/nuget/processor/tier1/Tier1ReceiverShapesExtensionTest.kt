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
    assertContains(cs, "if (receiverOwned) { NugetMarshal.Dispose(receiverHandle); }")
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
      result.generatedCSharp.contains("OrZero"),
      "a fan-out receiver must not render a C# binding at all",
    )
    assertTrue(
      result.kspWarnings.any { warning -> warning.contains("orZero") },
      "the drop must name itself; got: ${result.kspWarnings}",
    )
  }
}
