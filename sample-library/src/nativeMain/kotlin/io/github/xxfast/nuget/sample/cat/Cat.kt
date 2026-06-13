package io.github.xxfast.nuget.sample.cat

class Cat(
  name: String,
  val lives: Int = 9,
) : Animal(name) {
  var brother: Cat? = null
  var mood: Mood = Mood.SLEEPY

  override fun speak(): String = "Meow! My name is $name"

  fun meow(): String = "Meow! My name is $name"

  fun pet(): String = "$name purrs contentedly"
}
