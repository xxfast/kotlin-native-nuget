package io.github.xxfast.kotlin.native.nuget.hidden

import io.github.xxfast.kotlin.native.nuget.test.parcel.Crate

/**
 * ADR-101 amendment (2026-10-10): the dropped middle of `Barge : Keel : Crate<Int>`. The package is
 * outside `rootPackage`, so `Keel` has no C# class and `Barge` extends `Crate<int>` directly.
 *
 * `Keel` closes `Crate`'s `T`, which is why KSP parents `Crate.item` and
 * `Crate.describe(tag: T)` to it: neither is declared here, so neither may re-home onto `Barge`.
 * [weigh] and [load] are declared here and do.
 *
 * Mylo's boat. It floats, mostly because Oreo refuses to sit in it.
 */
open class Keel : Crate<Int>(7) {
  open fun weigh(): String = "keel:$item"

  override suspend fun load(): String = "keel loaded $item"
}
