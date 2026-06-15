package io.github.xxfast.kotlin.native.nuget.sample.cat

abstract class Animal(override val name: String) : Pet {
  override fun greet(): String = "Hi, I'm $name"

  fun introduce(): String = "My name is $name"
}
