# Two divergent, independently-maintained `BridgeType.csharpType()` private copies can render different strings for the same Kotlin member.

`ForwardCirPropertyProjection.kt:640` carries a private `BridgeType.csharpType()` that handles
`BridgeType.Throwable` but not `BridgeType.BoundInterface`/`BridgeType.Unit`. The shared
`forwardPublicCsharpType()` (`forward/ForwardCsharpTypes.kt:11`, delegated to by
`ForwardCirPlanProjection.kt:1379`) handles `BoundInterface`/`Unit` but not `Throwable`. Both end
in an exhaustive-looking `else -> error(...)`, so neither copy fails loudly when it is missing an
arm the *other* copy has: it just never gets asked, until some new call site asks it.

That is not hypothetical. [ADR-113](../adr/113-interface-declaration-on-the-forward-plan.md)
(issue #112) assumed "both interface and class member projection go through the same
`BridgeType` from the same classifier instance, therefore they render the same string" and named
it verified. Reading the two functions during implementation showed the assumption was wrong as
stated: had `translateInterface`'s new declaration-catalog code path called the shared
`forwardPublicCsharpType()` for property types (rather than the class route's own private copy),
a `Throwable`-typed interface property would have crashed KSP outright with "Forward CIR
direct-value projection cannot render public type", not silently mis-rendered. The implementer
sidestepped the divergence rather than it turning out to be fine.

Why it is still invisible: no fixture in `test-library` declares an interface member typed
`Throwable`, `BoundInterface`, or `Unit` in the specific position that would route through the
mismatched copy, so the exhaustive-looking `else -> error(...)` in each function has never fired
against the other function's blind spot.

[ADR-114](../adr/114-collection-parameters-on-legacy-flow-and-suspend-routes.md) (issue #109)
extracted `ForwardCirPlanProjection.kt:1377`'s copy into the shared `forwardPublicCsharpType()` so
the new legacy-route collection lowering could call it too, which is why only one of the two
copies survives today. `ForwardCirPropertyProjection.kt:640`'s copy is the one still unmerged: no
third consumer needed it at the time, and merging it is a separate refactor from either feature
that touched half of this. Discovered alongside [ADR-113](../adr/113-interface-declaration-on-the-forward-plan.md)
(issue #112) and [ADR-114](../adr/114-collection-parameters-on-legacy-flow-and-suspend-routes.md)
(issue #109), each of which independently found half of it.
