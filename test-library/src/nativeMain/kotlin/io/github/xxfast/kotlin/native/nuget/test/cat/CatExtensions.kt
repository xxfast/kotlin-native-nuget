package io.github.xxfast.kotlin.native.nuget.test.cat

fun Cat.sayName(): String = "My name is ${this.name}"
fun Cat.greetWith(greeting: String): String = "$greeting, ${this.name}!"

val Cat.isKitten: Boolean get() = lives > 7
val Cat.label: String get() = "${name} (${mood.name.lowercase()})"

// ADR-061: the same method-return matrix, mirrored at the extension-function position. The
// receiver is `Toy` (not `Cat`) deliberately — an extension can't be distinguished from a member
// of the same name on the same receiver, so exercising the extension-function export path for
// real needs a receiver with no colliding member. A toy's "owner" is the cat it belongs to; every
// null/non-null branch below is driven by the toy's own (immutable) fields, since `Toy` is a data
// class with nothing to mutate.

/** Object return (converting), extension-function position. Always non-null. */
fun Toy.findOwner(): Cat = Cat(name, color.length)

/** Nullable object return, extension-function position. Non-null only for the "Gray" toys. */
fun Toy.maybeOwner(): Cat? = if (color == "Gray") Cat(name, name.length) else null

/** Collection return, converting element, extension-function position. */
fun Toy.tags(): List<String> = listOf("$name-tag", "$color-tag")

/** Collection return, non-converting element, extension-function position. */
fun Toy.scores(): List<Int> = listOf(name.length, color.length)

/** Nullable String return, extension-function position. Non-null only for the "Gray" toys. */
fun Toy.alias(): String? = if (color == "Gray") "$name (aka Grey Ghost)" else null

/** Nullable primitive return, extension-function position. Non-null only for the "Gray" toys. */
fun Toy.ageInMonths(): Int? = if (color == "Gray") name.length * 12 else null
