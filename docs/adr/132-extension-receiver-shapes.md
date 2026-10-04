# ADR-132: Forward, every admitted extension receiver shape lowers like a parameter

## Status
Accepted (2026-09-13)

## Context

`ForwardCallablePlanner.planOrSkip` admits an extension receiver through exactly the same
`inputSkipReason()` gate as a declared parameter and mints the same wire fan-out for it
(`receiverParameter` -> `nativeInputParameters("receiver", ...)`). The two renderers, however,
special-cased the receiver instead of reusing the parameter lowering: `ForwardKotlinPlanEmitter`'s
`receiverExpression` and `ForwardCirPlanProjection.extension`'s `receiverArgument` each handled
only `ObjectHandle`, `ValueClass`, and (ADR-105, 2026-09-11) `Nullable(ObjectHandle)`, and fell
through to the bare name `receiver` for every other admitted shape. A bare `Interface` receiver
(`fun Pet.describe()`) or a `Nullable(ValueClass)` receiver (`fun CatId?.orAnonymous()`) reached
that `else` arm and rendered an unresolved Kotlin reference and/or a CS1503 in `Interop.cs`. The
ROADMAP item that named this (line 18, filed alongside ADR-105's 2026-09-11 amendment) named only
these two shapes as having no fixture; reading the two `when` blocks shows the same `else` arm is
reached by every admitted shape beyond the three already handled.

A second structural gap: `resultProjection` derives its prelude/cleanup steps (`HandleOf`,
`CreateList`, GCHandle alloc, `Dispose`) from `publicSignature.parameters` only, so even a correct
`receiverArgument` string could not give an `IPet` receiver its ADR-084 stage-3 transfer-handle
lifecycle (mint on entry, dispose in `finally`): that plumbing lives in `resultProjection`, and
the receiver never entered its parameter list.

## Alternatives Considered

### 1. Route the receiver through the parameter lowerings; delete both `else` arms (chosen)

Kotlin: `receiverExpression(receiver) = loweredArgument(ForwardPublicParameter(receiver.name,
receiver.transfer.type))`, parenthesised only when the lowering is an `if`/`else` expression
(explained below). C#: the receiver becomes a `ForwardPublicParameter` at the head of
`resultProjection`'s input list, so `callArgument` (including two-slot has-value fan-outs),
`interfacePrelude`/`collectionPrelude`/`boundInterfacePrelude`, and
`interfaceCleanup`/`collectionCleanup` apply to it unchanged, and the custom-body gate collapses to
`parameters.any { !isTrivialInput() }` over that same list (no separate `needsCustomParams`/
`forceCustomBody`). Both `else` arms are deleted, so a `BridgeType` variant with no lowering hits
`loweredArgument`'s own `error(...)`, a build failure, rather than silently passing a name through.

- Pros: one lowering per shape, already exercised at parameter positions by shipped fixtures; the
  receiver is now exactly "parameter zero", which is what the planner already models it as; the
  ADR-084 mint/dispose path comes for free through the shared preludes/cleanups; removing the
  `else` arms turns a future un-lowerable `BridgeType` into a loud failure instead of a silent one.
- Cons: none realized. The three shapes the old `when` handled (`ObjectHandle`, `ValueClass`,
  `Nullable(ObjectHandle)`) render byte-identically through the shared lowering, so the ADR-077 and
  ADR-105 Tier 1 pins that assert on the exact rendered string needed no change and pass
  unmodified. `loweredArgument` produces one non-postfix form, the has-value fan-out's
  `if (receiverHasValue) Dosage(receiver) else null`, which would bind a trailing member call
  (`.orAnonymous()`) to the `else` branch if left bare; `receiverExpression` wraps it in parens
  when the lowered string starts with `"if ("`. **Corrected (2026-10-03):** that branch was
  unreachable when this was written; since the 2026-10-03 amendment below lifted the function-route
  skip it is reachable and used by every has-value fan-out extension function receiver.

### 2. Add one arm per shape to both `when`s

Mirror `loweredArgument`/`callArgument` arm by arm. Rejected: it is the approach that produced the
current defect twice already (ADR-077 added `ValueClass`, ADR-105 added `Nullable(ObjectHandle)`,
each closing only the shape its own fixture declared), it still cannot give the receiver a
prelude/cleanup without separately touching `resultProjection`, and it keeps an `else` arm that
will swallow the next variant just as silently.

### 3. Narrow `inputSkipReason` for receivers to the three shipped shapes (named skip only)

Cheapest and non-breaking, but it turns working parameter machinery into a permanent receiver
limitation for no wire reason, and an extension on `Pet` (the obvious Kotlin idiom for behaviour
on an interface) would stay unbound.

### Sub-decision: the nullable-struct receiver's C# call sites

`fun CatId?.orAnonymous()` renders `static string OrAnonymous(this global::TestLibrary.Cat.CatId?
receiver)`, i.e. a `Nullable<CatId>` receiver, since `CatId` (a `String`-underlying value class)
generates as a `readonly record struct`. **Verified with a standalone `dotnet build` probe
(2026-09-13)**: C# binds an extension receiver only through an identity, implicit-reference, or
boxing conversion; `CatId -> CatId?` is an implicit *nullable* conversion, none of those. Calling
the extension on a bare `new CatId("x")` fails **CS1929** ("'CatId' does not contain a definition
for 'OrAnonymous' and the best extension method overload ... requires a receiver of type
'CatId?'"), even though Kotlin allows calling a `T?` extension on a non-null `T`. `Cat?` (ADR-105)
is unaffected because a nullable *reference* annotation is an identity conversion, not a nullable
value-type wrapping.

- (a) Emit only the nullable-struct overload; document that a consumer calls it on a `CatId?`
  local. **Chosen**: zero extra surface, no interaction with the ADR-095/117 overload-numbering
  machinery, and it is the same `Nullable<T>` shape every other value-class position already
  exposes.
- (b) Also emit a forwarding `OrAnonymous(this CatId receiver) => OrAnonymous((CatId?)receiver)`.
  Rejected for v1: it doubles the generated surface for one shape and needs a second `CirMethod`
  routed through the ADR-095/117 overload-suffix machinery, which no other receiver shape needs.
  Revisit if a consumer asks for the non-null call site.

## Decision

Alternative 1 with sub-decision (a). Every shape beyond the three already handled now binds
through the shared parameter lowering, sharing the interface/value-class/has-value mechanisms the
parameter position already ships, with no receiver-specific code:

- `Interface`: one `POINTER` slot, borrowed, exactly the ADR-084 stage-3 interface *argument*'s own
  lifecycle. C# prelude `NugetMarshal.HandleOf(receiver, out receiverOwned)`, cleanup
  `if (receiverOwned) { NugetMarshal.Dispose(receiverHandle); }`. Kotlin
  `receiver.asStableRef<Pet>().get()`: a Kotlin-implemented `Pet` and an ADR-084 bridge object
  backing a C#-implemented one are both Kotlin `Pet`s, so one read serves both.
  **Fixture-verified** (below). `Nullable(Interface)` shares the same prelude function
  (`interfacePrelude`), which reads `nullable` and substitutes `HandleOfOrZero` for `HandleOf`
  (`ForwardCirPlanProjection.kt:700`/`ForwardCirPropertyProjection.kt:456`): routed by
  construction, no fixture.
- `Nullable(ValueClass(String))`: null pointer in-band, `receiver?.Id`; Kotlin
  `receiver?.let { CatId(it) }`. **Fixture-verified** (below). `Nullable(ValueClass(ObjectHandle))`
  is the same shape one underlying over (`receiver?.Cat._handle ?? IntPtr.Zero`), routed by
  construction, no fixture.
- `Enum`, `Uuid`, `Instant`, `Duration`, `Collection`, `BoundInterface`: exactly the shared
  parameter lowering, one slot. Routed by construction, no fixture: nothing in `test-library`
  declares any of these at a receiver position, so the claim rests on the parameter position being
  fixture-verified and the receiver now sharing its code path exactly.
  **Amended 2026-09-20** (below): `Enum` gets a fixture on this route too (`fun Mood.rallyCry()`),
  added as the control for a pre-existing `CS0260` defect the same amendment closes on the
  extension-**property** route.
- Of these, only `Nullable(String)`, `Nullable(Uuid)`, and `Nullable(Collection)` are admitted
  nullable forms that route the same way (also no fixture). `Nullable(Enum)`, `Nullable(Instant)`,
  and `Nullable(Duration)` are **not** among the routed shapes: each is a has-value fan-out and
  hits the named `RECEIVER_FAN_OUT` skip below instead. (**Superseded:** functions since
  2026-10-03, properties since 2026-10-04; see the amendments at the end of this file.)

**Fixture** (bare `Interface` and `Nullable(ValueClass(String))` only): `fun Pet.describe(): String`
and `fun CatId?.orAnonymous(): String` in `test-library/.../cat/CatExtensions.kt`. `describe()`
composes `name`, `legs`, and `speak()` rather than echoing the receiver back, so a C#-implemented
`Dog` can only produce the right string if all three interface slots actually dispatched across the
bridge; xunit in `IntegrationTests/ExtensionFunctionTests.cs` covers a Kotlin-backed `Cat`, an
anonymous Kotlin object (`strayPet()`, no generated wrapper class), a C#-implemented `Dog : IPet`,
and the `CatId?` value/null pair. Tier 1 pins the two public signatures and the compile in
`Tier1ReceiverShapesExtensionTest.kt`.

**Named skip, not a crash: `RECEIVER_FAN_OUT`.** (**Superseded, 2026-10-03 for extension
functions and 2026-10-04 for extension properties:** nothing skips any more and the reason no longer
exists; see the amendments at the end of this file.) A has-value fan-out receiver (`Int?`-style
`Nullable(Primitive)`, `Nullable(Enum)`, `Nullable(Instant)`, `Nullable(Duration)`, or
`Nullable(ValueClass(Primitive|Enum))`) cannot take this path. Its wire is the ADR-079/080
adjacent `${name}HasValue` + `$name` pair, and the plan model (`validateRoles`) allows exactly one
RECEIVER-role slot, which must be first; `nativeInputParameters` tags only the *value* half of a
fan-out with the caller's role, so such a receiver's RECEIVER slot lands at index 1 and plan
validation throws rather than compiling wrong code. This was caught and closed in the same change:
`ForwardCallablePlanner.planOrSkip` now checks
`receiverType.sealedAsHandle().isHasValueFanOutInput()` before building a plan and returns a named
`ForwardPlanSkipReason.RECEIVER_FAN_OUT` (`droppedFromCSharp = true`) instead. It renders through
the existing `SKIPPED_UNSUPPORTED_INPUT` diagnostic, since `toDiagnosticKind` treats it as
input-position by construction (the receiver is always input zero). **Inferred**: without this
gate, such a receiver would have crashed the processor with an uncaught `validateRoles` exception
rather than merely rendering wrong code, since no fixture ever reached this path before this
change. Pinned by a Tier 1 control, `fun Int?.orZero()`, in `Tier1ReceiverShapesExtensionTest.kt`:
no export, no C# binding, a warning naming `orZero`. (Sentence and hint since
[ADR-064](064-forward-unsupported-declaration-diagnostics.md)'s amendment of 2026-09-20, which
also gave the extension-property route the same reason and sentence.)

**Leak harness.** The C#-implemented interface receiver mints a transfer `StableRef` per crossing
(ADR-084 stage 3) that the old `else` arm never disposed (it bypassed `interfaceCleanup` entirely,
since the receiver never entered `resultProjection`'s parameter list). `LeakTests/LiveHandleTests.cs`
gained Row 6b, `InterfaceReceiverExtension_CSharpImplementedPet_ReleasesTransferHandle`, asserting
the mint/dispose pair returns to baseline. The nullable value-class receiver mints nothing on
either side, so it gets no row.

## Consequences

- Breaking for nobody: every shape that changes rendering did not compile before.
- The three previously-handled shapes (`ObjectHandle`, `ValueClass`, `Nullable(ObjectHandle)`)
  render byte-identically; the ADR-077 and ADR-105 Tier 1 string pins needed no change.
- Extension **properties** keep their own `supportedReceiver` gate
  (`ForwardPropertyPlanner.kt:~284`: `ObjectHandle | Primitive | String | ValueClass` only), so
  `val Pet.x` and `val Cat?.x` still skip named (`SKIPPED_UNSUPPORTED_PROPERTY`). Unifying
  extension properties onto this same lowering is a separate, unstarted item (ROADMAP Phase 4).
  **Amended 2026-09-14** (below): `val Pet.x` and `val Cat?.x` now bind; `Enum`, `Uuid`,
  `Instant`, `Duration`, and the remaining nullable spellings stay a named skip.
  **Amended 2026-09-20** (below): that narrowing is mostly lifted; `Nullable(Collection)` and
  `Nullable(BoundInterface)` remain refused, for different reasons stated there.
- `ForwardCirPropertyProjection.kt`'s own receiver-argument `when` (~lines 58-70) still has an
  `else -> "receiver"` pass-through mirroring the one this ADR removed from the callable route.
  It is unreachable today only because the property planner's `supportedReceiver` gate above never
  lets an interface or nullable-value-class receiver reach it; unifying the two routes would need
  to either delete this arm too or prove it can never fire. Not touched by this ADR.
  **Amended 2026-09-14** (below): this arm is deleted.
- Deferred: sub-decision (b) (a forwarding non-null overload for a value-class receiver);
  `Nullable(BoundInterface)` receiver (still a named `BOUND_INTERFACE_POSITION` skip, out of scope
  for this change).

## Verified vs. inferred, summarized

**Verified** (by fixture, Tier 1 test, or a standalone build):
- Bare `Interface` receiver binds, dispatches through all three interface members, and disposes
  its ADR-084 transfer handle (Row 6b).
- `Nullable(ValueClass(String))` receiver binds on both the value and null branches.
- The three previously-shipped receiver shapes render byte-identically (ADR-077/105 Tier 1 tests
  pass unmodified).
- The `CatId?`-only CS1929 asymmetry (dotnet build probe, 2026-09-13).
- The `RECEIVER_FAN_OUT` skip fires as a named warning with no export and no C# binding
  (`fun Int?.orZero()` Tier 1 control). (Historical: the skip is gone, see the 2026-10-03 and
  2026-10-04 amendments.)

**Inferred, not verified by a fixture or a build:**
- `Nullable(Interface)`, `Nullable(ValueClass(ObjectHandle))`, `Enum` (**amended 2026-09-20**:
  now fixture-verified, `fun Mood.rallyCry()`), `Uuid`, `Instant`, `Duration`, `Collection`,
  `BoundInterface`, and the admitted `Nullable(String)`, `Nullable(Uuid)`, and
  `Nullable(Collection)` forms bind correctly at the receiver position.
  Rests entirely on the parameter position being fixture-verified and the receiver now sharing
  that exact code path (`ForwardPublicParameter` reused verbatim).
- That a has-value fan-out receiver would have thrown out of `validateRoles` rather than rendering
  wrong code, had the `RECEIVER_FAN_OUT` gate not been added in the same change; no fixture ever
  reached the ungated path to observe the crash directly.

## Prior art

Not investigated separately from ADR-077 (value classes at ordinary positions) and ADR-105 (sealed
types / nullable handles at property and receiver positions); this ADR is a mechanical unification
of those two ADRs' parameter-position work with the receiver slot the planner already modeled
identically, not a new mapping decision.

## Amendment (2026-09-14): extension properties take the same lowering as extension functions

The Consequences section above named the extension-**property** route as the one place still
carrying its own receiver gate and its own `else -> "receiver"` pass-through. Both are gone.
`ForwardPropertyPlanner.extensionProperty` replaced `supportedReceiver`/`isSupportedValueClass`
with one exhaustive `BridgeType.isSupportedReceiver()` (no `else`, `ForwardPropertyPlanner.kt`),
admitting `Interface`, `Nullable(Interface)` and `Nullable(ObjectHandle)` alongside the shapes it
already supported. `ForwardPropertyKotlinEmitter.valueExpression()`, the setter-value lowering that
already had an arm for every admitted `BridgeType`, was lifted to a name-keyed
`inputLowering(name)` and `accessExpression()`'s `Value` arm now calls it with `"receiver"` instead
of falling through to the bare `receiver.$kotlinName`. `ForwardCirPropertyProjection`'s
`setterPrelude`/`setterCleanup`/`valueArgument` were re-keyed by name the same way, the getter body
is now wrapped in `forwardCirHandleScope` so a C#-implemented receiver's ADR-084 transfer handle is
minted and disposed around the read (guarded per [ADR-135](135-interface-parameter-reachability.md)),
and `extension()`'s own `else -> "receiver"` arm is deleted.

Decided narrower than the ADR-132 parameter set: `Enum`, `Uuid`, `Instant`, `Duration`, and the
remaining nullable spellings (`Nullable(String)`, `Nullable(Uuid)`, `Nullable(ValueClass)`) stay a
named `SKIPPED_UNSUPPORTED_PROPERTY` skip on the property route, even though the shared lowering
has an arm for each and admits them at the parameter and function-receiver positions. No fixture or
demand for them yet; the `SKIPPED_UNSUPPORTED_PROPERTY` hint text
(`NugetProcessor.kt`) now reads "declare the property on a class, interface, nullable class,
nullable interface, String, primitive, or value class receiver, or expose a top-level getter
function instead". A has-value fan-out receiver (`Int?`-style) is refused the same way the
function-receiver route refuses it: the property route mints exactly one slot per receiver and a
fan-out needs two. **Amended 2026-09-20** (below): this narrowing is lifted, and folded into the
same change, a `Collection` receiver and a bound C# interface receiver also bind. **Amended
2026-10-04:** a has-value fan-out receiver binds on the property route too.

**Fixture-verified:** `val Pet.summary`, `val Cat?.nameOrStray`
(`test-library/.../cat/CatExtensions.kt`), and a receiver-only interface, `val Sitter.address`
(`test-library/.../nested/CatteryDesk.kt`), proving the property plan's own arm of
`NugetProcessor.reachableInterfaceNames` (the one ADR-135 added) reaches an interface that appears
*only* as an extension property's receiver. `var Pet.tag` over an interface receiver binds too, but
with no fixture backing a runtime `var` over that shape, it is a Tier 1 compile pin only
(`Tier1ReceiverShapesExtensionPropertyTest.kt`). `LeakTests/LiveHandleTests.cs` gained Row 6h,
mirroring Row 6b for the getter route: the C#-implemented receiver's transfer handle returns to
baseline. The nullable handle receiver mints nothing on either side, so it gets no row.

## Amendment (2026-09-20): extension properties reach full receiver parity with extension functions

The 2026-09-14 amendment above left `Enum`, `Uuid`, `Instant`, `Duration`, and the remaining
nullable spellings narrower on the extension-**property** route "no fixture or demand for them
yet". `ForwardPropertyPlanner.isSupportedReceiver()` now admits all of them: `Enum`, `Uuid`,
`Instant`, `Duration`, `Nullable(String)`, `Nullable(Uuid)`, and `Nullable(ValueClass)` over a
`String` or `ObjectHandle` underlying. Folded into the same change by the human maintainer, two
receivers ADR-132's own function-receiver set already admits but the property gate had never been
asked to carry: a `Collection` receiver (`val List<String>.longestName`) and a bound C# interface
receiver from a NuGet dependency (ADR-088, `val IFeedable.feedingNote`).

Fixture: `test-library/.../cat/ReceiverParityExtensions.kt`, one declaration per receiver
mechanism so a fixture that happened to pick the cheapest shape at each seam could not hide a gap:
enum ordinal (`val Mood.emoji`), converting text (`val Uuid.shortForm`, and `var Uuid.nickname` for
the setter-carries-the-receiver-too case), converting 64-bit (`val Instant.epochDay`, tested
against a **non-UTC** `DateTimeOffset` so a wall-clock read would be a whole day off), non-converting
64-bit (`val Duration.wholeHours`), null-in-band-by-reference (`val String?.orPlaceholder`,
`val CatId?.display`, `val ChartRef?.patientName`), a `var` of nullable-primitive type over an
**interface** receiver (`var Pet.napQuota: Int?`), a collection receiver
(`val List<String>.longestName`), and a bound-interface receiver (`val IFeedable.feedingNote`).
Tests: `IntegrationTests/ExtensionPropertyTests.cs`, Tier 1
`Tier1ReceiverShapesExtensionPropertyTest.kt`.

**Three pre-existing defects, none introduced by this change, surfaced and closed by this fixture**
(all verified by a failing build first):

1. **CS0260**, shipped since this ADR's original 2026-09-13 decision on the extension-**function**
   route: `CirEnumRenderer` rendered `{Enum}Extensions` non-`partial` whenever the enum has its own
   properties, colliding with the merged extension class the moment any extension (function or
   property) targets that enum. No fixture had ever declared one; `Mood` (which has a `description`
   property) now does, on both routes (`fun Mood.rallyCry()` is the function-route control), and
   `CirEnumRenderer` now always renders `partial`.
2. **CS0103**, this change's own defect: the `NullableDispatch` setter arm
   (`ForwardCirPropertyProjection.kt`) built its body outside the handle scope, so
   `var Pet.napQuota: Int?` over an interface receiver referenced an undeclared `receiverHandle`.
   Invisible to Tier 1, which compiles the Kotlin half only.
3. **CS0103**, this change's own defect: `CollectionHelperTracker.trackProperty`
   (`cir/CirTypeMapping.kt`) tracked a property's declared *type* but never its *receiver*, so
   `val List<String>.longestName` disposed its handle through a `NugetListNative` helper the file
   never emitted.

Also: the nullable string-wire receiver import (`String?`, `Uuid?`, and a `String`-underlying
value class receiver, all of which need `string?` rather than bare `string` under
`<Nullable>enable</Nullable>`) is now spelled through one shared predicate,
`BridgeType.isNullableStringWire()` (`forward/ForwardCsharpTypes.kt` ~:122), used by both the
callable route and the property route, closing the gap between them at the receiver position.

**Refused, by decision, not merely deferred:**
- A has-value fan-out receiver (`Int?`, `Mood?`, `Instant?`, `Duration?`, or a nullable value class
  over a `Primitive`/`Enum` underlying) stays refused: the property route mints exactly one ABI
  slot per receiver, and admitting these would silently drop the null rather than fail loudly. Own
  ROADMAP line; `RECEIVER_FAN_OUT` unchanged. **Superseded 2026-10-04:** these receivers bind on
  the property route (see the amendment at the end of this file). **Dated pointer (2026-09-20):** the reason's rendered
  sentence and hint are not unchanged, only the admission decision is; both routes now name the
  receiver and offer the parameter/non-null-receiver remedies, see [ADR-064](064-forward-unsupported-declaration-diagnostics.md)'s
  same-dated amendment.
- `Nullable(Collection)` stays refused, by an explicit allowlist rather than a wire limitation.
  This is the one shape where the property gate is narrower than the function-receiver route,
  which has no receiver allowlist for a nullable collection; no fixture exercises
  `Nullable(Collection)` at either position.
- `Nullable(BoundInterface)` stays refused, and this **is** parity: ADR-088 refuses a nullable
  bound interface at every position, not only here.
- `ByteArray` and `Char` receivers stay refused; neither is in this ADR's own function-receiver
  set either. (**Amended 2026-10-04:** `Char` and `Char?` bind on the property route. `ByteArray`
  stays refused.)

**Leak harness.** `LeakTests/LiveHandleTests.cs` gained three rows: 6i,
`CollectionReceiverExtensionProperty_ReleasesTheListHandle` (the C# prelude *builds* a Kotlin list
`StableRef` for the crossing, unconditionally, and the getter's handle scope disposes it); 6j,
`InterfaceReceiverExtensionPropertySetter_CSharpImplementedPet_ReleasesTransferHandle` (the other
half of Row 6h's receiver, now exercised through the `NullableDispatch` setter arm bug 2 fixed);
and 6k, `BoundInterfaceReceiverExtensionProperty_ReturnsToBaseline`. Row 6k is a smoke test, not a
leak pin, stated rather than left implied: the harness counts Kotlin `StableRef`s
(`NugetMarshal.LiveHandles`), but the bound-interface receiver crossing mints a C# `GCHandle`,
which Kotlin frees (immediately on a token-probe hit, otherwise through the `IFeedableHandle`
cleaner), neither object is a `StableRef`, so a pure `GCHandle` leak on the C# side would leave
this row green. That is shipped ADR-088 parameter-position behaviour, not new here. The seven
scalar-ish receivers (`Enum`, `Uuid`, `Instant`, `Duration`, `String?`, `Uuid?`, and a `String`- or
`ObjectHandle`-underlying value class, nullable) mint no handle on either side and get no row.

## Amendment (2026-09-28): a member property that shadows an extension property is a named skip

Discovered while implementing ADR-172: an extension property whose receiver already has a visible
member property of the same name (`class Foo { val x: Int = 1 }` alongside `val Foo.x: Int get() =
2` in another package) still bound and exported. The extension's generated Kotlin export body reads
`receiver.x`, and Kotlin's own name resolution always prefers a member over an extension of the same
name, so the export silently returned the *member's* value (`1`), never the extension's (`2`). Kotlin
call syntax has the identical problem: `foo.x` from anywhere that can see both declarations also
resolves to the member, so the extension was unreachable by ordinary Kotlin syntax either. This is
not a new receiver shape and does not change the table above; it is a gate on top of it.

`ForwardPropertyPlanner.shadowingMember` now looks up the receiver's member properties (declared and
inherited via `getAllProperties()`) for a same-named, non-private, non-protected, non-extension
candidate. Finding one drops the extension with a new `SHADOWED_BY_MEMBER` reason, reported under
`SKIPPED_UNSUPPORTED_PROPERTY`: "the member property `Foo.x` shadows it: Kotlin resolves
`receiver.x` to the member, so the extension is unreachable by plain call syntax", with a suggested fix to
rename the extension or expose a top-level function instead. A private or protected member is not
visible from the generated file and is not a candidate; a member *extension* property is not a
candidate either, since plain `receiver.x` never resolves to one. A nullable receiver (`val
Foo?.x`) is never shadowed: `(receiver).x` on a nullable static type has no member candidate, so the
extension resolves as declared.

**Fixture-verified**, `Tier1ShadowedExtensionPropertyTest.kt`: a declared shadowing member, an
inherited shadowing member, a private member (does not shadow), and a nullable receiver (does not
shadow) all render as expected; the member keeps its own export and the shadowed extension exports
on neither half.

**Known gap, closed by the 2026-10-03 amendment:** the extension **function** twin had the same
defect (`fun Foo.y()` beside member `Foo.y()` rendered `receiver.y()`, which also resolves to the
member). Detecting it would need overload applicability matching, not a name-only check, so the
amendment keeps the extension and calls it through an aliased import instead.

## Amendment (2026-10-03): an extension function shadowed by a member is kept and called through an aliased import

Closes the known gap of the 2026-09-28 amendment. An extension function whose receiver has an
applicable member of the same name (`fun Lantern.glow()` in another package beside member
`Lantern.glow()`) used to be exported with the body `receiver.glow()`, which Kotlin resolves to the
member, so C# `LanternExtensions.Glow(lantern)` silently returned the member's result. The wording
"unreachable by plain call syntax" in the 2026-09-28 amendment is true of `receiver.glow()` only: an
aliased import (`import pkg.glow as alias`) is ordinary call syntax and reaches the extension.

The rule: every extension function is imported under an alias and called through it. The alias is
`nuget_ext_` plus the qualified name with `_` written `_u` and `.` written `__`, so it is injective
across packages. One `import pkg.`name` as alias` covers every overload of `name` in that package,
and the name is backticked in the import. The generated body is
`receiver.asStableRef<Lantern>().get().nuget_ext_..._lamplight__ext__glow()`. An enum member shares
the extension call shape but is a real member call and keeps its plain name.

The extension is kept, not skipped. A name-only skip, which is exact for properties (no overloads),
would drop valid extensions such as `fun Foo.w(a: Long)` beside member `w(a: Int)`, where the member
is not applicable and the extension already wins. Extension functions and properties therefore
differ on purpose: a function is kept and correct, a shadowed property is still a named skip. C# and
Kotlin each keep their own resolution: `lantern.Glow()` is the member and
`LanternExtensions.Glow(lantern)` is the extension.

A side effect: two same-named extension functions on one receiver from two packages used to be
imported by simple name into one generated file, which failed to compile with an overload
resolution ambiguity. Distinct aliases remove it.

**Verified:** `Tier1ShadowedExtensionFunctionTest` failed before (the generated Kotlin did not
compile) and passes after, covering the exact, member-default, vararg, generic, supertype-parameter
and `invoke`-property shadows, the `w(a: Long)` control, an overload pair, the default package and
two packages sharing one extension name. `ShadowedExtensionFunctionTests` reads the value from C#
(`"extension"` from `LanternExtensions.Glow`, `"member"` from `lantern.Glow()`). Kotlin/Native
accepted the backticked aliased import in the real pipeline.

**Inferred, not checked:** an alias behaves the same for `suspend`, `infix`, `operator` and generic
extensions beyond the shapes above.

**Known gaps found alongside, not fixed here (all three are closed by 2026-10-04 amendments):**
a member function and an extension function of the
same name on one receiver declared in the same package claim one C entry point (verified,
`ERROR_C_ENTRY_POINT_COLLISION`), so the alias then only reached an extension in another package. A class
in the default package with an extension (`class Leash` plus `fun Leash.tug()`) generated
`Unresolved reference 'Leash'` (verified; closed by the default-package amendment at the end
of this file). An extension function whose
name needs backticks was unverified and could produce an invalid alias; it was real and is closed
by the backticked-name amendment at the end of this file.

## Amendment (2026-10-03): has-value fan-out receivers bind on the extension-function route

Judgement: an **amendment**, not a new ADR. It lifts the `RECEIVER_FAN_OUT` skip for extension
**functions** only; extension **properties** on the same receivers kept the named skip until the
2026-10-04 amendment below.

**Rule.** A Kotlin extension function whose receiver is `Int?`, `Char?`, an enum `?`, `Instant?`,
`Duration?`, or a value class over a primitive or enum `?` binds as a C# extension method on the
matching `Nullable<T>` (`this int?`, `this char?`, `this Mood?`, `this DateTimeOffset?`,
`this TimeSpan?`), and a C# `null` reaches Kotlin as a real `null`. An `Int` and an `Int?` receiver
of one name bind as two overloads. The extern is
`(bool receiverHasValue, int receiver, out IntPtr error)`; no handle is minted, so there is no
LeakTests row.

**Mechanism.** The plan carries the receiver's nullable type and its has-value flag as a public
receiver (`ForwardPublicSignature.receiver`). The flag name comes from the existing `freshName`
pool, so no parameter name is reserved library-wide. `validateRoles` accepts the RECEIVER-role slot
at index 1 only when slot 0 is that receiver's flag. The parenthesised `if (` branch of
`receiverExpression` is now reachable (see the corrected sentence in decision 1).

**Trap, confirmed red-first.** Relaxing only the role check compiles clean and silently drops the
null: the export called the non-null receiver and never read the flag. Verified: six of the nine
`Tier1ReceiverShapesExtensionTest` tests failed with the naive fix and all pass with the real one.

**Accepted asymmetry** (same as the `CatId?` receiver, sub-decision (a)): the C# call site is
nullable-only. Calling on a bare `int` is CS1929.

**Separate fix in the same change.** The extension receiver's `CirParameter` used the default
`isReferenceType = true`, so the C# collision check treated `this int?` like `this int` and an
`Int` / `Int?` pair raised a false `ERROR_CSHARP_SIGNATURE_COLLISION`. **Inferred, not run:** a
`CatId` / `CatId?` pair hit the same false collision before.

**Not shipped here (closed by the 2026-10-04 amendment).** Extension properties on these receivers
kept the named `RECEIVER_FAN_OUT` skip: the property route has a separate one-slot receiver
mechanism, and `val Int.x` beside `val Int?.x` would share one plan symbol. A bare `Char` receiver
already bound on the function route; the property route's receiver predicate did not admit `Char`.

**Evidence (verified).** Tier 1 `Tier1ReceiverShapesExtensionTest` 9 tests; `:nuget-processor:test`
1552 passed, 0 failed; full `scripts/verify.sh` green (Contract 3, Integration 3006, Leak 158,
MultiPackage 9, SharedException 2, all six NativeAOT shapes), including a run-time null-receiver
fact in `IntegrationTests/ExtensionFunctionTests.cs`.

## Amendment (2026-10-04): a member and a same-package extension of one name both bind

Closes the first known gap of the 2026-10-03 aliased-import amendment. A member function and an
extension function of one name on one receiver, declared in the SAME Kotlin package (`class Lantern
{ fun shine() }` beside `fun Lantern.shine()`), both derived `<lib>_<pkg>__lantern_shine`, because
the member's prefix and the extension's qualifier are the same package. That was the fatal
`ERROR_C_ENTRY_POINT_COLLISION`, so the aliased import only reached an extension declared in another
package. Both now bind, exactly as they already did across packages: C# `lantern.Shine()` is the
member and `LanternExtensions.Shine(lantern)` is the extension.

**Rule.** An extension's entry point becomes `<lib>_<pkg>__<owner chain>_ext_<name>[_<n>]` only
when its plain spelling is already taken by an export from a member, a constructor, a top-level
function, or a class or sealed-arm callback route (a stored-callback or interface-bridge
`add`/`remove` pair, which mints `<owner>_<name>` outside the plan catalog). The check is per
overload, so `fun Lantern.swing(arc: Int)` beside member `swing(arc: Int)` takes `lantern_ext_swing`
while `fun Lantern.swing(arc: Long)` keeps `lantern_swing_2`, and every symbol that did not collide
keeps its name. Extensions are planned after every member route, so object, companion, enum and
value-class members are in the check.

**Extension properties.** Accessors take the same marker per accessor, only when the plain name is
taken. This applies to a nullable-receiver extension property beside a member property, which is
never shadowed: `var Lantern?.wick` beside member `var wick` exports `lantern_ext_get_wick` and
`lantern_ext_set_wick`. A non-null-receiver extension property beside a same-package member of the
same name is still ADR-132's `SHADOWED_BY_MEMBER` named skip, as across packages: functions and
properties differ on purpose (2026-10-03 amendment). What changed is that this same-package case no
longer fails the generator with `ERROR_INTERNAL_GENERATOR_FAILURE (Expected extension property
plan)`. A member plan and an extension plan share the key `pkg.Lantern.wick`, so the extension
route could be handed the member's plan. `ForwardCallablePlanCatalog.extensionPropertyFor` now looks
up by position and returns only an extension plan.

**Not collisions.** A suspend member exports `_async` and a Flow member `_collect`, so a same-named
extension keeps its plain name beside either. `object Kennel` with `fun Kennel.bark()` is still a
named `SKIPPED_UNSUPPORTED_TYPE`. An enum member beside a same-package extension is still
`ERROR_CSHARP_SIGNATURE_COLLISION`: both render into `{Enum}Extensions`, which is a C# limit.

**Verified.** `Tier1SamePackageMemberExtensionTest` has 8 cells: a member and extension under
distinct entry points, an unshadowed overload keeping its plain symbol, a value-class member, the C#
surface, a legacy callback member, planned lambda/suspend/Flow members keeping their spellings, the
shadowed property as a named skip rather than a crash, and the nullable-receiver property taking the
marked accessors. `IntegrationTests/ShadowedExtensionFunctionTests.cs` reads `"member"` from
`lantern.Shine()` and `"extension"` from `LanternExtensions.Shine(lantern)`, and covers the overload,
stored-callback and nullable-property pairs. `:nuget-processor:test` 1643 passed, 0 failed; the native
pipeline passed IntegrationTests 3040, LeakTests 164 and the 7 AOT shapes. No LeakTests row: no new
handle route.

**Inferred, not checked.** A member function literally named `get_x` beside an extension property
`x` can still meet on `<owner>_get_x`, and a member lambda-typed property's hand-written
`<owner>_get_<name>` getter is not in the name set the check reads. ADR-117 stays the backstop for
both.

## Amendment (2026-10-04): a type in the default package is imported into the generated file

Closes the second known gap of the 2026-10-03 aliased-import amendment, which was wider than it
read: not only an extension, but every reference the generated `CNameExports.kt` makes to a type
declared with no `package` line was `Unresolved reference`. Each route spells a type by its
qualified name (`asStableRef<Leash>()`, `Leash()`, `Gait.entries`), and in the default package that
name is the bare `Leash`, which the generated file (its own package) cannot see without an import.
The fixture hit it in a class, an extension function, an extension property, the shadowed
member and extension pair, a nullable-receiver property and a top-level function taking the class.
`NugetProcessor` now adds `import Leash` for each default-package class, object, interface and
typealias the drafted file mentions, and none for one no export mentions. The entry-point prefix
(`library_leash_tug`, no empty package segment) and the aliased extension import were already
right. ADR-163's 2026-10-04 default-package amendment records the rule. Verified:
`Tier1DefaultPackageExtensionTest` compiles the generated Kotlin with the harness's JVM compiler.
Inferred: Kotlin/Native resolves the same import; no native fixture exists because every
`test-library` declaration lives in a package.

## Amendment (2026-10-04): a backticked extension name no longer breaks the import alias

The 2026-10-03 aliased-import amendment left one gap unverified: an extension whose Kotlin name needs
backticks. It was real, and wider than extensions: a keyword name emitted unparseable Kotlin on
every member route, and a name with a space or symbol emitted an invalid alias, `@CName` and C#
member. ADR-179's 2026-10-04 amendment carries the rule; this records what it does to the extension
route.

- A keyword-named extension (`fun Leash.in()`) imports and calls through the alias like any other,
  and C# keeps its escape (`In`).
- An extension named with a space or symbol and no `@CSharpName` is a named `NON_IDENTIFIER_NAME`
  skip. With `@CSharpName("TugHard")` it binds.
- The alias spells every character other than a letter, digit, `_` or `.` as `_x` plus four hex
  digits: `tug hard` in `tier1.backticks.named` is
  `nuget_ext_tier1__backticks__named__tug_x0020hard`. The call then needs no backticks and the
  import still needs only one `import pkg.`tug hard` as alias`. Injectivity holds because a literal
  `_` is always `_u`, so `_x` only comes from this arm.
- The entry point is cleaned by `asCSymbol()` (ADR-163's 2026-10-04 amendment), so the extension is
  `library_..._leash_ext_tug_hard` when a member already holds `leash_tug_hard`.

Verified: `Tier1BacktickedNameTest` compiles the generated Kotlin and C# for a member and an extension
both named `tug hard` (`@CSharpName("TugHard")`), asserting the alias above and the call
`.get().nuget_ext_tier1__backticks__named__tug_x0020hard()`. Not verified natively: Kotlin/Native
cannot link a public member named with a space, so the space-named case is Tier 1 only.

**Residuals, closed 2026-10-04 (see the amendment below).** This amendment listed two inferred
cases: a member function literally named `get_x` beside an extension property `x`, and a member
lambda-typed property's hand-written `<owner>_get_<name>` getter. Both are real collisions and are
now in the name set the check reads. ADR-117 stays the backstop for any other.

## Amendment (2026-10-04): has-value fan-out receivers bind on the extension-property route

Judgement: an **amendment**, not a new ADR. The wire, the C# shape and the nullable-only call site
were decided for the function route (2026-10-03 amendment) and for extension blocks (ADR-188); this
carries them to properties. It retires `RECEIVER_FAN_OUT`, which no route emits any more.

**Rule.** A `val` or `var` extension property whose receiver is `Int?`, `Char?`, an enum `?`,
`Instant?`, `Duration?`, or a value class over a primitive or enum `?` binds as a C# 14
`extension(T? receiver)` property on the matching `Nullable<T>` (`int?`, `char?`, `Mood?`,
`DateTimeOffset?`, `TimeSpan?`, the value-class struct), and a C# `null` reaches Kotlin as a real
`null`. A bare `Char` receiver binds too (the old receiver predicate did not admit it). The extern
mirrors the function route:
`([MarshalAs(UnmanagedType.I1)] bool receiverHasValue, <value> receiver, out IntPtr error)`, with
the setter adding `value`. A `Char` receiver crosses as `[MarshalAs(UnmanagedType.U2)] char`
(verified with `'Ж'`, which one ANSI byte could not carry). The flag is read in Kotlin
(`(if (receiverHasValue) receiver else null).x`) and the C# side passes
`receiver.HasValue, receiver.GetValueOrDefault()`: a cast such as `(int)receiver` would throw for a
null enum before reaching Kotlin. The flag is declared once per extern, whichever accessor it
belongs to. No handle is minted, so there is no LeakTests row.

**Setters bind.** `var Int?.livesNote` exports `get` and `set`, both carrying the flag. The setter
cannot mutate the receiver (a value); it runs the Kotlin setter with the receiver as a key, as
`var Uuid.nickname` already does. C# needs a variable on the left of the assignment (CS0131 on an
rvalue).

**Accepted asymmetry** (same as the function route and the `CatId?` receiver): the C# call site is
nullable-only. Reading the `int?` property on a bare `int` is CS1929.

**Trap, as on the function route.** Admitting the receiver in the planner alone compiles the Kotlin
half clean and never reads the flag, so a null silently reads as the non-null twin on the value
slot's default (`0`, the first enum entry). Every fixture getter answers a null receiver with
something the default would not produce.

**Twins.** A value-type receiver and its nullable form are two receivers to C#: `extension(int)`
and `extension(int?)` declare one property name side by side with no CS0102, and `7.Label` and
`some.Label` each resolve to their own. This holds for every C# value-type receiver (`int`, `char`,
an enum, `DateTimeOffset`, `TimeSpan`, `Guid`, a value-class `record struct`), whether the nullable
form is a has-value fan-out or rides one in-band null (`Uuid?`), and for properties and functions
alike. So `val Int.x` beside `val Int?.x` and `val Uuid.chipOwner` beside `val Uuid?.chipOwner` bind
both, as `fun Int.x()` beside `fun Int?.x()` already did. The nullable twin's plan symbol carries
`?` and its accessors take the `_ext_` marker of the 2026-10-04 same-package amendment
(`int_get_livesLabel` beside `int_ext_get_livesLabel`, `uuid_get_chipOwner` beside
`uuid_ext_get_chipOwner`; verified in the generated output).

`NULLABLE_RECEIVER_TWIN` stays a fatal `ERROR_CSHARP_SIGNATURE_COLLISION` only for a reference-type
receiver (`Cat` beside `Cat?`), where C# does reject the pair (ADR-188's 2026-10-03 amendment). Its
sentence now says so: "for a reference-type receiver C# reads the receiver and its nullable
spelling as one type, so it cannot declare the member twice (CS0102)". A function and a property of
one name follow the same split. When their value-type receivers differ in nullability, both bind
(`fun Int.label()` beside `val Int?.label`); the same nullability is still refused as
`SHADOWED_BY_EXTENSION_FUNCTION`, because `x.Label` would be ambiguous (CS9339).

**`RECEIVER_FAN_OUT` is removed** (the enum value, its sentence and its hint). A receiver the
property route still cannot lower (a raw generic `Box<Int>`, a type parameter, a `ByteArray`, a
nullable collection or bound interface) reads the generic receiver sentence, whose hint now lists
the nullable shapes that bind.

The earlier research expectation that this pair built before the change was wrong. When both twins
planned they shared one plan symbol and the catalog was looked up by that symbol, so one plan was
rendered twice and the build died with a fatal `ERROR_CSHARP_SIGNATURE_COLLISION`.
`ForwardCallablePlanCatalog.extensionPropertyFor(prop)` now looks the plan up by declaration.

**Residuals of the same-package amendment, closed.** That amendment named two inferred collisions
and did not fix them: a member function literally named `get_x` beside an extension property `x`
(`<owner>_get_x`), and a member lambda-typed property's hand-written `<owner>_get_<name>` getter.
Both collided (`ERROR_C_ENTRY_POINT_COLLISION`). Both are now in the taken-name set the extension
property accessors check, so the accessor takes the `_ext_` marker: `leash_get_x` beside
`leash_ext_get_x`. The lambda-property set is built from the same predicate the class and sealed
emitters use for that getter, so the two cannot drift.

**Verified.** Tier 1 `Tier1ReceiverShapesExtensionPropertyTest` (a fan-out receiver reads the flag,
a non-null and a nullable twin both bind, a non-fan-out struct twin (`Uuid`) binds, a function and
a property of differing nullability both bind, a `var` carries the pair on all four exports, `Char`
and `Char?` on the two-byte wire, every receiver kind converting off `GetValueOrDefault()`);
`Tier1ExtensionPropertyFunctionClashTest` pins that the reference twin message names CS0102 for a
reference-type receiver only; a C# compile of the function twin pair in
`Tier1ReceiverShapesExtensionTest`; and two cells in `Tier1SamePackageMemberExtensionTest` for the
residuals. `IntegrationTests/ExtensionPropertyTests.cs` reads each receiver kind with `null` and a
value, the twin pairs, the `var`, the UTC-vs-wall-clock `DateTimeOffset`, and `'Ж'`.
`:nuget-processor:test` 1730 passed, 0 failed; the native pipeline passed IntegrationTests 3112,
LeakTests 178 and the 7 AOT shapes. No LeakTests row: Tier 1 asserts no `HandleOf*(receiver` for
these receivers.

**Inferred, not checked.** Each value-type twin other than `Int` and `Uuid` (`Char`, an enum,
`Instant`, `Duration`, a value class) rides the same rule and has no twin cell of its own.
