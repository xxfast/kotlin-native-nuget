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
  val accessories: Map<String, Toy> = mapOf(
    "collar" to Toy("Bell Collar", "Gold"),
    "tag" to Toy("Name Tag", "Silver"),
  )
  val schedule: MutableMap<String, String> = mutableMapOf(
    "morning" to "Nap",
    "evening" to "Play",
  )

  override fun speak(): String = "Meow! My name is $name"

  fun meow(): String = "Meow! My name is $name"

  fun pet(): String = "$name purrs contentedly"
}
