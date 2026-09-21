# The enum-parameter route's fatal diagnostic could downgrade to a `SKIPPED_*`

> [ADR-162](../adr/162-per-declaration-error-containment.md) Q5, deferred rather than decided
> against, 2026-09-22.

`ERROR_UNSUPPORTED_ENUM_PARAMETER_ROUTE` (`CirFunctionTranslator`'s `enumParamsUnsupported`) fails
the whole KSP round for a top-level function with an enum parameter whose return shape is carried by
a legacy route that hand-builds its own native call and so never casts the enum parameter down to its
ordinal. ADR-162 kept it fatal on purpose, to preserve exactly the pre-existing behaviour while giving
it a kind. But the translator already returns `emptyList()` for the function — nothing else depends
on it, and the function is simply absent from the generated API, the same outcome every other
`SKIPPED_*` kind produces. Whether it should become a warn-and-omit `SKIPPED_*` instead of failing the
whole build was raised and left open by the human decision that made everything else in ADR-162 fatal:
that decision was about the internal-generator-failure family, not about this pre-existing,
already-classified refusal, which behaves like an ordinary "no route for this shape" skip rather than
a generator bug. Not implemented; would need a Tier 1 cell confirming the function's overloads (if
any) are unaffected before flipping the kind's severity.
