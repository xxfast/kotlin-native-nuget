# A `private` direct subclass of an exported sealed class breaks the generated Kotlin

Found with issue #612. Verified by compiling in the #612 worktree.

`private class TokenExpired : StoreException(...)` under an exported sealed `StoreException` fails Kotlin compilation of the generated `CNameExports.kt`: `Cannot access 'class TokenExpired : StoreException': it is private in file`. The sealed discriminator lists arms regardless of visibility, and the C# side emits `public sealed class TokenExpired` and registers it in two type maps.

The fix is likely to leave a non-public arm out of the discriminator, the C# declarations and both type maps, or to name it as a skip. Not yet investigated beyond the symptom.

Also observed in Tier 1 output while building ADR-241 (#612): an `internal` arm of an exported sealed class renders as a `public` C# class, which exposes a Kotlin-internal type. Not compiled or run, and the shared cause (arms enumerated regardless of visibility) is inferred.
