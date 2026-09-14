# ADR-147: Forward, a method declared on a generic class binds on the C# generic carrier through the boxed-handle wire

## Status

Proposed

## Context

`ROADMAP.md:36`, **verified by execution** alongside [ADR-101](101-unexported-supertype-skip.md)'s
2026-09-13 amendment: a function declared on a generic class has no C# carrier, no Kotlin export
and no diagnostic.

```kotlin
open class Crate<T>(val item: T) {
  fun describe(tag: T): String = "$tag:$item"
}
```

renders `public class Crate<T> : IDisposable, INugetHandle` with a constructor, `Item` and
`Dispose()` and nothing else. `LabelledCrate : Crate<string>` (the ADR-101 fixture) therefore
carries exactly one `Describe`, its own `Describe(int)`, and `Crate<string>` itself carries none.
Pinned deliberately by `IntegrationTests/GenericBaseOverloadTests.GenericBase_CarriesNoDescribeOfItsOwn`.

Why properties only. All **verified by reading this branch**:

- `translateGenericClass` (`cir/CirClassTranslator.kt:1028-1097`) walks `cls.getAllProperties()`
  and builds a `CirGenericClass` whose model has **no `methods` field at all**
  (`cir/CirModel.kt:243-254`: `name`, `typeParameters`, `libraryName`, `nativePrefix`,
  `properties`, `disposable`, `hasPublicConstructor`, `isOpen`). Functions are never visited, so
  no diagnostic is ever emitted for them either: the only `ForwardDiagnosticSink.emit` in the
  function is `INFO_DROPPED_VARIANCE`.
- `GenericClassExports.kt` (`exports/`) mints `create_<width>` x12 + `create_object`, `_dispose`,
  and one `get_<prop>` per property. Same walk, same omission.
- The generic class never reaches the [ADR-062](062-forward-callable-plan.md) callable plan:
  `NugetProcessor.kt:1097-1099` splits `allClasses` into `classes` (`typeParameters.isEmpty()`)
  and `genericClasses`, and only `classes` go through `translateClass` and the planner. So the
  "UNSUPPORTED type combination" sentences [ADR-064](064-forward-unsupported-declaration-diagnostics.md)
  guarantees for an ordinary class's dropped members do not fire here: the member is invisible.

### How a `T` value already crosses today

The property projection is the whole answer to the load-bearing question ("how does a `T` cross
the ABI"), and it generalises to a parameter and a return without a new wire protocol. All
**verified by reading**:

- **Return side (`T` out of Kotlin).** `export_<prefix>_get_<prop>` returns
  `handle.asStableRef<Crate<*>>().get().item?.let { NugetHandles.retain(it) }`
  (`GenericClassExports.kt`, ADR-083 null pointer for `null`). `NugetHandles.retain(value: Any)`
  is `StableRef.create(value).asCPointer()` (`nuget-runtime/.../NugetRuntime.kt:51-54`), so a
  Kotlin `Int` crosses as a StableRef to the boxed `Int`, a `String` as a StableRef to the
  `String`, an exported object as a StableRef to itself. There is **no per-width getter**: the
  wire type for `T` on the way out is always one opaque handle, whatever `T` is instantiated to.
- **C# decode.** `NugetMarshal.FromHandle<T>(IntPtr)` (`cir/CirMarshalRenderer.kt:129-300`)
  dispatches on `typeof(T)`: the twelve primitive widths plus `char` and `string` read through
  `nuget_unwrap_<width>` and then `Native_dispose` the box; anything else goes to
  `Materialize<T>` (the ADR-094 factory registry), which hands the handle to the wrapper's
  `internal X(IntPtr)` constructor. `Nullable<int>` is normalised to `int` first (ADR-067).
- **Input side (`T` into Kotlin), already shipped for collection components.**
  `NugetMarshal.Wrap<T>(T value, out bool owned)` (`CirMarshalRenderer.kt:306-345`) is the exact
  mirror: `null` is `IntPtr.Zero`; a primitive/`char`/`string` is boxed by `nuget_wrap_<width>`
  (`NugetRuntime.kt:209-260`, `NugetHandles.retain(value as Any)`) with `owned = true`; an
  `INugetHandle` wrapper contributes its own live `Handle` with `owned = false`. ADR-099's rule
  (`CirMarshalRenderer.kt:591`: `finally { if (owned) Native.Dispose(element); }`) says who frees
  a minted box: the caller that minted it, the instant the callee has dereferenced it.
- **Kotlin decode of a box.** `nuget_unwrap_int` is `handle.asStableRef<Any>().get() as Int`
  (`NugetRuntime.kt:92-94`). The `create_object` export already does
  `value.asStableRef<Any>().get()` (or `asStableRef<Bound>()` for a bounded parameter) and passes
  the `Any` straight into the generic constructor (`GenericClassExports.kt:56-71`). That is a
  `T`-typed constructor parameter receiving an erased `Any`: the same shape a `T`-typed method
  parameter needs.

So the two halves of a `T` position already exist, in opposite directions, on two different
routes. This ADR pairs them on a method.

### The other precedent: the generic top-level function route

`fun <T> identity(value: T): T` takes the **typed-variant** route instead
([ADR-010](010-generics-mapping.md) "Generic functions", `exports/GenericFunctionExports.kt`,
`cir/CirFunctionTranslator.kt:740-910`): twelve per-width exports (`identity_int`, ...) plus
`identity_object`, and a C# `Identity<T>` body that `typeof(T)`-switches to the matching extern.
That route pays 13 exports per function to keep primitives unboxed. It is the alternative to the
boxed wire, priced below.

### Constraints

- `CirGenericClass` renders through `renderGenericClass` (`cir/CirClassRenderer.kt:24-150`), a
  hand-written template, not `renderClass`. `renderMethod(CirMethod, className)`
  (`CirClassRenderer.kt:540`) is a free-standing renderer already reused by the sealed renderer, so
  a `CirMethod` with a custom `body` can be rendered inside the generic class without touching the
  template's other members.
- `ForwardDiagnosticKind` (`forward/ForwardDiagnostic.kt`) already has the named kinds a refusal
  needs: `SKIPPED_UNSUPPORTED_INPUT`, `SKIPPED_UNSUPPORTED_RETURN`,
  `SKIPPED_UNSUPPORTED_COMBINATION`. The ADR-064 contract is that every dropped public member is
  named by one of them.
- Two pins encode today's absence and must be flipped **together** with this ADR:
  `GenericBaseOverloadTests.GenericBase_CarriesNoDescribeOfItsOwn` (the one ROADMAP names) and
  `GenericBaseOverloadTests.LabelledCrate_DeclaresExactlyOneDescribeTakingInt`, which uses
  `typeof(LabelledCrate).GetMethods()` with no `BindingFlags`. That overload returns inherited
  public methods too (inferred from the .NET reflection contract, not spiked; `DeclaredOnly` is
  the flag that excludes them), so once `Crate<string>` carries `Describe(string)` the subclass
  reports two `Describe`s and `Assert.Single` fails. The Tier 1 twin
  (`Tier1GenericBaseOverloadTest.kt`) counts `public string Describe(` occurrences and asserts
  `"Describe(string" !in cs`; both assertions flip as well.

## Alternatives Considered

### 1. Boxed-handle wire on the legacy generic route, one export per method (chosen)

Every `T`-typed position crosses as one opaque handle: in through `NugetMarshal.Wrap<T>`, out
through `NugetMarshal.FromHandle<T>`, exactly as the property getter and the collection component
already do. One `@CName` export per method, `<prefix>_<name>` (ADR-090 numbering on overloads).
Non-`T` positions are admitted only for the kinds the generic route already spells by hand:
the twelve primitive widths, `Boolean`, `String`, `Unit`.

**Pros:** no new wire protocol, no new runtime export, no new `NugetMarshal` member; the
`T`-parameter and `T`-return halves are each a verbatim copy of a shipped emit site; one export
per method (the constructor's 13 already dominate the generic class's export count); the same
body shape works for a bounded `T` (`asStableRef<Bound>()`, as `create_object` does) and for
`T?` (null pointer, ADR-083).
**Cons:** a primitive argument costs a box mint and a box dispose per call (two extra crossings),
where the typed-variant route would pass it by value; a hand-built body on the legacy route
rather than an ADR-062 plan entry (the generic class is already entirely off-plan, so this does
not widen an existing inconsistency, but it does not close it either).

### 2. Typed variants, mirroring the generic top-level function route

Thirteen exports per method (`crate_describe_int`, ..., `crate_describe_object`), a `typeof(T)`
switch in the C# body. **Pros:** primitives by value, no per-call box. **Cons:** 13 exports and
13 `[DllImport]`s per method, multiplied by the number of methods, and the switch only works for
a **single** `T` position; a method with `T` at two positions, or `T` at a return beside a
primitive parameter, needs a variant per combination. The function route accepts exactly one
`T` parameter for this reason (`legacyGenericRouteParameterIndex()` picks the first). Rejected:
the export growth is quadratic in the number of `T` positions and the boxed wire already exists.

### 3. Move generic classes onto the ADR-062 callable plan with a `BridgeType.TypeParameter` kind

Add a `TypeParameter(name)` kind to `ForwardMarshallingModel.kt`, lower it as `Wrap<T>` /
`FromHandle<T>` in `ForwardCirPlanProjection` and as `asStableRef<Any>().get()` / `retain` in
`ForwardKotlinPlanEmitter`, teach the receiver lowering to spell `asStableRef<Crate<Any?>>`, and
route `genericClasses` through `translateClass`. **Pros:** every non-`T` position an ordinary class
supports (exported classes, collections, enums, value classes, nullable primitives, lambdas,
`suspend`, `Flow`) comes for free; the generic class stops being a second dialect; ADR-064's
diagnostics fire without a hand-written filter. **Cons:** the largest footprint (planner,
projection, emitter, membership, catalog, plus retiring the hand-written property/constructor
template or living with two paths for one class), and every existing generic-route pin
(`Tier1GenericClassPrefixTest`, `Tier1NullableGenericPropertyTest`, `GenericTests`,
`GenericConstraintTests`, `GenericConstructorExceptionTests`, `NullableGenericPropertyTests`)
becomes a migration risk. This is the right end state and the natural follow-up once a non-`T`
class-typed position is actually asked for; it is not the narrowest closure of the ROADMAP line.

### 4. Refuse every generic-class method with a named skip, change nothing else

Closes the "no diagnostic" half only. Rejected as the whole answer because the fixture already
shows the demand (`Crate.describe` exists because a real overload case needed it), and because
the boxed wire makes the mechanism nearly free. Its diagnostic half is kept: everything this ADR
does not admit gets the named skip Alternative 4 would have given it.

## Decision

Alternative 1. A public function declared on a generic class binds as an instance method on the
C# generic carrier when every position is admissible (below); otherwise it is dropped with a
named ADR-064 diagnostic. The receiver, every `T` position and every admitted non-`T` position
cross through mechanisms that already ship.

### Consumer API

```csharp
using var crate = new Crate<int>(3);
Assert.Equal("7:3", crate.Describe(7));          // T parameter, string return

using var named = new Crate<string>("apple");
Assert.Equal("ripe:apple", named.Describe("ripe"));

using var oreo = new Cat("Oreo", 9);
using var box = new Crate<Cat>(oreo);
using Cat picked = box.Pick(oreo);                // T parameter, T return: a fresh wrapper the caller owns

var labelled = new LabelledCrate("apple");
Assert.Equal("#7:apple", labelled.Describe(7));   // the subclass's own Int overload (ADR-101)
Assert.Equal("ripe:apple", labelled.Describe("ripe")); // inherited from Crate<string>, no longer absent
```

Generated shape (`Crate<T>` unconstrained, `fun describe(tag: T): String` and
`fun pick(other: T): T`):

```csharp
internal static class CrateNative
{
    // ... create_<width> x12, create_object, get_item, dispose as today ...

    [DllImport("test-library", CallingConvention = CallingConvention.Cdecl, EntryPoint = "crate_describe")]
    internal static extern IntPtr Describe(IntPtr handle, IntPtr tag, out IntPtr error);

    [DllImport("test-library", CallingConvention = CallingConvention.Cdecl, EntryPoint = "crate_pick")]
    internal static extern IntPtr Pick(IntPtr handle, IntPtr other, out IntPtr error);
}

public class Crate<T> : IDisposable, INugetHandle
{
    // ... constructor, Item, Dispose as today ...

    public string Describe(T tag)
    {
        IntPtr tagBox = NugetMarshal.Wrap<T>(tag, out bool tagOwned);
        try
        {
            IntPtr result = NugetErrorNative.Check(CrateNative.Describe(_handle, tagBox, out IntPtr error), error);
            return Marshal.PtrToStringUTF8(result)!;
        }
        finally
        {
            if (tagOwned) NugetMarshal.Dispose(tagBox);
        }
    }

    public T Pick(T other)
    {
        IntPtr otherBox = NugetMarshal.Wrap<T>(other, out bool otherOwned);
        try
        {
            IntPtr result = NugetErrorNative.Check(CrateNative.Pick(_handle, otherBox, out IntPtr error), error);
            return NugetMarshal.FromHandle<T>(result);
        }
        finally
        {
            if (otherOwned) NugetMarshal.Dispose(otherBox);
        }
    }
}
```

`NugetErrorNative.Check<T>(T result, IntPtr error)` exists (`cir/CirErrorRenderer.kt:53`,
verified), `NugetMarshal.Wrap<T>` and `NugetMarshal.Dispose(IntPtr)` are `internal`/`public`
statics on the root helper (verified, `CirMarshalRenderer.kt:306`, `:301`), and the wrapper is in
the same generated file, so nothing new is exposed.

### Kotlin exports

```kotlin
@CName("crate_describe")
fun export_crate_describe(handle: COpaquePointer, tag: COpaquePointer, errorOut: COpaquePointer?): String? =
  try {
    handle.asStableRef<Crate<Any?>>().get().describe(tag.asStableRef<Any>().get())
  } catch (e: Throwable) {
    if (errorOut != null) {
      errorOut.reinterpret<COpaquePointerVar>().pointed.value = NugetHandles.retain(buildError(e))
    }
    null
  }

@CName("crate_pick")
fun export_crate_pick(handle: COpaquePointer, other: COpaquePointer, errorOut: COpaquePointer?): COpaquePointer? =
  try {
    handle.asStableRef<Crate<Any?>>().get().pick(other.asStableRef<Any>().get())
      ?.let { NugetHandles.retain(it) }
  } catch (e: Throwable) {
    // same error-out shape
    null
  }
```

Mechanism claims, labelled:

- **Receiver.** `handle.asStableRef<Crate<Any?>>().get()`. The shipped getter spells
  `asStableRef<Crate<*>>()` (verified). `<*>` is unusable for a `T`-typed parameter (a
  star-projected `describe` accepts `Nothing`), so the export names the erased instantiation
  `Crate<Any?>` instead: a type argument to `asStableRef<T : Any>()`, not a cast expression, so
  no `UNCHECKED_CAST` warning is expected, and Kotlin/Native erases class type arguments so the
  runtime check on `get()` is the class check only. **Inferred, not spiked**: no scratch
  Kotlin/Native build was run for this ADR. If wrong, it is wrong **loudly** (a compile error in
  the generated Kotlin, caught by `packNuget`), never a silently wrong binding. For a bounded
  parameter (`class Kennel<T : Pet>`) spell the bound, `asStableRef<Kennel<Pet>>()`, and decode
  the argument with `asStableRef<Pet>()`, the `boundQualified` shape `create_object` already
  computes (verified, `GenericClassExports.kt:53-64`).
- **`T` parameter in.** `tag.asStableRef<Any>().get()` is byte-identical to `create_object`'s
  argument expression (verified). The box it reads was minted by `nuget_wrap_<width>` /
  `nuget_wrap_string` or is a live wrapper handle (verified, `Wrap<T>`), both StableRefs to an
  `Any` (verified, `NugetRuntime.kt`). Nullable `T?` parameter: `tag?.asStableRef<Any>()?.get()`
  with the C# parameter typed `T?` and `Wrap<T>` returning `IntPtr.Zero` for `null` (verified,
  `CirMarshalRenderer.kt:311`). The Kotlin side **borrows** the box and never disposes it; the
  C# `finally` does when `owned` (ADR-099, verified).
- **`T` return out.** `result?.let { NugetHandles.retain(it) }` is byte-identical to the
  property getter (verified). `FromHandle<T>` disposes the box for primitives/`string`
  and transfers ownership to the wrapper for objects (verified). A non-null `T` return that is
  `null` at runtime cannot happen; a nullable `T?` return is typed `T?` in C# and reads `default!`
  at `IntPtr.Zero` (ADR-083, verified). `FromHandle<T>` has no enum branch (backlog
  `fromhandle-no-enum-branch.md`), so an enum instantiation of a `T`-returning method throws
  the same named `NotSupportedException` a `Box<Mood>.Value` does today; unchanged, not widened.
- **Non-`T` positions.** `String` parameter/return and the twelve primitive widths ride the
  Kotlin/Native C export mapping the ordinary route already uses (`String` return declared as
  `returns(String)`, verified `exports/ClassExports.kt:231`; nullable `String?` return verified
  `forward/ForwardKotlinPlanEmitter.kt:711`), decoded in C# with `Marshal.PtrToStringUTF8`.
  Nothing new.
- **Error channel.** ADR-032's trailing `errorOut` + null/default return, verified against
  `addGenericCreateExport` and `GenericFunctionExports.kt:74-110` (`defaultValueFor` for a
  primitive return on throw, `Helpers.kt:95`).
- **Entry point.** `<nativePrefix>_<name>` (`nativePrefix()` is the lowercased owner chain,
  verified `cir/CirTypeMapping.kt:214`), overloads numbered `_2`, `_3` in declaration order
  per ADR-090. Collisions surface through ADR-117's owner-naming check like any other export.
- **ABI contract.** The generation-time forward ABI check pairs `[DllImport]` entry points with
  `@CName` exports (ADR-055/078). Both sides are added in the same change, so it is expected to
  stay green without an edit; **inferred**, the check's coverage of per-declaration exports was
  not read for this ADR. If it needs a hand-listed entry, that is one line in
  `ForwardAbiContract.kt`.

### Admission rule (v1)

A public, non-`suspend`, non-`inline`, declared (`getDeclaredFunctions()`, not `getAllFunctions()`:
inherited `Any` members and substituted base members stay off) function of a generic class binds
when:

- it declares **no type parameters of its own** (`fun <R> map(f: (T) -> R)` is refused:
  `SKIPPED_UNSUPPORTED_COMBINATION`, the structural wording `Tier1UnroutedPositionsTest` cell X2
  already uses for an own-`<T>` on an ordinary class);
- every parameter type is one of: a type parameter of the class (`T`, `T?`), `String`,
  `String?`, or a primitive width `Wrap<T>` handles (`Boolean`, `Byte`..`ULong`, `Float`,
  `Double`, `Char`); anything else is `SKIPPED_UNSUPPORTED_INPUT` naming the position;
- the return type is `Unit`, a class type parameter (`T`, `T?`), `String`, `String?` or a
  primitive width; anything else is `SKIPPED_UNSUPPORTED_RETURN` naming the position.

`T` may appear at any number of positions, in any combination (parameter only, return only,
both): each position is one independent box. A class with several type parameters
(`Pairing<A, B>`) admits any of its own parameters by name; the constructor and property limits
those classes already have (`typeParams.first()` everywhere, backlog
`concrete-typed-property-generic-class-mis-surfaced.md`) are untouched.

Deferred by name, all as ADR-064 named skips, never silent: exported class / object / sealed /
enum / value-class positions, collections, lambdas, `Flow`/`StateFlow`, `suspend`, nullable
primitives, extension functions with a generic receiver, functions on a generic **subclass**
(ADR-101: still renders base-less, still on ROADMAP). Every one of these is Alternative 3's
territory.

### Where it lands (price)

| File | Change |
|------|--------|
| `cir/CirModel.kt` | `CirGenericClass.methods: List<CirMethod> = emptyList()` |
| `cir/CirClassTranslator.kt` (`translateGenericClass`) | walk `getDeclaredFunctions()`, apply the admission rule, build one `CirMethod` (custom body, ADR-090 numbering) per admitted function, emit the named skip per refused one |
| `exports/GenericClassExports.kt` | one `@CName` export per admitted function, same walk, same rule (share the predicate with the translator so the two halves cannot disagree, the ADR-064 lesson) |
| `cir/CirClassRenderer.kt` (`renderGenericClass`) | one `[DllImport]` per method in `<Name>Native`, then `renderMethod(method, cls.name)` per method after the properties |
| `test-library/.../parcel/Parcel.kt` | add `fun pick(other: T): T` to `Crate` so both directions are exercised |
| `IntegrationTests/GenericBaseOverloadTests.cs` | flip `GenericBase_CarriesNoDescribeOfItsOwn`; rewrite `LabelledCrate_DeclaresExactlyOneDescribeTakingInt` with `BindingFlags.DeclaredOnly` or as a two-method assertion naming both declaring types |
| `nuget-processor/.../tier1/Tier1GenericBaseOverloadTest.kt` | the `Describe(` count becomes 2, `Describe(string` becomes expected, the export count adds `crate_describe` |
| `IntegrationTests/GenericMethodTests.cs` (new) | the sample below |
| `LeakTests/LiveHandleTests.cs` | the row below |

Four generator files, one fixture, three test files. No runtime, marshal-helper or ABI-contract
change expected.

### Sample xunit test

```csharp
using TestLibrary.Cat;
using TestLibrary.Parcel;

namespace IntegrationTests;

/// <summary>
/// ADR-147: a method declared on a generic class binds on the generic carrier. Oreo's crate is
/// described with whatever tag its contents are typed as; Mylo's is still numbered.
/// </summary>
public class GenericMethodTests
{
    [Fact]
    public void Crate_Int_DescribeTakesTheTypeParameter()
    {
        using var crate = new Crate<int>(3);
        Assert.Equal("7:3", crate.Describe(7));
    }

    [Fact]
    public void Crate_String_DescribeTakesTheTypeParameter()
    {
        using var crate = new Crate<string>("apple");
        Assert.Equal("ripe:apple", crate.Describe("ripe"));
    }

    [Fact]
    public void Crate_Cat_PickReturnsAFreshWrapperOverTheSameKotlinObject()
    {
        using var oreo = new Cat("Oreo", 9);
        using var crate = new Crate<Cat>(oreo);
        using Cat picked = crate.Pick(oreo);
        Assert.Equal("Oreo", picked.Name);
    }

    [Fact]
    public void LabelledCrate_InheritsDescribeOfString_AndKeepsItsOwnDescribeOfInt()
    {
        using var crate = new LabelledCrate("apple");
        Assert.Equal("ripe:apple", crate.Describe("ripe"));
        Assert.Equal("#7:apple", crate.Describe(7));
    }
}
```

### Leak row

One new `LiveHandleTests` row, the first generic-class row in the harness (there is none today;
grep `Box<` in `LeakTests/` returns nothing, verified):

```csharp
// Row 10. ADR-147: a T parameter on a generic-class method. `Wrap<int>` mints one
// `nuget_wrap_int` box, the Kotlin export borrows it, the C# `finally` disposes it; the
// String return mints nothing (Row 2). The T return of Pick mints one retain that
// `FromHandle<int>` unwraps and disposes.
[Fact]
public void GenericClassMethod_BoxedTypeParameter_ReturnsToBaseline()
{
    AssertNoLeak(() =>
    {
        using var crate = new Crate<int>(3);
        Assert.Equal("7:3", crate.Describe(7));
        Assert.Equal(7, crate.Pick(7));
    });
}
```

Expected ledger per iteration: `crate_create_int` +1, `nuget_wrap_int` +1 / `Native_dispose` -1
(Describe), `nuget_wrap_int` +1 / -1 and `retain` +1 / `Native_dispose` -1 (Pick),
`crate_dispose` -1. Net zero. A `Crate<Cat>` variant mints nothing on the parameter side
(`owned = false`) and transfers one retain into the returned wrapper, released by its `using`.

## Consequences

- `Crate<T>` gains its declared methods; `LabelledCrate` inherits `Describe(string)` beside its
  own `Describe(int)`, which is exactly what Kotlin gives a caller of `LabelledCrate`. C# overload
  resolution picks by argument type as Kotlin does.
- Two IntegrationTests pins and one Tier 1 pin flip deliberately; the ROADMAP line closes and
  its deferred list moves to this ADR's "Deferred by name".
- Every generic-class function the rule refuses is now **named** (ADR-064), where today it is
  invisible. That alone changes `nugetReportDiagnostics` output for any consumer with a generic
  class carrying, say, a `List<T>` method: new WARNING lines, no new failures.
- One box mint + dispose per primitive/`string` argument per call. Measured nowhere; if a
  crossing microbenchmark (backlog `crossing-microbenchmark.md`) ever shows it matters,
  Alternative 2's per-width variants can be added for the hot method without changing the C#
  signature, since the dispatch is inside the body.
- Not verified by a spike, stated in the red register: the `asStableRef<Crate<Any?>>()` receiver
  spelling and the "no `UNCHECKED_CAST` warning" expectation. Nobody has built it. If it fails,
  it fails at `packNuget` as a Kotlin compile error, and the fallback is a star-projected read
  plus an explicit `@Suppress("UNCHECKED_CAST") as Crate<Any?>` cast, which is a one-line change
  in `GenericClassExports.kt`. Also not verified: that `GetMethods()` without flags returns the
  inherited `Describe(string)` (it does per the documented reflection contract; if it does not,
  the second pin does not need to flip).
- Unchanged: the property projection, the constructor variants, `NugetMarshal`, the runtime,
  the ADR-062 plan. Alternative 3 remains the recorded path for any non-`T` position beyond
  primitives and `String`.
