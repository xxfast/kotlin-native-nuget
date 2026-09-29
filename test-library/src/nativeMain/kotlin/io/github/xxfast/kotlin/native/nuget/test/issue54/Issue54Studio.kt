package io.github.xxfast.kotlin.native.nuget.test.issue54

import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The sealed base at a **suspend** collection return (ADR-119 amendment): `List<Issue54Shape>`
 * crosses the legacy suspend route's `SpecializedProtocol` refusal once ADR-105's `sealedAsHandle`
 * rewrite runs before admission. Each element is read through
 * `NugetMarshal.FromHandle<Issue54Shape>`, the sealed base's `Factories` entry, exactly as the
 * synchronous [Issue54Shapes.everyShape] reads it.
 *
 * A class, not an `object`, so the owner's suspend scope and its drain are crossed too. The
 * top-level owner of the same return is [shapesLater] in `Issue54Sample.kt` (it cannot live in
 * this file: ADR-007 would name its static class `Issue54Studio` and collide with this one).
 *
 * The studio is where Oreo and Mylo pose for their portraits: Mylo sprawls first, Oreo curls
 * second, always in that order.
 */
class Issue54Studio {
  /** Both cats, in [shapes]' order: Mylo as [Issue54Shape.Empty], Oreo as a `1.0` circle. */
  suspend fun sketch(): List<Issue54Shape> {
    delay(1.milliseconds)
    return shapes()
  }

  /**
   * The nullable twin, crossing both halves of the amendment at once: a nullable collection whose
   * element is the sealed base. Absent, the cats did not turn up to the sitting.
   */
  suspend fun sketchOrNull(present: Boolean): List<Issue54Shape>? {
    delay(1.milliseconds)
    return if (present) shapes() else null
  }

  /**
   * The same collection as a `Flow` element (ADR-119 amendment, folded): the element classifier
   * runs the same `sealedAsHandle` rewrite, so `Flow<List<Issue54Shape>>` binds as
   * `IAsyncEnumerable<IReadOnlyList<Issue54Shape>>`. Two sittings: Mylo alone, then both cats.
   */
  fun sittings(): Flow<List<Issue54Shape>> = flowOf(shapes().take(1), shapes())
}
