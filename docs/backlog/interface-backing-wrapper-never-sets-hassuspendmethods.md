# `translateInterfaceBackingClass` never sets `hasSuspendMethods`

**The ADR-040 interface backing wrapper (`sealed class Foo : IFoo, ...`) always renders with
`hasSuspendMethods = false`, so it never gains `IAsyncDisposable`, a scope field, or
`GetOrCreateScope()` even if a member of `IFoo` suspends or returns `Flow<T>`/`StateFlow<T>`.**
`translateInterfaceBackingClass` (`CirClassTranslator.kt:~1998-2007`) constructs the wrapper's
`CirClass` without deriving the flag from the interface's own members the way `translateClass`
does for an ordinary class three lines later.

Harmless today only because `ForwardCallablePlanner.interfaceEntries` (`ForwardCallablePlanner.kt:838`)
skips every `suspend` interface member with `ForwardPlanSkipReason.SUSPEND` before the wrapper is
ever asked to dispatch one. Whether a `Flow<T>`/`StateFlow<T>`-typed interface property is refused
the same way was not established in the time available; no refusal for that shape was found in
`ForwardPropertyPlanner.interfaceProperties`, so it may already be admitted. If it is, or ever is,
the wrapper would render a flow-returning property with no scope to collect against and no
`IAsyncDisposable` to drain it, the same gap ADR-094's 2026-09-10 amendment closed for an ordinary
class implementing an interface.

Unverified: not reproduced by a fixture, since no interface in `test-library` currently declares a
`Flow`/`StateFlow`-typed member. Discovered alongside
[ADR-094](../adr/094-reflection-free-generic-dispatch.md)'s 2026-09-10 amendment (disposable base
list) and cross-referenced from [ADR-040](../adr/040-interface-return-type-mapping.md).
