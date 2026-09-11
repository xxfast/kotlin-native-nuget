package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-101 / ADR-075 amendment (2026-09-11): the abstract *method* walk decides `abstract` by the
 * body, not by the declaring class.
 *
 * Before this, the walk asked `parentDeclaration == cls || OVERRIDE` and got both halves wrong:
 * a class's own `abstract fun` was treated as implemented and dropped from C# entirely (a
 * subclass's `override` was then CS0115), while an *inherited* member with a body the planner
 * declined was treated as unimplemented and rendered `public abstract` (CS0534 on any further
 * C# subclass).
 */
class Tier1AbstractMethodTest {

  @Test
  fun `a class declared abstract fun renders as an abstract C sharp method`() {
    val result = Tier1Harness.run(
      """
      package tier1.abstractmethod

      abstract class Vehicle(val plate: String) {
        abstract fun honk(): String
        fun describe(): String = "${'$'}plate says ${'$'}{honk()}"
      }

      class Truck(plate: String) : Vehicle(plate) {
        override fun honk(): String = "HONK"
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected Vehicle/Truck to compile; got: ${result.compileErrors}",
    )

    val csharp: String = result.generatedCSharp
    assertContains(
      csharp,
      "public abstract string Honk();",
      message = "expected the class-declared `abstract fun` to render abstract and bodiless; " +
          "generatedCSharp:\n$csharp",
    )
    assertContains(
      csharp,
      "public override string Honk()",
      message = "expected the subclass to keep its override; generatedCSharp:\n$csharp",
    )
    // Control: an implemented method on the same abstract base stays concrete.
    assertFalse(
      "abstract string Describe" in csharp,
      "an implemented method on an abstract base stays concrete; generatedCSharp:\n$csharp",
    )
  }

  @Test
  fun `an inherited body the planner declines is dropped rather than rendered abstract`() {
    val result = Tier1Harness.run(
      """
      package tier1.abstractmethodrefused

      interface Register {
        fun <T> tally(row: T): T = row
      }

      abstract class Vault : Register

      class StrongRoom : Vault()
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected Register/Vault/StrongRoom to compile; got: ${result.compileErrors}",
    )

    val csharp: String = result.generatedCSharp
    val abstractTally: List<String> = csharp.lines()
      .filter { "abstract" in it && "Tally" in it }
    assertTrue(
      abstractTally.isEmpty(),
      "an inherited member the planner declined must be dropped, not rendered abstract; got " +
          "$abstractTally in generatedCSharp:\n$csharp",
    )
    assertContains(
      csharp,
      ": IRegister",
      message = "the interface must stay in the base list even with the member dropped; " +
          "generatedCSharp:\n$csharp",
    )
  }
}
