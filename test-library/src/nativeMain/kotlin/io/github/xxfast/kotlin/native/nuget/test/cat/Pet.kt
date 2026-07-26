package io.github.xxfast.kotlin.native.nuget.test.cat

// ADR-040: an interface-typed return/parameter position must surface in C# as `IPet` / `IPet?`,
// backed by a generated concrete `Pet` wrapper class. Every member below exercises a distinct
// marshalling seam on the generated `pet_*` interface-dispatch exports (see Cat.kt and the
// top-level `strayPet()` below for the return-position seams).
interface Pet {
  val name: String // String getter: needs UTF8 marshalling
  val legs: Int // primitive getter: no conversion at all - catches an open-coded conversion bug
  val nickname: String? // nullable String getter: IntPtr.Zero -> null
  fun speak(): String // String-returning method
  fun greet(): String = "Hi, I'm $name" // default method: dispatch must reach the override
  fun fetch(item: String): String // String *input* on the dispatch export
  fun nap() // Unit-returning method (void export)
}

// The strongest polymorphism proof: an anonymous object with no generated C# wrapper of its own,
// so the consumer can only reach it through `pet_*` dispatch. Values are distinctive so a test can
// prove this is not a `Cat`.
fun strayPet(): Pet = object : Pet {
  override val name: String = "Whiskers the Stray"
  override val legs: Int = 3
  override val nickname: String? = null
  override fun speak(): String = "Mrrp?"
  override fun fetch(item: String): String = "eyes the $item warily but doesn't fetch it"
  override fun nap() = Unit
}
