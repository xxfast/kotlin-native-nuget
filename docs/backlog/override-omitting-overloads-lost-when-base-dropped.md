# An overriding member whose defaults live on a dropped base loses its short C# overloads

> Extracted verbatim from `ROADMAP.md` (Phase 3: Basic type support).

**Once ADR-101's base-class amendment drops an unexported base and re-homes its members onto the
subclass, an inherited member the subclass overrides no longer gets the ADR-096 omitting-overload
treatment, so a caller loses the short C# overloads for its trailing defaulted parameters.**
`ForwardCallablePlanner.kt`'s synthesis loop (`if (method.modifiers.contains(Modifier.OVERRIDE))
return@forEachIndexed`, around line 800) skips synthesis for any method carrying the Kotlin
`override` modifier, unconditionally, whether or not the forward-direction `isOverride` bit (which
now reads `false` once the base is unexported and dropped) would otherwise allow it: the gate reads
Kotlin's own keyword, not the plan's C# override status. So `class X : UnexportedBase() { override
fun greet(name: String, loud: Boolean = false) = ... }` compiles with only the full-arity
`Greet(name, loud)`, never the omitting `Greet(name)` overload a non-overriding sibling with the
same defaulted signature would get. Went unnoticed because no shipped fixture combines an
unexported base with an overridden defaulted-parameter member; the ADR-101 amendment's own fixture
(`Issue42Derived`) overrides nothing. Verified by reading only, no fixture. Discovered alongside
[ADR-101](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/101-unexported-supertype-skip.md)'s
2026-09-05 amendment.
