package io.github.xxfast.kotlin.native.nuget.sample.cat

class Cat(
  name: String,
  val lives: Int = 9,
) : Animal(name) {
  var brother: Cat? = null
  var owner: String? = null
  var age: Int? = null
  var mood: Mood = Mood.SLEEPY
  val nicknames: List<String> = listOf("${name}y", "Little $name")
  val toys: List<Toy> = listOf(Toy("Mouse", "Gray"), Toy("Ball", "Red"))
  val favoriteFoods: MutableList<String> = mutableListOf("Tuna", "Salmon")
  val accessories: Map<String, Toy> = mapOf(
    "collar" to Toy("Bell Collar", "Gold"),
    "tag" to Toy("Name Tag", "Silver"),
  )
  val traits: Set<String> = setOf("Playful", "Curious", "Fluffy")
  val vaccinations: MutableSet<String> = mutableSetOf("Rabies", "FVRCP")
  val schedule: MutableMap<String, String> = mutableMapOf(
    "morning" to "Nap",
    "evening" to "Play",
  )

  val unsupported: Sequence<String> = sequenceOf("This", "is", "a", "sequence")

  val onMeow: () -> String = { "Meow! My name is $name" }
  val onPet: (String) -> String = { action -> "$name $action contentedly" }
  val countLives: () -> Int = { lives }
  val isAlive: () -> Boolean = { lives > 0 }
  val favoriteToy: () -> Toy = { toys.first() }

  override fun speak(): String = "Meow! My name is $name"

  fun meow(): String = "Meow! My name is $name"

  fun pet(): String = "$name purrs contentedly"

  companion object {
    const val SPECIES: String = "Felis catus"
    val defaultBreed: String = "Domestic Shorthair"
    fun fromName(name: String): Cat = Cat(name)
  }
}
