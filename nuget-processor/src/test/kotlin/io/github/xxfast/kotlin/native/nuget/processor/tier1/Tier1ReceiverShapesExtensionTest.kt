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
 * wire. A has-value fan-out receiver (`Int?`, `Mood?`, `Instant?`, ...) binds on the matching
 * C# `Nullable<T>` and reads its flag slot, pinned in the tests after those.
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
      "receiver.asStableRef<tier1.receivershapes.Pet>().get()" +
        ".nuget_ext_tier1__receivershapes__describe()",
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
   * ADR-132 amendment: a receiver whose wire is the ADR-079/080 adjacent `receiverHasValue` +
   * `receiver` pair binds as a C# extension on the matching `Nullable<T>`. The plan carries the
   * receiver's NULLABLE type and its minted flag slot as a public parameter, so the Kotlin export
   * reads the flag: lowering off the value slot alone (which carries the non-null inner type) would
   * compile clean and pass `0` where the caller passed `null`.
   *
   * The C# call site needs a `T?` receiver: `5.OrZero()` on a bare `int` is CS1929, the same
   * nullable-only call site ADR-132 sub-decision (a) accepted for the `CatId?` receiver.
   */
  @Test
  fun `a has-value fan-out receiver binds as an extension on the nullable struct`() {
    val result = Tier1Harness.run(
      """
      package tier1.receiverfanout

      fun Int?.orZero(): Int = this ?: 0
      """.trimIndent(),
    )

    assertTrue(result.kspErrors.isEmpty(), "expected no KSP error; got: ${result.kspErrors}")
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    assertTrue(
      result.kspWarnings.none { warning -> warning.contains("orZero") },
      "a fan-out receiver must bind, not warn; got: ${result.kspWarnings}",
    )
    val kotlin: String = requireNotNull(result.generated)
    assertContains(kotlin, "receiverHasValue: Boolean")
    assertContains(kotlin, "(if (receiverHasValue) receiver else null).")
    val cs: String = result.generatedCSharp
    assertContains(cs, "public static int OrZero(this int? receiver)")
    assertContains(cs, "receiver.HasValue, receiver.GetValueOrDefault()")
    // No handle is minted on either side for a value-type receiver, so there is nothing to leak.
    assertFalse(Regex("\\bHandleOf\\w*\\(receiver").containsMatchIn(cs))
  }

  /**
   * Every has-value fan-out receiver kind: an `enum class` (its `int` ordinal), `Instant` and
   * `Duration` (ticks), and value classes over a primitive and an enum. Each lowers through the
   * same nullable parameter arm, so each reads the flag.
   */
  @Test
  fun `every fan-out receiver kind binds on its nullable struct and reads the flag`() {
    val result = Tier1Harness.run(
      """
      package tier1.receiverfanoutkinds

      import kotlin.time.Duration
      import kotlin.time.ExperimentalTime
      import kotlin.time.Instant

      enum class Mood { HAPPY, SAD }

      @JvmInline
      value class Dosage(val mg: Int)

      @JvmInline
      value class Tagged(val mood: Mood)

      fun Mood?.loud(): String = if (this == Mood.HAPPY) "!" else "."

      fun Dosage?.orNone(): Int = this?.mg ?: -1

      fun Tagged?.moodName(): String = this?.mood?.name ?: "none"

      fun Duration?.orNothing(): Duration = this ?: Duration.ZERO

      @OptIn(ExperimentalTime::class)
      fun Instant?.orEpoch(): Instant = this ?: Instant.fromEpochSeconds(0)
      """.trimIndent(),
    )

    assertTrue(result.kspErrors.isEmpty(), "expected no KSP error; got: ${result.kspErrors}")
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    assertTrue(
      result.kspWarnings.isEmpty(),
      "no fan-out receiver may warn; got: ${result.kspWarnings}",
    )
    val kotlin: String = requireNotNull(result.generated)
    assertContains(
      kotlin,
      "(if (receiverHasValue) tier1.receiverfanoutkinds.Mood.entries[receiver]",
    )
    assertContains(kotlin, "(if (receiverHasValue) tier1.receiverfanoutkinds.Dosage(receiver)")
    assertContains(kotlin, "(if (receiverHasValue) durationFromDotNetTicks(receiver)")
    assertContains(kotlin, "(if (receiverHasValue) instantFromDotNetTicks(receiver)")
    val cs: String = result.generatedCSharp
    listOf(
      "string Loud\\(this [\\w.:]*Mood\\? receiver\\)",
      "int OrNone\\(this [\\w.:]*Dosage\\? receiver\\)",
      "string MoodName\\(this [\\w.:]*Tagged\\? receiver\\)",
      "OrNothing\\(this [\\w.:]*TimeSpan\\? receiver\\)",
      "OrEpoch\\(this [\\w.:]*DateTimeOffset\\? receiver\\)",
    ).forEach { signature ->
      assertTrue(Regex(signature).containsMatchIn(cs), "expected `$signature` in:\n$cs")
    }
  }

  /**
   * ADR-095: an `Int` and an `Int?` receiver of one name are two overloads, numbered `describe`
   * and `describe_2`, and both bind into one C# extension class with different `this` types. The
   * nullable one must call the nullable Kotlin overload with a real `null`: a bare
   * `receiver.describe()` would statically resolve to the non-null twin.
   */
  @Test
  fun `a non-null and a nullable receiver of one name bind as two overloads`() {
    val result = Tier1Harness.run(
      """
      package tier1.receiverfanouttwin

      fun Int.describe(): String = "nonnull:${'$'}this"

      fun Int?.describe(): String = "nullable:${'$'}this"
      """.trimIndent(),
    )

    assertTrue(result.kspErrors.isEmpty(), "expected no KSP error; got: ${result.kspErrors}")
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    assertTrue(result.kspWarnings.isEmpty(), "expected no warning; got: ${result.kspWarnings}")
    val kotlin: String = requireNotNull(result.generated)
    assertContains(kotlin, "(if (receiverHasValue) receiver else null).")
    val cs: String = result.generatedCSharp
    assertContains(cs, "public static string Describe(this int receiver)")
    assertContains(cs, "public static string Describe(this int? receiver)")
    // The pin the extension-PROPERTY twin mirrors: C# declares both overloads in one class, and a
    // `T?` variable and a bare `T` each resolve to their own.
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using Interop;
      public static class Consumer
      {
          public static string Run()
          {
              int? none = null;
              int? some = 5;
              return 7.Describe() + none.Describe() + some.Describe();
          }
      }
      """.trimIndent(),
    )
  }

  /**
   * The same pair over a struct receiver that is NOT a fan-out: `Uuid` / `Uuid?` (`Guid` /
   * `Guid?`) and a value class over a `String` / its nullable twin (a `record struct`). Both null
   * in-band on one slot, and both are two C# overloads all the same.
   */
  @Test
  fun `a non-fan-out struct receiver and its nullable twin bind as two overloads`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.receiverstructtwin

      import kotlin.uuid.Uuid

      @JvmInline
      value class CatId(val id: String)

      fun Uuid.tag(): String = "chip"

      // The Tier 1 harness compiles on the JVM, where the pair erases to one signature.
      @JvmName("tagOrNull")
      fun Uuid?.tag(): String = if (this == null) "no chip" else "chip?"

      fun CatId.badge(): String = id

      @JvmName("badgeOrNull")
      fun CatId?.badge(): String = this?.id ?: "stray"
      """.trimIndent(),
    )

    assertTrue(result.kspErrors.isEmpty(), "expected no KSP error; got: ${result.kspErrors}")
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    assertTrue(result.kspWarnings.isEmpty(), "expected no warning; got: ${result.kspWarnings}")
    val cs: String = result.generatedCSharp
    assertContains(cs, "Tag(this global::System.Guid receiver)")
    assertContains(cs, "Tag(this global::System.Guid? receiver)")
    assertContains(cs, "Badge(this global::Interop.CatId receiver)")
    assertContains(cs, "Badge(this global::Interop.CatId? receiver)")
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using Interop;
      public static class Consumer
      {
          public static string Run()
          {
              global::System.Guid? none = null;
              CatId? stray = null;
              return global::System.Guid.NewGuid().Tag() + none.Tag() +
                  new CatId("Oreo").Badge() + stray.Badge();
          }
      }
      """.trimIndent(),
    )
  }

  /**
   * The receiver's flag slot is minted through the same `freshName` pool as every parameter's, so
   * a user parameter literally spelled `receiverHasValue` keeps its name and the generator's flag
   * moves. Reserving the name would shift the user's parameter instead.
   */
  @Test
  fun `a user parameter named like the receiver flag keeps its name`() {
    val result = Tier1Harness.run(
      """
      package tier1.receiverfanoutflagname

      fun Int?.bump(receiverHasValue: Boolean): Int = if (receiverHasValue) (this ?: 0) + 1 else 0
      """.trimIndent(),
    )

    assertTrue(result.kspErrors.isEmpty(), "expected no KSP error; got: ${result.kspErrors}")
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    val cs: String = result.generatedCSharp
    assertContains(cs, "Bump(this int? receiver, bool receiverHasValue)")
    val kotlin: String = requireNotNull(result.generated)
    assertContains(kotlin, "(if (receiverHasValue_) receiver else null).")
  }

  /**
   * `Char?` fans out like the other value types (ADR-098 amendment part C) and follows the bare
   * `Char` receiver on the function route: both bind, `Char?` on `char?`.
   */
  @Test
  fun `a Char and a nullable Char receiver both bind`() {
    val result = Tier1Harness.run(
      """
      package tier1.receiverfanoutchar

      fun Char.shout(): String = uppercase()

      fun Char?.orSpace(): Char = this ?: ' '
      """.trimIndent(),
    )

    assertTrue(result.kspErrors.isEmpty(), "expected no KSP error; got: ${result.kspErrors}")
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    assertTrue(result.kspWarnings.isEmpty(), "expected no warning; got: ${result.kspWarnings}")
    val cs: String = result.generatedCSharp
    assertContains(cs, "Shout(this char receiver)")
    assertContains(cs, "OrSpace(this char? receiver)")
  }

  /**
   * ADR-096 synthesizes an omitting overload per trailing defaulted parameter; with a fan-out
   * receiver each of them binds too, on the same two-slot receiver.
   */
  @Test
  fun `a fan-out receiver with a defaulted parameter binds with its omitting overload`() {
    val result = Tier1Harness.run(
      """
      package tier1.receiverfanoutdefaults

      fun Int?.withDefault(a: Int = 0): Int = (this ?: 0) + a
      """.trimIndent(),
    )

    assertTrue(result.kspErrors.isEmpty(), "expected no KSP error; got: ${result.kspErrors}")
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    assertTrue(
      result.kspWarnings.none { warning -> warning.contains("withDefault") },
      "a fan-out receiver must bind, not warn; got: ${result.kspWarnings}",
    )
    assertContains(result.generatedCSharp, "WithDefault(this int? receiver")
  }
}
