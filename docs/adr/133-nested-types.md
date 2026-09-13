# ADR-133: Nested declarations as C# nested types: `Outer.Nested` for nested `class`, `object`, `interface` and `enum class`

## Status

Accepted (2026-09-13)

> **Amended below (2026-09-13).** An extension function or property whose receiver is a
> nested type now binds under the receiver's own owner chain, the same way a member of that
> receiver already does, closing the gap this ADR's Consequences and the ROADMAP had left open. See
> "Amendment (2026-09-13): extension receivers join the owner chain".

## Context

A public Kotlin `class`, `object`, `interface`, or `enum class` declared inside another class was not
declared in C# at all: every root bucket in `NugetProcessor.kt` filtered `parentDeclaration == null`,
the ADR-066 reachability closure refused a nested dependency declaration of every bucket, and
ADR-064's 2026-09-07 amendment made the absence loud (`SKIPPED_NESTED_DECLARATION` at the declaration,
`UNDECLARED_CLASS`/`UNDECLARED_ENUM`/`UNDECLARED_INTERFACE` at every member typed with it). That
amendment named the "declare it as an actual C# nested type" alternative and deferred it because it
touches collection, three translators, the renderer, `@CName` prefixing, the closure's edge table, and
the collision check. This ADR ships it, minus the closure's edge table: the owner-walk mechanism
below turns out to need no closure change at all, so `ForwardReachabilityClosure.kt` has no diff;
see Decision.

Two nested shapes were already declared, and they fix the design: a sealed subclass nested in its base
renders as a nested C# class `Shape.Circle` ([ADR-009](009-sealed-class-mapping.md)), and a companion
folds into its owner's statics ([ADR-013](013-extension-property-mapping.md)). Every member *type
position* already spelled a nested declaration with its enclosing scope (`KSClassDeclaration.nestedCsName()`
joins the enclosing-class chain outermost-first), so the spelling half of nesting had already shipped;
this ADR ships the declaration half.

Constraints:

- One Kotlin type must produce exactly one C# type (the issue #54/#110 lesson: a second declaration is
  CS0101 in every consumer).
- Export entry points are process-global C symbols; ADR-117 raises `ERROR_C_ENTRY_POINT_COLLISION` on a
  duplicate, so `Outer.Nested` and an unrelated top-level `Nested` cannot both export `nested_create`.
- No released consumer-visible name may change: a top-level declaration's prefix chain has one
  element, so its `@CName` is unchanged.

## Decision

Declare every public nested `class`, `object`, `interface`, and `enum class` as a C# nested type under
its enclosing declaration's C# type, at any depth, whether the owner is module-local or an admitted
dependency type, reusing ADR-009's nested-block rendering.

**Supported owner shapes:** a non-generic, non-`inner` `class` (root or admitted dependency, `open`/
`abstract` included) and a non-generic `object`. **Deferred, still skipped named** (`SKIPPED_NESTED_DECLARATION`,
now naming which shape defers it): an `inner class` owner (its constructor needs the outer instance),
a generic owner (`Outer<T>.Nested` would itself be generic in C#), an `enum class` owner (no C#
declaration block to nest into), an `interface` owner (declares no nested types in the generated C#),
a sealed base or sealed arm owner (ADR-009 owns that block already), and a nested `value class`
candidate (a separate numbering space, out of scope here) regardless of its owner.

**The owner walk is the sole declarer, and it needs no closure change.** A nested declaration is
declared iff its owner is already in the admitted set (`declaredClasses`/`declaredObjects`/etc.,
root or dependency): `nestedCandidates` flat-maps `owner.nestedClassDeclarations()` over every
already-admitted owner, a direct KSP declaration-structure query, not a reachability edge. The
ADR-066 closure itself is untouched by this ADR; it still only ever admits a *root* declaration
through an ordinary member-return/parameter edge, and a nested type free-rides on its owner once
that owner is admitted, whether the owner is module-local or a dependency type reached by its own
edge (`Broadcast` is admitted because `Newsroom.broadcast(): Broadcast` is an ordinary CLASS-bucket
edge; `Broadcast.Schedule`/`Broadcast.AdBand`/`Broadcast.Defaults` are then found as `Broadcast`'s
own nested declarations, regardless of whether `Newsroom.schedule(): Broadcast.Schedule` exists at
all). Consequently a nested dependency type whose *owner* is not independently reachable by some
other edge is still never declared, no matter how directly a member names the nested type itself;
see Consequences.

**Translators.** Each nested declaration is translated by the same function a root of its kind uses
(`translateClass`, `translateObject`, `translateEnum`, `translateInterface`, and, when ADR-040's
reachability rule triggers, `translateInterfaceBackingClass`). `CirClass` and `CirObject` gained a
`nestedDeclarations` slot, filled by translating the owner's nested candidates recursively. The
dispatch in `CirRenderer.kt` was extracted into a recursive `renderDeclaration(declaration)`, called
from `renderClass`/`renderObject` for each nested entry and re-indented through ADR-009's
`indentNestedBody()`, byte-for-byte the mechanism the sealed route already used for an arm.

**Interface spelling is the one classifier change beyond what already shipped.** The `I` prefix now
attaches to the interface's **last segment only**: `global::NS.Outer.IListener` /
`global::NS.Outer.Listener`, never `global::NS.IOuter.Listener` and never a bare `global::NS.IListener`
that does not exist. The ADR-040 backing wrapper nests beside its interface (`Aviary.Keeper : Aviary.IKeeper`)
rather than at namespace root.

**Enum exception.** `renderEnumExtensions` stays at namespace level, named for the whole owner chain
(`AviaryKindExtensions`, not `Aviary.KindExtensions`): C# forbids an extension method inside a nested
class (CS1109).

**`@CName` prefixing.** A new `KSClassDeclaration.nativePrefix()` (`CirTypeMapping.kt`, beside
`nestedCsName()`) replaced every `simpleName.asString().lowercase()` prefix site across the
translators, exporters, and both forward planners: the enclosing-class chain, each simple name
lowercased, joined with `_` (`outer_nested`, `aviary_middle_inner` at depth three). The sealed route
composes on top unchanged (`"${base.nativePrefix()}_${sub.simpleName.lowercase()}"`). For a top-level
declaration the chain has one element, so no existing entry point changes.

**Owner-scope collision check.** Kotlin permits a nested type named like its owner, or like a
same-named property/method of its owner; C# does not (CS0542, CS0102 respectively; **Inferred**, not
spiked against `dotnet` in this reconciliation, see Inferred claims). Both are now a
fatal `ERROR_CSHARP_SIGNATURE_COLLISION`, naming the nested declaration and the C# name it collides
with, emitted *after* both `Interop.cs` and `CNameExports.kt` are written (the colliding nested type is
skipped, not the whole module, so the ABI contract check still runs over the rest). The collision key
for the pre-existing sealed-subclass check also moved from the bare simple name to `nestedCsName()`,
closing a false positive an unrelated top-level type with the same simple name as a sealed arm could
trigger.

**New named skip, `OBJECT_POSITION`.** A member returning or taking a Kotlin `object` (nested or
top-level: `ProbeOuter.single(): Marker`, `Newsroom.defaults()`) is not a nesting question, it is a
pre-existing hazard the nesting gate happened to also cover: an `object` renders as a C# **static**
class, and C# forbids a static type at a parameter or return position (CS0722). Removing the nesting
gate without adding this one would have put uncompilable C# into `Interop.cs` the moment a nested
object's declaration started being emitted. `OBJECT_POSITION` renders through the existing
`SKIPPED_UNSUPPORTED_TYPE` diagnostic kind, with its own sentence and hint naming the object and CS0722,
`droppedFromCSharp = true`. It applies equally to a top-level object at a member position, which was
never gated before this ADR.

### Bridge mechanism

A nested class crosses exactly as a top-level class: `outer_nested_create` mints a `StableRef` via
`NugetHandles`, `outer_nested_dispose` releases it, members are planned by the same ADR-062 plan keyed
on the qualified name. A nested object is a static class with `outer_defaults_*` static exports. A
nested enum crosses by ordinal, `outer_kind_get_prop(ordinal)`, with its extension class hoisted to
namespace level. A nested interface follows ADR-040 unchanged (`outer_listener_*` dispatch exports),
its `I`-prefix rule fixed to the last segment. **This "unchanged" claim held only for the
plan-driven sync route**; the legacy `suspend`/`Flow` routes did not, see the 2026-09-13 amendment
below. No new marshalling, no new handle kind, no
`LeakTests/LiveHandleTests.cs` mint path beyond Row 1's (Row 1a pins a nested class mints and releases
through the same `NugetHandles` route Row 1 measures for a top-level class).

## Fixture and tests

`test-library/.../nested/Aviary.kt`: one exported outer class nesting one of each kind, with members
returning and taking the nested class, a nested-enum property and parameter, a nested-interface
parameter and return, a depth-2 nested class (`Aviary.Middle.Inner`), and an object owner (`Registry`)
nesting a class for the `CirObject` path. `test-models`' `Broadcast.kt` (`Broadcast.Schedule`,
`Broadcast.AdBand`, `Broadcast.Defaults`) is the admitted-dependency cell, reached through
`Newsroom`. The three pre-existing `issue54` fixtures (`ProbeOuter`, `NestedModeOwner`,
`NestedListenerOwner`) flip from asserting absence to asserting presence, each with one member
renamed to dodge the new owner-scope collision rule: `ProbeOuter.make()` to `nest()`,
`NestedModeOwner.mode` to `setting`, `NestedListenerOwner.listener` to `attached`. `Aviary.kt`'s own
member names (`perchAt`, `habitat`, `currentKeeper`, `Registry.lookup`) were chosen the same way,
new rather than renamed, since it is a new fixture. `Tier1NestedTypesTest.kt` pins both halves in
one file: every supported owner
shape declaring its children in the right place with the right export prefix, and every deferred owner
shape (`Box<T>`, `enum class Season`, `interface Cage`, `inner class Guest` under `Host`) still emitting
`SKIPPED_NESTED_DECLARATION`.

## Consequences

- Additive for consumers: every previously skipped nested declaration now appears as `Outer.Nested`; no
  released name changes for any kind.
- `SKIPPED_NESTED_DECLARATION` survives for exactly the deferred owner and candidate shapes above,
  its reason now naming which shape defers it instead of "only top-level declarations, sealed
  subclasses and companions are declared."
- ADR-040's 2026-09-07 amendment (the nested-interface skip was named, not fixed) is closed: a nested
  interface is now declared. ADR-066's closure itself is untouched: a nested dependency declaration
  still reaches C# only by free-riding on its owner's own admission, never through a closure edge of
  its own. ADR-009's nested-block rendering is reused unmodified, generalised from "a sealed arm" to
  "any supported owner's nested declaration."
- `docs/topics/classes-and-objects.md`, `docs/topics/enums.md`, and
  `docs/topics/interfaces-abstract-sealed.md` lose their "never declared" nested-declaration sections;
  `FEATURES.md` rows for `class`, `object`, `interface`, and `enum class` gain the nested mapping.
- A property position typed with a nested class/object/interface/enum still has no dedicated reason on
  its `SKIPPED_UNSUPPORTED_PROPERTY` message for the deferred owner shapes; unchanged, tracked
  separately (pre-existing, not introduced here).
- Two bugs fixed in passing, both **Verified** by reading the diff directly. A nullable interface
  **parameter** used to skip while a nullable interface return and property both bound
  (`ForwardCallablePlanner.kt`'s `Nullable` branch gained an `is BridgeType.Interface` arm).
  `translateEnum`'s member walk now filters to `ClassKind.ENUM_ENTRY`
  (`CirClassTranslator.kt`); before this, a plain class nested inside an `enum class` rendered as an
  extra C# enum member with an ordinal no Kotlin entry has, a pre-existing defect no fixture had
  reached.
- Not built here: `ForwardReachabilityClosure` has no edge from "a member returns a nested
  dependency type" to "admit its owner", so a dependency member naming only a nested type, with no
  other edge reaching the owner itself, still declares nothing; today's fixture cell
  (`Broadcast.Schedule`) works only because `Newsroom.broadcast(): Broadcast` independently admits
  the owner through an ordinary edge. The closure also never walks a nested dependency type's *own*
  member types once it is declared (`walkClassMembers` never sees it), so a further dependency type
  reachable only through a nested type's own member is never admitted either. An extension-receiver
  typed with a nested type still spells the bare simple name on two planner sites; two legacy-route
  sites still spell the `I$simpleName` shape unfixed; the CS0542 arm of the owner-scope collision and
  the value-class/sealed/companion owner-scope arms have no fixture. Recorded as Phase 4 ROADMAP
  items.

  **(2026-09-13) Closed** by [ADR-066](066-forward-export-reachability-closure.md)'s 2026-09-13
  amendment: the closure gained both edges this bullet named, so `Almanac.Page`
  (`Newsroom.page(): Almanac.Page`, nothing else returning `Almanac`) and
  `Broadcast.Schedule.timetable(): Timetable` now admit their owner and their own dependency
  respectively. The extension-receiver, legacy-route `I$simpleName`, and owner-scope-collision
  fixture gaps this bullet also named are untouched by that amendment and remain open, still on
  ROADMAP Phase 4.
- Cost: about 20 source files (collection, classifier, diagnostics wording, `CirTypeMapping.kt`,
  `CirModel.kt`, four renderers, `CirClassTranslator.kt`, `CirTranslator.kt`, two planners, five export
  generators); `ForwardReachabilityClosure.kt` itself is not one of them. Plus one fixture file, one
  xunit file, one Tier 1 test.

## Verified claims

- Every symbol cited above (`Aviary.Perch`, `Aviary.Defaults`, `Aviary.Kind`, `Aviary.IKeeper`,
  `Aviary.Keeper`, `Aviary.Middle.Inner`, `Registry.Entry`, `Broadcast.Schedule`, `Broadcast.AdBand`,
  `AviaryKindExtensions`, and their `@CName` exports) exists in the generated `Interop.cs`/`CNameExports.kt`
  for the shipped fixture, read directly rather than inferred from the design.
- CS1109 (an extension class cannot be nested) and the "no existing entry point changes" claim, both
  `Inferred` in the original draft, are now verified by the fixture's own compiled output:
  `AviaryKindExtensions` is namespace-level and every pre-existing top-level `@CName` in the fixture is
  byte-identical.
- Verify: green, 1787 / 0 / 0, 35 (IntegrationTests/LeakTests side); processor 828.

## Inferred claims (not independently re-spiked for this reconciliation, unchanged from the design)

1. No CS0108 for a nested type's own `Native_*` externs shadowing an owner's same-named externs (no
   inheritance between a nested type and its owner).
2. `ClassName.bestGuess("pkg.Outer.Nested")` yields a nested `ClassName` in the generated
   `CNameExports.kt` (consistent with the fixture's generated Kotlin, not independently spiked against
   KotlinPoet's source).
3. CS0542 (a nested type named exactly like its owner) and CS0102 (a nested type named like a
   PascalCased member of its owner) are genuine C# compile errors: not spiked against `dotnet` in
   this reconciliation, Kotlin permits both shapes so a wrong inference fails loud in the consumer's
   build rather than silently.

## Amendment (2026-09-13): extension receivers join the owner chain

The Consequences section above named the gap: an extension function or property whose receiver is
a nested type still spelled the bare simple name, unlike a member of that same receiver, which
already chained through `nativePrefix()`/`nestedCsName()`. `fun Aviary.Perch.summarize()` exported
as `perch_summarize` into a bare `PerchExtensions`, the same C symbol and class name a top-level
`Perch`, or another owner's nested `Perch`, would also claim.

**Measured, not the predicted failure mode.** The ROADMAP line this amendment closes predicted the
collision "fails loudly as a forward ABI mismatch." It does not: the duplicate is absorbed silently
by the pre-existing ADR-095 overload-numbering suffix, so the second owner's extension ships as
`inner_describe_2` and which of the two owners keeps the unsuffixed `inner_describe` is unpinned
(presumably visit order). An untouched declaration's published ABI can move when an unrelated type
is added elsewhere, with no diagnostic at all. That is the actual defect, and a stronger argument
for chaining than a hard error would have been.

**Fix.** An extension receiver now keys on `nestedCsName()` (a class) instead of the bare
`simpleName`, at the three sites that must all spell the plan symbol identically (`CirTranslator`'s
function and property receiver grouping, `ForwardPropertyPlanner`, and `ExtensionPropertyExports`,
the third site the original draft undercounted as two): the plan/export/`@CName` prefix now runs
through `nativePrefix()` the same way a member's does (`aviary_perch_summarize`,
`aviary_perch_get_isHigh`). The generated C# extension class is chain-named at namespace level
(`AviaryPerchExtensions`), never nested in its owner, since CS1109 still forbids nesting an
extension class, the same rule the enum extension class already used. Both are byte-identical to
the pre-existing shape for a top-level receiver, whose chain is empty.

**The ADR-095 overload counter's scope grows by exactly the receiver's *owner* chain, not the
receiver itself.** `Coop.Inner.describe` and `Roost.Inner.describe` export under different prefixes
now (`coop_inner_describe`/`roost_inner_describe`) and so must not share one counter, or the second
takes a gratuitous `_2` no collision requires; the scope stops short of the receiver itself, so a
same-owner receiver's own overloads keep numbering exactly as shipped (`Mitten.pat`,
`Mitten.pat(style)`, `Tomcat.pat` stay `mitten_pat`/`mitten_pat_2`/`tomcat_pat_3`).
`ForwardAbiContract.hint` was reworded from "derived from the unqualified simple name" to "derived
from the declaration's own enclosing chain of simple names, never its package" to match.

**Residual, not fixed here.** A typealias extension receiver still keeps the alias's own lowercased
name in the C entry point while the C# extension class spells the expanded type, the pre-existing
asymmetry this amendment does not move; harmless while every nested type is reachable without going
through an alias, tracked on the ROADMAP.

Tests: `NestedTypesTests.ExtensionOnANestedReceiver_BindsUnderTheOwnerChain` (xUnit); Tier 1 gains a
single-receiver cell and a two-owner cell (`Coop.Inner`/`Roost.Inner`) pinning both the chained
symbols and the distinct `{Chain}Extensions` classes. No new handle kind, no new marshalling.
Verify: green, 1788 / 0 / 0, 35.

## Amendment (2026-09-13): the legacy suspend and Flow routes now spell a nested interface with the interface

"A nested interface follows ADR-040 unchanged" (Bridge mechanism, above) held for the plan-driven
sync route only. The legacy `suspend`/`Flow` routes spelled an interface return with the
[ADR-040](040-interface-return-type-mapping.md) *backing wrapper* instead of the interface, nested
or top-level. (The ROADMAP line this amendment replaces cited `CirClassTranslator.kt` ~:1040 and
`CirFunctionTranslator.kt` ~:751; those lines are actually where a nested interface's generic-type
bound is spelled `I$simpleName`, a related but distinct defect fixed by the Generic bounds
paragraph below. The wrapper-spelling defect this amendment fixes lives at the suspend-completion
and `Flow`-element construction sites, elsewhere in the same two files.) `suspend fun currentKeeperLater(): Keeper`
completed as `Task<Aviary.Keeper>` rather than `Task<Aviary.IKeeper>`, and `fun keepers(): Flow<Keeper>`
constructed its element the same way, which additionally fails to compile at all, since
`NugetMarshal.FromHandle<T>`'s `Activator` branch cannot construct an interface. No fixture existed
in this shape, nested or top-level, until now: every suspend/Flow return in the fixture set was a
class or a sealed arm.

**Fix.** `ForwardLegacyReturnShape.Interface(type, nullable)` is a new case in `legacyReturnShape`,
mirroring [ADR-131](131-suspend-route-sealed-base-return.md)'s `Discriminated`; the suspend
completion and the `Flow` element sites both read the classifier's `csharpType` (the interface) and
`backingType` (the ADR-040 wrapper, used only to construct the value: `new Aviary.Keeper(resultPtr)`,
`read: static h => new Aviary.Keeper(h)`).

**Generic bounds.** A nested interface used as a generic type bound is now qualified
(`where T : global::Interop.Owner.IKeeper`); a top-level interface bound stays bare (`IPet`), which
is load-bearing byte-identity for the pre-existing `PetBox<T>` fixture. A cross-namespace top-level
interface bound is still spelled wrong; pre-existing, unrelated to nesting, tracked on the ROADMAP.

**Identity asymmetry, inherited from ADR-040/ADR-084, not introduced here.** The sync plan route
resolves a returned handle back to its original C#-implemented instance first
(`NugetMarshal.TryResolveCSharp`) and only wraps when there is no original to resolve to. The
suspend and Flow reads fixed here always construct the wrapper (`new Wrapper(ptr)`), the same as the
already-shipped collection- and sealed-return reads; a C#-implemented object returned over `Task<T>`
or through a `Flow<T>` never round-trips to the original instance the way the sync return does. See
[ADR-084](084-csharp-implemented-interfaces.md)'s 2026-09-13 amendment.

**Return-reachability, verified.** [ADR-084](084-csharp-implemented-interfaces.md)'s bridge plan
(state class + C#-implementable factory) is built only from interfaces reachable at a *return*
position (`CirTranslator.interfaceBackingClasses`). A nested interface used only as a *parameter*
type gets no wrapper and no bridge plan; passing a C# implementation at that position crashes the
host with an unlocated Kotlin `NullPointerException`, no diagnostic. See ADR-084's 2026-09-13
amendment for the full finding.

Fixtures: `nested/Aviary.kt` (`currentKeeperLater`, `keepers()`), `nested/AviaryRoutes.kt`
(`anyKeeperLater`, top-level), `cat/Pet.kt` (`strayPetLater`, top-level interface). Tests:
`NestedTypesTests.cs` reflection facts, four Tier 1 cells, `LiveHandleTests.cs` Row 9h
(`Suspend_ReturningAnInterface_ReturnsToBaseline`). Verify: green, 1793 / 0 / 0, 36; processor 834.
