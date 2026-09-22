# `const val` names get no after-casing collision guard

**Symptom.** Two `const val`s that convert to the same C# name inside one owner (an `object`, a
companion, or the top-level file class) still render as two same-named `public const` members,
which is CS0102 at the consumer's compile with no build-time diagnostic. The sibling enum-entry
route (`translateEnum`) gained a fatal `ERROR_CSHARP_NAME_COLLISION` guard as part of issue #285's
ADR-006 amendment (`emitEnumEntryNameCollisions`, `cir/CirClassTranslator.kt`); `translateConstProperty`
(`cir/CirTranslator.kt:1517-1529`) did not get the equivalent, even though it shares the same
`kotlinConstantToPascalCase()` helper and can produce the same kind of collision (`MAX_RETRIES` and
`maxRetries` both convert to `MaxRetries`).

**Cause.** No grouping/collision check exists on the `const val` route at all. Adding one needs an
owner-scope grouping key first: unlike an enum (where every entry is a direct child of one
`KSClassDeclaration`), a `const val`'s owner can be a top-level file, an `object`, or a companion,
so the guard has to group by "the same generated static/const container", not by a single
`enum.declarations` walk.

**Coverage gap.** No fixture declares two same-owner `const val`s that collide after casing.

**Discovered by:** the issue #285 research memo (`docs/research/roadmap/enum-entry-casing.md`,
open what-question 3 and the "Files an implementation touches" section) and confirmed by reading the
shipped `d2cf8aac` diff: `emitEnumEntryNameCollisions` is enum-only (`cir/CirClassTranslator.kt`),
`translateConstProperty` (`cir/CirTranslator.kt`) has no equivalent call. Verified by reading.
