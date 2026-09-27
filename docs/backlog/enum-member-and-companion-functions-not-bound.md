# Enum member functions and companion functions are not bound

**Symptom.** A function declared in an `enum class` body (`fun isLoudNow(): Boolean = isLoud`) or in
its `companion object` (`fun fallback(): Mood = FIRST`) generates nothing on either side of the
bridge. Since the ADR-006 2026-09-26 amendment this is at least a named skip,
`SKIPPED_ENUM_MEMBER_FUNCTION`, rather than a silent drop, but the function itself still never
becomes a C# member.

**Contradiction.** ADR-006's Decision states the mapping is symmetric: "Properties → extension
methods on the enum ... Methods → same pattern (extension methods)." Only the properties half ships.

**Cause.** `exports/EnumExports.kt:37` walks `enum.getAllProperties()` only; there is no matching walk
over the enum's (or its companion's) functions to generate exports for. Even if it did,
`CirEnum` (`cir/CirModel.kt` ~256-269) has a `properties: List<CirEnumProperty>` field and nothing
equivalent for methods, so the C# renderer has nowhere to project a method to.

**Coverage gap.** None; this is by design today, not an untested branch. Verified by
`Tier1EnumCamelCasePropertyTest`, which pins that `isLoudNow` and `fallback` are named skips and
never appear in either generated file.

**Fix shape.** Give `CirEnum` a method list mirroring `CirEnumProperty`, walk the enum's and its
companion's own functions in `EnumExports.kt` the way `properties` already are, and render them as
further `{Enum}Extensions` static methods, the same shape ADR-006 already uses for properties.

**Discovered by:** the ROADMAP line 24 fix (camelCase enum property entry points), which added the
named-skip diagnostic without adding the binding.
