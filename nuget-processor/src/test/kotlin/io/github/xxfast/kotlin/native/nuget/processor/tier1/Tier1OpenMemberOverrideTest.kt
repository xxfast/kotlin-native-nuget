package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-101 amendment (2026-09-10, methods 2026-09-11): a base class's *own* `open val` /
 * `open var` / `open fun` renders `virtual` in C#, so a subclass's `override` compiles instead
 * of `CS0506`.
 *
 * Before this, the only route to `virtual` was the `override && !final` arm (a class implementing
 * an interface member, pinned by `Tier1InterfaceReturnTest`), so a *declared* `open` member was
 * projected with no modifier at all and every subclass `override` of it was a C# build break.
 *
 * The same fixture pins the second half of the change: a concrete `open class` that has an
 * exported subclass must render `public virtual void Dispose()`, since the subclass's inherited
 * `Dispose` is always spelled `override`. Only abstract bases used to be overridable.
 */
class Tier1OpenMemberOverrideTest {

  /**
   * Truth-table rows 1, 5 and 6: an `open val` / `open var` / `open fun` declared on a base class
   * renders `virtual`, a final `val` and a final `fun` beside them do not, and the subclass keeps
   * `override`.
   */
  @Test
  fun `declared open members on a base class render virtual and the subclass renders override`() {
    val result = Tier1Harness.run(
      """
      package tier1.openmember

      open class Bed {
        open val softness: Int = 1
        open var occupant: String = "Oreo"
        val brand: String = "Catnap"

        fun describe(): String = brand

        open fun fluff(): String = occupant
      }

      class Hammock : Bed() {
        override val softness: Int = 9
        override var occupant: String = "Mylo"

        override fun fluff(): String = "swings"
      }
      """.trimIndent()
    )

    assertTrue(result.compiledClean, "expected Bed/Hammock to compile; got: ${result.compileErrors}")

    val csharp: String = result.generatedCSharp
    assertContains(
      csharp,
      "public virtual int Softness",
      message = "expected Bed's declared `open val` to render virtual so Hammock's override " +
          "compiles; generatedCSharp:\n$csharp",
    )
    assertContains(
      csharp,
      "public virtual string Occupant",
      message = "expected Bed's declared `open var` to render virtual; generatedCSharp:\n$csharp",
    )
    assertContains(
      csharp,
      "public string Brand",
      message = "expected Bed's final `val` to keep its shipped spelling; generatedCSharp:\n$csharp",
    )
    assertFalse(
      "virtual string Brand" in csharp,
      "a Kotlin `val` is final by default and must not advertise overridability; " +
          "generatedCSharp:\n$csharp",
    )
    assertContains(csharp, "public override int Softness")
    assertContains(csharp, "public override string Occupant")

    assertContains(
      csharp,
      "public virtual string Fluff()",
      message = "expected Bed's declared `open fun` to render virtual so Hammock's override " +
          "compiles; generatedCSharp:\n$csharp",
    )
    assertContains(
      csharp,
      "public override string Fluff()",
      message = "expected Hammock's `override fun` to render override; generatedCSharp:\n$csharp",
    )
    assertFalse(
      "virtual string Describe()" in csharp,
      "a Kotlin `fun` is final by default and must not advertise overridability; " +
          "generatedCSharp:\n$csharp",
    )
    assertContains(csharp, "public string Describe()")

    // The other half: a concrete open base has to make Dispose overridable, because the subclass
    // renders `public override void Dispose()` unconditionally (CS0506 otherwise).
    assertContains(
      csharp,
      "public virtual void Dispose()",
      message = "expected the open base Bed to render an overridable Dispose; " +
          "generatedCSharp:\n$csharp",
    )
    assertContains(csharp, "public override void Dispose()")
  }

  /**
   * Truth-table row 2, which no fixture covers: an `open val` / `open fun` declared on a class
   * that itself has an *exported* base. `isOverride` is gated on the superclass being exported, so
   * this member takes the `superClass != null` path with no `OVERRIDE` modifier, and must still
   * say `virtual`.
   */
  @Test
  fun `declared open members on a derived class with an exported base render virtual`() {
    val result = Tier1Harness.run(
      """
      package tier1.openmemberderived

      open class Bed {
        val brand: String = "Catnap"
      }

      open class Bunk : Bed() {
        open val ladder: Int = 2

        open fun climb(): String = "up"
      }

      class Loft : Bunk() {
        override val ladder: Int = 3

        override fun climb(): String = "up and over"
      }
      """.trimIndent()
    )

    assertTrue(result.compiledClean, "expected Bed/Bunk/Loft to compile; got: ${result.compileErrors}")

    val csharp: String = result.generatedCSharp
    assertContains(
      csharp,
      "public virtual int Ladder",
      message = "expected Bunk's declared `open val` to render virtual even though Bunk has an " +
          "exported base; generatedCSharp:\n$csharp",
    )
    assertContains(csharp, "public override int Ladder")

    assertContains(
      csharp,
      "public virtual string Climb()",
      message = "expected Bunk's declared `open fun` to render virtual even though Bunk has an " +
          "exported base; generatedCSharp:\n$csharp",
    )
    assertContains(csharp, "public override string Climb()")
  }

  /**
   * The final-class control for the Dispose half: a class with no `open` modifier and no exported
   * subclass keeps the shipped `public void Dispose()`, byte for byte.
   */
  @Test
  fun `a final class keeps a non-virtual Dispose`() {
    val result = Tier1Harness.run(
      """
      package tier1.finalmember

      class Cot {
        val brand: String = "Catnap"
      }
      """.trimIndent()
    )

    assertTrue(result.compiledClean, "expected Cot to compile; got: ${result.compileErrors}")

    val csharp: String = result.generatedCSharp
    assertContains(csharp, "public void Dispose()")
    assertFalse(
      "virtual void Dispose()" in csharp,
      "a final class has nothing that can override Dispose; generatedCSharp:\n$csharp",
    )
  }
}
