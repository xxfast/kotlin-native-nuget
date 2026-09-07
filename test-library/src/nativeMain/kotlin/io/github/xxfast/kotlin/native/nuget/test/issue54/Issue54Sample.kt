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
 * Deliberately absent: no `var` **mutable collection** of sealed. That write path is gated on
 * `viaDiscriminator` by design (ADR-105 "Collection write side") and belongs to a Tier 1 processor
 * test, not to an integration fixture.
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
 * Parameters stay non-sealed throughout: a bare sealed parameter is ADR-105's deferred scope (d).
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
 * property positions. The constructor and `copy` take sealed parameters, which stay skipped under
 * scope (c) — parameter position is ADR-105's deferred scope (d).
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
}
