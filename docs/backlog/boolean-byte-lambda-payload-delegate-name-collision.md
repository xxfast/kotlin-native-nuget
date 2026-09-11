# A `Boolean` and a `Byte` lambda payload collide on one delegate name

**A callback route that carries both a `(Boolean) -> Unit` and a `(Byte) -> Unit` per-call lambda
parameter registers one shared delegate name for both, so whichever one loses the
first-registration race gets the other's marshalling instead of its own.**

`CirClassTranslator.kt`'s `typeSuffix` (`translateCallbackMethod`,
`nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/cir/CirClassTranslator.kt:2501-2508`)
special-cases `kotlinType == "Boolean" -> "Byte"` before falling through to the general
`kotlinType in KOTLIN_TO_CSHARP_RETURN -> kotlinType` arm, and Kotlin's own `Byte` key in that map
(`CirTypeMapping.kt:26`) also resolves to the suffix `"Byte"`. Both payload kinds therefore produce
the identical delegate name `NugetByteVoidCallback`, even though their wire types differ:
`KOTLIN_TO_CSHARP_PARAM["Byte"]` is `sbyte` (`CirTypeMapping.kt:43`), while the by-value fix widens
a `Boolean` payload from a `byte` on the C# side (`bool arg0 = arg0Byte != 0;`). First registration
wins (`CirClassTranslator.kt:2266-2269`, `tracker.callbackDelegates.none { it.name == delegateName }`),
so on a callback route declaring both shapes, the delegate registered second reuses the first's
parameter list and thunk, and its lambda receives the wrong marshalling for its declared type.

It went unnoticed because no fixture in the repository declares both a `(Boolean) -> Unit` and a
`(Byte) -> Unit` per-call lambda parameter on the same class; `Metronome` (the primitive-payload
fixture) only exercises `Boolean` alongside `Int`/`Double`, which have no colliding suffix with
`Byte`.

Discovered alongside [ADR-036](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/036-reverse-interop-mechanism.md)'s
2026-09-11 amendment, while implementing the by-value primitive payload on the per-call lambda
route. Verified by reading; not reproduced by a fixture.
