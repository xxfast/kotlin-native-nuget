# Wrong skip reason for a top-level lambda return with a value parameter

Discovered during the ADR-173 spike (2026-09-28), while pinning `fun petRelay(): (Pet) -> Pet`
(admitted) against the memo's proposed `fun petSupplier(pet: Pet): () -> Pet` (skipped).

- `fun petSupplier(pet: Pet): () -> Pet = { pet }`, a top-level function with an ordinary value
  parameter that returns a lambda, is named `SKIPPED_UNSUPPORTED_RETURN` in generated `Interop.cs`
  (verified, generated text at the spike's line `:15847`). The diagnostic text says "a lambda
  return only at a top-level function", which is false here: `petSupplier` already is top-level.
- `fun petRelay(): (Pet) -> Pet = { it }`, the same lambda return shape with **no** value
  parameter, renders fine on the same route. The only difference between the two functions is the
  added `pet: Pet` parameter, which the diagnostic text never mentions.
- Not investigated: which check in the lambda-return admission path (`ForwardDiagnostic.kt:853`
  and its caller) actually rejects a value parameter alongside a lambda return, or whether it is
  deliberate and simply mis-worded.
- Not load-bearing for ADR-173: `petRelay` (no parameter) was enough to exercise the lambda
  argument and read routes, so the fixture never needed `petSupplier`'s shape.

Reference: `docs/research/roadmap/generic-route-fromhandle-interface-identity.md` (deleted
alongside the ADR-173 close-out; finding 8) had the same observation with the memo's own
corrected sample.
