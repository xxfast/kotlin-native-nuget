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
 * Issue #110 adds the second sibling, [Loaf], which is an `object` rather than a `data class`. That
 * one kind difference is the whole bug: `rootClasses` already filters `isSealedSubclass()` out, so
 * [Label] is owned by the sealed route alone, but `rootObjects` carries no such filter. A sibling
 * `object` subclass is therefore collected twice, once as `public sealed class Loaf : FlatShape` by
 * the sealed route and once as an empty `public static class Loaf { }` at namespace level, which is
 * CS0101 in every consumer. [Loaf] is deliberately a *sibling* rather than nested, because a nested
 * one cannot collide (the sealed route puts it inside the base's braces) and so is not the fatal
 * half of the bug.
 *
 * The cats hold the shapes, as usual. Oreo (black with the white middle) is the circle, tucked
 * three-deep on the sill. Mylo (brown and creamy) refuses geometry altogether and settles for being
 * flat, so he gets a label instead. When neither of them can be bothered having a shape at all,
 * they fold their paws under and become a loaf: no payload, exactly one of it, forever.
 */
sealed class FlatShape {
  /** Nested control: Oreo, curled, described by one non-null `Int`. */
  data class Circle(val radius: Int) : FlatShape()
}

/**
 * The cell under test: a **sibling** subclass, declared beside [FlatShape] rather than inside it.
 */
data class Label(val text: String) : FlatShape()

/**
 * Issue #110's fatal cell: a **sibling `object`** subclass, declared beside [FlatShape] rather than
 * inside it. Same declaration position as [Label], different `ClassKind`, and that is the only
 * thing separating a type that generates once from a type that generates twice.
 *
 * Must end up declared exactly once, by the sealed route, as
 * `public sealed class Loaf : FlatShape`. No empty `public static class Loaf { }` may accompany it.
 *
 * A `data object` rather than a plain `object` on purpose: it is the spelling in the report, and it
 * is the one that also drags `Equals`/`GetHashCode`/`ToString` (ADR-009) onto the sealed-route
 * declaration, so the CS0708 half of the reported cascade has real instance members to land on.
 */
data object Loaf : FlatShape()

/** Carries both subclasses and the sealed base across return and property positions. */
class FlatShapeFactory {
  /** Return position: the sibling subclass, spelled as a concrete type. */
  fun label(text: String): Label = Label(text)

  /** Property position: the nested subclass, the control spelling. */
  val circle: FlatShape.Circle = FlatShape.Circle(3)

  /** Sealed base at a class-method return: the discriminator has to pick an arm. */
  fun of(radius: Int): FlatShape = if (radius > 0) FlatShape.Circle(radius) else Label("flat")

  /**
   * Return position for the sibling `object` subclass, spelled as its own concrete type. This is
   * the site that has to name one C# type and only one: with the duplicate present there are two
   * candidates called `Loaf` in this namespace and the reference does not resolve.
   */
  fun loaf(): Loaf = Loaf
}

/**
 * Sealed base at a top-level function return (ADR-007 puts it on the static class
 * `FlatShapeSample`), the sealed-return position that works today. Always answers with the sibling
 * arm, so C# can assert the discriminator lands on the one and only [Label].
 */
fun anyFlat(): FlatShape = Label("any")

/**
 * Sealed base at a top-level function return, discriminating onto the sibling `object` arm. The
 * discriminator can only ever hand back the sealed route's declaration, so this is where a consumer
 * observes which of the two `Loaf`s is the real one.
 */
fun flatLoaf(): FlatShape = Loaf
