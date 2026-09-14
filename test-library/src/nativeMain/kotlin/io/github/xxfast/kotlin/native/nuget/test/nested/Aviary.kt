package io.github.xxfast.kotlin.native.nuget.test.nested

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.yield

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

  /**
   * Site (a) of the interface-spelling sweep: the listener interface of an ADR-039 add/remove pair
   * ([PerchWatch]), nested so that the pair site's bare `I$simpleName` names nothing.
   *
   * Unit-returning and property-less on purpose: the pair route's generated Kotlin bridge object
   * gives every override a Unit block body and implements no properties, so [Keeper] (which
   * returns a `String`) cannot travel it. Two arities, so an arity-0 slot and an arity-1 slot are
   * both proven.
   */
  interface Watcher {
    fun onLand(perch: String)
    fun onFlyOff()
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

  /**
   * ADR-040 x ADR-019: the **legacy suspend route** at an interface return position. The
   * synchronous [currentKeeper] above pins the plain route; this one travels the completion
   * callback, which spells its result with the ADR-040 *backing wrapper* (`Task<Aviary.Keeper>`,
   * `t.SetResult(new Aviary.Keeper(resultPtr))`) where ADR-040 says a consumer never sees the
   * wrapper at a declared position. The signature must be
   * `Task<global::TestLibrary.Nested.Aviary.IKeeper>`.
   *
   * No fixture existed in this shape at all, nested or top-level, which is how the wrong spelling
   * survived: every suspend return in the repo today is a class or a sealed arm.
   *
   * Oreo waits, radiating impatience, while the keeper works the treat cupboard open.
   */
  suspend fun currentKeeperLater(): Keeper = currentKeeper()

  /**
   * The **Flow element** half of the same seam (`qualifiedElementCsType`): the element of a
   * `Flow<Keeper>` is spelled with the backing wrapper too, and the stream is read through
   * `NugetMarshal.FromHandle<T>`, whose Activator branch cannot construct an interface -- so the
   * wrong spelling compiles and fails at the first emission. Two elements, so an implementation
   * that reads only the first is distinguishable from one that reads the stream.
   *
   * Mylo supervises both keepers, in order, from the cardboard box.
   */
  fun keepers(): Flow<Keeper> = flowOf(namedKeeper("first"), namedKeeper("second"))

  private fun namedKeeper(which: String): Keeper = object : Keeper {
    override fun greet(): String = "$which keeper of $name"
  }

  /**
   * Site (b) of the interface-spelling sweep: a `suspend fun` returning `StateFlow<Interface>`.
   *
   * ADR-133's amendment moved the plain-async branch and the `Flow` element read onto the
   * qualified interface spelling ([currentKeeperLater], [keepers]); `suspendStateFlowMembers`
   * still spells its element with the ADR-040 backing **wrapper** and passes no `read:`, so the
   * consumer is handed `Task<KotlinStateFlow<Aviary.Keeper>>` where ADR-040 says no consumer ever
   * sees the wrapper at a declared position. The signature must be
   * `Task<KotlinStateFlow<global::TestLibrary.Nested.Aviary.IKeeper>>`, and, since ADR-136, the
   * element read must resolve a C#-implemented keeper back to the original instance instead of
   * wrapping it, which is what [book] sets up.
   *
   * A real `yield()`, so the outer suspend is not vestigial. This is the only `StateFlow` of an
   * interface in the repository; the composition simply had no fixture.
   *
   * Oreo waits by the treat cupboard for whoever is on duty.
   */
  suspend fun keeperReport(): StateFlow<Keeper> {
    yield()
    return onDuty
  }

  /** Parameter half of [keeperReport]: stores a keeper (a C# one included) as the current value. */
  fun book(keeper: Keeper) {
    onDuty.value = keeper
  }

  private val onDuty: MutableStateFlow<Keeper> = MutableStateFlow(namedKeeper("on duty"))

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

  /**
   * The **collision** half of ADR-084: a second nested interface whose simple name is `Keeper`,
   * exactly like [Aviary.Keeper]. The bridge plan names its state class from the simple name alone
   * (`stateClassName = "${simpleName}BridgeState"`, ForwardInterfaceBridgePlanner) and renders it
   * into the ROOT namespace's `CirBridgeHelper`, so two owners' `Keeper`s emit two
   * `KeeperBridgeState` classes (CS0101) and two `keeperImpl` pattern variables in one block
   * (CS0128). The name has to come from the enclosing chain -- `AviaryKeeperBridgeState` and
   * `RegistryKeeperBridgeState` -- leaving a top-level interface's `PetBridgeState` unchanged.
   */
  interface Keeper {
    fun greet(): String
  }

  /**
   * Parameter position: this is what a C# implementation of `Registry.IKeeper` is handed to, so
   * the bridge is exercised at runtime and not merely declared.
   */
  fun greetVia(keeper: Keeper): String = "${keeper.greet()} @ registry"

  /**
   * Return position, and it is load-bearing (measured 2026-09-13, not what ADR-084's prose
   * suggests): `CirTranslator.interfaceBackingClasses` is the **return**-reachable subset, and the
   * ADR-084 bridge plan is built from exactly that list. With `greetVia` alone, `Registry.Keeper`
   * got no backing wrapper and therefore no bridge at all -- `NugetBridge.HandleFor` ended at its
   * `NotSupportedException` arm and the process died with a native `kotlin.NullPointerException`
   * before it -- so the second `KeeperBridgeState` never existed and the collision cell was
   * vacuous. With a return position both owners plan, and the two `KeeperBridgeState` classes in
   * one root-namespace helper are the CS0101 this fixture is for.
   */
  fun currentKeeper(): Keeper = object : Keeper {
    override fun greet(): String = "the registry keeper"
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
