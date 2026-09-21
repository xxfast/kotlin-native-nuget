package io.github.xxfast.kotlin.native.nuget.test.issue236

/**
 * Fixture for the **enum-armed sealed interface** hole (issue #236, ADR-157): a `sealed interface`
 * whose arms are `enum class`es is refused today by one branch, because a C# enum admits only an
 * integral base (`CS1008`) and so `enum Patch : Marking` has no shape. The refusal cascades: the
 * interface classifies as a bare protocol with no discriminator, so every member typed with it is
 * dropped with `SKIPPED_SEALED_POSITION`, taking the whole callable with it.
 *
 * After ADR-157 each enum arm binds as a **boxed arm**, `{Enum}Arm`, an ordinary sealed arm whose
 * handle is a `StableRef` to the enum entry, carrying a public constructor from the C# enum and a
 * `Value` getter back to it. The enum itself keeps being declared exactly once as a C# `enum`
 * (ADR-006), so its own members stay reachable as extension methods: [Swirl.patch] is
 * `swirl.Patch()`, never a second spelling on the box. No `IMarking` and no `ISnack` may remain
 * anywhere in the assembly.
 *
 * Every seam the feature crosses, once each, because a fixture trimmed to one arm at one position
 * would go green against a fix that only opened the return route:
 * - [Marking] is the **enum-only** hierarchy with **two** enum arms whose ordinals overlap on
 *   purpose ([Patch.SOCKS] and [Swirl.CREAM] are both ordinal 1), so the prohibition the issue
 *   names (do not map the arm to the bare enum value) is testable: only an `is` discriminator can
 *   tell those two apart,
 * - [Swirl] carries **its own property**, typed as the other arm's enum ([Swirl.patch]), which is
 *   the `Variant(val family: Family)` shape of the issue: a fix that boxes the arm but stops
 *   declaring the enum loses this member,
 * - [Snack] is the **mixed** hierarchy, one enum arm ([Crunch]) beside one `data class` arm
 *   ([Pouch]), so a fix that needs a second convention for mixed arms is visible,
 * - [Portrait] is the **holder**: a `data class` taking the interface at a constructor parameter
 *   and exposing it at a property, the two ADR-105 positions the issue's requirement 4 is about,
 *   plus the `copy` that is planned from the same parameters,
 * - [paintedMarking] is the interface at a **top-level function return** and [describeMarking] at
 *   a **top-level function parameter**, unwrapped back to a real Kotlin arm and answered with a
 *   `String` so the assertion reads the Kotlin side of the wire,
 * - [snackAt] is the mixed base at a return, answering with a different arm per input so the
 *   discriminator, not the declared type, decides.
 *
 * Deliberately not here: `Flow<Marking>` (still inside the backlog item's sealed-base hole), a
 * nullable `Marking?` input (a separate sealed-position reading), arms extending another class
 * (out of scope per the issue). The ineligible control for this feature is not in this file: it is
 * [io.github.xxfast.kotlin.native.nuget.test.issue54.Mixed], whose arm carries a second
 * superclass, and it stays refused.
 *
 * The cats wear the markings. Oreo is black with the white bib in the middle; Mylo is brown and
 * creamy all the way through, swirled like the drink.
 */
sealed interface Marking

/**
 * The plain enum arm: no members of its own. [BIB] is Oreo's white middle; [SOCKS] is the pair of
 * white feet he pretends not to have.
 */
enum class Patch : Marking { BIB, SOCKS }

/**
 * The enum arm that carries **its own property**, typed as the other arm's enum, which is the
 * `Variant(val family: Family)` shape of the issue. Ordinals collide with [Patch] on purpose:
 * [COCOA] is 0 as [Patch.BIB] is, [CREAM] is 1 as [Patch.SOCKS] is, so nothing but the arm itself
 * can discriminate. Mylo is cocoa on top and cream underneath.
 */
enum class Swirl(val patch: Patch) : Marking {
  COCOA(Patch.BIB),
  CREAM(Patch.SOCKS),
}

/**
 * The mixed hierarchy: one enum arm beside one `data class` arm. Requirement 5 of the issue, and
 * the reason the fix may not need a second convention for a boxed arm.
 */
sealed interface Snack

/** The enum arm of the mixed hierarchy: what is in the bowl. */
enum class Crunch : Snack { BISCUIT, KIBBLE }

/** The `data class` arm of the mixed hierarchy: the wet food, named by its [flavour]. */
data class Pouch(val flavour: String) : Snack

/**
 * The holder: the sealed interface at a **constructor parameter** and at a **property**, the two
 * ADR-105 positions requirement 4 is about. A `data class` on purpose, so `copy` is planned from
 * the same primary-constructor parameters and comes back with the constructor.
 */
data class Portrait(val marking: Marking)

/**
 * Top-level function **return** position (ADR-007 puts it on the static class
 * `EnumArmedSealedSample`). Always answers with the arm that overlaps [Patch] on its ordinal, so
 * C# can prove the discriminator, not the value, decided: Mylo, cream underneath.
 */
fun paintedMarking(): Marking = Swirl.CREAM

/**
 * Top-level function **parameter** position. The handle crosses back and is unwrapped to the real
 * Kotlin entry, so the `when` reads the arm and the entry name reads the value; a box that lost
 * either one cannot answer this.
 */
fun describeMarking(marking: Marking): String = when (marking) {
  is Patch -> "patch ${marking.name}"
  is Swirl -> "swirl ${marking.name} over ${marking.patch.name}"
}

/**
 * The mixed base at a return, one arm per input so the discriminator decides: `0` is the enum arm
 * (Oreo's biscuits), anything else is the `data class` arm (Mylo's tuna pouch).
 */
fun snackAt(index: Int): Snack = if (index == 0) Crunch.BISCUIT else Pouch("tuna")
