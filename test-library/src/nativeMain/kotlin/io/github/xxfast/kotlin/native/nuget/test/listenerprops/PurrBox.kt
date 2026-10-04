package io.github.xxfast.kotlin.native.nuget.test.listenerprops

enum class PurrMood { CALM, GRUMPY }

/**
 * An ADR-039 listener with properties. A C# implementation of `IPurrListener` supplies them, and
 * Kotlin reads each one back through its getter slot on the `add`/`remove` subscription route, the
 * same slot the ADR-084 bridge factory gives a `val`.
 */
interface PurrListener {
  val name: String
  val nickname: String?
  val lives: Int
  val sleepy: Boolean
  val temper: PurrMood
  fun onPurr(volume: Int)
}

/** Oreo and Mylo sign up for purrs; the box reads their properties back from Kotlin. */
class PurrBox {
  private val listeners: MutableList<PurrListener> = mutableListOf()

  fun addPurrListener(listener: PurrListener) { listeners.add(listener) }

  fun removePurrListener(listener: PurrListener) { listeners.remove(listener) }

  /** Every registered listener's properties, read through the bridge, one line each. */
  fun rollCall(): String = listeners.joinToString("; ") { listener ->
    "${listener.name}/${listener.nickname ?: "-"}/${listener.lives}/${listener.sleepy}/" +
      "${listener.temper}"
  }

  /** Purrs at every listener whose `sleepy` getter says it is awake. */
  fun purr(volume: Int) = listeners.filter { !it.sleepy }.forEach { it.onPurr(volume) }

  /** Reads every listener's `name` [times] times and returns the total length read. */
  fun readNames(times: Int): Int {
    var total = 0
    repeat(times) { listeners.forEach { listener -> total += listener.name.length } }
    return total
  }
}
