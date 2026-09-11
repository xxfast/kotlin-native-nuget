# A subclass declaring one overload of a name it also inherits from a generic base keeps both

> Extracted verbatim from `ROADMAP.md` (Phase 3: Basic type support).

**A subclass that declares one overload of a member name it also inherits, substituted onto it
from a generic base, gets both the inherited base member and its own declared overload rendered
in C#, rather than the declared one overriding the inherited one.** `isDeclaredBy`
(`nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/forward/ForwardClassMembership.kt:256-263`)
decides membership for a member KSP hands back substituted onto the subclass (parent test plus a
declared-list lookup), but the declared-list lookup matches by simple name only, not by the
member's full signature (arity, per-position parameter types). So `class Named(name: String) :
Parcel<String>(name) { fun value(extra: String): String = ... }`, where `Parcel<T>` declares a
`value` property substituted onto `Named` as `String Value`, would see `isDeclaredBy` match
`value` by name against the declared function and treat the inherited property as also declared,
keeping both `Value` (inherited) and `Value(string)` (declared) in the same C# class rather than
routing correctly.

Went unnoticed because item 15's fixture (`test-library/.../test/parcel/Parcel.kt`,
`NamedParcel.own()`) declares no member sharing a name with anything `Parcel<T>` exports, so the
declared-list lookup and the true intent never diverge. Verified by reading only
(`ForwardClassMembership.kt:251-254`'s own comment names the gap), no fixture reaches it.
Discovered alongside [ADR-101](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/101-unexported-supertype-skip.md)'s
2026-09-11 generic-base amendment.
