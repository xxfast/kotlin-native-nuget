package io.github.xxfast.kotlin.native.nuget.test.issue54

/**
 * Fixture for [#54](https://github.com/xxfast/kotlin-native-nuget/issues/54), the cell designed in
 * ADR-105 (`docs/adr/105-sealed-property-position.md`) at scope **(c)**:
 * a sealed type at a *property* position.
 *
 * `ForwardBridgeTypeClassifier` classifies every sealed class as
 * `BridgeType.SpecializedProtocol("sealed helper <fqn>")`, and `ForwardPropertyPlanner.isPlannable`
 * has no arm for it, so today every property below is dropped with `SKIPPED_UNSUPPORTED_PROPERTY`
 * and none of `Shape` / `Maybe` / `Shapes` / `Current` exists on the generated
 * `TestLibrary.Issue54.Issue54Drawing`. The feature must bind them as the **sealed base**,
 * materialised through the ADR-009 `Issue54Shape.FromHandle(IntPtr)` discriminator, so a C#
 * consumer can pattern match.
 *
 * Every seam the feature crosses, once each:
 * - [Issue54Drawing.shape] — bare sealed property (the `new <abstract>(handle)` CS0144 arm),
 * - [Issue54Drawing.maybe] — nullable sealed property (null crosses in-band on the pointer),
 * - [Issue54Drawing.shapes] — sealed **collection component**, read-only, which the ADR verified
 *   already reads through `NugetMarshal.FromHandle<T>` and only needs the planner gate opened,
 * - [Issue54Drawing.current] — a **scalar `var`**, present to promote ADR-105's *inferred* claim
 *   that a sealed setter rides the ordinary `ObjectHandle` wire (`value._handle` /
 *   `asStableRef<Issue54Shape>().get()`).
 *
 * The `var` **mutable collection** of sealed now lives here too, on [Issue54Board.shapes]. It sits
 * on its own class rather than on [Issue54Drawing], whose four-component `data class` signature is
 * itself under test as a constructor. That write path is gated on `viaDiscriminator` today (ADR-105
 * "Collection write side"), so the property binds get-only and the ADR-075 read-only diagnostic
 * names it; opening it is the same work as the parameter half below.
 *
 * The same base also rides three **function return** seams here, the ones the ordinary member plan
 * owns rather than the property plan:
 * - [Issue54Shapes.pick] carries a **scalar** sealed return on a class member (an `object` method,
 *   so the discriminator picks an arm from a static call site),
 * - [Issue54Shapes.everyShape] carries a sealed **collection** return, `List<Issue54Shape>`, on a
 *   class member, which is the position that is actually broken: the top-level spelling of the
 *   same return already binds,
 * - [shapes] is the **control**, that same collection return at a *top-level* function, which works
 *   today and must stay green so a regression is distinguishable from the bug being repaired.
 *
 * Parameters are the remaining half of ADR-105 scope (d), and every parameter shape rides
 * [Issue54Shapes] once each:
 * - [Issue54Shapes.describe] takes a **bare** sealed parameter, which crosses as the ordinary
 *   instance handle (`shape._handle`, since the generated abstract base implements `INugetHandle`),
 * - [Issue54Shapes.describeMaybe] takes a **nullable** one, null in-band on the pointer
 *   (`shape?._handle ?? IntPtr.Zero`),
 * - [Issue54Shapes.count] and [Issue54Shapes.radii] take a sealed **collection component**, which
 *   boxes each element through `NugetMarshal.Wrap<T>`'s `INugetHandle` arm, the ADR-073 write path
 *   that has never run for an abstract C# base,
 * - [Issue54Drawing]'s own constructor is the fourth shape: bare, nullable, collection and `var`
 *   sealed parameters at once, which is what leaves the class with no public constructor today.
 *
 * Fixture disjointness with the sibling issues in the same ROADMAP cluster:
 * - the sealed base is carried by a **plain** exported `data class`, not by a sealed subclass, so
 *   this is not #39's subclass-property renderer,
 * - no nullable payload *inside* a subclass (#38), no `Flow`/`StateFlow` of the base (#40),
 * - one package and one namespace, so no cross-namespace spelling hop (#41/#50),
 * - no supertype beyond the sealed base (#42), and non-null `Double` payloads only.
 *
 * The cats supply the geometry: Oreo curls into a tight circle on the windowsill, Mylo sprawls out
 * into nothing at all.
 */
sealed class Issue54Shape {
  /** The payload-free arm: Mylo, mid-sprawl, occupying no describable shape. */
  data object Empty : Issue54Shape()

  /** The payload arm: Oreo, curled up, described by one non-null `Double`. */
  data class Circle(val radius: Double) : Issue54Shape()
}

/**
 * The cell under test: a plain (non-sealed) exported class carrying the sealed base at four
 * property positions, and, on the same signature, at four **parameter** positions. Its constructor
 * (and the generated `copy`) is the densest parameter cell in the fixture: bare, nullable,
 * collection and `var` sealed parameters in one list. Under scope (c) it is skipped whole with
 * `SKIPPED_SEALED_POSITION`, which leaves the class with no public constructor at all
 * (`WARNING_NO_PUBLIC_CONSTRUCTOR`); C# reaches an instance only through [sleepingCats] /
 * [curledCats]. The parameter half of scope (d) is what gives it a `new Issue54Drawing(...)`.
 */
data class Issue54Drawing(
  val shape: Issue54Shape,
  val maybe: Issue54Shape?,
  val shapes: List<Issue54Shape>,
  var current: Issue54Shape,
)

/**
 * Producer whose [Issue54Drawing.maybe] is `null`, so C# sees the nullable sealed getter return
 * `null` rather than a discriminated handle. `shapes` carries both arms in a known order:
 * index 0 is [Issue54Shape.Empty] (Mylo), index 1 is a [Issue54Shape.Circle] (Oreo).
 */
fun sleepingCats(): Issue54Drawing = Issue54Drawing(
  shape = Issue54Shape.Circle(radius = 2.0),
  maybe = null,
  shapes = listOf(Issue54Shape.Empty, Issue54Shape.Circle(radius = 1.0)),
  current = Issue54Shape.Empty,
)

/**
 * Producer whose [Issue54Drawing.maybe] is a [Issue54Shape.Circle], the non-null half of the
 * nullable getter. Its [Issue54Drawing.shape] radius (`7.5`) is unique in this fixture so a test
 * can assign it into another drawing's [Issue54Drawing.current] and recognise it on the way back.
 */
fun curledCats(): Issue54Drawing = Issue54Drawing(
  shape = Issue54Shape.Circle(radius = 7.5),
  maybe = Issue54Shape.Circle(radius = 3.5),
  shapes = listOf(Issue54Shape.Empty, Issue54Shape.Circle(radius = 1.0)),
  current = Issue54Shape.Empty,
)

/**
 * Control: the sealed base at a **collection** return on a top-level function (ADR-007 puts it on
 * the static class `Issue54Sample`), the sealed-return position that already binds. Same order as
 * [Issue54Drawing.shapes]: index 0 is Mylo sprawled into [Issue54Shape.Empty], index 1 is Oreo
 * curled into a [Issue54Shape.Circle] of radius `1.0`.
 */
fun shapes(): List<Issue54Shape> = sleepingCats().shapes

/**
 * The sealed base at a return position on a **class member**, which ADR-007 renders as a C# static
 * class because the Kotlin declaration is an `object`. Both shapes of return are here, because the
 * top-level spellings of both already bind and only the member spellings are dropped.
 */
object Issue54Shapes {
  /**
   * Scalar: `0` is Mylo, refusing to be a shape at all; anything else is Oreo, curled to that
   * radius.
   */
  fun pick(n: Int): Issue54Shape =
    if (n == 0) Issue54Shape.Empty else Issue54Shape.Circle(radius = n.toDouble())

  /**
   * Collection: the same two cats [shapes] hands back, in the same order. Not named `all`, because
   * `Issue54Shapes.All()` binds against `System.Linq.Enumerable.All` on the C# side and reports
   * CS1501 rather than the CS0117 that says the member was never generated.
   */
  fun everyShape(): List<Issue54Shape> = shapes()

  /**
   * Scope (d), bare sealed **parameter**: the plain instance handle in the other direction. The
   * return is a `String` so the assertion reads the Kotlin side of the wire rather than another
   * handle. Oreo curled to a radius, or Mylo, who is no shape at all.
   */
  fun describe(shape: Issue54Shape): String = when (shape) {
    is Issue54Shape.Circle -> "circle:${shape.radius}"
    Issue54Shape.Empty -> "empty"
  }

  /**
   * Scope (d), **nullable** sealed parameter: `null` crosses in-band on the pointer, so the absent
   * cat has to be distinguishable from [Issue54Shape.Empty], the cat who is present and shapeless.
   */
  fun describeMaybe(shape: Issue54Shape?): String =
    if (shape == null) "none" else "some:${describe(shape)}"

  /**
   * Scope (d), sealed **collection component** at a parameter: every element is boxed into a Kotlin
   * list through `NugetMarshal.Wrap<T>`'s `INugetHandle` arm. Counting proves the list arrived with
   * the right length before [radii] proves the elements arrived as real shapes.
   */
  fun count(shapes: List<Issue54Shape>): Int = shapes.size

  /**
   * The same collection parameter, read for its payload: an [Issue54Shape.Circle] contributes its
   * radius, [Issue54Shape.Empty] contributes `0.0`. A handle that survived the crossing as a raw
   * pointer rather than a shape cannot answer this.
   */
  fun radii(shapes: List<Issue54Shape>): List<Double> =
    shapes.map { if (it is Issue54Shape.Circle) it.radius else 0.0 }
}

/**
 * The mutable-collection write cell: a `var` [MutableList] of the sealed base, which ADR-105 scope
 * (c) deliberately left get-only behind the `viaDiscriminator` gate on `isWrappableComponent`. Its
 * own class, so [Issue54Drawing]'s constructor signature stays the four-component one the parameter
 * cell pins.
 *
 * [summary] observes the list Kotlin-side, so a C# write only reads back correctly if the handles
 * arrived as genuinely re-wrapped [Issue54Shape]s rather than being echoed by the same getter that
 * wrote them.
 *
 * The board is the windowsill: whichever cat is on it right now, in the order they claimed it.
 */
class Issue54Board {
  /** The write path under test. Starts with Mylo sprawled, alone. */
  var shapes: MutableList<Issue54Shape> = mutableListOf(Issue54Shape.Empty)

  /**
   * Kotlin-side observation of [shapes], in order, using the same spelling as
   * [Issue54Shapes.describe].
   */
  fun summary(): String = shapes.joinToString(",") { Issue54Shapes.describe(it) }
}

/**
 * Factory for [Issue54Board]. The class has a parameterless constructor that binds on its own, but
 * the fixture hands one out by name so a test reads the same way as [sleepingCats] / [curledCats].
 */
fun windowsill(): Issue54Board = Issue54Board()

/**
 * The ADR-105 amendment cell: an extension function whose **receiver** is the sealed base.
 *
 * Every other sealed position in this file is a parameter, a property or a return. This one is the
 * position the parameter-rewrite deliberately skipped: `extensionEntry` classifies the receiver
 * without `sealedAsHandle()`, so today the export is dropped with `SKIPPED_SEALED_POSITION` and
 * `TestLibrary.Issue54.Issue54ShapeExtensions` does not exist at all. Binding it is the one
 * idiomatic C# answer for extending a closed hierarchy: a static method on the abstract base that
 * every arm inherits.
 *
 * Both arms are reachable through the same receiver, so the fixture pins the discriminator-free
 * side of the wire too: the Kotlin export takes the *base* handle and dereferences it with
 * `asStableRef<Issue54Shape>().get()`, exactly as [Issue54Shapes.describe] does at a parameter.
 *
 * Oreo's footprint on the windowsill is a circle; Mylo, sprawled, leaves none.
 */
fun Issue54Shape.footprint(): String = when (this) {
  Issue54Shape.Empty -> "empty"
  is Issue54Shape.Circle -> "circle r=$radius"
}

/**
 * The receiver and a declared parameter, both sealed, on **one** export: the receiver rewrite and
 * the ADR-105 scope (d) parameter rewrite have to agree on the same signature, which neither
 * [footprint] (receiver only) nor [Issue54Shapes.describe] (parameter only) can say on its own.
 *
 * The answer is a `Boolean` rather than another handle, so nothing about the assertion depends on
 * the return side of the wire.
 *
 * Oreo, curled, covers the whole windowsill and anything smaller on it. Mylo, sprawled into no
 * shape at all, covers only the nothing that is already there.
 */
fun Issue54Shape.covers(other: Issue54Shape): Boolean = when (this) {
  Issue54Shape.Empty -> other == Issue54Shape.Empty
  is Issue54Shape.Circle -> other !is Issue54Shape.Circle || other.radius <= radius
}

/**
 * The extension-*property* half of the same amendment cell: [footprint] proves an extension
 * **function** binds on a sealed receiver, and this proves the property route does the same. It is
 * a distinct seam, because `ForwardPropertyPlanner` classifies the receiver on its own path and
 * admits only `ObjectHandle` / primitive / `String` / value-class receivers, so a sealed receiver
 * is dropped today with `SKIPPED_UNSUPPORTED_PROPERTY` and `GetArea` never reaches
 * `TestLibrary.Issue54.Issue54ShapeExtensions`.
 *
 * A `val` rather than a `var`: an extension property has no backing field, so a setter would need
 * external storage and would say nothing more about the receiver rewrite under test.
 *
 * Oreo's curl has an area; Mylo's sprawl has none.
 */
val Issue54Shape.area: Double
  get() = when (this) {
    Issue54Shape.Empty -> 0.0
    is Issue54Shape.Circle -> kotlin.math.PI * radius * radius
  }
