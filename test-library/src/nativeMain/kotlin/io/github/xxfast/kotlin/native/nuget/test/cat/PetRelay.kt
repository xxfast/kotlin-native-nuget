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

// ROADMAP line 51 (ADR-160 amendment): a top-level lambda RETURN with ordinary value parameters.
// `petRelay()` above binds; the only difference here is the parameter list, and each supplier
// below crosses a different parameter mechanism the legacy generic-return gate refused (or, for
// the enum and the nullable, mis-bound). `greeter(greeting: String)` in Mappings.kt is the
// primitive-parameter control that already binds and must keep binding.
//
// Oreo hands a pet to the supplier, and the supplier hands exactly that pet back every time it is
// asked, long after the call that captured it returned (an interface parameter, which can be a
// C#-implemented bridge the returned lambda keeps alive).
fun petSupplier(pet: Pet): () -> Pet = { pet }

// The exported-class twin: an ObjectHandle parameter, captured and handed back.
fun catSupplier(cat: Cat): () -> Cat = { cat }

// A collection parameter: Mylo's treat portions, summed when the supplier is asked.
fun listSupplier(xs: List<Int>): () -> Int = { xs.sum() }

// Side finding A: an enum parameter. Today ERROR_UNSUPPORTED_ENUM_PARAMETER_ROUTE fails the build.
fun moodSupplier(m: Mood): () -> Int = { m.ordinal }

// Side finding B: a nullable primitive parameter. Today C# declares `int n` and drops the null;
// `-1` is the sentinel that makes a crossed null observable.
fun nullableSupplier(n: Int?): () -> Int = { n ?: -1 }

// An arity-1 lambda return with a value parameter: Oreo adds `n` extra treats to what he's given.
fun adder(n: Int): (Int) -> Int = { it + n }

// Fault injection: a Kotlin throw in a lambda-returning function after it has received a
// C#-implemented bridge parameter. Grumpy pets are refused before any lambda is made.
fun pickyPetSupplier(pet: Pet): () -> Pet {
  if (pet.name == "Grumpy") throw IllegalArgumentException("Oreo refuses to share with ${pet.name}")
  return { pet }
}
