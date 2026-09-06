# Sealed collection components at a parameter position are not bridged

> Extracted verbatim from `ROADMAP.md` (Phase 3).

**`fun f(shapes: List<Shape>)` doesn't bind when `Shape` is a sealed class, even though the same component binds at a property position and, since 2026-09-07, at a return position too.** [ADR-105](docs/adr/105-sealed-property-position.md) shipped scope (c): a bare sealed property, a nullable sealed property, and a sealed collection component in a property all read through the ADR-009 `FromHandle` discriminator. [ADR-009](docs/adr/009-sealed-class-mapping.md)'s 2026-09-07 amendment then shipped the return half of scope (d): `fun x(): List<Shape>` on a top-level function, a class method, an object/companion member, or an extension function all now bind, sharing the same `sealedAsHandle()` rewrite the property planner uses.

The parameter side is the half that remains, and it is the harder half: writing a sealed-base handle into a Kotlin collection means boxing an abstract C# base through the ADR-073 write path, which has never been rendered or run for a sealed base. `ForwardCallablePlanner.kt`'s `isBridgeableComponent`, `isWrappableComponent`, and the `skipReason` element recursion never open for a parameter position. Deferred as ADR-105 scope (d)'s remaining half; priced in that ADR's scope table.
