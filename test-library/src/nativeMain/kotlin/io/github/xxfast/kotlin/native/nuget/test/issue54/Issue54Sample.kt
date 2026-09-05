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
