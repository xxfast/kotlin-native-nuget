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
  when the lowered string starts with `"if ("`. That branch is unreachable today (see the
  `RECEIVER_FAN_OUT` skip below) and is kept deliberately, so that lifting the skip in the future
  cannot silently mis-bind the call.

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
- Of these, only `Nullable(String)`, `Nullable(Uuid)`, and `Nullable(Collection)` are admitted
  nullable forms that route the same way (also no fixture). `Nullable(Enum)`, `Nullable(Instant)`,
  and `Nullable(Duration)` are **not** among the routed shapes: each is a has-value fan-out and
  hits the named `RECEIVER_FAN_OUT` skip below instead.

**Fixture** (bare `Interface` and `Nullable(ValueClass(String))` only): `fun Pet.describe(): String`
and `fun CatId?.orAnonymous(): String` in `test-library/.../cat/CatExtensions.kt`. `describe()`
composes `name`, `legs`, and `speak()` rather than echoing the receiver back, so a C#-implemented
`Dog` can only produce the right string if all three interface slots actually dispatched across the
bridge; xunit in `IntegrationTests/ExtensionFunctionTests.cs` covers a Kotlin-backed `Cat`, an
anonymous Kotlin object (`strayPet()`, no generated wrapper class), a C#-implemented `Dog : IPet`,
and the `CatId?` value/null pair. Tier 1 pins the two public signatures and the compile in
`Tier1ReceiverShapesExtensionTest.kt`.

**Named skip, not a crash: `RECEIVER_FAN_OUT`.** A has-value fan-out receiver (`Int?`-style
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
no export, no C# binding, a warning naming `orZero`.

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
- `ForwardCirPropertyProjection.kt`'s own receiver-argument `when` (~lines 58-70) still has an
  `else -> "receiver"` pass-through mirroring the one this ADR removed from the callable route.
  It is unreachable today only because the property planner's `supportedReceiver` gate above never
  lets an interface or nullable-value-class receiver reach it; unifying the two routes would need
  to either delete this arm too or prove it can never fire. Not touched by this ADR.
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
  (`fun Int?.orZero()` Tier 1 control).

**Inferred, not verified by a fixture or a build:**
- `Nullable(Interface)`, `Nullable(ValueClass(ObjectHandle))`, `Enum`, `Uuid`, `Instant`,
  `Duration`, `Collection`, `BoundInterface`, and the admitted `Nullable(String)`,
  `Nullable(Uuid)`, and `Nullable(Collection)` forms bind correctly at the receiver position.
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
