package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-131: a `suspend fun` whose return is the sealed **base** (`suspend fun nextLater(): Shape`)
 * completes its `TaskCompletionSource<Shape>` through the generated discriminator,
 * `t.SetResult(Shape.FromHandle(resultPtr));`.
 *
 * ADR-118's amendment fixed the nested *arm* spelling (`Task<Shape.Circle>` /
 * `new Shape.Circle(resultPtr)`) and split this row out: an arm name resolves to a concrete
 * constructor, a base name does not. ADR-009 renders the base as `public abstract class Shape`, so
 * the shipped `new Shape(resultPtr)` is **CS0144, and the whole `Interop.cs` fails**, not just
 * that member -- the consumer's `dotnet build` died with six of them on the sample library.
 *
 * The Kotlin half is untouched, and that is the point: the suspend export already pins
 * `NugetHandles.retain(result)` on the *runtime* object, i.e. the concrete arm, exactly as the
 * ADR-105 plan route does, and `FromHandle` takes ownership of that same handle.
 *
 * Cells, one per spelling site plus two controls:
 * - `ShapeFactory.nextLater` -- the base on an ordinary class, the class route's speller;
 * - `ShapeFactory.nextOrNullLater` -- the **nullable** twin, whose completion guards the wire
 *   pointer before discriminating (the renderer's `resultPtr == IntPtr.Zero ? null : new T(...)`
 *   branch is the same defect one step up);
 * - `anyShapeLater` -- the **top-level** route, which owns a second copy of the same decision, so
 *   a fix applied to the class route alone still leaves this one broken;
 * - `pulseLater` -- the **sealed interface** twin (ADR-112/125): an eligible sealed interface is
 *   rendered by the same `CirSealedRenderer` as an abstract class with the same `FromHandle`, so
 *   it is the same defect and the same fix, not a second mechanism;
 * - `circleLater` -- the negative control: an **arm** return must keep ADR-118's
 *   `new Shape.Circle(resultPtr)`, since a concrete arm has that constructor and routing it
 *   through the base would lose the static type the author asked for;
 * - `oddLater` -- an **ineligible** sealed interface (`Odd` carries a second superclass, so no C#
 *   discriminator can exist). It has neither `FromHandle` nor a constructor, so it must skip *by
 *   name* through the existing `SKIPPED_UNSUPPORTED_RETURN` gate rather than render C# against a
 *   type nothing declares, which is what it did before this change.
 *
 * `libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore)` is load-bearing: without coroutines on
 * the KSP libraries path there is no suspend surface to project and every assertion below passes
 * for the wrong reason.
 *
 * Oreo answers with a different arm than the one you asked; Mylo answers with nothing at all.
 */
class Tier1SuspendSealedBaseReturnTest {

  private val fixture: String = """
    package tier1.suspendsealedbase

    sealed class Shape {
      data class Circle(val radius: Int) : Shape()

      data object Dot : Shape()
    }

    sealed interface Pulse {
      data class Beat(val bpm: Int) : Pulse

      data object Flat : Pulse
    }

    /**
     * The second superclass that makes `Mixed` ineligible: the shape of the hierarchy, rather than
     * an unexported supertype, which would be a different skip with a different diagnostic.
     */
    open class Rhythm {
      fun tempo(): String = "steady"
    }

    sealed interface Mixed {
      class Odd : Rhythm(), Mixed
    }

    class ShapeFactory {
      // The cell: the sealed base at a suspend return, answering with an arm chosen at runtime.
      suspend fun nextLater(radius: Int): Shape =
        if (radius > 0) Shape.Circle(radius) else Shape.Dot

      // The nullable twin.
      suspend fun nextOrNullLater(radius: Int): Shape? = if (radius < 0) null else Shape.Dot

      // The negative control: an arm return keeps ADR-118's concrete constructor.
      suspend fun circleLater(radius: Int): Shape.Circle = Shape.Circle(radius)

      // The sealed-interface twin.
      suspend fun pulseLater(bpm: Int): Pulse = if (bpm == 0) Pulse.Flat else Pulse.Beat(bpm)

      // The refusal: an ineligible sealed interface has no C# mapping at all.
      suspend fun oddLater(): Mixed = Mixed.Odd()
    }

    // The second route: the same base return from a top-level suspend fun (ADR-007 static class).
    suspend fun anyShapeLater(radius: Int): Shape = Shape.Circle(radius)
  """.trimIndent()

  private fun run(): Tier1Result = Tier1Harness.run(
    fixture,
    fileName = "ShapeSample.kt",
    libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
  )

  /** The Kotlin half is deliberately unchanged, so this is the control for "did anything move". */
  @Test
  fun `a sealed base suspend return compiles clean on the Kotlin half`() {
    val result = run()

    assertTrue(
      result.compiledClean,
      "expected the generated suspend exports to compile; got: " +
          "${result.compileErrors} ${result.kspErrors}",
    )
  }

  /** The row itself: `Task<Shape>` completed through the discriminator, never `new Shape(...)`. */
  @Test
  fun `a sealed base suspend return completes through FromHandle`() {
    val result = run()

    assertContainsCSharp(result, "Task<Shape> NextLaterAsync", "Task<Shape>")
    assertContainsCSharp(result, "t.SetResult(Shape.FromHandle(resultPtr));", "Shape.FromHandle")
  }

  /**
   * CS0144 itself. `new Shape(resultPtr)` against `public abstract class Shape` is the failure this
   * file exists for, and it must not survive anywhere in the file: one occurrence fails the
   * consumer's whole build, not merely that member.
   */
  @Test
  fun `the abstract base is never constructed`() {
    val result = run()

    assertFalse(
      result.generatedCSharp.contains("new Shape(resultPtr)"),
      "expected no `new Shape(resultPtr)` against the abstract base; got: " +
          "${csharpLinesFor(result, "new Shape(")}",
    )
    assertFalse(
      result.generatedCSharp.contains("new Pulse(resultPtr)"),
      "expected no `new Pulse(resultPtr)` against the abstract sealed-interface base; got: " +
          "${csharpLinesFor(result, "new Pulse(")}",
    )
  }

  /**
   * Issue #108's guard, kept: a null result travels as a null pointer, so the completion tests the
   * wire before discriminating rather than asking `FromHandle` to read `IntPtr.Zero`.
   */
  @Test
  fun `a nullable sealed base guards the wire pointer before discriminating`() {
    val result = run()

    assertContainsCSharp(result, "Task<Shape?> NextOrNullLaterAsync", "Task<Shape?>")
    assertContainsCSharp(
      result,
      "t.SetResult(resultPtr == IntPtr.Zero ? null : Shape.FromHandle(resultPtr));",
      "IntPtr.Zero ? null :",
    )
  }

  /**
   * The top-level route spells its own return, so a fix in `CirClassTranslator` alone leaves this
   * one completing with `new Shape(resultPtr)`. Counted rather than `contains`-ed, so all three
   * base cells are proven instead of one of them satisfying the assertion for the other two.
   */
  @Test
  fun `both suspend routes read the base through FromHandle`() {
    val result = run()

    assertContainsCSharp(result, "Task<Shape> AnyShapeLaterAsync", "AnyShapeLaterAsync")

    assertEquals(
      3,
      Regex(Regex.escape("Shape.FromHandle(resultPtr)")).findAll(result.generatedCSharp).count(),
      "expected the base read in all three cells (class, nullable class, top-level); got: " +
          "${csharpLinesFor(result, "FromHandle(resultPtr)")}",
    )
  }

  /** ADR-112/125: an eligible sealed *interface* base is the same abstract class, same fix. */
  @Test
  fun `an eligible sealed interface base reads through FromHandle too`() {
    val result = run()

    assertContainsCSharp(result, "Task<Pulse> PulseLaterAsync", "Task<Pulse>")
    assertContainsCSharp(result, "t.SetResult(Pulse.FromHandle(resultPtr));", "Pulse.FromHandle")
  }

  /**
   * The negative control. ADR-118's arm spelling must be untouched: a concrete arm has the
   * single-IntPtr constructor, and routing it through the base's discriminator would add a
   * pointless type test and hand back a less specific type than the author declared.
   */
  @Test
  fun `a sealed arm return still constructs the arm directly`() {
    val result = run()

    assertContainsCSharp(result, "t.SetResult(new Shape.Circle(resultPtr));", "new Shape.Circle")
  }

  /**
   * The ineligible sealed type: no `FromHandle`, no constructor, no C# type at all. Before this
   * change it rendered against a name nothing declares; now it skips through the gate both halves
   * already honour, and skips from *both* halves, so no dead export is left behind either.
   */
  @Test
  fun `an ineligible sealed base at a suspend return is refused by name`() {
    val result = run()

    assertFalse(
      result.generated.contains("shape_factory_odd_later_async"),
      "expected no Kotlin export for the refused return; got: " +
          "${result.generated.lines().filter { it.contains("odd_later") }.map(String::trim)}",
    )
    assertFalse(
      result.generatedCSharp.contains("OddLaterAsync"),
      "expected no C# member for the refused return; got: ${csharpLinesFor(result, "OddLater")}",
    )

    val named: List<String> = result.kspWarnings.filter {
      it.contains("[nuget:${ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN.name}]") &&
          it.contains("oddLater")
    }

    assertEquals(
      1,
      named.size,
      "expected exactly one SKIPPED_UNSUPPORTED_RETURN naming oddLater rather than a silent " +
          "vanish or a doubled warning; kspWarnings=${result.kspWarnings}",
    )
    assertTrue(
      named.single().contains("Mixed"),
      "expected the diagnostic to spell the offending return type; got: ${named.single()}",
    )
  }

  private fun assertContainsCSharp(result: Tier1Result, needle: String, context: String) {
    assertTrue(
      result.generatedCSharp.contains(needle),
      "expected `$needle` in the generated C#; got: ${csharpLinesFor(result, context)}",
    )
  }

  private fun csharpLinesFor(result: Tier1Result, needle: String): List<String> =
    result.generatedCSharp.lines().filter { it.contains(needle) }.map(String::trim)
}
