# A member with a nullable lambda return receives two named-skip warnings instead of one

> Extracted verbatim from `ROADMAP.md` (Phase 4).

`fun ask(cb: (Int) -> String?): String?` produces two separate `SKIPPED_UNSUPPORTED_INPUT` warnings
for the one member: the planner's own refusal (`legacyRefusedCallbackMember`,
`forward/ForwardLegacyRouteCollections.kt:142`) and the class walk's independent nullable-lambda-
return refusal (`refusedNullableLambdaPayload`, consulted through `isArmCallbackRoutable` and its
non-arm counterpart in `cir/CirClassTranslator.kt`, around `:909`). Both emit through
`ForwardDiagnosticSink.emit` (`forward/ForwardDiagnostic.kt:475`), which appends every diagnostic
handed to it in emission order with no dedupe key on member plus reason, so a consumer reading
`NugetDiagnostics.json` or the KSP warning log sees the same member named twice for what is one
underlying decision. Verified by reading; no fixture. Discovered alongside ADR-036's 2026-09-22
amendment (boundary-nullability-gaps item).
