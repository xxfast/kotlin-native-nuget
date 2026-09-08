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
 * [NestedShapeFactory.shapeOf] is the same sealed base at a **class method** return, the position
 * that used to be dropped from both the generated Kotlin exports and `Interop.cs` with no
 * `[nuget:SKIPPED_...]` diagnostic at all. It now plans on the ordinary member plan and binds
 * through the ADR-009 `NestedShape.FromHandle(IntPtr)` discriminator, so C# asserts it alongside
 * the top-level control.
 *
 * Deliberately absent: no [NestedShape.Empty] at a member position. Whether an `OBJECT`-kind
 * handle is admitted at a return/property position was never verified either way, so putting it
 * here would mix an unknown into a known-red cell. `Empty` exists only to make [NestedShape] a
 * genuine two-arm sealed hierarchy, which is what makes `Circle` a *nested* declaration at all.
 *
 * The second cell this file carries (issue #107): a `data object` sealed arm drops *every* C#
 * property, while the ADR-062 property planner and `SealedClassExports` both keep it, so
 * `ForwardAbiContract` aborts the KSP run on the orphan `nestedshape_empty_get_*` exports (it
 * checks names in sorted order, so `note` is the one it names first). Four cells, one per route:
 * - [NestedShape.sides], the reported repro shape: an `abstract val` on the sealed *base*. The
 *   base renders no C# member for it (`CirSealedClass` has no properties at all), so every
 *   assertion has to reach through a concrete arm.
 * - [NestedShape.Empty.sides], the bug: a **primitive** property on the `data object` arm, no
 *   conversion at the seam.
 * - [NestedShape.Circle.sides], the control: the same property on the `data class` arm, which
 *   binds today. It must stay bound, so a fix that restores parity is distinguishable from one
 *   that trades one broken arm for another.
 * - [NestedShape.Empty.note], a **reference**-typed property on the same `data object` arm: the
 *   cell that does need conversion at the seam. `Int` and `String` travel different plan routes.
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
  /**
   * The repro shape from issue #107: an abstract property on the sealed base. The base itself
   * renders no C# member for it, only the concrete arms below do.
   */
  abstract val sides: Int?

  /** Oreo, curled up on the windowsill, described by one non-null `Double`. */
  data class Circle(val radius: Double) : NestedShape() {
    /** Control: a circle has no sides, and the `data class` arm already binds this today. */
    override val sides: Int? = null
  }

  /** Mylo, mid-sprawl, occupying no describable shape at all. */
  data object Empty : NestedShape() {
    /** The bug: a primitive property on a `data object` arm, dropped from C# today. */
    override val sides: Int = 0

    /** The same arm at a reference type, the property that needs conversion at the seam. */
    val note: String = "sprawled"
  }
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
   * The sealed **base** at a *class method* return, the cell that must bind through
   * `NestedShape.FromHandle`. Named `shapeOf` rather than `any` so that no `Any`/`any` name filter
   * can be mistaken for the cause of the drop it used to suffer.
   */
  fun shapeOf(radius: Double): NestedShape = NestedShape.Circle(radius)
}

/**
 * Control: the sealed **base** at a top-level function return (ADR-007 puts it on the static class
 * `NestedShapeSample`), the position that works today. Always answers with a [NestedShape.Circle],
 * so C# can assert the ADR-009 discriminator still lands on the nested subclass — Oreo, curled.
 */
fun anyShape(radius: Double): NestedShape = NestedShape.Circle(radius)

/**
 * The only route C# has to the `data object` arm: the sealed **base** at a top-level return, the
 * same already-working seam [anyShape] uses, discriminating to [NestedShape.Empty] instead. The
 * member's type is the base, so this does not put `Empty` itself at a member position. Mylo,
 * sprawled, refusing geometry.
 */
fun emptyShape(): NestedShape = NestedShape.Empty
