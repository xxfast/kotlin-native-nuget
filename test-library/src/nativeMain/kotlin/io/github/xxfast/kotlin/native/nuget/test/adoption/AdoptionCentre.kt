package io.github.xxfast.kotlin.native.nuget.test.adoption

/**
 * ADR-243 (issue #610): a returned read-only `List` of handles. Every element type here is a plain
 * class or an interface, never a `data class`, so each element really crosses as a Kotlin handle
 * the consumer has to release. A data-class element could cross as a value and prove nothing.
 *
 * The cells cover every position the collection can come back from: property getter (interface
 * elements), method return (class elements), nested list, nullable element, nullable list, a
 * handle-free control and an echo of the caller's own list. [Rescue], [Ward] and the top-level
 * `streetCats()` are the overridable, interface-member and top-level positions.
 *
 * Oreo (black, white middle) and Mylo (brown, creamy) are up for adoption again. Nobody is taking
 * them, they come straight back every time.
 */
interface Adoptable {
  val name: String

  fun greet(): String
}

/** Plain class element: one property, two methods, no `equals`/`hashCode`/`toString` override. */
class Kitten(override val name: String) : Adoptable {
  override fun greet(): String = "Hi, I'm $name, please adopt me"

  fun purr(): String = "$name purrs like a tiny engine"
}

/** The host. Every read builds fresh Kitten instances, so each list owns its element handles. */
class AdoptionCentre {

  /** Property getter, interface elements: two Kotlin-backed [Kitten]s seen as [Adoptable]. */
  val pets: List<Adoptable> get() = listOf(Kitten("Oreo"), Kitten("Mylo"))

  /** Handle-free control: must stay `IReadOnlyList<string>`. */
  val names: List<String> get() = listOf("Oreo", "Mylo")

  /** Method return, plain class elements. */
  fun kittens(): List<Kitten> = listOf(Kitten("Oreo"), Kitten("Mylo"))

  /** Nested list: one litter each, Oreo and Mylo refuse to share. */
  fun litters(): List<List<Kitten>> = listOf(listOf(Kitten("Oreo")), listOf(Kitten("Mylo")))

  /** Nullable element, with the null in the middle where the empty cat bed is. */
  fun maybeKittens(): List<Kitten?> = listOf(Kitten("Oreo"), null, Kitten("Mylo"))

  /** Nullable list: `null` when the centre is closed. */
  fun kittensOrNone(present: Boolean): List<Kitten>? = if (present) kittens() else null

  /** Returns the caller's own list: a C#-implemented element must come back as the same object. */
  fun echo(pets: List<Adoptable>): List<Adoptable> = pets

  /** A Kotlin-backed [Ward], so its `wards` list is read through the interface-member route. */
  fun ward(): Ward = object : Ward {
    override val wards: List<Kitten> get() = listOf(Kitten("Mylo"), Kitten("Oreo"))
  }
}

/** Overridable position: an open member keeps the `IReadOnlyList<Kitten>` static type. */
open class Rescue {
  open fun strays(): List<Kitten> = listOf(Kitten("Oreo"), Kitten("Mylo"))
}

/** Interface-member position: the static type of [wards] stays `IReadOnlyList<Kitten>`. */
interface Ward {
  val wards: List<Kitten>
}
