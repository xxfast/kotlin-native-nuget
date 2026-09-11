package io.github.xxfast.kotlin.native.nuget.test.garage

/**
 * ADR-075 / ADR-101 amendment (2026-09-11): a class's own `abstract fun`. It has no body, so the
 * abstract method walk renders `public abstract string Honk();` on `Vehicle` and a C# consumer's
 * subclass, plus [Truck]'s `override`, compiles. Before the amendment the walk keyed on the
 * declaring class, treated a class-declared `abstract fun` as implemented, and dropped it.
 *
 * [describe] is the control: an implemented method on the same abstract base stays concrete, and
 * dispatches back through the Kotlin override.
 */
abstract class Vehicle(val plate: String) {
  abstract fun honk(): String

  fun describe(): String = "$plate says ${honk()}"
}

class Truck(plate: String) : Vehicle(plate) {
  override fun honk(): String = "HONK"
}
