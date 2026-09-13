package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-075 amendment (2026-09-13): the UNEXPORTED-interface cell of the sibling
 * [Tier1AbstractInterfacePropertyTest], which pins the exported one.
 *
 * `Nesting` sits in a package that is neither `nuget.rootPackage` nor a subpackage of it, so
 * ADR-101 drops `: Nesting` from `Nester`'s C# base list and the ADR-113 interface declaration
 * catalog holds no plan for its members (only *exported* interfaces are planned onto it). Today
 * `inheritedAbstractProperty` therefore finds nothing and drops `material`/`height` silently,
 * while `Wren`'s `override val` still renders `public override` -- CS0115 in the generated file
 * itself, the same blast radius the exported case had before ADR-075's 2026-09-11 amendment.
 *
 * `Nesting.lining` is typed with a NESTED class: unbridgeable however the fixture grows (the
 * ADR-066 closure cannot admit a nested class, so it cannot quietly become exported the way a
 * top-level one reached through `Wren.lining` could). It must be skipped NAMED, not crash.
 *
 * Diagnostic contract asserted here, the one the implementation has to honour: the miss is named
 * at the site where the member is missing, i.e. on the abstract CLASS (`Nester.lining`), not on
 * the unexported interface. That is research recommendation A's `"${'$'}name.${'$'}propName"`
 * declaration, and it puts the class's skip beside the concrete subclass's pre-existing
 * `Wren.lining` skip so an author reads the pair together.
 */
class Tier1AbstractUnexportedInterfacePropertyTest {

  // Two files: a Kotlin file declares one package, and the interface must live in a package
  // OUTSIDE the export root. `tier1.hiddennest` is a sibling of `tier1.nester`, not a subpackage,
  // so no rootPackage prefix match can pull it into the export set.
  private val sources: Map<String, String> = mapOf(
    "Nesting.kt" to """
      package tier1.hiddennest

      interface Nesting {
        val material: String
        var height: Int
        val lining: Lining

        class Lining(val fibre: String = "down")
      }
    """.trimIndent(),
    "Nester.kt" to """
      package tier1.nester

      import tier1.hiddennest.Nesting

      abstract class Nester : Nesting {
        fun describe(): String = "${'$'}material@${'$'}height"
      }

      class Wren : Nester() {
        override val material: String = "twig"
        override var height: Int = 3
        override val lining: Nesting.Lining = Nesting.Lining()
      }
    """.trimIndent(),
  )

  @Test
  fun `an inherited unimplemented property from an unexported interface renders abstract on the class`() {
    val result = Tier1Harness.run(
      sources,
      processorOptions = mapOf("nuget.rootPackage" to "tier1.nester"),
    )

    assertTrue(
      result.compiledClean,
      "expected Nesting/Nester/Wren to compile; got: ${result.compileErrors}",
    )

    val csharp: String = result.generatedCSharp

    // The fixture must genuinely exercise the UNEXPORTED case: if `Nesting` ever gets exported,
    // this becomes the already-shipped exported cell and proves nothing new.
    assertFalse(
      "INesting" in csharp,
      "Nesting is outside the export root: no interface may be rendered or referenced for it; " +
          "generatedCSharp:\n$csharp",
    )
    assertTrue(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_UNEXPORTED_SUPERTYPE.name) &&
            it.contains("tier1.hiddennest.Nesting")
      },
      "expected ADR-101's SKIPPED_UNEXPORTED_SUPERTYPE naming the dropped interface, which is " +
          "what makes this the unexported cell; kspWarnings=${result.kspWarnings}",
    )

    assertContains(
      csharp,
      "public abstract string Material { get; }",
      message = "expected the inherited read-only property of the UNEXPORTED interface to render " +
          "abstract on Nester; generatedCSharp:\n$csharp",
    )
    assertContains(
      csharp,
      "public abstract int Height { get; set; }",
      message = "expected the inherited mutable property to render abstract with a setter on " +
          "Nester, so Wren's `override var` compiles (CS0546/CS0534); generatedCSharp:\n$csharp",
    )

    // Nester declares the members but exports nothing for them: an import for an export that does
    // not exist is exactly what the ADR-055 contract check refuses.
    assertFalse(
      """EntryPoint = "nester_get_material"""" in csharp,
      "no native import may be emitted for a declaration-only member; generatedCSharp:\n$csharp",
    )
    assertFalse(
      """EntryPoint = "nester_get_height"""" in csharp,
      "no native import may be emitted for a declaration-only member; generatedCSharp:\n$csharp",
    )

    // The subclass side is what fails CS0115 today: it must keep rendering `override`, now with a
    // base member to bind to.
    assertContains(csharp, "public override string Material")
    assertContains(csharp, "public override int Height")

    // The unbridgeable slot reaches neither class.
    assertFalse(
      "Lining" in csharp,
      "a nested-class-typed property is unbridgeable: no member may be rendered for it on the " +
          "abstract class or the subclass; generatedCSharp:\n$csharp",
    )
  }

  @Test
  fun `an unbridgeable inherited property is skipped named on the abstract class`() {
    val result = Tier1Harness.run(
      sources,
      processorOptions = mapOf("nuget.rootPackage" to "tier1.nester"),
    )

    assertTrue(
      result.compiledClean,
      "expected no broken source for Nester.lining; got: ${result.compileErrors}",
    )

    val missSkips: List<String> = result.kspWarnings.filter {
      it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY.name) &&
          it.contains("Nester.lining")
    }
    assertEquals(
      1,
      missSkips.size,
      "expected exactly one SKIPPED_UNSUPPORTED_PROPERTY naming Nester.lining -- the member is " +
          "missing from the abstract class, so the class is where the author has to be told, and " +
          "one line per missing member is the contract; kspWarnings=${result.kspWarnings}",
    )

    // The concrete subclass's own dropped `override val` keeps its pre-existing skip: the author
    // sees the pair, not a lone half.
    assertTrue(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY.name) &&
            it.contains("Wren.lining")
      },
      "expected the planner's pre-existing skip for the subclass's own unbridgeable override; " +
          "kspWarnings=${result.kspWarnings}",
    )
  }
}
