# An enum member function, and a function declared in an enum's companion object, are not bound at all

- ROADMAP: line 33 as of 2026-09-29: "An enum member function, and a function declared in an enum's companion object, are not bound at all: each is a named skip (`SKIPPED_ENUM_MEMBER_FUNCTION`) today, contradicting ADR-006's Decision that methods map to extension methods the way properties do." ([details](../../backlog/enum-member-and-companion-functions-not-bound.md))
- Researched: 2026-09-29, about 20 minutes of a 20 minute budget.
- Restatement: forward (Kotlin declares, C# consumes). `enum class Mood { ...; fun isLoudNow(): Boolean }` becomes `public static bool IsLoudNow(this Mood mood)` in the existing `MoodExtensions` partial class; a companion `fun fallback(): Mood` becomes `public static Mood Fallback()` in that same class (called as `MoodExtensions.Fallback()`), because a C# enum cannot declare members and the generated code's language floor (C# 12) has no static extension members.
- Verdict: fix, as an ADR-006 amendment (draft below). No new ADR: the route choice follows the ADR-172 precedent one-to-one, and the companion placement has exactly one legal home at LangVersion 12 (verified by spike).

## Findings

1. **The backlog file's "Cause" paragraph is stale.** It cites `exports/EnumExports.kt:37` walking `enum.getAllProperties()` and a `CirEnumProperty` in `CirModel.kt`. Since ADR-172 (2026-09-28) neither exists: `EnumExports.kt:19-26` only selects ENUM_MEMBER property plans off the catalog, and `CirEnum` (`cir/CirModel.kt:304-331`) carries `extensionMembers: List<CirMember>` projected off those plans. **Verified** by reading. The fix shape the backlog proposes ("give `CirEnum` a method list mirroring `CirEnumProperty`, walk the functions in `EnumExports.kt`") is therefore the legacy shape ADR-172 retired; do not implement it.

2. **Both skips come from one producer outside the plan.** `warnEnumMemberFunctions` (`NugetProcessor.kt:642-681`) walks `declarations` (not `getAllFunctions()`), filters `Visibility.PUBLIC`, `isCompilerOwnedMember`, and the synthesized `values`/`valueOf` (`ENUM_SYNTHESIZED_FUNCTIONS`, `NugetProcessor.kt:684`), then does the same over a public companion. Called at `NugetProcessor.kt:2209`, beside `warnEnumCompanionProperties` at `:2208`. `owner = null`, so no `<remarks>` reaches the C# enum. **Verified** by reading; the filter set yielding exactly the two author functions is **verified** by `Tier1EnumCamelCasePropertyTest` (`an enum member or companion function is a named skip`, asserts `skips.size == 2`).

3. **Companion properties are not bound today either.** `warnEnumCompanionProperties` (`NugetProcessor.kt:594-629`) names every public companion property as `SKIPPED_UNSUPPORTED_PROPERTY`, including a `const val` (it does not filter `Modifier.CONST`). **Verified** by reading, and by `Tier1EnumCamelCasePropertyTest` (`an enum companion property is a named skip`). **Inferred** (not run): a `const val` in an enum companion is caught by the same walk, since nothing excludes it.

4. **Every piece the plan route needs already exists.**
   - The enum receiver lowering. `ForwardReceiver.Value(BridgeType.Enum)` lowers to `Mood.entries[receiver]` in Kotlin and `(int)mood` in C#. `extensionEntry` (`forward/ForwardCallablePlanner.kt:2113-2235`) already plans `fun Mood.rallyCry()` this way, shipped by the `test-library` fixture `cat/ReceiverParityExtensions.kt:62`. **Verified** by reading plus the shipped fixture.
   - The Kotlin call spelling. For the EXTENSION origin, `invocationExpression` (`forward/ForwardKotlinPlanEmitter.kt:1006-1011`) renders `${receiverExpression}.$functionName($arguments)`, i.e. `Mood.entries[receiver].isLoudNow()`, which is also exactly how a *member* call is spelled. **Verified** by reading. That this compiles and dispatches for a member (including an `abstract fun` whose body lives on each entry) is **inferred**: it is ordinary Kotlin member dispatch, and the identical `Mood.entries[receiver].nineLives` member read ships under ADR-172. Not spiked with kotlinc; the first Tier 1 compile of the fixture settles it.
   - Companion statics. `companionEntries` (`ForwardCallablePlanner.kt:2008-2041`) plans `origin = COMPANION`, `target = owner`, export `${prefix}_companion_${name}`. Kotlin renders `pkg.Mood.fallback()` (`ForwardKotlinPlanEmitter.kt:1025-1026`), and C# renders a static method over a `Native_Companion_$identifier` extern (`forward/ForwardCirPlanProjection.kt:277-320`). It runs only for `classes` (`ForwardCallablePlanner.kt:850`); `enums` reaches `catalog(...)` (`:766`) but is passed on to the property planner only (`:856-858`). **Verified** by reading.
   - Per-call lambda parameters. `STORED_CALLBACK_ORIGINS` (`ForwardCallablePlanner.kt:4775-4781`) does not include EXTENSION or COMPANION, so a new origin kept out of it plans ADR-160 per-call callbacks. **Inferred** from reading; not exercised for an enum receiver.
   - Collision gate. `emitEnumExtensionSignatureCollisions` (`cir/CirClassTranslator.kt:2891`, called from `cir/CirTranslator.kt:754-775`) already compares ENUM_MEMBER property methods with extension-function methods in `{Enum}Extensions`. **Verified** by reading.

5. **Only one exhaustive `when` over `ForwardCallableOrigin`** exists (`ForwardKotlinPlanEmitter.kt:1006`). A new origin costs one arm there plus one in `ForwardCirPlanProjection`. **Verified** by `grep "when (.*origin)"`.

6. **C# 14 static extension members are not available; a plain static in the extension class is.** The generated code is compiled at `net8.0` / `LangVersion 12.0` (`nuget-plugin/.../NugetCompileInteropTask.kt:89-90`, `GeneratedBindingsCheck/GeneratedBindingsCheck.csproj:11-12`). **Verified by spike** (2026-09-29, `dotnet` 10.0.301, scratch `classlib` with `net8.0` and `<LangVersion>12.0</LangVersion>`):
   ```C#
   public enum Mood { Happy = 0, Sleepy = 1 }
   public static partial class MoodExtensions { public static bool IsSleepy(this Mood mood) => ...; }
   public static partial class MoodExtensions {
       public static bool IsLoudNow(this Mood mood) => ...;
       public static Mood Fallback() => Mood.Happy;            // companion fun
       public static Mood Fallback(this Mood mood) => mood;    // same-named member fun
       public static bool IsDefault => true;                   // companion val
   }
   // call sites: Mood.Sleepy.IsLoudNow(); MoodExtensions.Fallback(); Mood.Sleepy.Fallback(); MoodExtensions.IsDefault
   ```
   `dotnet build` output: `0 Error(s)`. Adding `public static class E { extension(Mood) { public static Mood Fallback2() => Mood.Happy; } }` to the same project gave `error CS1001: Identifier expected`, `CS1513`, `CS1022`, so C# 14 extension blocks are ruled out at the repo's language floor.

7. **Receiver parameter spelling is currently inconsistent inside `MoodExtensions`.** ADR-172 kept `mood` for ENUM_MEMBER property methods (`ForwardCirPropertyProjection.enumMember`, `forward/ForwardCirPropertyProjection.kt:127-172`), while an extension function over the enum renders its receiver as `receiver` (`ForwardCirPlanProjection.extension`, `:385-450`, `receiver.csharpName`). **Verified** by reading.

8. **Collisions this route newly reaches.** Kotlin allows a property `description` and a function `description()` on one enum, a companion `fun describe(mood: Mood)` beside a member `fun describe()`, and a member `fun setNickname(v: String)` beside `var nickname`. Each pair renders the same C# signature in `MoodExtensions` (`CS0111`). **Inferred** (by reading, not spiked). The existing ERROR_CSHARP_SIGNATURE_COLLISION gate must see the new methods, or the consumer's compile fails instead of generation.

9. **ADR-157 enum arms.** `sealedSubclassEntries` skips an enum arm (`ForwardCallablePlanner.kt:~825`, comment: "What Kotlin declares on the enum belongs to `{Enum}Extensions` (ADR-006) and is planned there"). A sealed base's own members still plan on the base. ADR-175 finding 5 (`Note.pitch` is `SKIPPED_ENUM_MEMBER_FUNCTION`) becomes partly stale once a sync `pitch` binds as `NoteExtensions.Pitch(this Note)`. **Verified** by reading; the effect on ADR-175's fixture is **inferred**.

## Recommendation

**The plan route (end state): a new `ForwardCallableOrigin.ENUM_MEMBER` for the instance half, and the existing COMPANION origin for the companion half, both on the ADR-062 catalog.** This mirrors ADR-172, where enum member properties got `ForwardPropertyPosition.ENUM_MEMBER`.

- Planner: add `enumEntries(enum)`. It walks the enum's *declared* public functions with `warnEnumMemberFunctions`' exact filter (so the "exactly the two author functions" pin stays true), numbers overloads as `classEntries` does, and calls `planOrSkip(receiver = ForwardReceiver.Value(BridgeType.Enum(enum), name = "receiver"), origin = ENUM_MEMBER, exportName = "${enum.nativePrefix(symbols)}_$name$suffix", publicName = name.replaceFirstChar(::uppercaseChar), member = name, defaults = memberDefaultFlags(method), doc = ...)`. The structural gate is SUSPEND or GENERIC, with *no* ABSTRACT skip: an abstract enum member is the whole point of per-entry bodies, and `Mood.entries[receiver].f()` dispatches it. Chain `.nameUnroutedPositions { false }` (no legacy route is keyed to an enum) and `.ownedBy(enum.forwardDiagnosticOwner())` so skips reach the enum's `<remarks>` (ADR-172 side fix). Call it and `companionEntries(enum)` from `catalog(...)` over `enums`.
- Kotlin emitter: `ENUM_MEMBER -> "${receiverExpression(receiver)}.$functionName($arguments)"`, the EXTENSION spelling. `EnumExports.kt` adds `callableCatalog.enumMethods(q)` and `companionMethods(q)` through `addForwardKotlinPlanExport`.
- C#: `ForwardCirPlanProjection.extension` gains a receiver-name parameter (`mood`, see what-question 1). `static` already accepts COMPANION. Put the results into `CirEnum.extensionMembers` (`CirClassTranslator.kt:3946-3954`) and feed them to `emitEnumExtensionSignatureCollisions` (`CirTranslator.kt:754`).
- Delete `warnEnumMemberFunctions` (the plan names what it cannot route) or narrow it (see what-question 3).
- Free with the plan: every parameter and return type the plan admits, overload numbering, default arguments (ADR-164), error slot and containment (ADR-024), KDoc (ADR-150), per-call lambda parameters (ADR-160, inferred), and nullable returns through the ADR-170 single-call `valueOut`.

Priced at roughly 7 production files: `ForwardMarshallingModel.kt`, `ForwardCallablePlanner.kt`, `ForwardKotlinPlanEmitter.kt`, `ForwardCirPlanProjection.kt`, `CirClassTranslator.kt`, `CirTranslator.kt`, `EnumExports.kt`, plus `NugetProcessor.kt` for removing the skip producer. Folding in companion properties (what-question 2) adds `ForwardPropertyPlanner.kt` and `ForwardCirPropertyProjection.kt` (about +2).

Alternatives rejected:
- **A hand-written walk in `EnumExports.kt` plus a `CirEnumMethod` list** (the backlog's fix shape). Roughly 3 files, but it rebuilds the ADR-172-retired second route with no error slot, no type gate, no KDoc and no overloads. It does not reach the end state.
- **Reuse `ForwardCallableOrigin.EXTENSION`** instead of a new origin. It saves the one `when` arm, but ENUM_MEMBER plans would then look like extension plans to anything that selects by origin, and the member-versus-extension distinction the ADR-117 owner tag and the ADR-132 `SHADOWED_BY_MEMBER` logic rely on would be lost. ADR-172 made the same call for properties (a new position, excluded from `propertyFor`).
- **C# 14 `extension(Mood) { static Mood Fallback() }`**, which would allow `Mood.Fallback()`. Ruled out by spike at LangVersion 12 (finding 6).
- **A separate `MoodCompanion` static class for companion members.** It would be a second static class per enum with no precedent. ADR-013 folds a companion onto its owner's C# home, and `MoodExtensions` is the enum's only C# home.

## Files an implementation touches

- `nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/forward/ForwardMarshallingModel.kt` (`ForwardCallableOrigin.ENUM_MEMBER`, `:666`)
- `.../forward/ForwardCallablePlanner.kt` (`enumEntries`, call `companionEntries` over `enums` near `:850`, `enumMethods(owner)` selector near `:583-640`)
- `.../forward/ForwardKotlinPlanEmitter.kt` (`:1006` arm)
- `.../forward/ForwardCirPlanProjection.kt` (`extension` receiver name or an `enumMember` twin; `static` unchanged)
- `.../cir/CirClassTranslator.kt` (`:3946-3954` extensionMembers, `trackProperty` counterpart for callables)
- `.../cir/CirTranslator.kt` (`:754-775` collision feed)
- `.../exports/EnumExports.kt` (emit method and companion plans)
- `.../NugetProcessor.kt` (`:634-684` producer removed or narrowed, `:2209` call site; `:594` if companion properties fold in)
- `.../forward/ForwardDiagnostic.kt` (`:200-205` kind KDoc, or kind removal)
- If companion properties fold in: `.../forward/ForwardPropertyPlanner.kt` (COMPANION position over enums, `:480`), `.../forward/ForwardCirPropertyProjection.kt` (static property into `MoodExtensions`)
- Tests: `nuget-processor/src/test/.../tier1/Tier1EnumCamelCasePropertyTest.kt` (the skip pin flips to a binding pin), a new `Tier1EnumMemberFunctionPlanTest`, and ADR-175's enum-arm Tier 1 pin if it asserts the skip
- Fixture: `test-library/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/test/cat/Mood.kt` (member and companion functions), plus a new small enum with an `abstract fun`
- xunit: `IntegrationTests/EnumMemberFunctionTests.cs` (new)
- Leak rows: `LeakTests/LiveHandleTests.cs`, one row for a class-typed return (`Toy`) from an enum member function
- Docs: `docs/adr/006-enum-mapping.md` (amendment), `docs/topics/enums.md` (`#enum-member-functions-skip-named` section rewritten), `docs/topics/forward-overview.md:255-258`, `docs/topics/supported-features.md` (enum row), `docs/backlog/enum-member-and-companion-functions-not-bound.md` (delete), ROADMAP line 33 (delete)

## Sample test

Fixture additions (`cat/Mood.kt`, inside `enum class Mood`):

```kotlin
  /** Whether this mood is loud enough to wake Mylo. */
  fun isLoudNow(): Boolean = this == GRUMPY

  /** Greets [name] in this mood's voice. */
  fun greet(name: String): String = "$displayName greets $name"
  fun greet(name: String, times: Int): String = List(times) { greet(name) }.joinToString(" / ")

  /** Grumpy Oreo refuses to be petted. */
  fun pet(): Int = if (this == GRUMPY) throw IllegalStateException("Oreo hisses") else 1

  fun toyFor(): Toy = favouriteToy

  companion object {
    fun fallback(): Mood = SLEEPY
    fun byNickname(nickname: String): Mood? = entries.firstOrNull { it.nickname == nickname }
  }
```

plus a separate enum for per-entry bodies:

```kotlin
enum class Meow {
  CHIRP { override fun sound(): String = "chirp" },
  TRILL { override fun sound(): String = "trill" };
  abstract fun sound(): String
}
```

`IntegrationTests/EnumMemberFunctionTests.cs`:

```C#
using TestLibrary;
using TestLibrary.Cat;

namespace IntegrationTests;

public class EnumMemberFunctionTests
{
    [Fact]
    public void Mood_IsLoudNow_BindsAsAnExtensionMethod()
    {
        Assert.True(Mood.Grumpy.IsLoudNow());
        Assert.False(Mood.Sleepy.IsLoudNow());
    }

    [Fact]
    public void Mood_Greet_OverloadsAndStringParameterRoundTrip()
    {
        Assert.Equal("Purring Oreo greets Mylo", Mood.Happy.Greet("Mylo"));
        Assert.Equal("Snoozing Mylo greets Oreo / Snoozing Mylo greets Oreo", Mood.Sleepy.Greet("Oreo", 2));
        // named-argument call binds to the receiver's public name (what-question 1)
        Assert.Equal("Purring Oreo greets Mylo", MoodExtensions.Greet(mood: Mood.Happy, name: "Mylo"));
    }

    [Fact]
    public void Mood_Pet_ThrowingMemberFunction_IsContained()
    {
        var ex = Assert.ThrowsAny<InvalidOperationException>(() => Mood.Grumpy.Pet());
        Assert.IsType<KotlinInvalidOperationException>(ex);
        Assert.Equal("Oreo hisses", ex.Message);
        Assert.Equal(1, Mood.Happy.Pet());
    }

    [Fact]
    public void Mood_ToyFor_ReturnsAnOwnedWrapper()
    {
        using Toy toy = Mood.Happy.ToyFor();
        Assert.Equal("Purring Oreo", toy.Name);
    }

    [Fact]
    public void Meow_AbstractMember_DispatchesToEachEntrysBody()
    {
        Assert.Equal("chirp", Meow.Chirp.Sound());
        Assert.Equal("trill", Meow.Trill.Sound());
    }

    [Fact]
    public void Mood_CompanionFunctions_AreStaticsOnTheExtensionsClass()
    {
        Assert.Equal(Mood.Sleepy, MoodExtensions.Fallback());
        Assert.Equal(Mood.Grumpy, MoodExtensions.ByNickname("Hissing Oreo"));
        Assert.Null(MoodExtensions.ByNickname("Nobody"));
    }
}
```

(The `Toy.Name` accessor spelling and the `Mood.Grumpy` nickname baseline depend on the existing `Toy` fixture and on nickname test isolation in `EnumMemberPropertyContainmentTests`; the implementer should adjust to what those fixtures really expose.)

Tier 1 (`Tier1EnumMemberFunctionPlanTest`): assert `@CName("…__mood_isLoudNow")` with `Mood.entries[receiver].isLoudNow()` and an `errorOut` slot; `public static bool IsLoudNow(this global::Interop.Mood mood)` with `[return: MarshalAs(UnmanagedType.I1)]` on the extern; `…__mood_companion_fallback` and `public static global::Interop.Mood Fallback()`; `suspend fun`, `fun <T>`, and `fun ticks(): Flow<Int>` each named once (kind per what-question 3); a member `fun description()` beside `val description` fails as ERROR_CSHARP_SIGNATURE_COLLISION; compiles clean.

## Draft ADR-006 amendment text

> ## 2026-09-29 amendment: enum member functions and companion functions bind on the forward callable plan
>
> The Decision's "Methods → same pattern (extension methods)" line is now delivered, on the
> [ADR-062](062-forward-callable-plan.md) forward callable plan, the route
> [ADR-172](172-enum-member-properties-on-the-forward-plan.md) moved enum member properties onto.
> No second hand-written route is built.
>
> - **A function declared in the enum class body** plans under a new `ForwardCallableOrigin.ENUM_MEMBER`,
>   with receiver `Value(BridgeType.Enum)`. Kotlin calls `Mood.entries[receiver].isLoudNow()`, so an
>   `abstract fun` with per-entry bodies dispatches to each entry's body. C# gets
>   `public static bool IsLoudNow(this Mood mood)` in the existing `MoodExtensions` partial class,
>   with the same receiver parameter name (`mood`) the member properties already use. It carries
>   the ordinary error slot, so a throwing member surfaces as a mapped `KotlinException`. Overloads,
>   default arguments, KDoc and every parameter and return type the plan admits come with the route.
> - **A function declared in the enum's `companion object`** plans under the existing COMPANION
>   origin and renders as a plain static method in `MoodExtensions` (`MoodExtensions.Fallback()`).
>   A C# enum cannot declare members, and the static extension members that would allow
>   `Mood.Fallback()` need C# 14, above the generated code's C# 12 floor (verified by spike: an
>   `extension(Mood) { … }` block fails `CS1001` at `LangVersion 12.0`).
> - **Still named skips:** a `suspend` or generic enum member or companion function, and a
>   member returning `Flow`, have no route on an enum, and the plan names each one [kind per
>   what-question 3]. The skip remark now reaches the C# enum's `<remarks>`.
> - **Collisions:** a member function, a member property and a companion function that spell the same
>   C# signature in `MoodExtensions` (`fun description()` beside `val description`) fail generation
>   as `ERROR_CSHARP_SIGNATURE_COLLISION`, naming both declarations, rather than `CS0111` at the
>   consumer's compile.
>
> **Scope.** Generator-only (`nuget-processor`). The new exports are per-library and regenerate on
> both halves together, with no ADR-127 runtime ABI change. Additive public surface: every enum
> member or companion function that used to be skipped with a warning is now a C# member.

## Deferred scope

- `suspend` enum member or companion functions (no suspend route keyed to an enum; the extension route names the same gap).
- `Flow`/`StateFlow`-returning members (no legacy Flow route for an enum owner).
- Generic enum member functions (`fun <T>`).
- A lambda *return* (ADR-160's LAMBDA_TYPE_ARGUMENT is top-level only).
- Boxed ADR-157 enum arms getting instance methods on `NoteArm` (the enum's own `NoteExtensions` covers the enum value; the box keeps only the sealed base's members).
- Companion properties and `const val`, unless what-question 2 folds them in.

## Open what-questions

1. **Receiver parameter name on enum member functions: `mood` or `receiver`?** `MoodExtensions` today has `mood` on the ADR-172 property methods and `receiver` on `fun Mood.rallyCry()`. Recommendation: `mood`, matching the other ADR-006 enum surface. It is a named-argument-visible choice, and it is new surface, so it breaks nothing. Human decision: pending.
2. **Fold enum companion properties (and `const val`) into this item?** They are on the same path: the COMPANION property position exists (`ForwardPropertyPlanner.kt:480`), and the spike shows `public static bool IsDefault => …` in `MoodExtensions` is legal C# 12. It costs about 2 more files and moves `SKIPPED_UNSUPPORTED_PROPERTY` for companions to a binding. A `const val` would render `public const` in `MoodExtensions`, and the constants route is a third touch point. Recommendation: fold in the companion `val`/`var` (same path, per house practice) and leave `const val` a named skip unless the constants route turns out to be a one-line owner change. Human decision: pending.
3. **What happens to `SKIPPED_ENUM_MEMBER_FUNCTION`?** Option A: retire the kind, and let the plan name suspend/generic/Flow members under the standard kinds (suspend becomes `SKIPPED_UNSUPPORTED_COMBINATION` via UNROUTED_POSITION, as the extension route does; Flow return becomes `SKIPPED_UNSUPPORTED_RETURN`). Option B: keep the kind, narrowed to the structural refusals (suspend, generic), with a new reason text. Removing a kind changes what a consumer reads out of `NugetDiagnostics.json`. Recommendation: A, since it is one diagnostic vocabulary across all owners and the kind is two days old and unreleased-significant. Check against the release notes before deciding. Human decision: pending.
4. **Companion home spelling:** `MoodExtensions.Fallback()` reads oddly for a factory. The only alternative at C# 12 is a separate static class, which has no precedent. Recommendation: accept `MoodExtensions`, and record C# 14 `extension(Mood)` as a future option when the language floor moves. Human decision: pending.
