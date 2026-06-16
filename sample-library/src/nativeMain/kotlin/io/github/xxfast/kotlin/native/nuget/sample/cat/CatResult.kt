package io.github.xxfast.kotlin.native.nuget.sample.cat

value class CatResult(val cat: Cat) {
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
