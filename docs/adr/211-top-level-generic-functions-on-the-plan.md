# ADR-211: Forward, a top-level `fun <T>` the width-variant route refuses binds on the callable plan

## Status
Accepted

Extends [ADR-197](197-member-generic-methods-on-the-plan.md) (owner: top-level). Narrows the
legacy width-variant route of [ADR-064](064-forward-diagnostics.md)'s 2026-09-13 amendment to the
one shape ADR-197:21 already documents for it. Issues #557 and #556.

## Context

A top-level `fun <T>` has exactly one route, the legacy width-variant route
(`exports/GenericFunctionExports.kt` `addGenericFunctionExports`, `cir/CirFunctionTranslator.kt`
`translateGenericFunction`). Its gate, `legacyGenericRouteParameterIndex()`
(`GenericFunctionExports.kt:37-56`), admits a function whose return is `T` or a generated generic
class over `T`, and which has at least one parameter typed `T`. It then carries **only the first**
such parameter; every other parameter is dropped from the generated call. **Verified by reading**,
and by spike below: `fun <T> pick(first: T, second: T): T` emits 13 exports each calling
`pick(first)`, and the generated Kotlin fails with `No value passed for parameter 'second'` (#556),
with no `[nuget:...]` diagnostic, since the gate accepted it.

A top-level generic whose return is not `T` (`fun <T : Any> describe(value: T): String`, #557) is
refused by that gate and never reaches the ADR-062 plan: `NugetProcessor.kt:1859-1866` splits
`typeParameters.isNotEmpty()` out of the catalog input (`:2421-2422` again for the catalog), the
planner's `staticEntry` (`forward/ForwardCallablePlanner.kt:2578`) admits a method's own type
parameter for `OBJECT` and `COMPANION` origins only, and `isForwardGenericMemberOwner`
(`forward/ForwardMemberTypeParameters.kt:20-28`) answers false for a top-level function.
**Verified by reading.** The refusal is named `SKIPPED_UNSUPPORTED_RETURN` by
`warnUnroutedGenericFunctions` (`NugetProcessor.kt:530-558`) with the `GENERIC` sentence
(`forward/ForwardDiagnostic.kt:910-916`), which says a function's own type parameter binds "at a
top-level function with a parameter of it", which is exactly what `describe` has. The sentence is
wrong for the top-level owner: the unstated condition is the return.

The same `describe` on an `object` binds through ADR-197 as `public static string Describe<T>(T
value_) where T : notnull`: one export, every `T` position a boxed handle, any number of
parameters, any plannable return. ADR-197:177 left the top-level owner on the legacy route only
because migrating it "would rename shipped entry points".

## Alternatives Considered

### 1. Fallback routing: the plan takes what the legacy gate refuses, the gate takes exactly its documented shape (chosen)

Tighten `legacyGenericRouteParameterIndex()` to the ADR-197:21 shape (exactly one parameter, typed
`T`, return `T` or a generated generic class over `T`). Every other top-level `fun <T>` enters the
catalog as an ordinary `TOP_LEVEL` static entry and routes on ADR-197's boxed-handle wire. Every
fixture the legacy route ships today has exactly one parameter, so no shipped entry point changes
(see Decision). Cost: two wires for the top-level owner, chosen by shape; a C# consumer sees one
generic method either way.

### 2. Full migration: retire the width variants, every top-level `fun <T>` on the plan

One wire, one owner predicate. Breaking: every shipped `..._{string,int,...,object}` entry point is
replaced by one boxed export, and the primitive widths that today cross raw would box. ADR-197:177
priced this as a separate breaking change; nothing in #557 or #556 needs it.

### 3. Widen the legacy route to carry every parameter

Keep top-level on the width variants and make the generator emit the extra parameters (typed per
variant for a second `T`, raw for a non-`T`). Rejected: 13 exports per function on a route the
codebase is retiring, the `_object` variant's `NotSupportedException` for a builtin, and a `String`
return (#557) still has no width to dispatch on, so it would need the plan anyway.

## Decision

Alternative 1.

```C#
// fun <T : Any> describe(value: T): String   (Describe.kt)
public static string Describe<T>(T value) where T : notnull     // plan, one export
// fun <T> pick(first: T, second: T): T
public static T Pick<T>(T first, T second)                      // plan, one export
// fun <T> identity(value: T): T                                   (unchanged, legacy widths)
public static T Identity<T>(T value)                            // 13 exports, raw primitives
```

### Routing

1. `legacyGenericRouteParameterIndex()` returns `-1` unless `parameters.size == 1`, in addition
   to its return and parameter-type checks. **Verified by spike**: with that one line, `pick` and
   `fun <T> tally(x: T, n: Int): T` leave the legacy route; `identity` stays on it byte-identical
   (`library_spike__identity_{string,...,object}` in both halves of the spike output).
2. `NugetProcessor.kt:1859-1866` and `:2421-2422`: the catalog input is `typeParameters.isEmpty()
   || !hasLegacyGenericFunctionRoute()`; `genericFunctions` (the legacy loops on both halves,
   `:3322` and `cir/CirTranslator.kt:356`) is `typeParameters.isNotEmpty() &&
   hasLegacyGenericFunctionRoute()`. `warnUnroutedGenericFunctions` receives only the legacy list
   and therefore names nothing a plan entry also names.
3. `isForwardGenericMemberOwner` answers true for a non-extension top-level function that the
   legacy gate refuses (`parentDeclaration == null && !hasLegacyGenericFunctionRoute()`), so the
   classifier (`forward/ForwardBridgeTypeClassifier.kt:158`) spells its `T` as
   `BridgeType.TypeParameter`. `forwardMemberGenericRefusal` reads the owner's type parameters in
   scope through `as? KSClassDeclaration` (empty for top-level) instead of the hard cast at
   `ForwardMemberTypeParameters.kt:120`; the rest of its refusals (reified, bound by a type
   parameter, multi-bound unmentioned, nested `T`, lambda, Flow, shadowing) apply unchanged.
4. `staticEntry` (`ForwardCallablePlanner.kt:2578`) adds `TOP_LEVEL` to `routesGeneric`.

**Verified by spike** (scratch edits, reverted; `:nuget-processor:test --tests
'*Tier1TopLevelGenericSpikeTest*'`, 2026-10-14): with those four edits and no other change the
Tier 1 harness reports `kspErrors=[] compileErrors=[] warnings=[]` for a file declaring
`describe`, `pick`, `tally` and `identity`, and emits:

```
[DllImport("library", ..., EntryPoint = "library_spike__describe")]
private static extern IntPtr Native_Describe(IntPtr value_, out IntPtr error);
public static string Describe<T>(T value_) where T : notnull
[DllImport("library", ..., EntryPoint = "library_spike__pick")]
private static extern IntPtr Native_Pick(IntPtr first, IntPtr second, out IntPtr error);
public static T Pick<T>(T first, T second)
public static T Tally<T>(T x, int n)
public static T Identity<T>(T value)        // legacy, 13 EntryPoints as before
```

```kotlin
@CName("library_spike__describe")
public fun export_library_spike__describe(value_: COpaquePointer, errorOut: COpaquePointer?): String = try {
  tier1.spike.describe<Any>(value_.asStableRef<Any>().get())
...
  val result = tier1.spike.pick<Any?>(first?.asStableRef<Any>()?.get(), second?.asStableRef<Any>()?.get())
```

So the ADR-163 export name (`library_spike__describe`, package-qualified, no width suffix), the
ADR-197 Kotlin type-argument spelling (`describe<Any>`, `pick<Any?>`), the file-facade static class
and the `where T : notnull` clause all come out of the existing plan machinery for a `TOP_LEVEL`
origin. Nothing else broke. Before the gate was tightened, the first spike run reproduced #556
exactly (`No value passed for parameter 'second'`, 13 times) while `describe` already bound, which
is the evidence that step 1 and steps 2-4 are independent.

### No shipped entry point moves

Every top-level `fun <T>` in `test-library` has exactly one parameter: `cat/Helpers.kt` (`identity`,
`wrapInBox`, `adoptPet`, reified `groomPet`), `pantry/Treats.kt` (`handBack`), `rankings/Treats.kt`
(`favourite`, `weigh`, `portion`, `nickname`), `multibound/Chains.kt` (`lead`),
`multibound/Rehearsals.kt` (`rehearse`, `headline`), `routes/KeywordRoutesSample.kt` (`put`, `keep`,
`hold`, `pick`, `span`, `seen`, `mark`), `unrouted/UnroutedPositionsSample.kt`
(`structuralOnTopLevel`; `structuralRefusedOnTopLevel(): List<T>` stays refused, see below).
**Verified by grep.** Each stays on the legacy route under the tightened gate. A multi-parameter
top-level generic the gate accepts today (`fun <T> f(x: T, n: Int): T`) produces uncompilable Kotlin
on every shipped version (#556), so nothing a consumer runs today moves; it is free to take the
plan.

### `value` as a C# parameter name (#557, second ask)

`Reserved.kt:187` lists `value` in `PLAN_OWNED_NAMES` (the property-setter slot) and
`bridgeParameterName` (`:221-225`) shifts it to `value_` on every planned callable. The legacy
route uses the narrower `abiSlotParameterName()` (`:244-248`), which is why `Identity<T>(T value)`
keeps its name and `Describe<T>(T value_)` does not. **Verified by reading and by the spike
output above.**

Decision: option (a). The ABI slot keeps the shift (`ForwardAbiParameter.name` stays `value_`, so
the role/name agreement at `forward/ForwardMarshallingModel.kt:609-625` and the Kotlin export are
untouched); the **public C# parameter** takes the source name. `ForwardPublicParameter`
(`ForwardMarshallingModel.kt:640-652`) already separates `name` from `kotlinName` (ADR-164); it
gains a `csharpName` that is `kotlinName.csharpParameterName()` when `name` differs from it only by
the `PLAN_OWNED_NAMES` shift and the plan-owned name is `value`, else `name`. The CIR projection
(`forward/ForwardCirPlanProjection.kt:37` and every site that spells the public parameter or a
`${local}Box` / `${name}HasValue` local off it) reads `csharpName`; the extern keeps `name`.
`handle`, `receiver`, `outer`, `errorOut`, `valueOut` and `resultFailedOut` keep the shift on
both names, since the C# wrapper body does declare those.

**Inferred (not spiked)**: the only C# body identifier spelled `value` on a planned callable is
ADR-195's Try twin out slot, and `CirTryNames.of(leading names, ...)`
(`ForwardCirPlanProjection.kt:642`) already moves that slot off a colliding user name
(`Tier1BacktickedNameRoutesTest.kt:409` shows a user `failure` next to the twin's out slots). If
that inference is wrong, `TryX(T value, out T value, ...)` is a CS0100 on a Result-returning
callable with a `value` parameter; the implementing agent must compile that shape in Tier 1 first.

Consumer impact: a source break for named arguments only (`Describe(value_: x)` becomes
`Describe(value: x)`); positional calls, the extern, the Kotlin export and the ADR-055 contract are
unchanged. In the repo it changes 42 fixture declarations across 15 `test-library` files and the
`value_` assertions in 12 test files (`Tier1ReservedParameterNamesTest` 12, `ForwardAbiDeclarationFormTest`
5, `ForwardAbiContractTest` 4, `Tier1TopLevelSuspendStateFlowTest` 4, `IntegrationTests/ReservedNamesTests.cs`
4, and one or two each in `Tier1BacktickedNameRoutesTest`, `Tier1BuiltinGenericBoundTest`,
`Tier1GenericNullableTypeArgumentTest`, `Tier1MultiBoundGenericTest`, `Tier1NullablePlainFlowElementTest`,
`Tier1UnroutedPositionsTest`). The ABI-form tests keep `value_` (they assert the extern); the
public-signature ones flip. Because it touches every planned callable with a `value` parameter and
not only generics, it ships as its own lane after the routing lane, behind the same ADR.

### Diagnostics

With fallback routing, a top-level `fun <T>` is refused only by `forwardMemberGenericRefusal`'s
shape rules (and the ADR-198 reified-unspellable case, unchanged). What remains refused at
top-level: a `T` nested in another type (`fun <T> f(): List<T>`, `structuralRefusedOnTopLevel`,
also #555), a `reified` `T`, a bound that is a type parameter, a multi-bound `T` no parameter
mentions, a lambda or Flow in the signature, and an extension function. Each is named through the
plan's existing `GENERIC` skip with the ` (here, <reason>)` clause, so `warnUnroutedGenericFunctions`'
`SKIPPED_UNSUPPORTED_RETURN` branch has nothing left to name except the reified case and is reduced
to that. The `GENERIC` sentence at `ForwardDiagnostic.kt:910-916` drops "at a top-level function
with a parameter of it" and reads: "a function's own type parameter binds on a top-level function
and on a class, object, companion or sealed-class member as a bare `T` or `T?` parameter or
return". A refused top-level shape then says why in the `(here, ...)` clause, never that a
parameter of `T` would have sufficed.

## Consequences

- #557: `fun <T : Any> describe(value: T): String` binds as `public static string Describe<T>(T
  value) where T : notnull` on the file-facade class, one boxed export.
- #556: `fun <T> pick(first: T, second: T): T` binds as `public static T Pick<T>(T first, T
  second)`; the uncompilable output is gone and no diagnostic is needed because it binds.
- A top-level generic with one `T` parameter and a `T` / `Box<T>` return is byte-identical to
  0.9.0 on both halves. A top-level generic of any other shape boxes its `T` positions, as a
  member's already does.
- The `value_` lane is a named-argument source break on every planned callable with a parameter
  called `value`; `CHANGELOG` names it.
- Deferred: full migration of the width variants (alternative 2); `T` inside a collection at
  top-level (#555, covered by ADR-212); `suspend fun <T>` at top-level (the suspend route's own owner rules).
- Leak row: `TopLevelGenericMethod_BoxedTypeParameter_ReturnsToBaseline` in
  `LeakTests/LiveHandleTests.cs`, beside the three `MemberGenericMethod_*` rows.
