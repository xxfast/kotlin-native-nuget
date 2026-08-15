package io.github.xxfast.kotlin.native.nuget.test.clinic

/**
 * ADR-075 · `needsCollectionParamWrap` regression cell. Deliberately its own file: no method or
 * constructor anywhere in it takes a collection parameter, so wrap-export gating (the six Kotlin
 * `nuget_wrap_*` exports `NugetMarshal.Wrap<T>`/`CreateList`/`CreateMap`/`CreateSet` depend on) can
 * only be driven here by this class's property setters. Before the fix, `needsCollectionParamWrap`
 * is computed only from `callableCatalog.plans`, so a class shaped exactly like this one would
 * generate C# calling `nuget_wrap_string` against a native library that never exported it.
 *
 * Also the "Expected consumer-side C#" fixture from ADR-075: every eligible setter lowering shape
 * once each. The two shapes that landed here as ineligible (get-only C# property plus a
 * `SKIPPED_UNSUPPORTED_INPUT` diagnostic) have both since been promoted to full round trips --
 * [aliases]'s nullable element by ADR-083, [moods]'s bare enum element by ADR-097.
 */
class Chart(val patientName: String) {
  /** Eligible: `CreateList` + `Wrap<T>` string. */
  var tags: List<String> = emptyList()

  /** Eligible: `CreateMap`, wrappable key (String) AND value (Int). */
  var counts: Map<String, Int> = emptyMap()

  /** Eligible: ObjectHandle element ([Nurse]) + the mutable-list lowering. Reading this back is a
   *  detached copy per ADR-011: mutating the returned list does not reach Kotlin, only assigning a
   *  fresh list does (read-modify-write). */
  var seen: MutableList<Nurse> = mutableListOf()

  /** Eligible: the SET/MUTABLE_SET shared lowering. */
  var codes: Set<String> = emptySet()

  /** Eligible, and nullable: the sharp case ADR-075 exists to fix. Round-trips both ways: assign
   *  a list and read it back, assign `null` and read back `null`. */
  var notes: List<String>? = null

  /** ADR-097 promoted this cell out of the ineligible group: a bare enum element is no longer
   *  what makes a collection property setter ineligible. `isWrappableComponent()` gains `is
   *  BridgeType.Enum -> true`, which is the one predicate shared by `Map`/`Set` callable inputs
   *  and by collection property setters, so this flips from get-only to a full round trip in the
   *  same branch that admits `MoodLedger.logMoods`. Reading it was broken too
   *  (`FromHandle<Mood>` -> `Materialize` -> `NotSupportedException`, no `Factories` entry for an
   *  enum); the same int-ordinal projection fixes both halves, so this is the one cell where
   *  getter and setter are both new. [moodsSummary] observes it Kotlin-side, so a C# write only
   *  reads back correctly if the ordinals arrived as genuinely re-wrapped [Mood]s rather than
   *  being echoed by the same broken getter. */
  var moods: List<Mood> = emptyList()

  /** The Kotlin-side observation for [moods]'s setter (ADR-097). */
  fun moodsSummary(): String = moods.joinToString(",")

  /** Eligible as of ADR-083, which was not the case when ADR-075 wrote this cell: the *element*
   *  is nullable (`String?`, not `String`), not the collection reference, and a null component now
   *  rides the null pointer in its own slot. Still sharp on purpose — the collection reference's
   *  own nullability ([notes]) and its components' marshallability ([aliases]) are independent
   *  questions; ADR-083 made this one settable and ADR-097 did the same for [moods], each through
   *  its own component-slot wire. */
  var aliases: List<String?> = emptyList()
}
