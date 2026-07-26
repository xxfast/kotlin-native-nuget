package io.github.xxfast.kotlin.native.nuget.test.cat

abstract class Animal(override val name: String) : Pet {
  override val legs: Int = 4
  override val nickname: String? = null
  override fun greet(): String = "Hi, I'm $name"
  override fun fetch(item: String): String = "$name sniffs the $item"
  override fun nap() = Unit

  fun introduce(): String = "My name is $name"
}
