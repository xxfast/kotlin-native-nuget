package dev.other.core

/**
 * The open-class supertype of a **sealed** class, one module away and outside `:test-library`'s
 * `rootPackage`, so ADR-101 drops it from the sealed base's C# base list.
 *
 * Unlike [UnexportedQuilt] (which deliberately carries only the one member its sealed subclass
 * overrides), this class carries one member per mechanism the re-homing has to cross: a final
 * `val`, a `var` (the setter path), a final `fun` taking a string, and an `open fun` with a body
 * that one sealed arm overrides and the other inherits. None of them is overridden by the sealed
 * base itself, so each reaches C# only if the sealed route re-homes what it inherits.
 *
 * Mylo gets swaddled in the fleece one; Oreo shakes it off.
 */
open class UnexportedBlanket {
  val fabric: String = "fleece"

  var warmth: Int = 3

  fun drape(cat: String): String = "fleece draped over $cat"

  open fun shake(): Int = 1
}

/**
 * The interface supertype of a **sealed** class, one module away. Carries a defaulted method, a
 * defaulted property, an abstract method whose parameter default lives only here, and an abstract
 * property with no default. The sealed base implements none of them, so all four have to be
 * re-homed onto it; the two abstract ones are implemented by the arms alone.
 *
 * Oreo's purr has a rumble you can feel through the sofa.
 */
interface UnexportedPurring {
  fun purr(): String = "prrr"

  val rumble: Int get() = 5

  /** [paws] defaults here, one module away, and nowhere else. */
  fun knead(paws: Int = 2): Int

  val whiskers: Int
}
