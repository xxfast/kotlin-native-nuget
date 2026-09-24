# The Kotlin-side `when (mask)` dispatch for a widened default can resolve against a real shorter overload instead of the intended defaulted one.

> Discovered alongside [ADR-164](docs/adr/164-optional-default-parameters.md) (issue #297). Inferred, pre-existing, no fixture.

**Kotlin resolves the generated named-argument call against a real shorter overload when one exists** (`Foo(name)` beside `Foo(name, lives = 9)`), so the "unset" branch of the `when (mask)` dispatch reaches the secondary overload instead of the intended one, the same resolution the old ADR-091/096 omitting overload's positional call already produced. Pre-existing behaviour, not a regression from ADR-164; no fixture reproduces it. ADR-164's own Consequences section names this as inferred.
