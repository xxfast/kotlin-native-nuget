# A `${name}HasValue` synthesized slot can collide with a real user parameter name

- Discovered alongside the ADR-122 2026-09-26 amendment (issue #299 nullable scalar/`String`
  parameter fix). Inferred from reading the naming scheme, not reproduced by a fixture or test;
  unknown whether the ADR-164 plan route's own `${name}HasValue` pair guards this either.
- The nullable-scalar wire names its companion slot `${name}HasValue` next to the value slot `name`
  (`forward/ForwardLegacyRouteCollections.kt`, reusing ADR-098's convention). A parameter list
  containing both `limit: Int?` and a second parameter literally named `limitHasValue` would compose
  two slots named `limitHasValue` on the same export: one synthesized, one user-declared. Nothing in
  the classifier or builders checks a user parameter name against the synthesized name before
  emitting it.
- No fixture exercises this shape today; if it occurs, the likely symptom is either an illegal
  duplicate-parameter-name declaration on the Kotlin export or a name collision on the C# `DllImport`
  parameter list, either of which should fail the build loudly rather than silently misroute a value.
- Candidate fix: have the legacy-parameter builders check the synthesized name against every other
  parameter name in the same member and fail with a named diagnostic (or rename the slot) on a
  collision, the same way other synthesized-name collisions in this codebase are guarded.
