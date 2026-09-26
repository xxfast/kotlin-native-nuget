# An enum member getter export has no exception containment

**Symptom (inferred).** `EnumExports.kt`'s generated `@CName` getter body is a bare two-statement
function:

```kotlin
val mood: Mood = Mood.entries[ordinal]
return mood.isCuddly
```

There is no `errorOut` parameter and no `try`/`catch` around the property read, unlike every other
forward property/method route since [ADR-030](../adr/030-property-exception-propagation.md)/
[ADR-031](../adr/031-constructor-exception-propagation.md). A getter body that throws (a custom
getter doing real work, not just a `when` over `this`) would propagate a raw Kotlin exception across
an `@CName` boundary with no containment, which Kotlin/Native's `@CName`-exported functions abort the
host process for on an uncaught throw, rather than reach C# as a catchable `KotlinException`.

**Verification status.** Verified by reading the generated Kotlin (`addEnumExports` in
`exports/EnumExports.kt`); the runtime effect (host abort on an uncaught exception crossing a plain
`@CName` boundary) is inferred from the documented Kotlin/Native behavior and the containment every
other route already has, not reproduced by a crashing fixture.

**Fix shape.** Route the enum getter body through the same `errorOut` + `try`/`catch` shape
`ForwardCallablePlanner`'s ordinary property/method routes already use. This changes the enum
getter's C ABI (an added `error` out-parameter), so it is not a drop-in patch to the existing export
signature; sequence it with any other enum-route ABI change.

**Discovered by:** the ROADMAP line 24 fix (camelCase enum property entry points), while reading
`EnumExports.kt` to fix the entry-point spelling.
