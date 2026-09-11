package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-075 amendment (2026-09-11): an exported abstract class that inherits an exported interface's
 * property without implementing it must still declare that property on itself, abstract, so the
 * generated `Bird : IFeathered` is not CS0535 and a subclass `override` has a base member to bind
 * to (CS0115).
 *
 * The sibling [Tier1AbstractPropertyTest] covers the class-own case, where the abstract property
 * is declared on the abstract class itself and keeps its plan (and therefore its `DllImport`
 * pair). Here the declaration lives on the interface: there is no plan for `Bird.plumage` at all,
 * so the C# declaration comes off the ADR-113 interface declaration catalog and carries no native
 * import. An import for an export that does not exist is exactly what the ADR-055 contract check
 * refuses.
 */
class Tier1AbstractInterfacePropertyTest {

  @Test
  fun `an inherited unimplemented interface property renders abstract on the class`() {
    val result = Tier1Harness.run(
      """
      package tier1.abstractinterfaceproperty

      interface Feathered {
        val plumage: String
        var perch: String
      }

      abstract class Bird(val name: String) : Feathered {
        fun describe(): String = "${'$'}name: ${'$'}plumage on ${'$'}perch"
      }

      class Finch : Bird("finch") {
        override val plumage: String = "brown"
        override var perch: String = "twig"
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected Feathered/Bird/Finch to compile; got: ${result.compileErrors}",
    )

    val csharp: String = result.generatedCSharp
    assertContains(
      csharp,
      "public abstract string Plumage { get; }",
      message = "expected the inherited read-only interface property to render abstract on Bird; " +
          "generatedCSharp:\n$csharp",
    )
    assertContains(
      csharp,
      "public abstract string Perch { get; set; }",
      message = "expected the inherited mutable interface property to render abstract with a " +
          "setter on Bird, so Finch's `override var` compiles; generatedCSharp:\n$csharp",
    )
    assertFalse(
      """EntryPoint = "bird_get_plumage"""" in csharp,
      "Bird declares the property but exports nothing for it: no native import may be emitted; " +
          "generatedCSharp:\n$csharp",
    )
    assertFalse(
      """EntryPoint = "bird_get_perch"""" in csharp,
      "Bird declares the property but exports nothing for it: no native import may be emitted; " +
          "generatedCSharp:\n$csharp",
    )

    // The subclass side is unchanged: it implements both members concretely.
    assertContains(csharp, "public override string Plumage")
    assertContains(csharp, "public override string Perch")
  }
}
