package io.github.xxfast.kotlin.native.nuget.test.cat

// Generic class with a validating init — exercises ADR-032 (unconstrained typed
// create_* variants route through NugetMarshal.CreateBox<T>).
class Box<T>(val value: T) {
  init {
    require(value.toString().isNotEmpty()) { "Box cannot hold a blank value" }
  }
}
