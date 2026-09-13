package io.github.xxfast.kotlin.native.nuget.test.nested

/**
 * ADR-133 fixture: every nested declaration kind under an exported **class** owner, declared in C#
 * as a real nested type (`Aviary.Perch`, `Aviary.Defaults`, `Aviary.Kind`, `Aviary.IKeeper`),
 * generalising ADR-009's sealed-arm nesting.
 *
 * The three `issue54` fixtures ([io.github.xxfast.kotlin.native.nuget.test.issue54.ProbeOuter],
 * `NestedModeOwner`, `NestedListenerOwner`) already pin one kind each at the *declaration* seam and
 * flip from absence to presence with this ADR. What they do not cover, and what this file exists
 * for, is the set of seams a nested type must cross once it is declared:
 *
 * - [perchAt] returns the nested class and [heightOf] takes it, so a handle minted behind
 *   `aviary_perch_create` has to survive a full round trip back into Kotlin. The issue54
 *   fixtures deliberately have no parameter position typed with their nested class, so without
 *   this cell "the nested class is declared" could go green while the parameter direction is
 *   unbridged,
 * - [habitat] reads the nested **enum** at a property position and [rename] takes it at a parameter
 *   position, which is where the ordinal wire and the top-level `AviaryKindExtensions` class
 *   (CS1109: an extension class cannot itself be nested) are observable,
 * - [greetVia] takes the nested **interface**, so a C# class implementing `Aviary.IKeeper` is
 *   called back from Kotlin; [currentKeeper] returns one, which is the ADR-040 backing wrapper
 *   (`Aviary.Keeper : Aviary.IKeeper`) nested beside its interface rather than at namespace root,
 * - [Middle] / [Middle.Inner] and [inner] are the **depth 2** cell: the prefix chain is the whole
 *   enclosing chain (`aviary_middle_inner_create`), not just the immediate owner,
 * - [Defaults] is the nested **object**, reachable only as static members (`Aviary.Defaults` is a
 *   C# static class, exactly as a top-level Kotlin `object` renders today). There is deliberately
 *   **no** member returning it: a position typed with a static class is CS0722 and is the
 *   pre-existing object-return hazard, out of scope here,
 * - [name] is the control: a change that drops the owner instead of declaring its children is
 *   distinguishable from one that only fails the nested types.
 *
 * [Registry] is the **object owner**: `CirObject` carries only methods, so a nested declaration
 * under it travels a different translator path from a nested declaration under a class, and an
 * implementation that only teaches `CirClass` to carry children would still lose `Registry.Entry`.
 *
 * Deferred by ADR-133 and therefore absent here on purpose: `inner class`, a generic owner, an
 * `enum`/`interface`/sealed owner, and a nested `value class`. Those must keep skipping named, and
 * the Tier 1 test pins that.
 *
 * Oreo (black with the white middle) claims the highest perch in the aviary and refuses to come
 * down; Mylo (brown and creamy) supervises from the nested cardboard box underneath it.
 */
class Aviary(val name: String) {

  /** Nested `class`: `public class Aviary.Perch : IDisposable, INugetHandle`. */
  class Perch(val height: Int) {
    fun describe(): String = "perch@$height"
  }

  /** Nested `object`: `public static class Aviary.Defaults`, statics only. */
  object Defaults {
    fun capacity(): Int = 12
  }

  /**
   * Nested `enum class`, with a property so the extension class is actually rendered: it must be
   * the TOP-LEVEL `AviaryKindExtensions`, because C# (CS1109) forbids extension methods in a
   * nested class.
   */
  enum class Kind(val label: String) {
    INDOOR("indoor"),
    OUTDOOR("outdoor"),
  }

  /** Nested `interface`: `Aviary.IKeeper`, with the ADR-040 wrapper `Aviary.Keeper` beside it. */
  interface Keeper {
    fun greet(): String
  }

  /** Depth-2 owner: [Middle.Inner] is two levels in, so the prefix chain has three segments. */
  class Middle {
    class Inner(val depth: Int) {
      fun describe(): String = "inner@$depth"
    }
  }

  /**
   * Return position, nested class: mints a handle the consumer disposes.
   *
   * Named `perchAt`, not `perch`: C# forbids a member and a nested type sharing a name in the same
   * declaring type (CS0102), and `fun perch(): Perch` PascalCases into exactly that. The same rule
   * is why the enum property is [habitat] and the interface return is [currentKeeper]. The
   * collision cell itself belongs in a Tier 1 test on the KSP output (ADR-133 surface 6), not here,
   * where it would make the whole fixture unbridgeable.
   */
  fun perchAt(height: Int): Perch = Perch(height)

  /** Parameter position, nested class: the handle has to unwrap back into Kotlin. */
  fun heightOf(perch: Perch): Int = perch.height

  /** Property position, nested enum. */
  val habitat: Kind = Kind.OUTDOOR

  /** Parameter position, nested enum. */
  fun rename(kind: Kind): String = "$name/${kind.label}"

  /** Parameter position, nested interface: Kotlin calls back into a C# implementation. */
  fun greetVia(keeper: Keeper): String = "${keeper.greet()} @ $name"

  /** Return position, nested interface: the ADR-040 wrapper, nested beside its interface. */
  fun currentKeeper(): Keeper = object : Keeper {
    override fun greet(): String = "hi from $name"
  }

  /** Depth-2 return position. */
  fun inner(depth: Int): Middle.Inner = Middle.Inner(depth)

  /** Control: must keep binding whatever happens to the nested declarations. */
  val label: String = "aviary $name"
}

/**
 * The **object owner** half of the ADR-133 fixture: a nested class under a Kotlin `object`.
 *
 * `Registry` renders as `public static class Registry` today; [Registry.Entry] must render as a
 * nested `public class Registry.Entry` inside it, exported as `registry_entry_create`. A
 * class-owner-only implementation passes every [Aviary] cell and still loses this one.
 *
 * Mylo keeps the registry of which cat is allowed on which windowsill. It has one entry and it is
 * Mylo.
 */
object Registry {

  /** Nested class under an `object` owner. */
  class Entry(val id: Int) {
    fun describe(): String = "entry#$id"
  }

  /** Return position: mints the nested handle from the static owner (`lookup`, not `entry`: CS0102). */
  fun lookup(id: Int): Entry = Entry(id)

  /** Control. */
  fun label(): String = "registry"
}

/**
 * ADR-133 amendment: an extension whose **receiver is a nested type** must bind under the owner
 * chain, exactly as the member route already does.
 *
 * [Aviary.Perch.summarize] is the member route's twin: `Perch.describe()` exports as
 * `aviary_perch_describe`, so this one has to export as `aviary_perch_summarize` and land in the
 * chain-named `AviaryPerchExtensions`, not the bare `perch_summarize` / `PerchExtensions` the
 * unchained name produces today. The bare name is not merely inconsistent: it is the same C symbol
 * a top-level `Perch`, or any other owner's nested `Perch`, would claim. Measured 2026-09-13: that
 * duplicate does **not** raise `ERROR_C_ENTRY_POINT_COLLISION`; it is absorbed silently by the
 * numbering suffix, so a second owner's extension ships as `inner_describe_2` and one of the two
 * owners' published symbols moves when the other is added. `Tier1NestedTypesTest` pins that.
 *
 * Fresh names on purpose: `Perch` already has a member `describe()`, and an extension called
 * `describe` would be shadowed in Kotlin *and* collide on `aviary_perch_describe` once the chain
 * is applied, so the cell would go red for the wrong reason.
 *
 * [Aviary.Perch.isHigh] is the property half: ADR-013 spells an extension property as
 * `GetIsHigh(this Aviary.Perch)`, and its export must chain to `aviary_perch_get_isHigh`.
 *
 * Oreo only respects a perch above five; Mylo is content at ground level.
 */
fun Aviary.Perch.summarize(): String = "perch@$height (ext)"

/** The extension-property half of the same rule (ADR-013: `GetIsHigh`). */
val Aviary.Perch.isHigh: Boolean get() = height > 5
