package io.github.xxfast.kotlin.native.nuget.sample.cat

object CatRegistry {
  private val cats: MutableList<String> = mutableListOf()

  fun register(name: String) {
    cats.add(name)
  }

  fun count(): Int = cats.size

  fun clear() {
    cats.clear()
  }
}
