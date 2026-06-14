package io.github.xxfast.nuget.sample.cat

class Cat(
  name: String,
  val lives: Int = 9,
) : Animal(name) {
  var brother: Cat? = null
  var mood: Mood = Mood.SLEEPY
  val nicknames: List<String> = listOf("${name}y", "Little $name")
  val toys: List<Toy> = listOf(Toy("Mouse", "Gray"), Toy("Ball", "Red"))
  val favoriteFoods: MutableList<String> = mutableListOf("Tuna", "Salmon")

  override fun speak(): String = "Meow! My name is $name"

  fun meow(): String = "Meow! My name is $name"

  fun pet(): String = "$name purrs contentedly"
}
