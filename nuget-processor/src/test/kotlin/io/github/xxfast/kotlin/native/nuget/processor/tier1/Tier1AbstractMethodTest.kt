package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
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

  /**
   * The walk hand-spelled every non-enum class-typed position by its bare simple name, which only
   * ever compiled because the shipped fixtures typed their abstract members `String`. Three cells,
   * one per way the bare name is wrong, all on one abstract class so one generated file proves
   * them together:
   *
   *  - `describe(): other.Other`, exported but in another namespace. The generated file carries
   *    only the `System` usings, so a bare `Other` names nothing: must be `global::`-qualified.
   *  - `part(p: Owner.Part): Owner.Part`, a nested declared class. `Part` exists only as
   *    `Owner.Part`, and the parameter arm is a separate hand-spelling from the return arm, so a
   *    return-only fix leaves half of this red.
   *  - `lining(): hidden.Nesting.Lining`, declared outside the export scope. Nothing declares it
   *    in C#, so the member is dropped with one named skip rather than spelled at all.
   */
  @Test
  fun `an abstract method spells a class-typed position or refuses it named`() {
    val result = Tier1Harness.run(
      sources = mapOf(
        "Other.kt" to """
        package tier1.garage.other

        class Other(val tag: String = "x")
        """.trimIndent(),
        "Hidden.kt" to """
        package tier1.hidden

        class Nesting {
          class Lining(val kind: String = "fleece")
        }
        """.trimIndent(),
        "Garage.kt" to """
        package tier1.garage

        import tier1.garage.other.Other
        import tier1.hidden.Nesting

        class Owner {
          class Part(val n: Int = 1)
        }

        abstract class Vehicle {
          abstract fun describe(): Other
          abstract fun part(p: Owner.Part): Owner.Part
          abstract fun lining(): Nesting.Lining
        }

        class Truck : Vehicle() {
          override fun describe(): Other = Other()
          override fun part(p: Owner.Part): Owner.Part = p
          override fun lining(): Nesting.Lining = Nesting.Lining()
        }
        """.trimIndent(),
      ),
      processorOptions = mapOf("nuget.rootPackage" to "tier1.garage"),
    )

    assertTrue(
      result.compiledClean,
      "expected the fixture to compile; got: ${result.compileErrors}",
    )

    val csharp: String = result.generatedCSharp
    assertTrue(
      Regex("""public abstract global::[\w.]*Other\.Other Describe\(\);""").containsMatchIn(csharp),
      "expected the cross-namespace abstract return to be fully qualified; " +
          "generatedCSharp:\n$csharp",
    )
    assertTrue(
      Regex("""public abstract global::[\w.]*Owner\.Part Part\(global::[\w.]*Owner\.Part p\);""")
        .containsMatchIn(csharp),
      "expected the nested class to be spelled through its owner at BOTH positions; " +
          "generatedCSharp:\n$csharp",
    )
    assertFalse(
      "Lining Lining(" in csharp,
      "expected the undeclared nested class to be dropped, not spelled; generatedCSharp:\n$csharp",
    )

    // The walk names the member by the C# class it was dropped from (`Vehicle.lining`), the same
    // shape the shipped enum skip uses; the concrete `Truck.lining` override is the planner's own
    // separate skip.
    val liningWarnings: List<String> = result.kspWarnings
      .filter { "Vehicle.lining" in it }
    assertEquals(
      1,
      liningWarnings.size,
      "expected exactly one named skip for the dropped abstract member; " +
          "kspWarnings=${result.kspWarnings}",
    )
    assertContains(
      liningWarnings.single(),
      ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_TYPE.name,
      message = "expected the class reason's own kind, the same one the planner route emits",
    )
    assertContains(
      liningWarnings.single(),
      "Lining",
      message = "expected the skip to name the type that has no C# declaration",
    )
  }

  /**
   * The three arms the class cells above do not reach: a collection (today `List Items()`, which
   * is CS0246/CS0305), a class's own type parameter (ADR-147 made `T` a real name on the generic
   * carrier, and the carrier is exactly where this walk renders), and an unspellable type at a
   * PARAMETER, whose skip is a separate branch from the return's.
   */
  @Test
  fun `an abstract method spells a collection and a carrier type parameter and refuses a parameter`() {
    val result = Tier1Harness.run(
      """
      package tier1.abstractspellings

      abstract class Crate<T>(val id: String) {
        abstract fun tag(): T
        abstract fun items(): List<String>
        abstract fun log(error: Throwable)
      }
      """.trimIndent(),
      fileName = "AbstractSpellings.kt",
    )

    assertTrue(
      result.compiledClean,
      "expected the fixture to compile; got: ${result.compileErrors}",
    )

    val csharp: String = result.generatedCSharp
    assertContains(
      csharp,
      "public abstract T Tag();",
      message = "expected the generic carrier's own type parameter to be spelled; " +
          "generatedCSharp:\n$csharp",
    )
    assertContains(
      csharp,
      "public abstract IReadOnlyList<string> Items();",
      message = "expected the collection to take its public C# spelling; generatedCSharp:\n$csharp",
    )
    assertFalse(
      "Log(" in csharp,
      "expected the Throwable-typed parameter to drop the member; generatedCSharp:\n$csharp",
    )
    assertTrue(
      result.kspWarnings.any { "Crate.log" in it && "Throwable" in it },
      "expected the parameter-position skip to name the member and the type; " +
          "kspWarnings=${result.kspWarnings}",
    )
  }

  /**
   * The parameter arm carried nullability for `String` only, so a `Foo?` parameter rendered `Foo`
   * while the concrete subclass's planned override spells `Foo?`: a nullable-annotation drift
   * between an abstract declaration and its own override.
   */
  @Test
  fun `an abstract method keeps nullability on a class-typed parameter`() {
    val result = Tier1Harness.run(
      """
      package tier1.nullableabstract

      class Foo(val tag: String = "x")

      abstract class Sorter {
        abstract fun sort(foo: Foo?): Foo?
      }

      class Bin : Sorter() {
        override fun sort(foo: Foo?): Foo? = foo
      }
      """.trimIndent(),
      fileName = "NullableAbstract.kt",
    )

    assertTrue(
      result.compiledClean,
      "expected the fixture to compile; got: ${result.compileErrors}",
    )

    val csharp: String = result.generatedCSharp
    assertTrue(
      Regex("""public abstract [\w:.]*Foo\? Sort\([\w:.]*Foo\? foo\);""").containsMatchIn(csharp),
      "expected the nullable class parameter to keep its `?`; generatedCSharp:\n$csharp",
    )
  }
}
