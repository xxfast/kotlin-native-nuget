# `IDerived : IBase` may declare neither interface's inherited async member

Where only `IDerived` is reachable (no function or property ever types a member `IBase`),
[ADR-174](../adr/174-interface-async-members.md) is expected to leave an async member `IBase`
declares and `IDerived` inherits off both generated interfaces, even though the ADR-040 backing
wrapper for `IDerived` still implements it publicly (dispatching through `IBase`'s own exports).

This has not been verified against a fixture: no `test-library` interface hierarchy combines an
inherited `suspend`/`Flow`/`StateFlow` member with an unreachable base interface. If confirmed, a
caller holding the wrapper through neither generated interface type (only the concrete backing
class) can still call the member; a caller holding it through `IBase` or `IDerived` cannot, unless
ADR-174's placement rule (which follows `ForwardInterfaceHierarchy`, the same as the sync route) is
extended to redeclare an inherited async member whenever it is not already visible through the
kept interface chain.

Discovered alongside [ADR-174](../adr/174-interface-async-members.md).
