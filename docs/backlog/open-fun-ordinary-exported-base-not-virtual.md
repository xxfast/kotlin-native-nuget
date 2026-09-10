# An `open fun` on an ordinary exported base still renders no `virtual`, and a subclass `override` is CS0506

**A declared `open fun` on an ordinary exported base class (no interface, no `override` keyword
of its own) never renders `virtual` in the generated C#, so a Kotlin subclass overriding it renders
`public override ...` against a non-virtual base member and fails CS0506.** Verified by a Tier 1
probe: `open class Kennel { open fun describe() }` / `class Crate : Kennel()` renders `public string
Describe()` on `Kennel` and `public override string Describe()` on `Crate`.

The cause is `ForwardCallablePlanner.kt:906-908` (`classEntries.entryFor`):

```kotlin
val isVirtual: Boolean = omitted == 0 && superClass == null &&
    method.modifiers.contains(Modifier.OVERRIDE) &&
    !method.modifiers.contains(Modifier.FINAL)
```

`isVirtual` only ever looks at `Modifier.OVERRIDE`; a plain `open` declaration carries `OPEN`, not
`OVERRIDE`, so this predicate is always false for it. `isOverride` at `:904-905` has the mirror
problem on the subclass side: it renders `override` whenever the exported base declares a method of
the same name, regardless of whether the base's own member was `virtual`.

It went unnoticed because every fixture that reaches the `virtual` arm gets there through an
`override` of an *interface* member (`Animal.fetch` overriding `Pet.fetch`, ADR-040) or an
`override` of an *unexported* base's member (`Issue42Derived`, ADR-101), both of which set
`superClass == null` at the gate and so take the `OVERRIDE && !FINAL` branch. No fixture before this
feature declared a plain `open fun` on an ordinary exported base that a subclass then overrides;
`Rhythm.tempo()` (issue54) is final, and `Job.describe()` (issue115) is on a sealed base, which
takes the separate ADR-009 sealed route where both flags are pinned false.

The fix is the same predicate the property route now uses (`Set<Modifier>.isOpenForOverride()` in
`ForwardClassMembership.kt`), applied at this call site: `isVirtual = omitted == 0 && !isOverride &&
method.modifiers.isOpenForOverride()`. About three lines; `isOverride` already folds `omitted == 0`
so a synthesized omitting overload (ADR-096) stays neither, as it does today.

Discovered alongside [ADR-101](../adr/101-unexported-supertype-skip.md)'s 2026-09-10 amendment,
which fixed the property half (`open val`/`open var`) of the same predicate gap but deliberately
left the fixture with no overridden `open fun`, to keep the method half a separate, split-out fix.
