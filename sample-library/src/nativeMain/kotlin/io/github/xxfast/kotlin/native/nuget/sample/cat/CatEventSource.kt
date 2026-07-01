package io.github.xxfast.kotlin.native.nuget.sample.cat

class CatEventSource(val name: String) {
  private val listeners: MutableList<CatEventListener> = mutableListOf()

  fun addListener(listener: CatEventListener) { listeners.add(listener) }

  fun removeListener(listener: CatEventListener) { listeners.remove(listener) }

  fun trigger() {
    val msg = "$name says meow!"
    listeners.forEach { it.onMeow(msg) }
    listeners.forEach { it.onPurr() }
  }
}
