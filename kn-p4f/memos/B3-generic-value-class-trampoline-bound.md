# A function's own `T` inside a generic value class binds whatever its bound

- ROADMAP: line 62 as of 2026-10-22: "A function's own `T` inside a generic value class whose bound needs the ADR-198 trampoline (`T : Comparable<T>`, an intersection) is skipped, named: the re-wrap sits outside the trampoline ([ADR-220](docs/adr/220-generic-value-class-record-struct.md))"
- Researched: 2026-10-22, about 6 of a shared 35 minute budget (researched together with B1, B2, B4)
- Restatement: forward. `fun <T : Comparable<T>> ranked(sticker: Sticker<T>): T`, `fun <T : Enum<T>> nap(s: Sticker<T>): Sticker<T>`, and `fun <T> both(s: Sticker<T>): Sticker<T> where T : CharSequence, T : Comparable<T>` bind in C# (`Ranked<T>(Sticker<T>) where T : IComparable<T>` or with the bound dropped as ADR-198 does), with the bound checked in Kotlin.
- Verdict: fix. **ADR-220 amendment**, not its own ADR: the ROADMAP line's premise is wrong (see finding 2), so there is no design choice, only a gate to lift. Reserved ADR-227 goes unused; say so in the ledger.

## Findings

1. **The skip site.** `forward/ForwardMemberTypeParameters.kt:154-159`, `hasSpellableBound()`: inside `isOwnInsideGenericValueClass()` (`:164-184`) the own `T` at the underlying position must have `bounds.size <= 1 && bounds.none { mentionsAnyTypeParameter() }`. Anything else falls out of the admission and the generic "its type parameter is nested in `Sticker<T>`" refusal fires (`:215-219`). Pinned as a skip by `Tier1GenericValueClassTest.kt:64` (`ranked`) and `:158`. **Verified** by reading.
2. **The premise "the re-wrap sits outside the trampoline" is false.** `FunSpec.trampolined()` (`forward/ForwardKotlinPlanEmitter.kt:1371-1393`) takes the export's whole `body` and moves it into the local `nugetTrampoline` (`.add(body)`), so the unwrap of every parameter, the call, and the result's `.value` all run inside it. And the re-wrap lowering already reads a trampolined `T`: `valueClassUnderlyingLowering` (`:2407-2420`) passes `underlying.typeVariable()` to `forwardBoundedRead`, and `typeVariable()` (`:1239`) is the Kotlin name exactly when `trampolined`. **Verified** by reading.
3. **`T : Comparable<T>` does not even need the trampoline.** `hasUnspellableBound()` (`ForwardBridgeTypeClassifier.kt:1480-1482`) is false for it because `Comparable` is `in`, so the erased spelling `Comparable<Any?>` is within the bound (ADR-198's own rule). ADR-220's gate refused it only because the bound *mentions* a type parameter. **Verified** by reading; the compile is **verified** by spike (below).
4. **Intersections take the trampoline through ADR-217.** `needsTypeVariable` (`ForwardMemberTypeParameters.kt:240-256`) is true for a multi-bound `T` that a parameter mentions and none witnesses. `witnessesTypeParameter` (`ForwardMemberTypeParameters.kt:280-284`) counts only a bare `T` and an ADR-212 collection of it, so `Sticker<T>` is not a witness and an intersection-bound `T` inside it takes the trampoline. **Verified** by reading; the end-to-end compile of the generated export is **inferred** until the Tier 1 cell runs.
5. **Spike: every lowered shape compiles and runs** (Kotlin/Native 2.4.20; repo pins 2.4.10):
   ```kotlin
   fun <T : Comparable<T>> rank(s: Sticker<T>): Sticker<T> = s
   fun <T : Enum<T>> nap(s: Sticker<T>): Sticker<T> = s
   fun <T, U> both(s: Sticker<T>): Sticker<T> where T : CharSequence, T : Comparable<T> = s
   println(rank(Sticker(read as Comparable<Any?>)).value)                         // erased, no trampoline
   fun <T : Enum<T>> nugetTrampoline(): Any? = nap(Sticker(m.let { b -> b as Enum<*>; b as T })).value
   fun <T> tramp2(): Any? where T : CharSequence, T : Comparable<T> =
     both<T, Nothing>(Sticker(s.let { b -> b as CharSequence; b as Comparable<*>; b as T })).value
   ```
   ```
   $ kotlinc-native main.kt -o probe && ./probe.kexe
   5
   HAPPY
   oreo
   ```
   **Verified** (hand-written in the generated shape; not the generator's own output).

## Recommendation

Delete `hasSpellableBound()` and its use in `isOwnInsideGenericValueClass()`, so the underlying position admits `type.ownParameter()` alone. Then:

- `T : Comparable<T>`: the plan's type argument is the erased bound (`ForwardMemberTypeParameters.kt:83-87`), the read is `x as kotlin.Comparable<Any?>` through `forwardBoundedRead`, and Kotlin infers `Sticker<Comparable<Any?>>`. No trampoline.
- `T : Enum<T>` or an invariant self-bound: `hasUnspellableBound()` is true, the member's type parameter carries `trampolineBounds` (`:72-81`), and the whole body runs in `nugetTrampoline` with `Sticker(x.let { b -> b as Enum<*>; b as T })`.
- An intersection: ADR-217's `needsTypeVariable` sends it to the trampoline (finding 4).
- C# constraints follow ADR-198 unchanged: `where T : struct, global::System.Enum` for `Enum<T>`, `IComparable<T>` if that bound is spelled today (else `INFO_DROPPED_BOUND`), interface intersections as `where T : IA, IB`.
- The ADR-220 "own `T` with a trampolined bound inside `Sticker<T>`" named-skip row and the comment at `ForwardMemberTypeParameters.kt:152-153` go; the diagnostics GENERIC hint (`ForwardDiagnostic.kt:1430`) loses that clause.

Same change applies to B1's class-`T` members (a `value class Ranked<T : Comparable<T>>`'s members) and B2's collection and nullable positions, since they share `isOwnInsideGenericValueClass`. Land B3 first.

Alternatives rejected: a separate trampoline for the re-wrap (unneeded, finding 2).

## Files an implementation touches

- `nuget-processor/src/main/.../forward/ForwardMemberTypeParameters.kt` (`hasSpellableBound`, `isOwnInsideGenericValueClass`)
- `nuget-processor/src/main/.../forward/ForwardDiagnostic.kt:1425-1432` (hint text)
- Tier 1: `Tier1GenericValueClassTest.kt` (`ranked` flips to bound; add `nap` and `both` cells that compile the generated Kotlin)
- Fixture `test-library/.../stickers/Stickers.kt`; `IntegrationTests/GenericValueClassTests.cs`; `LeakTests/LiveHandleTests.cs`
- Docs: `docs/adr/220-generic-value-class-record-struct.md` (amendment), `docs/topics/value-classes.md:75-78`, `docs/topics/supported-features.md`

## Sample test

Fixture:

```kotlin
fun <T : Comparable<T>> ranked(sticker: Sticker<T>): T = sticker.value
fun <T : Comparable<T>> higher(a: Sticker<T>, b: Sticker<T>): Sticker<T> = if (a.value >= b.value) a else b
fun <T : Enum<T>> napOf(sticker: Sticker<T>): Sticker<T> = sticker
fun <T> loudest(sticker: Sticker<T>): Sticker<T> where T : CharSequence, T : Comparable<T> = sticker
```

```csharp
[Fact]
public void ComparableBound_InsideTheStruct_Binds()
{
    Assert.Equal(3, Stickers.Ranked(new Sticker<int>(3)));
    Assert.Equal("Oreo", Stickers.Higher(new Sticker<string>("Mylo"), new Sticker<string>("Oreo")).Value);
}

[Fact]
public void EnumSelfBound_InsideTheStruct_RunsThroughTheTrampoline()
{
    Assert.Equal(Mood.Sleepy, Stickers.NapOf(new Sticker<Mood>(Mood.Sleepy)).Value);
}

[Fact]
public void IntersectionBound_InsideTheStruct_RunsThroughTheTrampoline()
{
    Assert.Equal("Oreo", Stickers.Loudest(new Sticker<string>("Oreo")).Value);
}
```

Leak row:

```csharp
[Fact]
public void GenericValueClassTrampolinedBound_Boxes_ReturnToBaseline()
{
    long before = NugetMarshal.LiveHandles;
    for (int i = 0; i < 100; i++)
    {
        _ = Stickers.Higher(new Sticker<string>("a"), new Sticker<string>("b"));
        _ = Stickers.NapOf(new Sticker<Mood>(Mood.Sleepy));
    }
    Assert.Equal(before, NugetMarshal.LiveHandles);
}
```

## Deferred scope

None.

## Open what-questions

1. Amendment or ADR-227? Recommendation: amendment to ADR-220; release the number.
