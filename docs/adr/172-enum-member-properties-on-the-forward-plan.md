# ADR-172: Enum member properties move onto the ADR-062 forward property plan

## Status

Accepted (2026-09-28)

## Context

An enum member property getter's `@CName` export (`addEnumExports`, `exports/EnumExports.kt`) had
no error channel:

```kotlin
@CName("test_cat__mood_get_nineLives")
public fun export_test_cat__mood_get_nineLives(ordinal: Int): Int {
  val mood: Mood = Mood.entries[ordinal]
  return mood.nineLives
}
```

Verified by spike: a scratch getter that threw `IllegalStateException` compiled and packed
cleanly, and a `dotnet test` run reading it crashed the whole test host.
`Record.Exception` never got a chance to run; the run printed `Test Run Aborted.` This is the exact hazard
[ADR-024](024-sync-exception-propagation.md)/[ADR-030](030-property-exception-propagation.md)
closed for every other property route; the enum member getter was the one route that never
got it.

This one hand-written route (Kotlin: `EnumExports.kt`; C#: `CirEnumRenderer.kt`) is shared by
every enum-member-property surface: a top-level enum, a nested enum
([ADR-133](133-nested-types.md)), and an [ADR-157](157-enum-armed-sealed-interface.md) enum arm
of a sealed interface all export their member properties through it. Reading it surfaced three
more gaps beside the missing containment, none of them symptoms of the same one-line fix:

- An enum `var`'s setter was never read (`isMutable` unchecked on either half): a mutable enum
  member silently exported as get-only, with no diagnostic naming the dropped setter.
- There was no type gate: `EnumExports.kt` handed any type to `ClassName.bestGuess`, and the C#
  side fell back to `mapReturnType`'s raw `IntPtr`. A class-typed member crossed as an unowned
  pointer instead of an owned wrapper, `Int?` would have exported a plain `Int` over a nullable
  read, and a `List`/`Uuid`/`Instant` member had no conversion at all.
- KDoc never reached C#: `CirEnumProperty` carried no `doc` field.

## Decision

Enum member properties are retired from their own hand-written route and reclassified as an
ordinary shape on the [ADR-062](062-forward-callable-plan.md) forward property plan: a new
`ForwardPropertyPosition.ENUM_MEMBER`, receiver `ForwardPropertyReceiver.Value(BridgeType.Enum)`
(the same receiver shape [ADR-132](132-extension-receiver-shapes.md) already lowers to
`Mood.entries[receiver]` on Kotlin and `(int)receiver` on C#). `CirEnumProperty` is deleted;
`CirEnum` instead carries `extensionMembers: List<CirMember>`, the plan-projected getters and
setters, rendered into the same `{Enum}Extensions` partial class an ordinary extension property
already uses.

This gets every getter the same containment every other property route already has, for free,
because it is the same route:

```kotlin
@CName("test_cat__mood_get_nineLives")
public fun export_test_cat__mood_get_nineLives(`receiver`: Int, errorOut: COpaquePointer?): Int = try {
  Mood.entries[receiver].nineLives
} catch (e: Throwable) {
  if (errorOut != null) {
    errorOut.reinterpret<COpaquePointerVar>().pointed.value = NugetHandles.retain(buildError(e))
  }
  0
}
```

```C#
private static extern int Native_MoodGetNineLives(int receiver, out IntPtr error);

public static int NineLives(this Mood mood)
{
    int nativeResult = Native_MoodGetNineLives((int)mood, out IntPtr error);
    if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);
    return nativeResult;
}
```

A throwing getter now surfaces as a catchable `KotlinInvalidOperationException`
(`IntegrationTests/EnumMemberPropertyContainmentTests.cs`,
`Mood_NineLives_ThrowingGetter_SurfacesAsMappedKotlinException`), not a host abort, and the route
still answers on the next call.

### What moved along with containment

Landing on the plan closes the getter's containment gap and, as the same move, the three other
gaps found while reading the old route, because the plan already had all three:

- **A `var` binds a setter**, new public surface: `SetX(this Mood mood, value)`, containment
  included.

  ```C#
  private static extern void Native_MoodSetNickname(int receiver, string value, out IntPtr error);
  public static void SetNickname(this Mood mood, string value)
  {
      Native_MoodSetNickname((int)mood, value, out IntPtr error);
      if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);
  }
  ```

  A throwing setter (`require(value >= 0)`) is equally contained
  (`Mood_TreatsEaten_Setter_RoundTripsAndThrowingSetterIsContained`).
- **The plan's type gate now applies to enum members.** `Int?` gets the ADR-002 two-call
  presence/value pair instead of a raw nullable read; a class-typed member (`Toy FavouriteToy()`)
  gets an owned wrapper the caller must dispose, where the old route handed back a raw,
  unowned `IntPtr`; `List`/`Uuid`/`Instant` marshal through the plan's existing conversions; a
  lambda-typed member (`val onPet: () -> Unit`) is a named `SKIPPED_UNSUPPORTED_PROPERTY` skip
  instead of exporting a broken shape.
- **KDoc reaches C#** (`plan.doc`), where `CirEnumProperty` carried none.
- **A keyword or `Error`-named enum gets a legal receiver parameter name**, incidentally: the
  receiver is minted through the shared `csharpParameterName()` escape
  (`CirClassTranslator.kt:3768`), the same one every other parameter name on the plan already
  goes through, so an enum named `Error` or a C# reserved word no longer produces an illegal
  receiver parameter.

`Tier1EnumMemberPropertyPlanTest` pins the getter/setter containment, the type gate (`Int?`,
a class-typed member, a `List`, and a lambda-typed member's named skip) and the KDoc carry-over
in one fixture.

### Naming, unchanged

Two spellings the old route already had are preserved, by decision rather than by the plan's
default: the public getter stays the bare property name (`Description()`, not the extension
projection's `GetDescription()`), and the receiver parameter keeps the lowercased enum name
(`mood`, not `receiver`), both pinned by
`Mood_ExistingGetters_KeepTheirBareNamesAndReceiverName`, including a named-argument call
(`MoodExtensions.Description(mood: Mood.Happy)`). Neither is a rename of any already-published
member.

### `propertyFor` and the two collisions this surfaces

`propertyFor` (the extension-property route's plan lookup) excludes `ENUM_MEMBER` plans: an
enum member property is selected by position, through a dedicated `enumMembersOf` walk, not by
the by-name lookup the extension route uses. Two same-named declarations over one enum now
collide in a way that used to be a silent double-export or an internal crash, and are now both
named diagnostics:

- A member `val grooming` beside an extension `fun Coat.grooming()`: both render
  `Grooming(this Coat …)` in the one `CoatExtensions` partial class,
  [ADR-034](034-secondary-constructor-exceptions.md)'s `ERROR_CSHARP_SIGNATURE_COLLISION`
  (`Tier1EnumMemberExtensionNameClashTest`, `an enum member property and an extension function of
  one name fail generation naming both`).
- A member `val grooming` beside a (member-shadowed) extension `val Coat.grooming`: both plans
  claim the one C entry point `…__coat_get_grooming`, a pre-existing hazard on the old route too,
  now a named [ADR-117](117-forward-abi-collision-names-owning-declarations.md)
  `ERROR_C_ENTRY_POINT_COLLISION`, with the owner tag `(enum member property)` distinguishing the
  two routes in the message (`Tier1EnumMemberExtensionNameClashTest`, `an enum member property and
  an extension property of one name resolve to their own plans`).

### Side fix: skip remarks on the C# enum itself

`CirSkipRemarks`'s `else -> this` branch swallowed a skip remark targeting `CirEnum` (the
lambda-typed member above, for example): the `<remarks>` paragraph naming the dropped member
never reached the generated enum. Fixed alongside this move, since the new type gate is what
first produces an enum-targeted skip remark on a fixture.

### No ABI version bump

[ADR-127](127-nuget-runtime-library.md)'s `NugetRuntimeAbi1` versions only the fixed `nuget_*`
runtime block; per-library exports (this route's externs) regenerate on both halves together on
every `packNuget`, so the getter's added `errorOut` parameter and every new setter export are not
a compatibility hazard the way a runtime-block change would be.

## Alternatives Considered

### Patch the hand-written route directly (rejected)

Add `errorOut` and a `try`/`catch` to `EnumExports.kt`'s getter body, and an `out IntPtr error`
extern slot plus a throwing block body to `CirEnumRenderer.kt`. Priced at 2 production files
against the plan route's roughly 9. Closes the getter abort alone. Rejected because it leaves a
second, hand-maintained property route that still silently drops `var` setters, still has no
type gate (a class-typed member stays a raw `IntPtr`, an `Int?` member stays wrong), still has no
KDoc, and diverges further from where every other property position has already gone
([ADR-062](062-forward-callable-plan.md)). It does not reach the same end state the plan route
does, so per house practice it is the alternative, not the recommendation.

## Consequences

- `errorOut`/`out IntPtr error` is an ABI change on every enum member getter export: the private
  extern name also changes (`Native_GetX` → `Native_MoodGetX`), regenerated on both halves
  together, so no consumer-visible break beyond the usual "rebuild both halves" rule other
  per-library ABI changes already carry.
- Public surface: every enum `var` member now has a setter that did not exist before
  (`SetX(this Mood mood, value)`), and a class-typed member getter now returns an owned wrapper
  instead of a raw `IntPtr`. Source-compatible for any caller that was already ignoring the
  meaningless raw pointer, but a new disposal obligation.
- `docs/topics/enums.md` and `docs/topics/supported-features.md` updated; the enum topic's
  now-false "there is no generated setter" limitation line is removed.
- `LeakTests/LiveHandleTests.cs` row 1h, `EnumMemberClassTypedGetter_UsingDispose_ReturnsToBaseline`,
  pins the class-typed getter's owned wrapper returning to baseline on `Dispose`.
- Left open, deliberately not touched by this ADR: enum member *functions* and companion
  functions/properties still have no route (`SKIPPED_ENUM_MEMBER_FUNCTION`,
  `SKIPPED_UNSUPPORTED_PROPERTY`); see ROADMAP.md Phase 4, "An enum member function ... not
  bound."

## Prior art

- **ADR-062** is the plan itself; this follows the same one-shape-at-a-time migration precedent
  ADR-111/116/118/124/160 already set for moving a sealed arm's members, and now an enum's own
  members, onto it.
- **ADR-132** established the `Value(BridgeType.Enum)` receiver lowering this route reuses
  unchanged.
- **ADR-117** is the entry-point collision diagnostic this move now also reaches from the enum
  member side.
