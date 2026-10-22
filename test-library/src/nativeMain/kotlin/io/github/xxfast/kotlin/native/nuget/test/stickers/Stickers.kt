package io.github.xxfast.kotlin.native.nuget.test.stickers

import io.github.xxfast.kotlin.native.nuget.test.cat.Cat
import io.github.xxfast.kotlin.native.nuget.test.cat.Mood

/**
 * ADR-220: a generic value class renders as a positional `readonly record struct Sticker<T>(T Value)`.
 *
 * A closed instantiation crosses as its substituted underlying, so `Sticker<Int>` rides the
 * primitive route, `Sticker<String>` the string route, `Sticker<Mood>` the enum route and
 * `Sticker<Cat>` the object-handle route. A member's own `T` inside one crosses on the bare-`T`
 * wire, null pointer in-band. The struct has no `CreateChecked`, so [init] runs in Kotlin the first
 * time a value crosses in, not at `new Sticker<int>(-1)` in C#.
 *
 * Oreo (black, white in the middle) collects the stickers. Mylo (brown and creamy) peels them off.
 */
value class Sticker<T>(val value: T) {
  init {
    require(value != -1) { "Mylo peeled this sticker already" }
  }
}

/**
 * A phantom type parameter: the underlying mentions no `T`, so it crosses as a plain `Long` and only
 * the C# spelling is generic (`Decal<Cat>`).
 */
value class Decal<T>(val serial: Long)

/** Closed `Sticker<Int>` at a return. */
fun count(): Sticker<Int> = Sticker(3)

/** Closed `Sticker<Int>` at a parameter and a return. Re-wrapping `-1` runs [Sticker]'s `init`. */
fun bump(sticker: Sticker<Int>): Sticker<Int> = Sticker(sticker.value + 1)

/** Closed `Sticker<String>` at a parameter. */
fun shout(sticker: Sticker<String>): String = sticker.value.uppercase()

/** Closed `Sticker<String>?` at a return, absent. */
fun missing(): Sticker<String>? = null

/** Closed `Sticker<String>?` at a return, present. */
fun found(): Sticker<String>? = Sticker("Oreo")

/** Closed `Sticker<Mood>` at a return. */
fun nap(): Sticker<Mood> = Sticker(Mood.SLEEPY)

/** Closed `Sticker<Mood>?` at a parameter. */
fun describe(sticker: Sticker<Mood>?): String = sticker?.value?.displayName ?: "no mood"

/** Closed `Sticker<Cat>` at a parameter: the handle underlying is borrowed. */
fun nameOf(sticker: Sticker<Cat>): String = sticker.value.name

/** Closed `Sticker<Cat>?` at a return. */
fun adopt(name: String?): Sticker<Cat>? = name?.let { Sticker(Cat(it)) }

/** A closed instantiation inside a collection: `IReadOnlyList<Sticker<int>>` (pin if it binds). */
fun tally(stickers: List<Sticker<Int>>): List<Sticker<Int>> = stickers.map { Sticker(it.value * 2) }

/** A top-level function's own `T` inside the struct, out: `Sticker<T> Stick<T>(T value)`. */
fun <T> stick(value: T): Sticker<T> = Sticker(value)

/** A top-level function's own `T` inside the struct, in: `T Peel<T>(Sticker<T> sticker)`. */
fun <T> peel(sticker: Sticker<T>): T = sticker.value

/**
 * A bound own `T` (`T : Any`): the underlying is a non-null `TypeParameter`, so `default(Sticker<T>)`
 * must throw in C# rather than send a null pointer into this slot.
 */
fun <T : Any> firm(sticker: Sticker<T>): T = sticker.value

/** An explicit `Sticker<T?>`: classifies like an unconstrained `Sticker<T>`, null in-band. */
fun <T> blank(value: T?): Sticker<T?> = Sticker(value)

/**
 * A `Comparable<T>` own `T` inside the struct, in: `Comparable` is contravariant, so the erased
 * spelling `Comparable<Any?>` satisfies the bound and no trampoline is needed.
 */
fun <T : Comparable<T>> ranked(sticker: Sticker<T>): T = sticker.value

/** A `Comparable<T>` own `T` inside the struct, in twice and out: Oreo outranks Mylo. */
fun <T : Comparable<T>> higher(a: Sticker<T>, b: Sticker<T>): Sticker<T> =
  if (a.value >= b.value) a else b

/**
 * An `Enum<T>` own `T` inside the struct: the bound has no erased spelling, so the whole call,
 * the unwrap and the re-wrap run inside the ADR-198 trampoline, and the enum crosses as an ordinal.
 */
fun <T : Enum<T>> napOf(sticker: Sticker<T>): Sticker<T> = sticker

/** An intersection-bound own `T` inside the struct: the ADR-217 trampoline checks both bounds. */
fun <T> loudest(sticker: Sticker<T>): Sticker<T> where T : CharSequence, T : Comparable<T> = sticker

/** A phantom instantiation at a return. */
fun oreoDecal(): Decal<Cat> = Decal(7L)

/** A phantom instantiation at a parameter. */
fun serial(decal: Decal<Cat>): Long = decal.serial

/**
 * Oreo's sticker book: closed instantiations as constructor parameters and properties (`val` and
 * `var`), and a member's own `T` inside the struct.
 */
class StickerBook(val cover: Sticker<String>, var pages: Sticker<Int>) {
  /** A `var` over a closed `Sticker<Mood>?`. */
  var favourite: Sticker<Mood>? = null

  /** A `val` over a phantom instantiation. */
  val decal: Decal<Cat> = Decal(9L)

  /** A member's own `T` inside the struct, out. */
  fun <T> stick(value: T): Sticker<T> = Sticker(value)

  /** A member's own `T` inside the struct, in. */
  fun <T> peel(sticker: Sticker<T>): T = sticker.value

  /** A member's `Enum<T>` own `T` inside the struct: the member runs through the trampoline. */
  fun <T : Enum<T>> napOf(sticker: Sticker<T>): Sticker<T> = sticker

  /** A member's `Comparable<T>` own `T` inside the struct, erased, no trampoline. */
  fun <T : Comparable<T>> ranked(sticker: Sticker<T>): T = sticker.value
}
