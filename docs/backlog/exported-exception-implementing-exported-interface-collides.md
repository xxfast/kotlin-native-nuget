# An exported exception implementing an exported interface leaves `Exception.Source` as the interface implementation

Found with issue #612 (ADR-241). Verified in a Tier 1 compile; `Tier1ExportedExceptionMemberCollisionTest`'s KDoc records it.

An exported exception class that implements an exported interface whose member collides with an `Exception` member (`interface Sourced { val source: String }`) is not a skipped member: the interface's own member renders on the interface, and the class's `System.Exception.Source` then implicitly implements `ISourced.Source`. That is CS8766 on nullability under warnings-as-errors, and the wrong value either way (the exception's source, not the Kotlin property).

The fix is an explicit interface implementation for the colliding member, which is not contained: it changes how a class implements an interface, not just how an exception class is rendered. `SKIPPED_EXCEPTION_MEMBER_COLLISION` covers only members the class declares itself.
