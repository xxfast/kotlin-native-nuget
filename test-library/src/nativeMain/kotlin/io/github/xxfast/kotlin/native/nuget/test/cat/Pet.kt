package io.github.xxfast.kotlin.native.nuget.test.cat

// ADR-040: an interface-typed return/parameter position must surface in C# as `IPet` / `IPet?`,
// backed by a generated concrete `Pet` wrapper class. Every member below exercises a distinct
// marshalling seam on the generated `pet_*` interface-dispatch exports (see Cat.kt and the
// top-level `strayPet()` below for the return-position seams).
interface Pet {
  val name: String // String getter: needs UTF8 marshalling
  val legs: Int // primitive getter: no conversion at all - catches an open-coded conversion bug
  val nickname: String? // nullable String getter: IntPtr.Zero -> null
  val vibe: String // read-only in the interface, but an implementation may widen it to `var`
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
  override val vibe: String = "aloof"
  override fun speak(): String = "Mrrp?"
  override fun fetch(item: String): String = "eyes the $item warily but doesn't fetch it"
  override fun nap() = Unit
}

// ADR-040 at the TOP-LEVEL legacy suspend route (CirFunctionTranslator): the same defect as
// `Aviary.currentKeeperLater`, but with a top-level interface, where ADR-040's own example spells
// the position `IPet`. The completion callback spells it with the backing wrapper instead
// (`Task<Pet>` + `new Pet(resultPtr)`), so the signature has to become `Task<global::TestLibrary.
// Cat.IPet>`. `strayPet()` above is the synchronous control that already binds correctly.
//
// Whiskers the Stray keeps Oreo and Mylo waiting at the cat flap before ambling in.
suspend fun strayPetLater(): Pet = strayPet()
