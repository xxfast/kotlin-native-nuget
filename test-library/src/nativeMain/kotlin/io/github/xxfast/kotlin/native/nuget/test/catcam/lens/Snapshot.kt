package io.github.xxfast.kotlin.native.nuget.test.catcam.lens

/**
 * The lambda *return* type argument's expressible half, sibling of [CamId] and in the same
 * deliberately-different namespace (`TestLibrary.Catcam.Lens`).
 *
 * Both positions of a `(CamId) -> Snapshot` are cross-namespace here on purpose: a fix that only
 * qualified the result and left the argument bare would still fail to compile, and a fixture with
 * a primitive argument would not notice.
 *
 * Mylo is brown and creamy, so his snapshots are all captioned "loaf, unmoved".
 */
class Snapshot(val caption: String)
