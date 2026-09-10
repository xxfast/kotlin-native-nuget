# A value-class emitter test fixture builds a plan the planner never produces, and an `isReceiver` flag it exercises is dead

Two independent findings from the same read, both about `ForwardKotlinPlanEmitter.kt`'s
receiver-slot handling. Neither changes generated output.

**(a) `ForwardKotlinPlanEmitterTest.kt:246-284` hand-builds a constructor plan carrying a
`RECEIVER` slot, a shape the real planner never produces.** The test (`value class constructor
returns underlying with errorOut`) puts a slot named `value`, role `RECEIVER`, type
`Primitive(INT)` on a plan whose invocation is a constructor. `ForwardCallablePlanner.kt:693` gives
every value-class constructor entry `receiver = ForwardReceiver.Static`, and
`receiverParameter(Static)` (`:2752`) returns `emptyList()`, so no constructor plan the planner
builds ever carries a `RECEIVER` slot. The fixture passes today only because the slot's type
(`Int`, not an `ObjectHandle`) makes both the guarded and unguarded code paths converge on the same
`kotlinInputType` call; it does not exercise the guard it appears to guard. It went unnoticed
because nothing else in the suite builds a constructor plan by hand with a `RECEIVER` role, so
nothing cross-checked it against `ForwardCallablePlanValidator.validateRoles` or the planner's own
construction sites.

**(b) The `isReceiver` flag on `valueClassKotlinType` and `kotlinType` in
`ForwardKotlinPlanEmitter.kt` is dead.** `valueClassKotlinType` (`:485-494`) short-circuits to
`cOpaquePointer` when `isReceiver && ObjectHandle`, but the fall-through path,
`kotlinInputType(ObjectHandle, POINTER)` at `:927`, yields the same `cOpaquePointer` for every
`ObjectHandle` input regardless of the flag (receivers are always `IN`, so the two branches never
diverge in practice). The same dead parameter is threaded through `kotlinType` (`:524-533`, called
with `isReceiver = false` at `:288` and implicitly `index == 0` at `:54`). It went unnoticed because
removing the flag requires proving the short-circuit and the fall-through always agree for every
declared role and direction, which nothing was checking; the two branches simply never disagreed on
any input the test-library fixtures or the emitter's own callers happened to construct.

Both are Verified by reading (traced against `ForwardCallablePlanValidator.validateRoles`,
`ForwardCallablePlanner.kt:643-703`, and the two call sites), not reproduced by a failing test:
fixing either is a same-file, byte-identical cleanup, not a behaviour change.

Discovered alongside [ADR-062](../adr/062-forward-callable-plan.md)'s 2026-09-10 amendment, which
dropped the `!isConstructor && index == 0` belt-and-braces guard these two findings sit next to.
