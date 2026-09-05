# Explicitly overridden supertype members on a value class are never exported (#57's first ask).

**What is skipped**: `@JvmInline value class Name(val value: String) : CharSequence` with
`override val length`, `override fun get(index)` and `override fun subSequence(...)` declared
directly on the class reports `SKIPPED_INHERITED_MEMBER` for all three, and every one is absent from
the generated `readonly record struct`. The consumer keeps the underlying `Value` property and
nothing else. Issue [#57](https://github.com/xxfast/kotlin-native-nuget/issues/57) asked for either
the members to bridge or the hint to stop recommending what the code already does; PR #63 closed
it with the hint correction only.

**Why it stays skipped**: [ADR-082](../adr/082-value-class-inherited-members.md) ratifies the skip
as permanent and is explicit that an explicit `override` *is* the inherited signature. It rejected
`findOverridee()` as a discriminating signal because a klib-loaded `by` delegation forwarder is
indistinguishable from a hand-written override once the declaring module is consumed cross-module,
so any "bridge overrides, skip delegations" rule would be unsound for the multi-module case the
ADR was written for.

**What taking it up would need**: reopening ADR-082 with a signal that separates the two shapes.
The candidates are the signature-level (rather than simple-name) inherited check the ADR's
amendment already approved as a follow-up, or an explicit opt-in (an annotation, or a DSL entry)
that the user applies to the members they know are hand-written. Either way the exported struct
member has to call through the value class's underlying property on the Kotlin side, which is the
same wire the ADR-082 escape hatch (a non-colliding signature) uses today, so the boundary
mechanism is not the blocker; the decision is.

**When it is worth it**: only if a consumer needs the `CharSequence`-wrapper identifier idiom
bridged as members rather than reached through the underlying `Value` from C#. No current
consumer does.
