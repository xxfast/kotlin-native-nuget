package io.github.xxfast.kotlin.native.nuget.test

import io.github.xxfast.kotlin.native.nuget.test.models.Parcel

/** No API here references Parcel<T> directly: Lid alone must admit its owner. */
class ParcelDesk {
  fun lid(): Parcel.Lid = Parcel.Lid(3)
}