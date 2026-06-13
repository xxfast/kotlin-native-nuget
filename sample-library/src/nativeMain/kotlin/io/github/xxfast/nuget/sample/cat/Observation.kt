package io.github.xxfast.nuget.sample.cat

sealed class Observation {
  data object Superposition : Observation()
  data class Alive(val cat: Cat) : Observation()
  data class Dead(val cause: String) : Observation()
}

fun openBox(name: String): Observation {
  if (name == "Oreo") return Observation.Alive(Cat("Oreo"))
  return Observation.Dead("The cat was not $name")
}

fun peekBox(): Observation = Observation.Superposition
