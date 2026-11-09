package io.github.xxfast.kotlin.native.nuget.test.orchard

/**
 * Fixture for issue #613: an `open` class with a subclass nested inside it.
 *
 * A nested C# class inherits its enclosing class's `private` members, so a nested subclass that
 * redeclares the base's `private static extern Native_Create` / `Native_Dispose` (and any other
 * per-type `Native_*` an override re-emits) hides them: CS0108, which the warnings-as-errors
 * `GeneratedBindingsCheck` turns into a failed pack. The fix marks only those colliding externs
 * `new`, and keeps the nesting (`Fruit.Apple` stays `Fruit.Apple`).
 *
 * The seams, once each:
 * - [Fruit.Apple]: the base's constructor shape (`String?` with a default), overriding a method
 *   and a property, so `Native_Create`, `Native_Dispose`, the method and the getter externs all
 *   collide;
 * - [Fruit.Pear]: a different constructor shape (`Int`, no default) and no overrides;
 * - [Quince]: a top-level subclass, which must keep plain externs (a `new` there is CS0109);
 * - [OrchardException.BlightException]: the same nesting under an exported exception base, which
 *   binds as an ordinary handle class at a return (ADR-201, `Tier1ThrowablePositionsTest`);
 * - [Basket] / [pick] / [inspect]: put the nested types at a property, a constructor parameter
 *   and a return.
 *
 * Multi-level nesting (`Fruit.Stone.Plum`) is deliberately absent: it is the implementer's Tier 1
 * cell, with an inferred accessibility question still open.
 *
 * Oreo bats the apple off the counter; Mylo only eats the pear if it is ripe.
 */
open class Fruit(val name: String? = null) {
  /** Overridden by [Apple]: a `Fruit`-typed reference must still reach Apple's body. */
  open fun ripen(): String = "${name ?: "a fruit"} ripens slowly"

  /** Overridden by [Apple] as a property, so a getter extern collides too. */
  open val colour: String get() = "green"

  /** Same constructor shape as [Fruit]; overrides both open members. */
  class Apple(name: String? = "apple") : Fruit(name) {
    override fun ripen(): String = "Oreo bats the $name off the counter"
    override val colour: String get() = "red"
  }

  /** A different constructor shape: a required `Int`, no overrides. */
  class Pear(val ripeness: Int) : Fruit("pear") {
    /** Mylo's verdict on this pear. */
    fun forMylo(): Boolean = ripeness >= 3
  }
}

/** A top-level subclass: unaffected by the fix, so its externs stay plain. */
class Quince : Fruit("quince")

/** An exported, open exception base with a subclass nested inside it. */
open class OrchardException(message: String? = null, cause: Throwable? = null) :
  Exception(message, cause) {

  /** The nested exception subclass. */
  class BlightException(message: String? = "blight on the apple tree") : OrchardException(message)
}

/** Holds an apple, so `Fruit.Apple` sits at a constructor parameter and a property. */
data class Basket(val apple: Fruit.Apple)

/** Returns an [Fruit.Apple] typed as its base. */
fun pick(): Fruit = Fruit.Apple()

/** Returns the nested exception subclass as a value (not thrown). */
fun inspect(): OrchardException.BlightException = OrchardException.BlightException()
