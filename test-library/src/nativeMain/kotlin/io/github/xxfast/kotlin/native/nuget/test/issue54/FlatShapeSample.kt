package io.github.xxfast.kotlin.native.nuget.test.issue54

/**
 * Fixture for the **sibling sealed subclass** bug: a sealed subclass declared *beside* its sealed
 * base rather than inside it. [Label] is a subclass of [FlatShape], but it is a top-level
 * declaration in its own right, so today it is collected **twice**:
 * - once by the ordinary class route, as a namespace-level `TestLibrary.Issue54.Label` with
 *   `label_*` exports, and
 * - once by the ADR-009 sealed route, as a nested `TestLibrary.Issue54.FlatShape.Label` with
 *   `flatshape_label_*` exports.
 *
 * One Kotlin type, two C# types. Any `is`/`IsType` check disagrees with itself depending on which
 * of the two the caller happens to hold, and the sealed discriminator can only ever hand back the
 * nested one. After the fix the sealed route is the sole owner and a *sibling* subclass is declared
 * at namespace level (`public sealed class Label : FlatShape`), while a subclass that really is
 * nested in Kotlin stays nested in C# ([FlatShape.Circle]).
 *
 * Both declaration positions in one hierarchy on purpose, because the fix has to keep them apart:
 * - [Label] is the sibling, the cell under test,
 * - [FlatShape.Circle] is the nested control, which is already correct today and must stay nested.
 *
 * Every seam the two names travel through, once each:
 * - [FlatShapeFactory.label] carries the sibling subclass at a **method return**, the position that
 *   picks a concrete C# type and a `new <T>(handle)` construction site,
 * - [FlatShapeFactory.circle] carries the nested subclass at a **property**, the control spelling,
 * - [FlatShapeFactory.of] carries the sealed **base** at a *class method* return, so the runtime
 *   discriminator has to choose between the two arms,
 * - [anyFlat] carries the sealed **base** at a **top-level function** return, the one sealed-return
 *   position this repository already exercises, and always answers with the sibling arm.
 *
 * Sits beside [NestedShape] in the same package deliberately: that fixture is the all-nested
 * hierarchy, this one is the mixed hierarchy, and the two must coexist without the ADR-040
 * collision check firing.
 *
 * The cats hold the shapes, as usual. Oreo (black with the white middle) is the circle, tucked
 * three-deep on the sill. Mylo (brown and creamy) refuses geometry altogether and settles for being
 * flat, so he gets a label instead.
 */
sealed class FlatShape {
  /** Nested control: Oreo, curled, described by one non-null `Int`. */
  data class Circle(val radius: Int) : FlatShape()
}

/**
 * The cell under test: a **sibling** subclass, declared beside [FlatShape] rather than inside it.
 */
data class Label(val text: String) : FlatShape()

/** Carries both subclasses and the sealed base across return and property positions. */
class FlatShapeFactory {
  /** Return position: the sibling subclass, spelled as a concrete type. */
  fun label(text: String): Label = Label(text)

  /** Property position: the nested subclass, the control spelling. */
  val circle: FlatShape.Circle = FlatShape.Circle(3)

  /** Sealed base at a class-method return: the discriminator has to pick an arm. */
  fun of(radius: Int): FlatShape = if (radius > 0) FlatShape.Circle(radius) else Label("flat")
}

/**
 * Sealed base at a top-level function return (ADR-007 puts it on the static class
 * `FlatShapeSample`), the sealed-return position that works today. Always answers with the sibling
 * arm, so C# can assert the discriminator lands on the one and only [Label].
 */
fun anyFlat(): FlatShape = Label("any")
