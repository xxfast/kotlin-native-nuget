package io.github.xxfast.nuget.sample.cat

class Cat(
  val name: String,
  val lives: Int = 9,
) {
  var brother: Cat? = null
  var mood: Mood = Mood.SLEEPY

  fun meow(): String = "Meow! My name is $name"

  fun pet(): String = "$name purrs contentedly"
}
