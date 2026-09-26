# Default arguments are not honoured on the legacy `suspend`/`Flow` routes

- Discovered during the ADR-122 2026-09-26 amendment (issue #299 nullable scalar/`String` parameter
  fix); the underlying gap is older than that fix, and the fix does not change this. Verified by
  reading.
- `renderAsyncMethod` (`cir/CirConcurrencyRenderer.kt:~220`) renders each parameter as
  `"${it.type} ${it.name}"`, never `it.declaration`, so a Kotlin default value never surfaces as a
  C# `= <value>` default on these routes. `legacyRouteParameters`
  (`cir/CirCollectionParameters.kt`) never sets `CirParameter.defaultValue` in the first place, so
  there is nothing for the renderer to read even if it used `declaration`.
- Consequence: `suspend fun get(limit: Int? = null)` binds as a *required* `int? limit` in C#, not an
  optional one with `= null`. A caller must always pass a value; the Kotlin default is only ever
  reached by other Kotlin callers, never through the bridge.
- Scope: the legacy suspend member, sealed-arm suspend, top-level suspend, and Flow/StateFlow member
  routes all share `legacyRouteParameters` and `renderAsyncMethod`/its Flow counterpart, so this
  affects all four uniformly.
- Candidate fix: extend `legacyRouteParameters` to carry a `defaultValue` (mirroring
  [ADR-164](../adr/164-optional-default-parameters.md)'s widened-signature scheme used elsewhere),
  and have the async/Flow renderers emit `declaration` instead of `type name`.
