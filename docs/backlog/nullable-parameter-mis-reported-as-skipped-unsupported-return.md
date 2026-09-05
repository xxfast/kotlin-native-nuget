# A nullable parameter, or a declaration with no return position at all, can be mis-reported with the `SKIPPED_UNSUPPORTED_RETURN` kind.

**What breaks**: the diagnostic a consumer sees names the wrong side of the signature, or a side
that does not exist. `Microchip.describe(tag: Uuid?): String` (a nullable *parameter*, non-nullable
return) skipped as `SKIPPED_UNSUPPORTED_RETURN`, and `Issue56Failure`'s primary constructor and
`copy()` (a `Throwable`-parametered `data class`, no return position at all) also skipped as
`SKIPPED_UNSUPPORTED_RETURN`, both before their respective fixes landed. Neither the kind nor the
hint text names the offending parameter.

**Root cause**: `ForwardPlanSkipReason.toDiagnosticKind()` (`forward/ForwardDiagnostic.kt:200`)
hardcodes `NULLABLE -> ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN`, documented at `:190-196`
as safe only because `NULLABLE` was believed to be "asserted at the nullable-Boolean-return site
(ADR-061's deferred width)". But `ForwardCallablePlanner.kt` mints the identical `NULLABLE` reason
from **two** call sites with no return/input tag attached: `skipReason()`'s own `is
BridgeType.Nullable -> ... else -> ForwardPlanSkipReason.NULLABLE` (`:2361`, the return-side path)
and `inputSkipReason()`'s matching `is BridgeType.Nullable -> when (inner) { ... else ->
ForwardPlanSkipReason.NULLABLE }` (`:2466`, the parameter-side path). Both fixture examples below
are parameters (`Microchip.describe`'s `tag: Uuid?`, `Issue56Failure`'s `error: Throwable?`
constructor argument), so they hit `inputSkipReason()`'s `:2466` specifically, and still inherit
the return-only kind and hint text `toDiagnosticKind()` gives every `NULLABLE` reason regardless of
which of the two sites produced it.

**Why it went unnoticed**: the file's own doc comment already flags the fragility ("a future reason
that is genuinely ambiguous between input and return position would need the planner to carry that
distinction explicitly"), but every nullable type that reached this path before was in fact at a
return position (the nullable-Boolean-return case `NULLABLE` was named for). Nothing exercised the
input or constructor path with a nullable-but-otherwise-unclassified type until `Uuid?` and
`Throwable`-bearing constructors did.

**Discovered alongside** [#56](https://github.com/xxfast/kotlin-native-nuget/issues/56) parts 2 and 3,
[ADR-106](docs/adr/106-uuid-mapping.md) and [ADR-107](docs/adr/107-throwable-property-mapping.md); both
member shapes are named-skips only before their fix, not silent drops, so the mis-kind is a
misleading message, not a correctness bug. Distinct from the existing
`SKIPPED_UNSUPPORTED_RETURN`-names-"Boolean" item
([details](skipped-unsupported-return-diagnostic-names-boolean-non.md)): that one is about the
hint's wording claiming the wrong *type*; this one is about the diagnostic *kind* naming the wrong
*position* (or a position that doesn't exist), a strictly separate defect on the same reason.
