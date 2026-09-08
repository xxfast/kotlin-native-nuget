package io.github.xxfast.kotlin.native.nuget.test.catcam

import io.github.xxfast.kotlin.native.nuget.test.catcam.lens.CamId
import io.github.xxfast.kotlin.native.nuget.test.catcam.lens.Snapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Issue #111's **top-level function** arm (`CirFunctionTranslator.kt:134`): a function whose
 * *return* type is a lambda takes its own copy of the `simpleName` spelling, so the same defect
 * reaches a static route that no class-property fix touches.
 *
 * [camPicker] is the expressible cell (qualify), [camStreamer] the unspellable one (absent, with
 * a `SKIPPED_UNSUPPORTED_RETURN` behind it). Both live in this file so the generated
 * `CatCamRoutes` static class keeps at least one member and is not elided, which would make
 * "the skip fired" indistinguishable from "the whole file vanished".
 */

/** Expressible: `KotlinFunc<global::...Lens.CamId, global::...Lens.Snapshot>`. */
fun camPicker(): (CamId) -> Snapshot = { id -> Snapshot("${id.value}: picked") }

/** Unspellable `Flow<Snapshot>` at a return position: the function must be absent from C#. */
fun camStreamer(): (CamId) -> Flow<Snapshot> = { id -> flowOf(Snapshot("${id.value}: streamed")) }
