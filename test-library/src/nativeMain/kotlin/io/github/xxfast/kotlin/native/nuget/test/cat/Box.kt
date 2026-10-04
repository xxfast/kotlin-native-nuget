package io.github.xxfast.kotlin.native.nuget.test.cat

// Generic class with a validating init — exercises ADR-032 (unconstrained typed
// create_* variants route through NugetMarshal.CreateBox<T>).
class Box<T>(val value: T) {
  init {
    require(value.toString().isNotEmpty()) { "Box cannot hold a blank value" }
  }

  // ADR-160 on a generic owner (ADR-147): a per-call callback the plan owns, so the export reads
  // the `Box<Any?>` receiver and calls the C# delegate back with the label's length.
  fun measure(scale: (Int) -> Int): Int = scale(value.toString().length)
}
