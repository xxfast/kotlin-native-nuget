# An interface's own planner-dropped member is diagnosed nowhere

**A member an exported interface's own bridge-declaration route drops (unspellable, or refused by
the planner) vanishes from the generated `IFoo` with no diagnostic naming it, for any interface that
is not *reachable* (returned somewhere).** `translateInterface` (`CirClassTranslator.kt:2062`) reads
its members off `interfaceDeclarationCatalog`, a second `ForwardCallablePlanCatalog` built over
every exported interface specifically so `IFoo` stays populated even for an interface that is only
implemented and never returned (ADR-113). That catalog carries its own `droppedCallables` for
whatever it declines to plan, the same field `warnDroppedForwardCallables` reads for the ordinary
class/top-level route. But `NugetProcessor.kt`'s single call to `warnDroppedForwardCallables`
(`:1034`) is given `callableCatalog`, the *reachable*-interface catalog built a few lines above
(`:1001-1016`), never `interfaceDeclarationCatalog` (`:1027-1030`). The two are deliberately kept
separate, per the comment at `:1021-1025`: merging them would double-report a reachable interface's
drop, since a reachable interface is planned by both catalogs. That reasoning covers the
double-report, but leaves the complementary gap unhandled: an interface that is *only*
`interfaceDeclarationCatalog`'s (implemented, never returned) has its drops read by nobody.

Root cause: `NugetProcessor.kt:1034` calls `warnDroppedForwardCallables(callableCatalog, ...)` only;
`interfaceDeclarationCatalog`'s own `droppedCallables` (populated by `declarationPlanner` at
`:1027-1030`) are never passed to a warning call at all.

Why it went unnoticed: every interface in the shipped fixture set that has a planner-dropped member
is also reachable (returned by something), so its drop is reported through `callableCatalog` and the
gap never shows. `Tier1AbstractMethodEnumTest`'s `Potter` interface is implemented by `Kiln` but
never returned, so it is declaration-catalog-only; its `fire(firing: Pottery.Firing)` member (typed
with an undeclared nested enum) is silently absent from the generated `IPotter`, and nothing warns
naming `Potter.fire`, even though the sibling abstract-method walk's drop of the same shape on
`Kiln` does warn, with a `SKIPPED_UNSUPPORTED_TYPE`/`UNDECLARED_ENUM` diagnostic naming `Kiln.fire`.
Verified by reading; not
independently reproduced with a standalone fixture beyond `Tier1AbstractMethodEnumTest`'s own
assertion that `IPotter` renders without a diagnostic naming `fire`.

Fix shape: either merge `interfaceDeclarationCatalog`'s drops into a second
`warnDroppedForwardCallables` call filtered to non-reachable interfaces only (avoiding the
double-report the current split guards against), or track reachable-interface membership on the
drop record itself so one call can de-duplicate. Discovered alongside the abstract-method enum gate
(2026-09-11, no ADR).
