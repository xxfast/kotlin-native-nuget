package io.github.xxfast.kotlin.native.nuget.test.cat

abstract class Animal(override val name: String) : Pet {
  override val legs: Int = 4
  override val nickname: String? = null

  /**
   * Read-only all the way down this far: an `override val` of an interface `val`, so the
   * generated C# renders a get-only `public virtual string Vibe => ...` on the base class.
   * [Cat] then widens it to `var`.
   */
  override val vibe: String = "calm"
  override fun greet(): String = "Hi, I'm $name"
  override fun fetch(item: String): String = "$name sniffs the $item"
  override fun nap() = Unit

  fun introduce(): String = "My name is $name"
}
