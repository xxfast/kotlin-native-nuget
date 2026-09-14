# A sealed base member whose trailing default lives on the interface it overrides gets ADR-096 omitting overloads

- ROADMAP: line 41 as of 2026-09-14: "`sealedBaseEntries`' own ADR-096 pass (`ForwardCallablePlanner.kt` ~:1065) still counts raw `method.parameters.map { it.hasDefault }`, so a sealed **base** member that itself overrides an interface member with a trailing default (or is an `actual`) synthesizes no omitting overload (`CS7036`/`CS1501` on the short call)"
- Researched: 2026-09-14, about 8 of 15 minutes, reading only (no spike, no build)
- Restatement: forward. `interface Shape { fun scale(f: Double = 1.0): Shape }` plus `sealed class Tone : Shape { override fun scale(f: Double) }` must give the C# `Tone` base an omitting `Scale()` beside `Scale(double)`, and every arm inherits both, so `tone.Scale()` compiles.
- Verdict: fix. One-line change in the planner, no ADR (ADR-152 stays reserved, unused). The class route and the arm route already read defaults through the override chain; the sealed-base route is the last of the three still reading the raw bit.

## Findings

All **verified by reading** unless marked otherwise. Line numbers are against `main` at `112475a1`.

1. **The helper the class route uses** is `memberDefaultFlags(method)` at `nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/forward/ForwardCallablePlanner.kt:1515`. It walks `generateSequence(method.findOverridee()) { it.findOverridee() }.lastOrNull()` to the **root** overridee and ORs each parameter's own `hasDefault` with the root's positional bit. Added by ADR-096's 2026-09-11 amendment (`docs/adr/096-*.md:189-194`), because KSP reports `hasDefault = false` on every parameter of an `override` (Kotlin forbids restating a default; ADR-096 `:394` marks the bit **Verified** on KSP 2.3.10).
2. **`classEntries` uses it**: `ForwardCallablePlanner.kt:1080`, `repeat(memberDefaultFlags(method).trailingCount())`, gated by `isCsharpOverride(method)` (`:1019`, `overridesBaseClassMember(superClass)`), which is false for an override of an *interface* member, so a base-less class overriding a defaulted interface member synthesizes (ADR-096 `:186-188` says so explicitly).
3. **`sealedSubclassEntries` uses it** since ADR-116's 2026-09-13 amendment: `ForwardCallablePlanner.kt:1341`, gated by `method.findOverridee() in plannedBaseMembers` (`:1337`). `docs/adr/116-*.md:651-655` records the arm-side fix as "as `classEntries` already does for the same reason".
4. **`sealedBaseEntries` does not use it**: `ForwardCallablePlanner.kt:1197` still reads `method.parameters.map { it.hasDefault }.trailingCount()`. The ROADMAP's `~:1065` is stale; the pass is at `:1191-1200`. Why: the base pass was written in ADR-116's 2026-09-11 amendment when `memberDefaultFlags` was hours old and the base was assumed to be the *root* of every override chain it carries (comment at `:1194-1195`: "the base is the carrier, so it owes its own omitting overloads; every arm that overrides the member inherits them"). That assumption fails exactly when the base itself overrides: a Kotlin interface member (`sealed class Tone : Shape`) or, in principle, an unexported superclass member. The 2026-09-13 amendment fixed the arm pass and the gate but did not revisit the base pass.
5. **Consequence chain for the cell**: base `Tone.scale` is planned (`parentDeclaration == sealed` at `:1138`, not suspend/generic/callback, `isVirtual = true` via `isOpenForOverride()` at `ForwardClassMembership.kt:469-470` since `override` without `final` is open). Its raw flags are all `false`, so no `Scale()` on the base. An arm's `override fun scale` has `findOverridee() == Tone.scale`, which *is* in `plannedBaseMembers`, so the arm returns early at `:1337` and synthesizes nothing either. Result: no `Scale()` anywhere, `CS1501` on `tone.Scale()` / `arm.Scale()`. Matches the ROADMAP symptom. Verified by reading, not reproduced.
6. **After the fix**, the same gate at `:1337` is what makes "every arm inherits them" true again: the base carries `Scale()` (non-virtual, `omitted > 0` forces `isVirtual = false` at `:1157-1158`), the arm is gated out, C# inheritance supplies the short call on the arm. No arm-side change needed.
7. **The C# sealed base does not implement the Kotlin interface**: `CirSealedRenderer.kt:16` renders `public abstract class X : IDisposable, INugetHandle`, and `CirSealedClass` (`cir/CirModel.kt:129-`) carries no interface list. So the synthesized `Scale()` on the base cannot collide with anything the generated `IShape` carries (ADR-096 `:186-188`: the generated interface carries no overload anyway). `isOverride` stays pinned `false` on the base (`:1170`), which is right: there is no C# base above it.
8. **Rendering of base-synthesized entries already works**: `CirSealedRenderer.kt:40` loops `sealed.methods`, projected from base-keyed plans, and the base pass has produced synthesized entries since 2026-09-11. **Inferred**: no shipped fixture has a sealed base member with its own *planned* default (grep of `Tier1Sealed*.kt` and `issue115/JobSample.kt` found only `Job.tag`, which ADR-115 declines), so the base's synthesized overload has never been rendered end to end. The Tier 1 cell below adds a control member for it.
9. **No fixture covers the case**: no `sealed class X : SomeInterface` exists in `test-library/src` or the Tier 1 tests (grep for `sealed class [A-Za-z]* *: *[A-Z]` in both, comments only). `issue115/JobSample.kt:213` `sealed class Job` has no supertypes; `issue54/SealedInterfaceSample.kt` is sealed *interfaces* with arms, not a sealed class implementing an interface.
10. **The `actual` half of the ROADMAP line is not sealed-specific**: `memberDefaultFlags` never consults the ADR-074 expect index; `topLevelDefaultFlags` doc at `:1543-1545` says "No other route consults it in v1; class/object/companion/extension read the exported declaration's own bit only". An `actual` member function on an ordinary class has the same gap. Sharing the helper closes the interface-override case for the sealed base and leaves `actual` members at parity with `classEntries`. Deferred below.

## Recommendation

Replace the raw read at `ForwardCallablePlanner.kt:1197` with `memberDefaultFlags(method).trailingCount()` and update the comment at `:1194-1195` to say the base is the *C# carrier*, not necessarily the Kotlin root, so the flags come through the override chain. One line of code, one comment. Files: 1 processor file, 1 new Tier 1 test, 1 fixture edit, 1 xunit test edit, 2 ADR amendment notes, ROADMAP line removal.

Rejected:
- Move the gate so the *arm* synthesizes for an interface-rooted base member: duplicates `Scale()` on base and arm once the base is fixed, and contradicts ADR-116's "the base is the carrier" posture.
- Consult the expect index for member functions in the same change: a separate, unspiked pairing rule (ADR-091 already names it) that touches `classEntries` too. Out of scope.
- ADR: none. Same mechanism as ADR-096's 2026-09-11 amendment and ADR-116's 2026-09-13 amendment, third and last site.

## Files an implementation touches

- `nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/forward/ForwardCallablePlanner.kt` (`:1191-1200`, one call plus comment)
- `nuget-processor/src/test/kotlin/io/github/xxfast/kotlin/native/nuget/processor/tier1/Tier1SealedBaseInterfaceDefaultTest.kt` (new; template `Tier1SealedArmOmittingOverloadDeclinedBaseTest.kt`, reuse its `armBody`/`baseBody` helpers)
- `test-library/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/test/issue115/JobSample.kt` or a new `issue115/ToneSample.kt`: top-level `interface Shape` with a defaulted member, `sealed class Tone : Shape` overriding it, two arms (one overriding, one inheriting)
- `IntegrationTests/SealedBaseMemberTests.cs` (short call through the base and through an arm)
- `docs/adr/096-default-argument-overloads.md`, `docs/adr/116-sealed-subclass-methods.md`: one-paragraph amendment each, dated
- `ROADMAP.md:41` (delete), `FEATURES.md` (sealed row note if one carries the ADR-096 column)
- No `LeakTests` row: the synthesized overload reuses the declared arity's receiver and result kinds (same reasoning as ADR-116's 2026-09-13 amendment)

## Sample test

Tier 1 cell (generator-only, red today on the `Scale()` asserts):

```kotlin
package tier1.sealedbaseinterfacedefault

interface Shape {
  fun scale(f: Double = 1.0): Double
}

sealed class Tone : Shape {
  // The default lives on Shape.scale; Kotlin forbids restating it here.
  override fun scale(f: Double): Double = f

  // Control: a base-declared default the base already owns, pins the base pass renders its own synthesized arity.
  open fun fade(steps: Int = 3): Int = steps

  data class Loud(val db: Int) : Tone() {
    override fun scale(f: Double): Double = f * db
  }

  data object Quiet : Tone()
}

fun anyTone(): Tone = Tone.Quiet
```

Asserts: Kotlin has `@CName("tone_scale")`, `@CName("tone_scale_2")` with body `scale()` (zero positional args), `@CName("tone_fade_2")`; no `tone_loud_scale_2` and no `tone_quiet_scale*`. C# `baseBody` contains `public virtual double Scale(double f)` and `public double Scale()`; `armBody("Loud")` contains `override double Scale(double f)` and does **not** contain `Scale()`; `armBody("Quiet")` contains no `Scale(`. `compiledClean` true.

Return type is `Double`, not `Shape` as the ROADMAP-side example had it: an interface return on a sealed base adds an ADR-040 interface-return dependency the cell should not carry. The `Shape` return can be a second cell later if wanted.

xunit (`SealedBaseMemberTests.cs`):

```csharp
[Fact]
public void ScaleOmittingOverloadIsInheritedFromTheBase()
{
    using Tone tone = Tone.Loud(db: 2);   // via the fixture factory
    Assert.Equal(tone.Scale(1.0), tone.Scale());
    Assert.Equal(2.0, ((Tone.Loud)tone).Scale());
}
```

## Deferred scope

- `actual` member functions with `expect`-side defaults (both `classEntries` and the sealed routes). Needs the ADR-091 signature-pairing rule for members. Keep a ROADMAP line for it, reworded to drop the sealed-specific framing.
- The C# sealed base implementing the Kotlin interface (`Tone : IShape`). Separate feature, not needed for the short call.
- An unexported superclass (klib) above a sealed base carrying the default: `memberDefaultFlags` walks to the root so it should work, but ADR-096's klib verification was on an ordinary class only. Inferred.

## Open what-questions

- Does the sample library get a new file (`ToneSample.kt`) or grow `JobSample.kt`? Main thread recommendation: new file, `JobSample.kt` is already 250 lines of amendment history.
- Keep `Shape`-typed return as a second Tier 1 cell? Recommendation: no, unless the interface-return-on-sealed-base path is already green elsewhere.
