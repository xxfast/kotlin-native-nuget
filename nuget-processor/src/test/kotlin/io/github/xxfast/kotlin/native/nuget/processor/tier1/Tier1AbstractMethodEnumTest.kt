package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The abstract-*method* walk's enum spelling gap. The walk in `CirClassTranslator` renders the C#
 * `abstract` member for a method an exported abstract class inherits and never implements. It had
 * no classifier call at all: an enum parameter was spelled by bare simple name (`isEnum ->
 * kotlinType`) and an enum return fell through the same `else -> methodReturn`, so
 *
 * - a **declared** enum rendered `Glaze` instead of the classifier's `global::<ns>.Glaze`, the
 *   spelling `ForwardBridgeTypeClassifier` says "must never drift", and
 * - an **undeclared** enum (nested, or outside the export scope) rendered a reference to a type
 *   nothing declares, with no diagnostic naming the member, because this walk is the only route
 *   such a member has: `isForwardPlannableMemberOf` keeps an inherited unimplemented member out of
 *   the planner, so nothing else classifies it, skips it or warns about it.
 *
 * The rule is the 2026-09-05 gate's, applied to the one route that bypassed it: spell it through
 * the classifier, or drop the member with a named `SKIPPED_UNSUPPORTED_TYPE`. Never dangle.
 *
 * Asserted on the `abstract` line specifically, not on the enum's name anywhere in the file: the
 * interface projection of `Potter` spells its own members on its own route, and the two gaps must
 * fail independently.
 */
class Tier1AbstractMethodEnumTest {

  private val source: String = """
    package tier1.abstractenum

    enum class Glaze { MATTE, GLOSS }

    class Pottery {
      enum class Firing { BISQUE, GLOST }
    }

    interface Potter {
      fun fire(firing: Pottery.Firing): Pottery.Firing
      fun coat(glaze: Glaze): Glaze
      fun tint(glaze: Glaze?): Glaze?
      fun label(): String
    }

    abstract class Kiln : Potter {
      override fun label(): String = "kiln"
    }
  """.trimIndent()

  @Test
  fun `a declared enum on an inherited unimplemented method is spelled through the classifier`() {
    val result = Tier1Harness.run(source)

    assertTrue(
      result.compiledClean,
      "expected Glaze/Pottery/Potter/Kiln to compile; got: ${result.compileErrors}",
    )
    assertContains(
      result.generatedCSharp,
      "public abstract global::Interop.Glaze Coat(global::Interop.Glaze glaze);",
      message = "expected the abstract member to spell the declared enum the way the classifier " +
          "does, at both the return and the parameter; generatedCSharp=" +
          "${result.generatedCSharp.lines().filter { "abstract" in it }}",
    )
    // The nullable twin: `forwardPublicCsharpType()` renders the `?` off the classifier's
    // `Nullable` wrapper, so the walk never re-derives nullability for an enum.
    assertContains(
      result.generatedCSharp,
      "public abstract global::Interop.Glaze? Tint(global::Interop.Glaze? glaze);",
      message = "expected a nullable enum to keep the classifier's spelling plus the `?`; " +
          "generatedCSharp=${result.generatedCSharp.lines().filter { "abstract" in it }}",
    )
  }

  @Test
  fun `an undeclared nested enum drops the abstract member rather than dangling`() {
    val result = Tier1Harness.run(source)

    val dangling: List<String> = result.generatedCSharp.lines()
      .filter { "abstract" in it && "Fire" in it }
    assertTrue(
      dangling.isEmpty(),
      "an abstract member typed with an undeclared nested enum must be dropped, not emitted as a " +
          "dangling reference; got $dangling in generatedCSharp:\n${result.generatedCSharp}",
    )
  }

  @Test
  fun `the dropped abstract member skips named with the undeclared-enum reason`() {
    val result = Tier1Harness.run(source)

    val diagnostics: List<String> = result.kspWarnings.filter {
      it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_TYPE.name) && it.contains("Kiln.fire")
    }
    assertEquals(
      1,
      diagnostics.size,
      "expected exactly one SKIPPED_UNSUPPORTED_TYPE naming Kiln.fire; " +
          "kspWarnings=${result.kspWarnings}",
    )
    val diagnostic: String = diagnostics.single()
    assertTrue(
      diagnostic.contains("tier1.abstractenum.Pottery.Firing"),
      "expected the diagnostic to name the undeclared enum; got: $diagnostic",
    )
    assertTrue(
      diagnostic.contains("never declared as a C# enum") && diagnostic.contains("UNDECLARED_ENUM"),
      "expected the shared UNDECLARED_ENUM reason sentence; got: $diagnostic",
    )
    assertTrue(
      diagnostic.contains("move it to the top level"),
      "expected the shared UNDECLARED_ENUM hint; got: $diagnostic",
    )
  }

  /**
   * The two routes have to agree: `translateInterface` is plan-driven and drops `fire` from
   * `IPotter` already, so the walk dropping it from `Kiln` leaves the class implementable rather
   * than CS0535 against an interface member it no longer declares. (The interface's own drop is
   * silent, which is a separate gap: nothing feeds the declaration catalog's dropped callables to
   * `warnDroppedForwardCallables`. Out of scope here; the walk's diagnostic above is the one this
   * route owns.)
   */
  @Test
  fun `the interface projection drops the same member, so the class stays implementable`() {
    val result = Tier1Harness.run(source)

    assertContains(
      result.generatedCSharp,
      "public interface IPotter",
      message = "expected the declaring interface to still be projected; generatedCSharp=" +
          "${result.generatedCSharp.lines().filter { "IPotter" in it }}",
    )
    val fire: List<String> = result.generatedCSharp.lines().filter { "Fire(" in it }
    assertTrue(
      fire.isEmpty(),
      "expected neither route to declare the member typed with the undeclared enum; got $fire",
    )
  }
}
