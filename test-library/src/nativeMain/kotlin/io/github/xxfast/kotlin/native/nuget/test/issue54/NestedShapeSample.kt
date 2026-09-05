package io.github.xxfast.kotlin.native.nuget.test.issue54

/**
 * Fixture for the **nested-class enclosing scope** bug: a sealed subclass declared *inside* its
 * sealed base is *declared* in C# as a nested class (`NestedShape.Circle`, ADR-009's
 * `CirSealedRenderer`), but at every member **type position** the forward classifier spells it
 * from the simple name alone — `global::TestLibrary.Issue54.Circle` — dropping the enclosing base.
 * That type does not exist, so the whole generated `Interop.cs` fails to compile with CS0246.
 * After the fix the same positions must spell `global::TestLibrary.Issue54.NestedShape.Circle`.
 *
 * Every mechanism the spelling travels through, once each — the point is not the fewest types but
 * the widest set of seams, because the classifier reaches the name from three different callers:
 * - [NestedShapeFactory.circle] — subclass at a **method return** (`new <T>(handle)` construction
 *   site as well as the return type),
 * - [NestedShapeFactory.unit] — subclass at a **property** position (the classifier's
 *   `csharpTypeNameFor` spelling, the same function [NestedShapeFactory.circle] and
 *   [NestedShapeFactory.radiusOf] go through),
 * - [NestedShapeFactory.radiusOf] — subclass at a **parameter** position (the handle is unwrapped
 *   back to Kotlin, so the name is spelled on the way in as well as out),
 * - [anyShape] — the **control**: the sealed *base* at a **top-level function** return, the one
 *   sealed-return position this repository already exercises (every sealed-base return in
 *   test-library is top-level), which spells correctly today and must stay green so a regression
 *   in the fix is distinguishable from the bug it repairs.
 *
 * [NestedShapeFactory.shapeOf] is the same sealed base at a **class method** return, a position no
 * fixture had ever covered. It is dropped from both the generated Kotlin exports and `Interop.cs`
 * with no `[nuget:SKIPPED_...]` diagnostic at all, so it is *not* asserted from C#; it stays here
 * as standing evidence of that silent drop, a defect separate from the spelling bug.
 *
 * Deliberately absent: no [NestedShape.Empty] at a member position. Whether an `OBJECT`-kind
 * handle is admitted at a return/property position was never verified either way, so putting it
 * here would mix an unknown into a known-red cell. `Empty` exists only to make [NestedShape] a
 * genuine two-arm sealed hierarchy, which is what makes `Circle` a *nested* declaration at all.
 *
 * Disjoint from its neighbour [Issue54Shape] in the same package: that one carries the sealed
 * **base** at property positions (ADR-105, scope (c)); this one carries a concrete **subclass** at
 * member positions. Sharing the namespace is deliberate — it proves two sealed hierarchies can
 * each own a nested `Circle` without the ADR-040 collision check firing, because both live under
 * their own enclosing type.
 *
 * The cats, as ever, supply the geometry: Oreo (black with the white middle) curls into a perfect
 * circle of radius whatever the sunbeam allows; Mylo (brown and creamy) refuses to be a shape.
 */
sealed class NestedShape {
  /** Oreo, curled up on the windowsill, described by one non-null `Double`. */
  data class Circle(val radius: Double) : NestedShape()

  /** Mylo, mid-sprawl, occupying no describable shape at all. */
  data object Empty : NestedShape()
}

/**
 * The cell under test: a plain exported class whose members are typed as the *nested* subclass
 * [NestedShape.Circle] at a return, a property and a parameter position, plus one control member
 * typed as the sealed base [NestedShape].
 */
class NestedShapeFactory {
  /** Return position: Oreo curls to order. */
  fun circle(radius: Double): NestedShape.Circle = NestedShape.Circle(radius)

  /** Property position: the unit circle, Oreo at his most compact. */
  val unit: NestedShape.Circle = NestedShape.Circle(1.0)

  /** Parameter position: measure a circle the consumer hands back. */
  fun radiusOf(circle: NestedShape.Circle): Double = circle.radius

  /**
   * The sealed **base** at a *class method* return. Named `shapeOf` rather than `any` so that no
   * `Any`/`any` name filter can be mistaken for the cause of the drop. Not asserted from C#; see
   * the class KDoc.
   */
  fun shapeOf(radius: Double): NestedShape = NestedShape.Circle(radius)
}

/**
 * Control: the sealed **base** at a top-level function return (ADR-007 puts it on the static class
 * `NestedShapeSample`), the position that works today. Always answers with a [NestedShape.Circle],
 * so C# can assert the ADR-009 discriminator still lands on the nested subclass — Oreo, curled.
 */
fun anyShape(radius: Double): NestedShape = NestedShape.Circle(radius)
