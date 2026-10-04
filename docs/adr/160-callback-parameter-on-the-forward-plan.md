# ADR-160: A per-call callback parameter is projected off the ADR-062 forward callable plan

## Status

Accepted

## Context

A class member (or a sealed arm's, or a top-level/extension function's) that takes one per-call
lambda parameter and also returns a value of its own failed `packNuget`:

```
Forward ABI mismatch for metronome_countTicks; expected metronome_countTicks(in pointer, in
pointer, in pointer, out pointer) -> pointer, actual metronome_countTicks(...) -> int
```

The hand-written route (`translateCallbackMethod` on the C# side, `addLambdaParamMethodExport` on
the Kotlin side) declared the extern's own return as `IntPtr` regardless of what the member
actually returned, while the Kotlin export returned the scalar untouched. The ADR-055 contract
caught the mismatch and aborted the whole build rather than generating broken code. Two further
axes were broken the same route never implemented:

- a scalar **lambda** return (`(Int) -> Int`) rendered `NugetMarshal.WrapString(weigh(arg0))`
  against a delegate declared to return `IntPtr`, and the Kotlin side read the box back with
  `resultRef.asStableRef<String>().get()`, both of which fail to compile for a non-`String` result;
- a non-lambda parameter declared beside the lambda was silently dropped from the extern, the
  public method, and the Kotlin call site alike, so the generated Kotlin failed with
  `No value passed for parameter 'x'` with no diagnostic pointing at the member.

The exported-object, nullable-object and enum outer returns, and the top-level, extension and
object positions, had no route on this shape at all.

Growing the hand-written route to cover this would mean re-deriving, by hand, the same result
matrix (scalars, `String`, an exported object, its nullable twin, an enum) the ADR-062 forward
callable plan already builds and dual-projects for every ordinary method return. That is the
second return matrix the research memo for this item warned against.

## Decision

The per-call lambda-parameter route moves onto the ADR-062 plan. `BridgeType.Callback(parameters,
result)` is a new leaf of the plan's type model: the first plan input that fans out to two native
slots of its own, `${name}Ptr` and `${name}UserData`
(`forward/ForwardCallablePlanner.kt:2621`), the same shape ADR-080's `HasValue` fan-out already
established for a nullable primitive. Both the Kotlin export and the C# call site are projected
from that one classification, so the method's own (outer) return is no longer a second, hand-rolled
`when`: it is whatever the plan already emits for that return type, scalar, `String`, an exported
object, its nullable twin, or an enum, exactly as an ordinary method without a lambda parameter
would get.

```kotlin
class Metronome(private val beats: Int) {
  fun countAbove(min: Int, listener: (Int) -> Unit): Int { /* ... */ }
  fun sumWeights(weigh: (Int) -> Int): Int = (1..beats).sumOf { weigh(it) }
}
```

```C#
[UnmanagedFunctionPointer(CallingConvention.Cdecl)]
internal delegate int NugetIntIntCallback(int a0, IntPtr ctx);

[DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "metronome_countAbove")]
private static extern int Native_CountAbove(IntPtr handle, int min, IntPtr listenerPtr, IntPtr listenerUserData, out IntPtr error);

public int CountAbove(int min, Action<int> listener)
{
    NugetIntVoidCallback listenerNative = (int a0, IntPtr ctx) => { listener(a0); };
    GCHandle listenerCtx = default;
    try
    {
        listenerCtx = GCHandle.Alloc(listenerNative);
        int nativeResult = Native_CountAbove(_handle, min, NugetThunks.NugetIntVoidCallbackPtr, GCHandle.ToIntPtr(listenerCtx), out IntPtr error);
        if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);
        return nativeResult;
    }
    finally
    {
        if (listenerCtx.IsAllocated) listenerCtx.Free();
    }
}
```

`min` crosses like any other plan parameter, beside the two callback slots, which fixes the
dropped-parameter defect as a side effect of planning the member at all.

### What the classifier admits as a `Callback`

A `kotlin.FunctionN` type becomes a `BridgeType.Callback` only when every payload and the lambda's
own result are one of the shapes both plan halves can lower
(`forward/ForwardBridgeTypeClassifier.kt:684-710`):

- payload: a primitive by value, `String`, an exported object or interface over a handle, an enum
  over its ordinal;
- lambda result: `Unit`, a primitive by value, or `String` (C# mints the box, Kotlin releases it,
  unchanged from the existing `String` lambda-return convention).

A `FunctionN` type outside that set (a `Char` payload, an object or enum lambda result, a
suspend lambda, a nullable lambda, a lambda type nested inside a collection) classifies as the
existing `lambda <fqn>` specialized protocol instead, so the member keeps whatever legacy route it
already had, or is named `CALLBACK_PROTOCOL` if it has none. The delegate name is still minted from
the wire, not from the semantic payload type, so two members with different Kotlin payloads but the
same wire (`(Int) -> Unit` and `(Mood) -> Unit`, both `void(int, IntPtr)`) still share one declared
delegate, preserving ADR-036's injectivity rule. A slot name is unescaped unless the emitted
identifier is itself a C# keyword, the same rule every other plan slot follows.

### What stays on the legacy route, refused by name

`translateCallbackMethod` and `addLambdaParamMethodExport` are retired for every position the plan
now owns. What is left on the hand-written route is exactly what the classifier declines to
project, and a member on that route that also cannot be marshaled at all is refused by name on both
halves (`legacyRefusedCallbackMember`, `forward/ForwardLegacyRouteCollections.kt:142`; one warning per
member, see the 2026-10-03 amendment) instead of
failing with a forward ABI mismatch or emitting C# that will not compile:

- more than one lambda parameter;
- a non-lambda parameter beside a lambda the legacy route still owns (a `Char` payload, for
  example);
- a nullable return;
- a return that is not `Unit`, `String`, or `List` (the three shapes the legacy route has always
  been able to marshal on its own).

A callback parameter never binds at a position that would store the lambda past the crossing: a
constructor, a data class's `copy()`, an enum-arm box constructor, or a value-class member. The
plan's callback prelude allocates the `GCHandle` immediately before the call and frees it in the
`finally`, so storing the delegate anywhere past that scope would dispatch the ADR-102 thunk
through an already-freed handle on the member's next invocation, a use-after-free rather than a
compile error. Storing a callback handed in for later re-invocation is ADR-037's stored-callback
route, not this one; a function-typed *property* (handing a Kotlin lambda **out** as
`KotlinFunc`/`KotlinAction`) is untouched and keeps its own pre-existing route.

Top-level and extension positions, and a sealed arm's own declared member, bind through the same
plan the class-method position does, since the plan is keyed to the position rather than to the
owner kind.

### Ownership, unchanged from ADR-036

Kotlin retains a handle-passed payload for the duration of the call and never releases it; the C#
side owns the free (`FromHandle<string>` disposes as it reads a `String` payload, an object
payload's generated wrapper owns its handle and its `Dispose()` is the free). A `String` lambda
result is boxed by C# (`NugetMarshal.WrapString`) and released by Kotlin, unchanged. The ADR-102
AOT-safe thunk shape is untouched: Kotlin still receives `NugetThunks.{Delegate}Ptr`, the
link-time-resolved address of a static `[UnmanagedCallersOnly]` thunk, plus the echoed `GCHandle`
context; nothing here calls `Marshal.GetFunctionPointerForDelegate`.

## Alternatives Considered

### Patch the hand-written route directly (rejected)

Fix `nativeImportReturnType` on the C# half to spell the real scalar return, add the by-value
lambda-return arm, and add a named refusal for the shapes that still cannot marshal. Smaller diff,
and was the research memo's original recommendation. Rejected once the human reviewed it: it fixes
the two symptomatic returns but still leaves a second, hand-maintained return matrix that cannot
express mixed parameters, an object or nullable-object outer return, or the top-level/object/
extension positions without a third and fourth hand-written branch. The plan already has all of
that; duplicating it invites the two halves to drift again the way ADR-055's contract exists to
catch.

### Refuse every non-`Unit`/`String`/`List` outer return (rejected)

Closes the build failure with the smallest possible diff, but does not deliver the restatement: a
consumer cannot get `int total = metronome.CountTicks(...)` back at all.

## Consequences

- A member with a per-call lambda parameter now has the full ADR-062 result matrix on its own
  return: scalar, `String`, an exported object, a nullable exported object, or an enum. Mixed
  parameter lists, a scalar lambda return, and the top-level/extension/sealed-arm positions bind.
- `LeakTests` rows 13 and 13a measure the one cell that mints a handle on this route, an exported
  object outer return, at 5000 iterations, with the predicate disposing the payload it is handed
  each call (ADR-036's `using (c)` pattern, restated). A consumer lambda that skips that `using`
  leaks one handle per invocation, the same residual ADR-036 already names for every callback
  payload: a generated wrapper has `Dispose()` and no finalizer.
- Superseded 2026-10-03 (see the amendment): `isForwardLegacyRoute()` reported any lambda-parameter member
  as legacy regardless of whether the plan owned it; it no longer does.
- Deliberately still refused by name, on both halves: `Char` on either the payload or the lambda-
  result axis (no by-value crossing convention and not a legal `[UnmanagedCallersOnly]` signature
  type); an object or enum lambda **result** (releasing a box the C# wrapper still owns is an
  unresolved ownership question); a nullable lambda; a `suspend` lambda; a lambda type nested inside
  a collection; a callback at a **result** position (returning a lambda, as opposed to taking one);
  a callback parameter on a constructor, a data class's `copy()`, an enum-arm box constructor, or a
  value-class member.
- Unsigned-primitive and interface-typed callback payloads had no fixture when this ADR shipped; the
  interface half hid a reachability defect. Both are now covered (see the "interface and unsigned payload
  coverage" amendment).
- The plan's delegate segment used to spell unsigned kinds `Uint`/`Ulong`; it now spells `UInt`/`ULong`
  like every other lambda route (see the 2026-10-03 amendment).
- A planned callback member on a generic class is untested.

- No fixture exercises an unsigned-primitive or interface-typed callback payload on the plan; ten of
  forty-four branches in `forward/ForwardCirCallbackProjection.kt` are cold. Tracked on the
  ROADMAP.
- The plan's delegate segment spelling (`Uint`, `Ulong`) does not match the interface-bridge route's
  own unsigned spelling (`UInt`, `ULong`); cosmetic, tracked on the ROADMAP.
- A planned callback member on a generic class binds and runs since the 2026-10-03 amendment.

## Prior art

- **ADR-036** is the mechanism this route still uses end to end: the delegate, the `GCHandle`
  context, and the ownership rule for a handle-passed payload are all unchanged, only the two
  return axes and the position matrix move onto the plan.
- **ADR-062** is the plan itself; this admits a per-call callback parameter as a `BridgeType`
  rather than a skip, following the precedent ADR-111/116/118/124 set for moving a sealed arm's
  members onto the same plan one shape at a time.
- **ADR-080** established the two-slot fan-out for a single logical input (`HasValue` plus the
  value); `${name}Ptr`/`${name}UserData` is the same idea for a callback.

## Amendment (2026-09-22): a nullable lambda type binds on the legacy route

This corrects two sentences above that are no longer accurate: the classifier list under "What the
classifier admits as a `Callback`" (:97, "a nullable lambda" among the shapes that fall through to
the legacy protocol) and the "Deliberately still refused by name" bullet in Consequences (:176,
"a nullable lambda" among the refused shapes). Both conflated two different axes of nullability that
[ADR-036's 2026-09-22 amendment](036-reverse-interop-mechanism.md#amendment-2026-09-22-a-nullable-lambda-parameter-decided-one-way-a-nullable-payload-another)
decided in opposite directions.

A `FunctionN` parameter whose own type is nullable (`listener: ((Int) -> Unit)?`) is a
`Nullable(Callback)` at the plan's classifier, which is not a `BridgeType.Callback` leaf: the plan
declines it silently, reporting skip reason `CALLBACK_PROTOCOL` with no diagnostic (the same "or is
named `CALLBACK_PROTOCOL` if it has none" clause on line 99 doing its job here too). That decline is
not a refusal: it hands the member to the legacy per-call route (`translateCallbackMethod`/
`addLambdaParamMethodExport`), which still binds it as the plain non-nullable delegate and now
guards the argument with `ArgumentNullException.ThrowIfNull` before it crosses. See
[Lambdas and callbacks: a nullable lambda parameter](../topics/lambdas-and-callbacks.md#a-nullable-lambda-parameter).

A lambda whose **payload** or **return** is nullable (`(Int?) -> Unit`, `(Int) -> String?`) is the
shape the original wording meant to describe: that one stays a named `SKIPPED_UNSUPPORTED_INPUT`
skip, on this route, on a sealed arm, and on a stored-callback pair alike, unchanged by this
amendment. Do not conflate either of these with the outer, non-lambda return the member itself
declares (":116, `a nullable return`"), which is still refused by name on the legacy route
regardless of the lambda parameter's own nullability.

## Amendment (2026-09-27): legacy lambda routes refuse builtin payloads and non-scalar results

This corrects the classifier list under "What the classifier admits as a `Callback`" above (:96-98:
"an object or enum lambda result ... classifies as the existing `lambda <fqn>` specialized protocol
instead, so the member keeps whatever legacy route it already had"). It does not keep working: every
lambda result outside `Unit`/primitive/`String` (an object, an enum, `Char`, or a Kotlin builtin) was
read back through `resultRef.asStableRef<String>().get()` on the Kotlin half and boxed with
`NugetMarshal.WrapString` on the C# half regardless of its real type, so the generated Kotlin failed
to compile (`Return type mismatch: expected 'List<Int>', actual 'String'`) for every one of those
shapes. The Consequences bullet at :173-179 already listed an object/enum lambda result as
"deliberately still refused by name", which was equally inaccurate the other way: no predicate named
it before this amendment, it simply failed to build. No fixture in the tree ever exercised one, so
nothing that worked is now refused; the two statements are reconciled by making the refusal real.

The classifier's admitted payload set (:87-94) was also silently escaped by the legacy route: a
payload that is a Kotlin builtin non-scalar (`List`, `Set`, `Map`, `Any`, `Pair`, an array,
`Duration`, or anything else under `kotlin`/`kotlinx` the classifier does not key) rendered
`Action<List>` on the C# half, which no `using` resolves (`CS0246`), and separately aborted the KSP
round on the stored route (ADR-037) with `ERROR_INTERNAL_GENERATOR_FAILURE` when the same shape hit
`qualifiedElementCsType`'s ADR-123 builtin-package check.

Both holes are now one shared, pre-partition predicate (`refusedLegacyLambdaShape`,
`exports/ClassExports.kt:135`), read at the same five sites the nullable-payload rule already used
(`ForwardLegacyRouteCollections.kt:158`), naming the member `SKIPPED_UNSUPPORTED_INPUT` for a
builtin payload and `SKIPPED_UNSUPPORTED_RETURN` for an out-of-set lambda result. `Char` is
deliberately still admitted on both axes: this is a denylist, not ADR-160's `isCallbackPayload`
plus `Char`, since unifying on that allowlist would also newly refuse a sealed-base or value-class
payload whose runtime behavior on either legacy route nobody has verified. See
[Lambdas and callbacks](../topics/lambdas-and-callbacks.md).

## Amendment (2026-09-29): a top-level lambda return binds on the plan

This corrects the "a callback at a **result** position ... refused by name" bullet under
Consequences, which stopped being true for a top-level function, and the ROADMAP report that
`fun petSupplier(pet: Pet): () -> Pet` was named `SKIPPED_UNSUPPORTED_RETURN` under a wrong reason.
The reason was wrong because the sentence blamed the position, but the legacy lambda-return branch
that fired never accepted a handle-typed or collection parameter beside the lambda return; the
added `pet: Pet` was the real difference, not the position.

The legacy branch is deleted. A top-level function's lambda return is now a
`BridgeType.ReturnedLambda` on the ADR-062 plan: one owned handle (`NugetHandles.retain`), wrapped
C#-side as `new KotlinFunc<...>(handle)`, beside parameters that get the plan's whole vocabulary
(an interface per ADR-173, an exported class, an enum, a nullable, a collection). The returned lambda
captures a parameter for as long as it lives, so a C#-implemented interface argument is kept alive by
the ADR-084 bridge until the `KotlinFunc` is disposed and Kotlin's cleaner runs.

Refused by name, unchanged in kind:

- An object or companion member returning a lambda keeps its named skip.
- A lambda type argument C# cannot spell (`() -> List<Int>`) is `SKIPPED_UNSUPPORTED_RETURN` with the
  new reason `LAMBDA_TYPE_ARGUMENT` (GitHub issue #111 wording). A sealed interface or class type
  argument keeps binding as before.
- A `suspend` lambda return (`fun napper(): suspend () -> Int`) is a named skip. It used to leave
  a Kotlin export with no C# declaration and no diagnostic. Its skip sentence also said "not bridged
  at any position" when it binds as a class property, and now says so.

Fixed on the way, each a defect of the deleted branch: `fun moodSupplier(m: Mood): () -> Int` failed
the build with `ERROR_UNSUPPORTED_ENUM_PARAMETER_ROUTE` and now binds; `fun nullableSupplier(n: Int?):
() -> Int` generated `int n` and dropped `null`, and now generates `int? n`; the enum-route error
hint now lists a lambda among the return shapes that carry an enum parameter.

`LeakTests` rows 6j-6n measure the new ownership: a captured C#-implemented pet released by the
ADR-084 cleaner, a captured Kotlin `Cat`, value-only parameters, Kotlin throwing before it makes the
lambda, and a throwing `IPet` factory on the returned lambda. One branch stays cold: a value-class
type argument on this route has no fixture.

## Amendment 2026-10-03: unsigned delegate segments use the Kotlin spelling

The plan names its internal delegate with `UByte`, `UShort`, `UInt` and `ULong` segments, for
example `NugetUIntVoidCallback`, instead of `Ubyte`, `Ushort`, `Uint` and `Ulong`. The plan now
reuses `simpleKotlinName()`, the speller the Kotlin half already uses for the callback's wire type,
so the plan and every other lambda route mint the same name for the same wire.

Only `internal` names change: the generated delegate and the `NugetThunks` members. The public C#
signature (`Action<uint>` and so on), the C export names and the wire are unchanged. A module that
mixes the plan route with a legacy lambda route on an unsigned payload now shares one delegate and
one thunk per wire instead of declaring two, as it already did for `Int`.

Evidence:

- Verified: a Tier 1 test with both routes in one module and all four unsigned kinds failed before
  the change (two delegates for one wire) and passes after (one declaration, one thunk pointer, the
  generated Kotlin compiles).
- Verified: `:nuget-processor:test` passes (1546 tests), and `scripts/verify.sh` is green, including
  all six NativeAOT shapes.
- Inferred: the shared thunk works at run time on an unsigned wire. No fixture runs it; `Int`
  already shares a thunk the same way.
- No `LeakTests` row: the change adds no handle route.

## Amendment 2026-10-03: interface and unsigned payload coverage

A Kotlin interface that C# reaches only as the payload of a per-call lambda parameter, for example
`fun eachDrummer(listener: (Drummer) -> Unit)` where `Drummer` appears nowhere else, was missing
from the reachable set. The method was emitted, but the interface's backing wrapper class and its
`NugetMarshal.Factories` entry were not, so the generated thunk's `FromHandle<IDrummer>` had nothing
to materialise through.

Rule: `componentInterfaceQualifiedNames()` (`NugetProcessor.kt`) has a `BridgeType.Callback` arm
that walks the callback's parameters, so an interface payload is reachable like an interface
returned or passed directly. Such an interface binds fully (backing wrapper, factory entry, bridge
arm) and the C# lambda receives a working `IDrummer` that it owns and disposes (ADR-036).

Unsigned payloads (`UInt`, `ULong` in and out, `UByte`, `UShort`) needed no code change. The fixture
`Bandstand` hands over values above the signed range of each width, which a signed misread of the
wire would corrupt, and `IntegrationTests` asserts the exact values.

Evidence:

- Verified: a Tier 1 test failed before the fix (`FromHandle<global::Interop.IVisitor>(a0)` with no
  `Visitor : IVisitor` wrapper) and passes after (wrapper, factory entry and bridge arm present).
- Verified: `:nuget-processor:test` passes (1547 tests), and `scripts/verify.sh` is green (Contract,
  Integration, Leak, MultiPackage, SharedException, and all six NativeAOT shapes).
- Verified: `LeakTests` row 13-iface,
  `CallbackMemberInterfacePayload_EachInvocation_ReturnsToBaseline`, returns to baseline over 5000
  invocations on the success path.
- Inferred: before the fix, the first invocation threw `NotSupportedException` from
  `Materialize<T>` inside the thunk. The native pipeline was not run before the fix.
- Not measured: whether the payload handle leaks when `Materialize<T>` throws; only the success
  path has a row.

## Amendment (2026-10-03): a member both callback gates refuse is named once

Step 4 names a refused member by name, but two gates could each do it for the same class member:
the planner's `classEntries` (a `CALLBACK_PROTOCOL` skip, generic wording) and the class walk
`warnRefusedLegacyRouteMembers` (the specific "a lambda carrying the nullable type ..." wording).
`fun ask(cb: (Int) -> String?): String?` and `fun mixed(x: Int, cb: (Int?) -> Unit)` hit both and
received two `SKIPPED_UNSUPPORTED_INPUT` warnings, spelled differently, and the C# `<remarks>` on
the owner kept the weaker generic reason.

The rule: one warning per member, and the walk's specific reason wins. `classEntries` now also
suppresses its `CALLBACK_PROTOCOL` skip when `refusedLegacyLambdaShape()` is non-null, because the
walk names that member. `legacyRefusedCallbackMember` is unchanged, so no member is re-admitted to
a route. The C# `<remarks>` on the owner carries the specific wording.

Deliberately unchanged: an interface default plus a class implementing it still yields two
warnings, because they name two different C# owners (the interface and the implementing class).

Evidence, verified: the Tier 1 test `a member both callback gates refuse is named once, with the
specific reason` failed before the change and passes after it; `:nuget-processor:test` passed
(1546 tests, 0 failed). Inferred: the native pipeline was not run for this item, and nothing new is
generated, so no `LeakTests` row applies.

## Amendment (2026-10-03): the legacy-route predicate defers to the plan

`isForwardLegacyRoute()` no longer reports a lambda-parameter member the plan owns: it is now
"async, or an add/remove pair half, or a lambda member the plan does not own", and the Kotlin and
C# halves both call that one predicate (the Kotlin half had an inline copy). The two consequence
bullets above about the predicate and the untested generic case are closed by this.

A per-call lambda member the plan owns now binds on a generic class (ADR-147) and runs: `Box<T>`'s
`fun measure(scale: (Int) -> Int)` is called from C# as `box.Measure(n => n * 10)`. A lambda member
the plan does not own (`(Char) -> Unit`, `(T) -> Unit`) stays unbound on a generic owner and is now
named, see ADR-147's 2026-10-03 amendment. `(T) -> Unit` is declined in `isCallbackPayload`'s
`else -> false` arm for a `BridgeType.TypeParameter`.

Evidence, verified: `Tier1GenericOwnerLegacyRouteTest` (planned `Each` and `Count` bind) and the
consumer test `Box_PlannedCallbackMember_CallsTheDelegateBack`. No `LeakTests` row: the payload is a
scalar, so no handle is minted.
