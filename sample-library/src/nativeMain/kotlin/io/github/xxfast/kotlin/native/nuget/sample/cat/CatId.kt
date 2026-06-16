package io.github.xxfast.kotlin.native.nuget.sample.cat

value class CatId(val id: String) {
  constructor(name: String, number: Int) : this("$name-$number")
  val length: Int get() = id.length
  fun isValid(): Boolean = id.isNotBlank()
}
