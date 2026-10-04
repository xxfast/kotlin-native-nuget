package io.github.xxfast.kotlin.native.nuget.test.charms

/**
 * A member-less (marker) interface. A C# class implementing `ICharm` reaches Kotlin two ways: at a
 * plain parameter, through an ADR-084 bridge factory that has no slots, and through the ADR-039
 * `add`/`remove` pair below, whose subscription export takes no slots either.
 */
interface Charm

/** Oreo's bracelet: one charm clipped on at a time, and any number worn. */
class CharmBracelet {
  private var clippedCharm: Charm? = null
  private val worn: MutableList<Charm> = mutableListOf()

  fun clip(charm: Charm) {
    clippedCharm = charm
  }

  /** The charm [clip] last took, handed back across the boundary. */
  fun unclip(): Charm = checkNotNull(clippedCharm) { "nothing is clipped on" }

  fun addCharm(charm: Charm) {
    worn.add(charm)
  }

  fun removeCharm(charm: Charm) {
    worn.remove(charm)
  }

  fun wornCount(): Int = worn.size
}
