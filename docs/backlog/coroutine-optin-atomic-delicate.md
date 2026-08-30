# Re-evaluate the generated `@OptIn` for `CoroutineStart.ATOMIC` (marker moved since kotlinx.coroutines 1.9)

> Extracted verbatim from `ROADMAP.md` (Phase 6).

The opt-in was originally added for `CoroutineStart.ATOMIC` (ADR-021's Further Refinements). Since kotlinx.coroutines 1.9, `ATOMIC`'s marker is `@DelicateCoroutinesApi`, not `@ExperimentalCoroutinesApi` (both `WARNING` level, so this never gated a compile error, only a diagnostic). Suspend/Flow-using generated output built against coroutines >= 1.9 therefore likely emits a `DelicateCoroutinesApi` warning today; adding that marker to the now-gated `@OptIn` would silence it. Inferred from the coroutines changelog, not verified against a real consumer build log; deliberately deferred out of this fix's scope.
