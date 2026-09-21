# The `fun dispose()` collision diagnostic is located at the containing class, not the offending member

> Discovered while implementing [ADR-162](../adr/162-per-declaration-error-containment.md)
> (2026-09-22). Verified by reading.

Since ADR-162's amendment to [ADR-117](../adr/117-forward-abi-collision-names-owning-declarations.md),
a user `fun dispose()` colliding with the generated `Dispose()` reports
`ERROR_CSHARP_SIGNATURE_COLLISION` naming the method and the generated member's role. The message
text is correct, but the KSP `symbol` the diagnostic attaches its `file:line` to is the **containing
class's** `KSNode` (or the sealed base's, or the sealed arm's), not the `dispose()` function's own
declaration, because `emitCsharpSignatureCollisions(methods, container, symbol, logger)`'s `symbol`
parameter is the container passed in by all three call sites (`CirClassTranslator.kt:1096`, `:1938`,
`:2063`), and every ordinary signature collision it reports (two constructors, two methods) shares
that same container-level location by design.

Giving the `dispose()` collision specifically a per-member node would mean threading a second,
member-level symbol through `emitCsharpSignatureCollisions` just for the reserved-signature branch,
which changes the shared guard's signature for every one of its producers (classes, sealed bases,
sealed arms, objects, file classes, extensions) for the benefit of one caller. Not attempted here;
a member-level location would need either a second overload or a per-collision symbol map keyed by
signature.
