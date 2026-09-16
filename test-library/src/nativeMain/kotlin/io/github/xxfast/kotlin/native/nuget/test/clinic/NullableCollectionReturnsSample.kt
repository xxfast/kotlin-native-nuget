package io.github.xxfast.kotlin.native.nuget.test.clinic

/**
 * ROADMAP "a nullable collection method return (`fun x(): List<T>?`) has no planner route and is
 * silently skipped". One class, cells as members, mirroring the sibling
 * `NullableComponentCollectionsSample.kt`. The new thing crossing here is the *method-return*
 * position of `Nullable(Collection)`: `ForwardCallablePlanner.nullableResultShape()` has no
 * `BridgeType.Collection` branch, so every member below is absent from the generated C# today and
 * named `SKIPPED_UNSUPPORTED_RETURN`. The same shape already binds at a property (ADR-075) and as
 * a parameter, so the fix mirrors the ADR-075 getter: a null result pointer means null, otherwise
 * materialise through the usual `nuget_list_*` / `nuget_set_*` / `nuget_map_*` read.
 *
 * [stocked] is the one switch every member branches on, so each cell has both a null path and a
 * populated path from the same object.
 *
 * The cells cover the component kinds separately, because the null pointer is only half the route
 * and the per-component projection is the other half. [ids] is `List<ChartId>?`, a value-class
 * element that needs the ADR-081 underlying re-wrap on the non-null branch. [counts] is
 * `Set<Int>?`, a direct primitive element with no conversion at all, so a fix that only works
 * when a projection exists cannot hide here. [staff] is `Map<String, Nurse>?`, an object-handle
 * value that materialises through `FromHandle<T>` and is the consumer's job to dispose.
 * [aliases] is an extension function, the same result route as a method per ADR-061, and its
 * `List<String>?` is the conversion-free reference element.
 *
 * Every populated collection carries at least two distinct entries, so a read that loses ordering
 * or count cannot pass by coincidence. Oreo and Mylo are the patients on file, as ever.
 */
class Dispensary(val stocked: Boolean) {
  /** Nullable `List` return with a value-class element: needs the underlying re-wrap per entry. */
  fun ids(): List<ChartId>? =
    if (stocked) listOf(ChartId("CH-OREO-1"), ChartId("CH-MYLO-2")) else null

  /** Nullable `Set` return with a direct primitive element: no per-entry conversion at all. */
  fun counts(): Set<Int>? = if (stocked) setOf(2, 7, 11) else null

  /** Nullable `Map` return with an object-handle value: materialised through `FromHandle<T>`. */
  fun staff(): Map<String, Nurse>? =
    if (stocked) mapOf("oreo" to Nurse("Nightingale"), "mylo" to Nurse("Barnard")) else null
}

/**
 * Extension-function position of the same gap (ADR-061 puts extensions on the method result
 * route), with the conversion-free `String` element.
 */
fun Dispensary.aliases(): List<String>? =
  if (stocked) listOf("biscuit", "milo") else null
