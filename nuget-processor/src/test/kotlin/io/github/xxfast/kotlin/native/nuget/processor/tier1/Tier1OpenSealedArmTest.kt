package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-009 amendment (2026-09-11): an `open` arm of a sealed class renders `public class`, not
 * `public sealed class`, its `open` members render `virtual`, and a further Kotlin subclass of
 * that arm takes the ordinary class route with the arm spelled by its nested C# name.
 *
 * Before this, every arm was rendered `public sealed class` unconditionally and every arm member
 * was pinned to neither `virtual` nor `override`, so a Kotlin `class HighPerch : Roost.Perch()`
 * produced a non-compiling `Interop.cs`: `CS0509` (base is sealed), and `CS0246` for the
 * unqualified `: Perch` base spelling of a nested arm.
 */
class Tier1OpenSealedArmTest {

  @Test
  fun `an open sealed arm renders non-sealed with virtual members and a resolvable base`() {
    val result = Tier1Harness.run(
      """
      package tier1.openarm

      fun roost(): Roost = Roost.Perch(3)

      fun highRoost(): Roost = HighPerch()

      sealed class Roost {
        open class Perch(open val height: Int) : Roost() {
          val material: String = "oak"

          open fun describe(): String = "perch ${'$'}height"

          fun label(): String = "perch"
        }

        data object Ground : Roost()
      }

      class HighPerch : Roost.Perch(1) {
        override val height: Int get() = 99

        override fun describe(): String = "high"
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected Roost/HighPerch to compile; got: ${result.compileErrors}",
    )

    val csharp: String = result.generatedCSharp
    assertContains(
      csharp,
      "public class Perch : Roost",
      message = "expected the open arm Perch to render non-sealed so HighPerch can extend it; " +
          "generatedCSharp:\n$csharp",
    )
    assertFalse(
      "public sealed class Perch" in csharp,
      "an `open` arm must not render sealed (CS0509 on the subclass, CS0549 on its virtual " +
          "members); generatedCSharp:\n$csharp",
    )
    assertContains(
      csharp,
      "public sealed class Ground : Roost",
      message = "a final arm beside the open one keeps its shipped sealed spelling; " +
          "generatedCSharp:\n$csharp",
    )

    assertContains(
      csharp,
      "public virtual int Height",
      message = "expected the open arm's `open val` to render virtual so HighPerch's override " +
          "compiles; generatedCSharp:\n$csharp",
    )
    assertContains(
      csharp,
      "public virtual string Describe()",
      message = "expected the open arm's `open fun` to render virtual; generatedCSharp:\n$csharp",
    )

    assertFalse(
      "virtual string Label()" in csharp,
      "a final `fun` on an open arm is not overridable in Kotlin and must not advertise " +
          "overridability; generatedCSharp:\n$csharp",
    )
    assertContains(csharp, "public string Label()")
    assertFalse(
      "virtual string Material" in csharp,
      "a final `val` on an open arm must not advertise overridability; generatedCSharp:\n$csharp",
    )

    assertContains(
      csharp,
      "public class HighPerch : Roost.Perch",
      message = "expected the subclass of a nested arm to spell its base by nested C# name; " +
          "generatedCSharp:\n$csharp",
    )
    assertContains(
      csharp,
      "public override int Height",
      message = "expected HighPerch's `override val` to render override; generatedCSharp:\n$csharp",
    )
    assertContains(
      csharp,
      "public override string Describe()",
      message = "expected HighPerch's `override fun` to render override; generatedCSharp:\n$csharp",
    )

    // ADR-009 open question 2, pinned rather than fixed: the discriminator is over *direct* arms
    // only, because the Kotlin `when (obj)` behind `roost_get_type` is. A `HighPerch` handle
    // therefore reconstructs as a `Perch` wrapper; the handle is still the `HighPerch` instance
    // and Kotlin dispatch stays virtual, so a call through it reaches `HighPerch.describe()`.
    // Widening this switch would reopen the flat-ordinal shape and is deliberately not done here.
    assertContains(
      csharp,
      "1 => new Perch(handle),",
      message = "expected the open arm to keep its direct-arm case; generatedCSharp:\n$csharp",
    )
    assertFalse(
      "new HighPerch(handle)," in csharp,
      "the sealed discriminator must stay over direct arms only; a further subclass of an open " +
          "arm materialises as the arm; generatedCSharp:\n$csharp",
    )
  }
}
