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

  /**
   * ADR-133 inverted this cell's premise: a nested `enum class` is now DECLARED as the C# nested
   * type `Pottery.Firing`, so the inherited unimplemented member is no longer dropped -- it is
   * emitted, spelled through the classifier with its enclosing scope. The gap this file owns is
   * unchanged: the walk must spell an enum the classifier's way, never by bare simple name.
   */
  @Test
  fun `a nested enum on an inherited unimplemented method is spelled with its enclosing scope`() {
    val result = Tier1Harness.run(source)

    assertContains(
      result.generatedCSharp,
      "public abstract global::Interop.Pottery.Firing Fire(global::Interop.Pottery.Firing firing);",
      message = "expected the abstract member to spell the nested enum `Outer.Inner`, qualified " +
          "exactly as the classifier does; generatedCSharp=" +
          "${result.generatedCSharp.lines().filter { "abstract" in it }}",
    )
  }

  /**
   * Was: exactly one `SKIPPED_UNSUPPORTED_TYPE` naming `Kiln.fire`. ADR-133 declares the nested
   * enum, so the member binds and nothing about it is skipped -- a surviving skip would mean the
   * walk still consults a membership gate the declaration set has moved past.
   */
  @Test
  fun `the member typed with the nested enum is no longer skipped`() {
    val result = Tier1Harness.run(source)

    val diagnostics: List<String> = result.kspWarnings.filter {
      it.contains("SKIPPED_") && it.contains("Kiln.fire")
    }
    assertEquals(
      0,
      diagnostics.size,
      "expected no skip for the now-declared nested enum; kspWarnings=${result.kspWarnings}",
    )
    assertTrue(
      result.kspWarnings.none {
        it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_TYPE.name) &&
            it.contains("tier1.abstractenum.Pottery.Firing")
      },
      "expected nothing to blame the nested enum any more; kspWarnings=${result.kspWarnings}",
    )
  }

  /**
   * The two routes still have to agree, with the sign flipped by ADR-133: `translateInterface` is
   * plan-driven and now projects `fire` onto `IPotter`, so the walk must declare it on `Kiln` too
   * -- an abstract class missing an interface member it inherits is CS0535 in the consumer.
   */
  @Test
  fun `the interface projection declares the same member, so the class stays implementable`() {
    val result = Tier1Harness.run(source)

    assertContains(
      result.generatedCSharp,
      "public interface IPotter",
      message = "expected the declaring interface to still be projected; generatedCSharp=" +
          "${result.generatedCSharp.lines().filter { "IPotter" in it }}",
    )
    val fire: List<String> = result.generatedCSharp.lines().filter { "Fire(" in it }
    assertTrue(
      fire.size >= 2,
      "expected both the interface and the abstract class to declare the member typed with the " +
          "now-declared nested enum; got $fire",
    )
  }
}
