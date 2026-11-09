# The nested-subclass `new` rule has no Tier 1 row for the raw-string extern routes

Found with issue #613 (ADR-116 amendment of 2026-11-09). Inferred, untested.

The `new` marking on a subclass nested in its own `open` base is pinned for constructor, dispose, property and method externs. The flow, callback and interface-bridge routes build their externs as raw strings, and no Tier 1 row checks that they return `new` when a nested override collides. The flow row is blocked by the missing `override` bug in [flow-property-override-drops-override-modifier.md](flow-property-override-drops-override-modifier.md); add the rows once that is fixed.
