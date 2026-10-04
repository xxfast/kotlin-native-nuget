package io.github.xxfast.kotlin.native.nuget.test.models

/** Reached by the publisher only through Lid, across the dependency klib boundary. */
class Parcel<T>(val value: T) {
  class Lid(val number: Int)
}