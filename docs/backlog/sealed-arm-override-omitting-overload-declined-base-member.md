# An overriding arm's omitting overload is missing when the sealed base declined the member (CS1501)

**What breaks**: an arm's `override fun` with defaulted parameters, whose sealed base *also*
declares the member but the base's own plan happened to decline it (a structural skip, not the
covariant-narrowing case item 35 handles), gets no [ADR-096](../adr/096-function-default-parameters.md)
omitting overload generated. A consumer calling the short arity fails to compile, `CS1501` ("no
overload takes N arguments"), against a `Base` or `Base.Arm`-typed reference either way.

**Root cause**: `ForwardCallablePlanner.kt:1052` (`sealedSubclassEntries`) keeps an early return for
`Modifier.OVERRIDE`: `if (OVERRIDE && method.findOverridee()?.parentDeclaration == sealed) return`.
Item 35 narrowed this from the old blanket `OVERRIDE` skip to a base-ownership check, which is
correct exactly when the base's own `sealedBaseEntries` walk actually planned the member the arm
overrides (the base then carries the omitting overloads itself, so the arm needs none of its own).
But `findOverridee()` resolves structurally, off the Kotlin declaration graph, not off what the
planner actually planned: if the sealed base's own member was itself dropped by a structural skip
(a lambda parameter, a generic, or any other `droppedFromCSharp` reason `sealedBaseEntries` applies),
`findOverridee()` still points at the base, the early return still fires, and the arm's own omitting
overloads are never synthesized, even though nothing on the base exists to provide them.

**Why it went unnoticed**: no shipped fixture pairs a sealed base member the planner structurally
declines with an arm that overrides it using defaulted parameters; both `NestedShape` and `Job` keep
every base-declared member in the ordinary planned shape. Inferred from reading `ForwardCallablePlanner.kt`,
not verified by execution.

**Discovered alongside** [ADR-116](../adr/116-sealed-subclass-methods-on-the-callable-plan.md)'s
2026-09-11 amendment (item 35), which fixed the general form of this early-return bug (it used to
fire for *every* `OVERRIDE`, unconditionally, because no sealed C# base carried anything to override
before item 35) but left this narrower residual case, where the base member exists in Kotlin but was
never planned in C#, in the same shape as the original bug.
