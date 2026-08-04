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
 * once each, plus the two ineligible shapes that must fall back to a get-only C# property with a
 * `SKIPPED_UNSUPPORTED_INPUT` diagnostic.
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

  /** Eligible, and nullable — the sharp case ADR-075 exists to fix. Round-trips both ways: assign
   *  a list and read it back, assign `null` and read back `null`. */
  var notes: List<String>? = null

  /** Ineligible: `Mood` is an enum element, not `isWrappableComponent()`. Must become a get-only
   *  C# property (`{ get; }`, no `set`) plus a `SKIPPED_UNSUPPORTED_INPUT` diagnostic naming this
   *  property. */
  var moods: List<Mood> = emptyList()

  /** Ineligible for a different reason than [moods]: the *element* is nullable
   *  (`String?`, not `String`), not the collection reference. Sharp on purpose — the collection
   *  reference's own nullability ([notes]) and its components' marshallability ([aliases]) are
   *  independent questions. Must also become get-only + `SKIPPED_UNSUPPORTED_INPUT`. */
  var aliases: List<String?> = emptyList()
}
