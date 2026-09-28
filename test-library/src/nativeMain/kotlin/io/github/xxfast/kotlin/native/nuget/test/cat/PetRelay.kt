package io.github.xxfast.kotlin.native.nuget.test.cat

// ADR-173: the three erased generic routes carry a C#-implemented interface in and hand the SAME
// instance back. `Helpers.adoptPet` and `PetBox<T : Pet>` already cover the constrained legacy and
// generic-class routes; these two add the lambda route and the unconstrained legacy `fun <T>`.
//
// Oreo hands his favourite pet to the relay, and the relay hands exactly that pet back.
fun petRelay(): (Pet) -> Pet = { it }

fun <T> relayPet(value: T): T = value

// ADR-173: an interface whose ONLY appearance anywhere is as a lambda type argument. It is never a
// parameter, a return, a property or a supertype of anything else, so this shape is what proves the
// erased position alone makes its backing wrapper, its `Factories` entry and its bridge exist.
// One `val` only: a `var` member plans the bridge to null (see LeakTests Row 6d).
//
// Mylo's squeaky mouse, which he carries to the relay and expects back unchewed.
interface Squeaker {
  val squeak: String
}

fun squeakerRelay(): (Squeaker) -> Squeaker = { it }

// ADR-173: the generic-class twin of `Squeaker`. `Chewer` appears ONLY as the type argument of an
// exported generic class at a top-level return, so the generic-class arm of the erased-position
// walk alone must give it its backing wrapper, `Factories` entry and bridge.
//
// Mylo's chew toy, which comes in a box.
interface Chewer {
  val chew: String
}

fun chewerBox(): Box<Chewer> = Box(object : Chewer {
  override val chew: String = "nom"
})
