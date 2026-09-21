# The ADR-040 interface backing wrapper admits no `suspend`/`Flow`/`StateFlow` interface member

**Resolved by [ADR-159](../adr/159-async-member-on-kotlin-subclass.md) (2026-09-22): the flag-derivation half only.** `translateInterfaceBackingClass` now sets `hasSuspendMethods = false` /
`ownsScope = false` explicitly, reading the same reasoning every other class reads (`forwardScopeOwner`), rather than defaulting the flag with no derivation at all. The remaining half is unfixed: `ForwardCallablePlanner.interfaceEntries` (`ForwardCallablePlanner.kt:838`) still skips every `suspend` interface member with `ForwardPlanSkipReason.SUSPEND` before the wrapper is ever asked to dispatch one, and no Flow route runs for an interface at all, so the wrapper genuinely owns no scope today, it does not merely fail to declare one.

Whether a `Flow<T>`/`StateFlow<T>`-typed interface property is refused the same way was not
established in the time available; no refusal for that shape was found in
`ForwardPropertyPlanner.interfaceProperties`, so it may already be admitted. If it is, or ever is,
the wrapper would render a flow-returning property with no scope to collect against and no
`IAsyncDisposable` to drain it, the same gap ADR-094's 2026-09-10 amendment closed for an ordinary
class implementing an interface. If interface async members are ever admitted, the line to change is
`translateInterfaceBackingClass`'s explicit `hasSuspendMethods = false` / `ownsScope = false`, back to
reading `forwardScopeOwner` like every other class.

Unverified: not reproduced by a fixture, since no interface in `test-library` currently declares a
`Flow`/`StateFlow`-typed member. Discovered alongside
[ADR-094](../adr/094-reflection-free-generic-dispatch.md)'s 2026-09-10 amendment (disposable base
list) and cross-referenced from [ADR-040](../adr/040-interface-return-type-mapping.md).
