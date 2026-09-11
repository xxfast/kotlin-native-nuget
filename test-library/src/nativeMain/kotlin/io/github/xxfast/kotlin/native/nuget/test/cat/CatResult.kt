package io.github.xxfast.kotlin.native.nuget.test.cat

value class CatResult(val cat: Cat) {
  /**
   * ADR-035 amendment: a secondary constructor on a value class over an exported class. Kotlin
   * mints the [Cat] and the consumer owns the resulting handle through [cat].
   */
  constructor(name: String) : this(Cat(name, 1))

  val name: String get() = cat.name
  fun isAlive(): Boolean = cat.lives > 0
}

value class ObservationResult(val observation: Observation) {
  fun describe(): String = when (observation) {
    is Observation.Alive -> "Alive: ${observation.cat.name}"
    is Observation.Dead -> "Dead: ${observation.cause}"
    is Observation.Superposition -> "Unknown"
  }
}
