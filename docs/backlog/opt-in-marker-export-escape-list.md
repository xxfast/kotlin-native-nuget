# `exportMarkers(...)` escape list: force-export declarations behind a named opt-in marker

[ADR-115](docs/adr/115-opt-in-marker-declarations.md) drops any declaration behind an author's own
`@RequiresOptIn` marker from the C# surface, at any `RequiresOptIn.Level`, with no way to opt back
in. That is deliberately the v1 default (alternative 1 in the ADR), chosen because it fixes the
reported build break (`CNameExports.kt` failing to compile against the author's own marker) without
adding any new plugin/processor plumbing.

`@RequiresOptIn` covers two different intents, though, and v1 treats them identically:

- **internal**: "this is bookkeeping, never meant to be public". Dropping it from C# is exactly
  right.
- **experimental**: "this is public, but the shape may still change" (the kotlinx-style
  `@ExperimentalFooApi` pattern). Dropping it too is a regression from "leaks with no signal" to
  "silently absent", and a library whose entire public API sits behind one such marker gets an
  empty C# surface with no remedy beyond removing the marker.

The escape list closes that gap without touching the v1 default: `nuget { publish {
exportMarkers("com.example.ExperimentalFooApi") } }` names markers whose declarations should keep
exporting despite carrying them. It is the inverse of `binary-compatibility-validator`'s
`nonPublicMarkers` (BCV lists what to *hide*; this lists what to *keep*), and it is ADR-115's
alternative 3 (hybrid): automatic-by-default, with an escape hatch, deferred rather than rejected
because it is purely additive on top of what shipped and can land later without breaking anything
already working.

Shape, mirroring `include`/`exclude`'s existing `NugetPublishConfig` plumbing: a
`NugetPublishConfig` field and DSL method, threaded into a KSP option (`nuget.exportMarkers`) the
same way `nuget.publishedScopes` already threads the export-scope predicate into the processor, read
back in `NugetProcessorProvider`, and checked in `optInMarker()`/`isMarkedOptIn()` before the
`SKIPPED_OPT_IN_MARKER` skip fires: a marker on the list is treated as if the declaration carried no
marker at all, so it exports through the ordinary route with no diagnostic.

No fixture exists yet. Not scoped here: whether the escape list should also suppress
`SKIPPED_OPT_IN_MARKER` in the reverse direction (there is no reverse projection of an opt-in marker
to begin with, so this is likely moot), and whether a still-more-precise per-`RequiresOptIn.Level`
default (auto-export `WARNING`, keep dropping `ERROR`) is worth offering instead of, or alongside,
the escape list; ADR-115 alternative 5 (`[Experimental]` C# mapping) is the level-aware idea and is
separately deferred.
