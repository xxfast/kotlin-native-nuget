# Two small coverage gaps left by [ADR-097](docs/adr/097-enum-collection-components.md)'s `List` gate narrowing, named but not chased.

> Extracted verbatim from `ROADMAP.md` (Phase 4: Rich type support) in the 2026-08-31 roadmap slim-down.

**Two small coverage gaps left by [ADR-097](docs/adr/097-enum-collection-components.md)'s `List` gate narrowing, named but not chased.** `collectionInputSkipReason`'s `!isBridgeableComponent()` arm (the branch a component fails even the *wider*, pre-narrowing check) has no fixture driving it; every current skip cell goes through the narrower `isWrappableComponent()` arm instead. Separately, `simpleKotlinName`'s narrow-primitive arms (`Byte`/`UByte`/`Short`/`UShort`/`UInt`/`ULong`) became less reachable at a collection-component position now that the gate excludes them there, though they remain reachable at ordinary (non-collection) positions. Verified by source reading only during ADR-097's implementation, not runtime-reproduced.
