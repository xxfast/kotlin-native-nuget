package io.github.xxfast.kotlin.native.nuget.sample.cat

value class CatId(val id: String) {
  init {
    // Exercises ADR-035: the primary constructor routes through Kotlin, so a
    // direct `new CatId(tooLong)` runs this init and throws across the bridge.
    // ADR-033: the secondary constructor re-runs this init too.
    require(id.length <= 20) { "Cat ID too long: $id" }
  }

  constructor(name: String, number: Int) : this("$name-$number")
  val length: Int get() = id.length
  fun isValid(): Boolean = id.isNotBlank()
}
