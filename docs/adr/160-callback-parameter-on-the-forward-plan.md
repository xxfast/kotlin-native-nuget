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
halves (`legacyRefusedCallbackMember`, `forward/ForwardLegacyRouteCollections.kt:142`) instead of
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
- `isForwardLegacyRoute()` (`exports/ClassExports.kt:82`) still reports any lambda-parameter member
  as legacy regardless of whether the plan actually owns it. Tracked on the ROADMAP.
- Deliberately still refused by name, on both halves: `Char` on either the payload or the lambda-
  result axis (no by-value crossing convention and not a legal `[UnmanagedCallersOnly]` signature
  type); an object or enum lambda **result** (releasing a box the C# wrapper still owns is an
  unresolved ownership question); a nullable lambda; a `suspend` lambda; a lambda type nested inside
  a collection; a callback at a **result** position (returning a lambda, as opposed to taking one);
  a callback parameter on a constructor, a data class's `copy()`, an enum-arm box constructor, or a
  value-class member.
- No fixture exercises an unsigned-primitive or interface-typed callback payload on the plan; ten of
  forty-four branches in `forward/ForwardCirCallbackProjection.kt` are cold. Tracked on the
  ROADMAP.
- The plan's delegate segment spelling (`Uint`, `Ulong`) does not match the interface-bridge route's
  own unsigned spelling (`UInt`, `ULong`); cosmetic, tracked on the ROADMAP.
- A planned callback member on a generic class is untested.

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
