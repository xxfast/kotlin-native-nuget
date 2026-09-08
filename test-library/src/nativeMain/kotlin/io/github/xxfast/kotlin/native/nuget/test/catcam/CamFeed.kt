package io.github.xxfast.kotlin.native.nuget.test.catcam

import io.github.xxfast.kotlin.native.nuget.test.catcam.lens.CamId
import io.github.xxfast.kotlin.native.nuget.test.catcam.lens.Snapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Issue #111's **sealed subclass** arm (`CirClassTranslator.kt:1118`), the third copy of the same
 * `simpleName` spelling. A sealed subclass property is the one position where the residual legacy
 * lambda route still runs after ADR-111 moved every ordinary property onto the plan, so it has to
 * be crossed on its own: fixing the two ordinary copies leaves this one rendering bare `Flow`.
 *
 * [Live] carries both cells at once ([Live.onPick] expressible, [Live.onStream] not), and
 * [Live.label] is the control that must keep binding. [Off] is a second arm so the base is a real
 * discriminated union rather than a one-armed sealed class.
 *
 * Oreo takes the front lens, Mylo takes the loaf-shaped one on the couch.
 */
sealed class CamFeed {

  /** The arm carrying the lambda properties. */
  data class Live(val label: String) : CamFeed() {

    /** Expressible, cross-namespace: must qualify. */
    val onPick: (CamId) -> Snapshot = { id -> Snapshot("$label/${id.value}") }

    /** Unspellable `Flow<Snapshot>`: must be absent, and named in a diagnostic. */
    val onStream: (CamId) -> Flow<Snapshot> = { id -> flowOf(Snapshot("$label/${id.value}")) }
  }

  /** Second arm, no lambda properties: the base stays a genuine union. */
  data class Off(val reason: String) : CamFeed()
}
