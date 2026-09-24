# A public `var` with `private set` makes the property planner emit a Kotlin setter call that does not compile.

> Discovered alongside [ADR-164](docs/adr/164-optional-default-parameters.md) (issue #297), via the fixture.

**A public `var` with `private set` makes the property planner emit a Kotlin setter call that does not compile ("Cannot access 'clicks': it is private").** Found while writing the `Button`/`clicks` fixture for ADR-164 (`test-library/.../issue297/Issue297Sample.kt`); worked around in the fixture by making the property `private` instead of `private set`. Not traced beyond the compile error; the property route in `nuget-processor` (the planner is unknown, start at the property export in `exports/`) generates a wrapper call to the setter without checking that the setter's own visibility is narrower than the property's.
