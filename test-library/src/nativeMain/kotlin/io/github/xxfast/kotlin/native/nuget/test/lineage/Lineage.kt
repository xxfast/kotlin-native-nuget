package io.github.xxfast.kotlin.native.nuget.test.lineage

import io.github.xxfast.kotlin.native.nuget.hidden.Pedigree

// ROADMAP Phase 4, "`interface Derived : Base` flattens". An exported interface that extends
// another exported interface must render `public interface IDerived : IBase, IDisposable` in C#,
// declaring its OWN members only and inheriting the rest, the way C# itself spells `IList<T> :
// ICollection<T>`.
//
// Today `renderInterface` hardcodes `: IDisposable`, so `IPet` carries neither `Name` nor `Age`.
// That is not only a truncation: the ADR-084 bridge walks INHERITED members (`getAllProperties`)
// and reads `impl.Name` off an `IPet`, so a derived interface at a PARAMETER position makes the
// generated `Interop.cs` fail CS1061. `test-library` had no such interface until this file.
//
// The fixture crosses every seam the feature has, not the fewest types:
//
//   - multiple supers:   `Pet : Named, Aged`            -> `IPet : INamed, IAged, IDisposable`
//   - three levels:      `HouseCat : Pet`               -> `IHouseCat : IPet, IDisposable`
//   - identical override: `HouseCat.greet()` restates `Named.greet()` with a default body. With
//     the base list present, a redeclared `string Greet()` on `IHouseCat` is CS0108, and the
//     consumer compiles with warnings as errors, so it must be omitted rather than repeated,
//   - generic super:     `IntHolder : Holder<Int>`      -> `IIntHolder : IHolder<int>,
//     IDisposable`, and the substituted `peek(): Int` must NOT reappear on `IIntHolder` (KSP
//     reports the substituted fake override as owned by `IntHolder`, so an owner filter admits it
//     today),
//   - generic super on a CLASS: `KibbleJar : Holder<Int>` renders `: IHolder` today (CS0305),
//   - unexported super:  `ShowCat : Named, Pedigree`    -> `IShowCat : INamed, IDisposable` with
//     `Breed` and `Registry()` re-homed onto `IShowCat` (ADR-101 mirror),
//   - both directions:   anonymous objects RETURNED as the derived interface (ADR-040 backing
//     wrapper, which must now carry inherited members too) and a class whose methods TAKE the
//     derived interface (ADR-084 bridge for a C# implementer, handle unwrap for a Kotlin one),
//   - a Kotlin class implementing the deepest interface (`Tuxedo : HouseCat`), whose C# class must
//     be an `INamed` transitively so it passes to a `Named` parameter.
//
// Deliberately absent: `var` members (ROADMAP line 28), overloads (the inherited-overload bridge
// crash belongs to the overload-numbering item), and a covariant override (a named skip, pinned in
// Tier 1). Every returned value embeds the cat's name, so a member dispatched to the wrong
// receiver reads visibly wrong.

/** The root of the hierarchy. Reached by C# through every derived interface below. */
interface Named {
  /** String getter, inherited by every derived interface. */
  val name: String

  /** Nullable string getter: `IntPtr.Zero` -> `null` through an inherited export. */
  val nickname: String?

  /** Restated by [HouseCat] with a default body, the identical-signature override. */
  fun greet(): String
}

/** The second super of [Pet], so the base list has two entries. */
interface Aged {
  /** Primitive getter, no conversion at all. */
  val age: Int
}

/** The middle level: two supers, one own member with a `String` input. */
interface Pet : Named, Aged {
  /** Own member of `IPet`. */
  fun feed(food: String): String
}

/** The deepest level: `HouseCat : Pet : Named, Aged`. */
interface HouseCat : Pet {
  /** Own member of `IHouseCat`, with an `Int` input. */
  fun purr(times: Int): String

  /** Identical-signature override of [Named.greet]: must not be redeclared on `IHouseCat`. */
  override fun greet(): String = "Purr, I'm $name"
}

/**
 * A generic super-interface. `peek` is `T`, so it only crosses through a substituted derivative.
 */
interface Holder<T> {
  /** Concrete-typed member of a generic super, inherited by [IntHolder]. */
  val size: Int

  /** `T`-typed member: `IHolder<T>.Peek()`, which `IHolder<int>` spells `int Peek()`. */
  fun peek(): T
}

/** A non-generic interface over a generic super with a concrete type argument. */
interface IntHolder : Holder<Int> {
  /** Own member of `IIntHolder`. */
  fun shake(): String
}

/** One exported super ([Named]) and one unexported super (`Pedigree`, outside `rootPackage`). */
interface ShowCat : Named, Pedigree {
  /** Own member of `IShowCat`. */
  fun pose(): String
}

/**
 * Oreo, black with a white middle. An anonymous object, so C# can only reach it through the
 * `IHouseCat` backing wrapper and must find every inherited member there. Does not override
 * `greet`, so the call lands on [HouseCat]'s default body.
 */
fun adoptHouseCat(): HouseCat = object : HouseCat {
  override val name: String = "Oreo"
  override val nickname: String? = "Cookie"
  override val age: Int = 5
  override fun feed(food: String): String = "Oreo crunches the $food"
  override fun purr(times: Int): String = "Oreo purrs x$times"
}

/** Mylo, brown and creamy, returned as the MIDDLE level: the `IPet` backing wrapper. */
fun adoptPet(): Pet = object : Pet {
  override val name: String = "Mylo"
  override val nickname: String? = null
  override val age: Int = 4
  override fun greet(): String = "Mylo says hi"
  override fun feed(food: String): String = "Mylo laps up the $food"
}

/** Oreo in his show collar: the unexported super's members must reach C# on `IShowCat`. */
fun adoptShowCat(): ShowCat = object : ShowCat {
  override val name: String = "Oreo"
  override val nickname: String? = "Sir Cookie"
  override val breed: String = "Tuxedo Shorthair"
  override fun greet(): String = "Oreo bows"
  override fun registry(): String = "Oreo is registered with the Biscuit Fanciers"
  override fun pose(): String = "Oreo poses on the white bit"
}

/** Mylo's treat tin: the `IIntHolder` backing wrapper, reached through `IHolder<int>`. */
fun treatTin(): IntHolder = object : IntHolder {
  override val size: Int = 12
  override fun peek(): Int = 7
  override fun shake(): String = "Mylo hears 12 treats rattle"
}

/**
 * A Kotlin class implementing the deepest interface. Its C# class must be an `IHouseCat`, and
 * through it an `IPet`, `INamed` and `IAged`, so it passes to every [Doorstep] method.
 * Leaves `greet` to [HouseCat]'s default.
 */
class Tuxedo(override val name: String, override val age: Int) : HouseCat {
  override val nickname: String? = null
  override fun feed(food: String): String = "$name the tuxedo nibbles the $food"
  override fun purr(times: Int): String = "$name the tuxedo purrs x$times"
}

/** A class implementing a generic interface with a concrete type argument (CS0305 today). */
class KibbleJar(override val size: Int) : Holder<Int> {
  override fun peek(): Int = size - 1
}

/**
 * Takes the hierarchy at PARAMETER positions (ADR-084) and calls INHERITED members on it from
 * Kotlin. With a C#-implemented argument each call is a bridge slot on the C# object; with a
 * Kotlin-implemented one it is a handle unwrap. Every result is joined in call order so a C#
 * recorder can assert which members were reached and in what order.
 */
class Doorstep {
  /** Deepest level: every inherited member plus the own one. */
  fun letIn(cat: HouseCat): String =
    listOf(
      cat.name,
      cat.nickname ?: "no nickname",
      "age ${cat.age}",
      cat.greet(),
      cat.feed("tuna"),
      cat.purr(3),
    ).joinToString(" / ")

  /** Middle level: both supers' members plus the own one. */
  fun weigh(pet: Pet): String = "${pet.name} is ${pet.age} and ${pet.feed("kibble")}"

  /** Root level: a derived instance crossing at its base's parameter position. */
  fun callOut(named: Named): String = "${named.greet()} (${named.name})"

  /** Unexported super: `breed` and `registry` come from `Pedigree`, re-homed onto `IShowCat`. */
  fun judge(show: ShowCat): String =
    "${show.name} the ${show.breed}: ${show.registry()}, ${show.pose()}"
}
