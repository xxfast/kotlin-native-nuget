package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-075 amendment: the unexported abstract BASE CLASS cell, the base-class twin of
 * [Tier1AbstractUnexportedInterfacePropertyTest].
 *
 * `Roost` sits in a package that is neither `nuget.rootPackage` nor a subpackage of it, so ADR-101
 * drops `: Roost()` from `Nester`'s C# base list and re-homes the base's members onto `Nester`.
 * The declaration catalog was planned over interface supertypes only, so `inheritedAbstractProperty`
 * missed and `emitInheritedAbstractPropertySkip`'s owner guard refused to say so: `material` and
 * `height` vanished while `Wren`'s `override val` still rendered `public override` -- CS0115 in the
 * generated file itself. `speak()` is the control: the abstract METHOD walk carries no owner-kind
 * guard and already declares on `Nester`.
 *
 * `Roost.Lining` is a NESTED class, unbridgeable however the fixture grows (the ADR-066 closure
 * cannot admit a nested class), so its property must be skipped NAMED at the site where the member
 * is missing, i.e. on the abstract class (`Nester.lining`), beside the concrete subclass's
 * pre-existing `Wren.lining` skip.
 */
class Tier1AbstractUnexportedBasePropertyTest {

  // Two files: a Kotlin file declares one package, and the base class must live in a package
  // OUTSIDE the export root. `tier1.hiddenroost` is a sibling of `tier1.nester`, not a subpackage,
  // so no rootPackage prefix match can pull it into the export set.
  private val sources: Map<String, String> = mapOf(
    "Roost.kt" to """
      package tier1.hiddenroost

      abstract class Roost {
        abstract val material: String
        abstract var height: Int
        abstract val lining: Lining
        abstract fun speak(): String

        class Lining(val fibre: String = "down")
      }
    """.trimIndent(),
    "Nester.kt" to """
      package tier1.nester

      import tier1.hiddenroost.Roost

      abstract class Nester : Roost() {
        fun describe(): String = "${'$'}material@${'$'}height"
      }

      class Wren : Nester() {
        override val material: String = "twig"
        override var height: Int = 3
        override val lining: Roost.Lining = Roost.Lining()
        override fun speak(): String = "cheep"
      }
    """.trimIndent(),
  )

  // A dropped INTERMEDIATE base: `Dinghy : Skiff : Vessel` with only `Skiff` outside the export
  // root, so `Dinghy` keeps a C# base (`Vessel`) and still has to declare `Skiff`'s abstract
  // member itself. `getAllSuperTypes()` is transitive, so the catalog has to reach `Skiff` through
  // a kept hop rather than only through a direct supertype.
  private val transitiveSources: Map<String, String> = mapOf(
    "Skiff.kt" to """
      package tier1.hiddenyard

      import tier1.dock.Vessel

      abstract class Skiff : Vessel() {
        abstract val sail: String
      }
    """.trimIndent(),
    "Dock.kt" to """
      package tier1.dock

      import tier1.hiddenyard.Skiff

      abstract class Vessel {
        fun hull(): String = "oak"
      }

      abstract class Dinghy : Skiff()

      class Rowboat : Dinghy() {
        override val sail: String = "canvas"
      }
    """.trimIndent(),
  )

  @Test
  fun `an inherited unimplemented property from an unexported abstract base renders abstract on the class`() {
    val result = Tier1Harness.run(
      sources,
      processorOptions = mapOf("nuget.rootPackage" to "tier1.nester"),
    )

    assertTrue(
      result.compiledClean,
      "expected Roost/Nester/Wren to compile; got: ${result.compileErrors}",
    )

    val csharp: String = result.generatedCSharp

    // The fixture must genuinely exercise the UNEXPORTED case: if `Roost` ever gets exported, this
    // becomes an ordinary base-class cell and proves nothing new.
    assertFalse(
      "class Roost" in csharp,
      "Roost is outside the export root: no class may be rendered for it; generatedCSharp:\n$csharp",
    )
    assertTrue(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_UNEXPORTED_SUPERTYPE.name) &&
            it.contains("tier1.hiddenroost.Roost")
      },
      "expected ADR-101's SKIPPED_UNEXPORTED_SUPERTYPE naming the dropped base class, which is " +
          "what makes this the unexported cell; kspWarnings=${result.kspWarnings}",
    )

    assertContains(
      csharp,
      "public abstract string Material { get; }",
      message = "expected the inherited read-only property of the UNEXPORTED base to render " +
          "abstract on Nester; generatedCSharp:\n$csharp",
    )
    assertContains(
      csharp,
      "public abstract int Height { get; set; }",
      message = "expected the inherited mutable property to render abstract with a setter on " +
          "Nester, so Wren's `override var` compiles (CS0546/CS0534); generatedCSharp:\n$csharp",
    )

    // The control: the abstract method half already declares on Nester and must keep doing so.
    assertContains(csharp, "public abstract string Speak();")

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
      "Lining" in csharp.withoutDocComments(),
      "a nested-class-typed property is unbridgeable: no member may be rendered for it on the " +
          "abstract class or the subclass; generatedCSharp:\n$csharp",
    )
  }

  @Test
  fun `an unbridgeable inherited base property is skipped named on the abstract class`() {
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

  @Test
  fun `a property declared by a dropped intermediate base renders abstract on the kept subclass`() {
    val result = Tier1Harness.run(
      transitiveSources,
      processorOptions = mapOf("nuget.rootPackage" to "tier1.dock"),
    )

    assertTrue(
      result.compiledClean,
      "expected Vessel/Skiff/Dinghy/Rowboat to compile; got: ${result.compileErrors}",
    )

    val csharp: String = result.generatedCSharp

    assertFalse(
      "class Skiff" in csharp,
      "Skiff is outside the export root: no class may be rendered for it; generatedCSharp:\n$csharp",
    )
    assertContains(
      csharp,
      "public abstract string Sail { get; }",
      message = "the dropped INTERMEDIATE base declares the member, and `getAllSuperTypes()` is " +
          "transitive, so Dinghy must declare it; generatedCSharp:\n$csharp",
    )
    assertContains(csharp, "public override string Sail")
  }
}
