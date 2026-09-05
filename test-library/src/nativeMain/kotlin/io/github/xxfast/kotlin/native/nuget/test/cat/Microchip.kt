package io.github.xxfast.kotlin.native.nuget.test.cat

import kotlin.uuid.Uuid

/**
 * Fixture for [#56](https://github.com/xxfast/kotlin-native-nuget/issues/56) part 3, designed in
 * ADR-106 (`docs/adr/106-uuid-mapping.md`): `kotlin.uuid.Uuid` must surface as `System.Guid`
 * (and `Uuid?` as `Guid?`) at every ordinary position, with the human decision that the **wire is
 * the hex-dash string**, not the two 64-bit halves the ADR's Decision picks. That choice is
 * invisible to every assertion below, which is the point: the consumer contract is the same either
 * way, and the byte-order vector pins it.
 *
 * Today `Uuid` is a stdlib declaration with `containingFile == null`, so it classifies as
 * `Unsupported(isUnexportedDependency = true)`: the properties drop with
 * `[nuget:SKIPPED_UNSUPPORTED_PROPERTY]` and `<init>`/`copy`/the methods drop with
 * `[nuget:SKIPPED_UNEXPORTED_DEPENDENCY_TYPE]`.
 *
 * Every position ADR-106 names, once each:
 * - [Microchip] constructor — a non-null and a nullable `Uuid` parameter,
 * - [Microchip.chipId] — `val` property (the getter-with-OUT-parameters path, the one shape that
 *   does not already exist for a 128-bit payload),
 * - [Microchip.previousChipId] — `var Uuid?` property: nullable getter **and** setter,
 * - [Microchip.matches] — method parameter,
 * - [Microchip.reissue] — non-null method return,
 * - [Microchip.lastRetired] — nullable method return,
 * - [Microchip.describe] — nullable method parameter (the has-value fan-out),
 * - [Microchip.maybeEcho] / [Microchip.echo] — nullable and non-null in *and* out on one callable,
 * - [MicrochipRegistry] — the static (`object`) export path, return and parameter,
 * - [wellKnownChip] / [parseChip] — top-level non-null and nullable returns,
 * - [ChipRecord] — the issue's exact repro; proves the data-class constructor and `copy` take the
 *   parameter position for free.
 *
 * (ADR-106 calls the object `ChipRegistry`; that name is already taken by the ADR-069 fixture in
 * this same package, so it is `MicrochipRegistry` here.)
 *
 * Deliberately absent, because ADR-106 defers them: `List<Uuid>` and any other collection
 * component, `Flow<Uuid>`, `Uuid` as an extension receiver, and any `Instant`/`Duration` cell.
 *
 * Oreo and Mylo are both chipped; Oreo has been re-chipped once, Mylo never has.
 */
class Microchip(val chipId: Uuid, var previousChipId: Uuid?) {
  /** Method parameter. True when [candidate] is this chip's own id. */
  fun matches(candidate: Uuid): Boolean = candidate == chipId

  /** Non-null method return. A fresh, random, version-4 id from the vet's scanner. */
  fun reissue(): Uuid = Uuid.random()

  /** Nullable method return: `null` for a cat that has never been re-chipped. */
  fun lastRetired(): Uuid? = previousChipId

  /** Nullable method parameter (the has-value fan-out), non-null `String` return. */
  fun describe(tag: Uuid?): String = if (tag == null) "no tag scanned" else "scanned $tag"

  /** Nullable in, nullable out, one callable. */
  fun maybeEcho(tag: Uuid?): Uuid? = tag

  /** Non-null in, non-null out, one callable. */
  fun echo(tag: Uuid): Uuid = tag
}

/** The static export path (`ForwardCallableOrigin.OBJECT`): a `Uuid` return and a `Uuid` input. */
object MicrochipRegistry {
  /** Static non-null return. Must arrive in C# as `Guid.Empty`. */
  fun nil(): Uuid = Uuid.NIL

  /** Static non-null parameter. Must read `true` for C#'s `Guid.Empty`. */
  fun isNil(tag: Uuid): Boolean = tag == Uuid.NIL
}

/**
 * Top-level non-null return, and the byte-order vector: `ToString()` on the C# side must equal
 * this literal exactly. A single assertion that fails if either side reorders the 16 bytes.
 */
fun wellKnownChip(): Uuid = Uuid.parse("00112233-4455-6677-8899-aabbccddeeff")

/** Top-level nullable return: `null` for anything that is not a well-formed id. */
fun parseChip(text: String): Uuid? = try {
  Uuid.parse(text)
} catch (e: IllegalArgumentException) {
  null
}

/** The issue's exact repro: a `data class` whose sole component is a `Uuid`. */
data class ChipRecord(val id: Uuid)
