package io.github.xxfast.kotlin.native.nuget.test.issue112

/**
 * Fixture for [#112](https://github.com/xxfast/kotlin-native-nuget/issues/112) / ADR-113: the
 * generated C# `IFoo` is projected from Kotlin *simple names* while every implementation of it is
 * projected from the **forward plan**, so the two disagree and nothing can implement the interface.
 *
 * `translateInterface` (`cir/CirClassTranslator.kt`) takes no `ForwardCallablePlanCatalog`. It
 * walks `getAllProperties()` / `getAllFunctions()` and maps each member's C# type through
 * `mapParamType` / `mapReturnType`, which are `KOTLIN_TO_CSHARP_PARAM[kotlinType] ?: "IntPtr"`
 * (`cir/CirTypeMapping.kt`). Every reference type outside the primitive table therefore lands on
 * the literal string `IntPtr`, and no member is ever filtered for bridgeability. `packNuget` is
 * green with this in it; the failure only appears when the consumer compiles `Interop.cs`.
 *
 * Pre-fix, [Advertisement] renders as
 *
 * ```csharp
 * public interface IAdvertisement : IDisposable {
 *     string Identifier { get; }
 *     IntPtr? CollarTag { get; }   // CS0738 against BleAdvertisement's global::...CollarTag?
 *     IntPtr Codes { get; }        // CS0535, the class route skipped `codes`
 *     IntPtr CollarTag(int code);  // CS0102 with the property, CS0535 against the class
 *     string Describe(string prefix);
 * }
 * ```
 *
 * Each cell is a distinct *mechanism* of the interface route, not just another type:
 * - [Advertisement] / [BleAdvertisement]: the reported shape, on a **non-reachable** interface
 *   (nothing returns an `Advertisement`, so ADR-040's reachability gate builds no backing class and
 *   no `advertisement_*` dispatch exports). It carries all three sub-problems at once: a
 *   reference-typed member that must project the wrapper the class uses, two members the class
 *   route skipped that must vanish, and the issue's literal `val collarTag` + `fun collarTag(code)`
 *   pair whose method is unbridgeable, which is why the fatal CS0102 guard has to run POST-filter
 *   or this perfectly reasonable Kotlin stops building.
 * - [Microchipped] / [MicrochippedCat]: the ADR-040 regression guard. Also non-reachable, but every
 *   member is bridgeable today, so `IMicrochipped` is *correct* right now. Interface plans are
 *   computed only for reachable interfaces, so a fix that naively threads today's catalog into
 *   `translateInterface` empties this one completely, silently deleting public API that ADR-040
 *   promised stays unconditional. This cell passes today and must keep passing.
 * - [Prowling] / [prowl]: the **reachable** half of the same route. It is returned from a top-level
 *   function, so it gets ADR-040's concrete backing wrapper and `prowling_*` dispatch exports, and
 *   `IProwling` has to agree with a class the generator wrote rather than one the user did. Its
 *   `codes` is planned twice once ADR-113 lands (once into the export catalog, once into the
 *   declaration catalog), which is what Decision D's "do not merge the second planner's drop
 *   channels" is about: its skip must still be reported exactly once.
 *
 * Deliberately absent: an interface `val` whose setter should survive (`hasSetter` is never set, so
 * a `var` interface property renders get-only; ADR-113 defers that), super-interface members (a
 * separate deferred item, `CirInterface` has no base list), and the same CS0102 collision on the
 * ordinary *class* route (unguarded, also deferred). The fatal collision cell itself cannot live
 * here at all: it must fail the build, so it is a Tier 1 cell
 * (`Tier1Issue112InterfaceProjectionTest`).
 *
 * The cats are wearing their collars: Oreo (black, white in the middle) and Mylo (brown and
 * creamy) both broadcast from the hallway.
 */
class CollarTag(val label: String) {
  fun describe(): String = "tag:$label"
}

/**
 * The reported interface, reduced to types this repo already has fixtures for. Non-reachable: no
 * exported declaration anywhere returns an `Advertisement`.
 */
interface Advertisement {
  /** Bridgeable scalar. Present on `IAdvertisement` before and after ADR-113. */
  val identifier: String

  /**
   * Bridgeable, reference-typed, nullable. The class route projects it as the [CollarTag] wrapper;
   * `translateInterface` projects it as `IntPtr?`. That disagreement is CS0738.
   */
  val collarTag: CollarTag?

  /**
   * `kotlin.collections.Collection` is not one of the six collection kinds the forward classifier
   * knows, so the class route skips it with `SKIPPED_UNSUPPORTED_PROPERTY`. It must therefore be
   * absent from `IAdvertisement` too, silently: the skip is already reported once by the class.
   */
  val codes: Collection<String>

  /**
   * The issue's literal collision: same Kotlin name as [collarTag], different namespace in Kotlin,
   * the same member name `CollarTag` in C#. Unbridgeable (a nullable `ByteArray` return), so the
   * forward plan drops it and the fatal CS0102 guard must NOT fire for this hierarchy.
   */
  fun collarTag(code: Int): ByteArray?

  /** Bridgeable method: `string` in, `string` out. */
  fun describe(prefix: String): String
}

/** The user-written exported class the reporter could not compile against `IAdvertisement`. */
class BleAdvertisement(
  override val identifier: String,
  override val collarTag: CollarTag?,
) : Advertisement {
  override val codes: Collection<String> get() = listOf(identifier)

  override fun collarTag(code: Int): ByteArray? = null

  override fun describe(prefix: String): String = "$prefix$identifier"
}

/**
 * ADR-040 regression guard. Non-reachable (nothing returns a `Microchipped`) and every member is
 * bridgeable, so `IMicrochipped` must keep declaring all five members after ADR-113. If the
 * declaration catalog is built from the reachable-only plans, this interface goes empty.
 */
interface Microchipped {
  val label: String
  val lives: Int
  val nickname: String?
  fun describe(prefix: String): String
  fun nap()
}

/** The only thing that mentions [Microchipped]: an implementation, never a return position. */
class MicrochippedCat(
  override val label: String,
  override val lives: Int,
  override val nickname: String?,
) : Microchipped {
  override fun describe(prefix: String): String = "$prefix$label"

  override fun nap() = Unit
}

/**
 * The reachable half: [prowl] returns one, so ADR-040 generates a concrete `Prowling` wrapper and
 * `prowling_*` dispatch exports. `IProwling` has to agree with that generated class, which is a
 * different implementer than [BleAdvertisement] and a different source of CS0535/CS0738.
 */
interface Prowling {
  val collarTag: CollarTag?
  val codes: Collection<String>
  fun describe(prefix: String): String
}

/**
 * Anonymous implementation with no C# wrapper of its own, so a consumer can only reach it through
 * the `prowling_*` dispatch exports (the same proof shape as `cat/Pet.kt`'s `strayPet()`).
 */
fun prowl(): Prowling = object : Prowling {
  override val collarTag: CollarTag? = CollarTag("Mylo")
  override val codes: Collection<String> get() = listOf("brown", "creamy")
  override fun describe(prefix: String): String = "$prefix roams the hallway"
}
