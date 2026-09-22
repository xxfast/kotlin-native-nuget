# `NugetManagedException` carries no `@NugetRuntimeApi`, unlike every other public runtime export

> Discovered while landing [ADR-161](../adr/161-csharp-callback-exception-into-kotlin.md); flagged
> there for a human decision rather than resolved.

Every other public declaration `nuget-runtime` exposes is marked `@NugetRuntimeApi` (ADR-115's
opt-in marker), so a consumer must `@OptIn(NugetRuntimeApi::class)` to reference it directly. The
new `NugetManagedException` deliberately does not carry the marker, since it is a type an author
catches at an ordinary `try`/`catch` around a callback invocation, and spreading the `@OptIn`
requirement to every catch site (rather than only the handful of call sites that invoke a marked
function) was judged an unwanted expansion of the annotation's reach. This is inconsistent with
ADR-127's general rule that everything the runtime klib exposes is marked. Needs a human decision:
mark it and accept the `@OptIn` spread, or record an explicit carve-out in ADR-127/115 for a
caught-not-called type.
