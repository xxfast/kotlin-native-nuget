# ADR-147: Forward, generic classes move onto the ADR-062 callable plan with a `BridgeType.TypeParameter` kind, so a method declared on a generic class binds on the C# generic carrier

## Status

Accepted 2026-09-16

Decided 2026-09-16 by the human: build Alternative 3 (the end state), not Alternative 1 (the
narrower patch on the legacy generic route). Sub-option (b) below, whole-class migration, is the
recommendation; sub-option (a), methods-only, is priced as the alternative.

## Context

`ROADMAP.md` Phase 4, **verified by execution** alongside [ADR-101](101-unexported-supertype-skip.md)'s
2026-09-13 amendment: a function declared on a generic class has no C# carrier, no Kotlin export
and no diagnostic.

```kotlin
open class Crate<T>(val item: T) {
  fun describe(tag: T): String = "$tag:$item"
}
```

renders `public class Crate<T> : IDisposable, INugetHandle` with a constructor, `Item` and
`Dispose()` and nothing else. `LabelledCrate : Crate<string>` (the ADR-101 fixture,
`test-library/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/test/parcel/Parcel.kt:31-43`,
verified 2026-09-16) therefore carries exactly one `Describe`, its own `Describe(int)`, and
`Crate<string>` itself carries none. Pinned deliberately by
`IntegrationTests/GenericBaseOverloadTests.GenericBase_CarriesNoDescribeOfItsOwn`.

Why properties only. All **verified by reading, re-cited 2026-09-16**:

- `translateGenericClass` (`cir/CirClassTranslator.kt:1033`) walks `cls.getAllProperties()` and
  builds a `CirGenericClass` whose model has **no `methods` field** (`cir/CirModel.kt:301-312`:
  `name`, `typeParameters`, `libraryName`, `nativePrefix`, `properties`, `disposable`,
  `hasPublicConstructor`, `isOpen`). Functions are never visited, so no diagnostic is emitted for
  them either.
- `exports/GenericClassExports.kt` (145 lines) mints `create_<width>` x12 + `create_object`
  (`:47-70`, the `boundQualified` / `asStableRef<Bound>()` shape), `_dispose`, and one
  `get_<prop>` per property. Same walk, same omission.
- The generic class never reaches the [ADR-062](062-forward-callable-plan.md) plan.
  `NugetProcessor.kt:1105-1107` splits `allClasses` into `classes` (`typeParameters.isEmpty()`)
  and `genericClasses`; only `classes` go through `translateClass` (`CirClassTranslator.kt:469`,
  which takes the `callableCatalog`) and the planner. `genericClasses` go to
  `builder.addGenericClassExports(it)` (`NugetProcessor.kt:1580`) and `renderGenericClass`
  (`cir/CirRenderer.kt:109`, `is CirGenericClass ->`). The ABI contract lists the whole class as
  one legacy route, `ForwardAbiLegacyRoute.GENERIC_CLASS` (`ForwardAbiLegacyRoutes.kt:43`, `:96`).
- The classifier refuses a type-parameter position outright:
  `ForwardBridgeTypeClassifier.classifyNonNullable` (`:74-81`) returns
  `BridgeType.Unsupported(name, "type parameters require the named generic legacy route")` for any
  `KSTypeParameter`, and returns `SpecializedProtocol("generic declaration ...")` for a reference
  *to* a generic class (`:251-253`). So even if a generic class were fed to the planner today,
  every `T` position would be a named `SKIPPED_UNSUPPORTED_*`.

### How a `T` value already crosses today

This is the load-bearing question ("how does a `T` cross the ABI") and it is already answered by
shipped code, in both directions, on two routes. All **verified by reading**:

- **Return side (`T` out of Kotlin).** `export_<prefix>_get_<prop>` returns
  `handle.asStableRef<Crate<*>>().get().item?.let { NugetHandles.retain(it) }`
  (`GenericClassExports.kt`, ADR-083 null pointer for `null`). `NugetHandles.retain(value: Any)`
  is `StableRef.create(value).asCPointer()` (`nuget-runtime/.../NugetRuntime.kt`), so a Kotlin
  `Int` crosses as a StableRef to the boxed `Int`, a `String` as a StableRef to the `String`, an
  exported object as a StableRef to itself. One opaque handle, whatever `T` is instantiated to.
- **C# decode.** `NugetMarshal.FromHandle<T>(IntPtr)` (`cir/CirMarshalRenderer.kt`) dispatches on
  `typeof(T)`: the twelve primitive widths plus `char` and `string` read through
  `nuget_unwrap_<width>` and then dispose the box; anything else goes to `Materialize<T>` (the
  ADR-094 factory registry). `Nullable<int>` is normalised to `int` first (ADR-067).
- **Input side (`T` into Kotlin), shipped for collection components.**
  `NugetMarshal.Wrap<T>(T value, out bool owned)` is the mirror: `null` is `IntPtr.Zero`; a
  primitive/`char`/`string` is boxed by `nuget_wrap_<width>` with `owned = true`; an
  `INugetHandle` wrapper contributes its own live `Handle` with `owned = false`. ADR-099's rule
  (`finally { if (owned) Native.Dispose(...) }`) says who frees a minted box: the caller that
  minted it, the instant the callee has dereferenced it.
- **Kotlin decode of a box.** `create_object` already does `value.asStableRef<Any>().get()`
  (or `asStableRef<Bound>()` for a bounded parameter, `GenericClassExports.kt:52-63`) and passes
  the `Any` into the `T`-typed constructor parameter. The same shape a `T`-typed method parameter
  needs.

### The plan already knows how to do everything except spell `T`

**Verified by reading, 2026-09-16.** The ADR-062 route already carries, per callable: KDoc
(`ForwardCallablePlanner.kt:937`, `doc = method.forwardKdoc(expects).forParameters(...)`, so
[ADR-150](150-kdoc-to-csharp-xml-docs.md) needs no work here); ADR-090 overload numbering
(`:911-913`, `_2`, `_3` suffixes); the ADR-101 inherited-vs-declared test that understands
substituted generic-base members (`ForwardClassMembership.kt:380-395`, `inherited.declares(method)`
at `ForwardCallablePlanner.kt:905`); own-type-parameter refusal
(`method.typeParameters.isNotEmpty() -> ForwardPlanSkipReason.GENERIC`, `:916`); the `suspend`
hand-off to its legacy route (`:915`); the ADR-064 named skip for every unsupported position; the
`ObjectHandle` lowering that `TypeParameter` copies (`ForwardCirPlanProjection.kt:578`, `:610`,
`:838`, `:1115`, `:1132`; `ForwardKotlinPlanEmitter.kt:1113-1117`, `:1135-1139`, `:226`); and the
receiver lowering `handle.asStableRef<$owner>().get().$functionName($arguments)`
(`ForwardKotlinPlanEmitter.kt:918-921`, `owner = plan.invocation.symbol.substringBeforeLast('.')`).

Precedent for moving a whole family onto the plan: [ADR-111](111-sealed-subclass-properties-on-the-property-plan.md)
(sealed-subclass properties) and [ADR-116](116-sealed-subclass-methods-on-the-callable-plan.md)
(sealed-subclass methods) did exactly this for the sealed route.

### Measured: what a new `BridgeType` variant breaks, and what swallows it

**Verified by spike, 2026-09-16.** In a detached worktree of `main`, a dummy
`data class TypeParameterProbe(val name: kotlin.String) : BridgeType` was added after
`RawCollection` (`ForwardMarshallingModel.kt:261`) and `./gradlew :nuget-processor:compileKotlin`
was run. Exactly **7** `'when' expression must be exhaustive` errors, at:

```
ForwardCallablePlanner.kt:3367   BridgeType.wireType()             (ABI wire type)
ForwardCallablePlanner.kt:3478   BridgeType.isBridgeableComponent() (collection component gate)
ForwardCallablePlanner.kt:3745   BridgeType.skipReason()           (ADR-064 named skip)
ForwardDiagnostic.kt:949         BridgeType.diagnosticTypeName()   (diagnostic wording)
ForwardMarshallingModel.kt:697   ForwardCallablePlan.validateType  (plan invariant)
ForwardPropertyPlanner.kt:329    BridgeType.isSupportedReceiver()  (extension-property receiver)
ForwardPropertyPlanner.kt:698    BridgeType.isReadableComponent()  (property getter component)
```

Those seven are the sites that *decide*; the compiler forces each to take a position on `T`.
Everything else falls through an `else ->`. A heuristic count (a `when` whose body tests
`is BridgeType.X` and ends in a top-level `else ->`; nested `when`s may be double-counted) found
**87** such sites: `ForwardCallablePlanner.kt` 18, `ForwardKotlinPlanEmitter.kt` 18,
`ForwardCirPlanProjection.kt` 14, `ForwardCirPropertyProjection.kt` 13,
`ForwardPropertyKotlinEmitter.kt` 9, `ForwardPropertyPlanner.kt` 6,
`ForwardCirCollectionComponents.kt` 3, `ForwardLegacyRouteCollections.kt` 2, and one each in
`ForwardCsharpTypes.kt:11`, `ForwardMarshallingModel.kt:741`, `ForwardPropertyPlan.kt:110`,
`CirClassTranslator.kt:258`. The consequence for the implementation: the seven compile errors
are the *entry* to the work, not its extent. Every `else ->` in the projection and the emitter
that lowers a parameter, a result or a receiver has to be read for what it does to an unknown
kind (most spell the wire as `IntPtr`, which happens to be right for `T`; some spell a C# type
name from the kind, which would print nonsense). The Tier 1 test in the file table pins the
rendered text so a missed `else` fails there, not at the consumer.

### Constraints

- Two pins encode today's absence and flip **together** with this ADR:
  `GenericBaseOverloadTests.GenericBase_CarriesNoDescribeOfItsOwn` and
  `GenericBaseOverloadTests.LabelledCrate_DeclaresExactlyOneDescribeTakingInt` (uses
  `typeof(LabelledCrate).GetMethods()` with no `BindingFlags`, which returns inherited public
  methods too; confirmed by the shipped `GenericBase_CarriesDescribeAndPickOfItsOwn` and
  `LabelledCrate_DeclaresExactlyOneDescribeTakingInt` cells). The generated **text** never
  contains `Describe(string`, before or after this ADR: the carrier declares `Describe(T tag)`
  once; `LabelledCrate`'s inherited `string` overload is a C# generic-inheritance fact that only
  reflection sees (`typeof(Crate<string>).GetMethods()`), not a second declaration in the emitted
  source. A Tier 1 test asserting `"Describe(string" !in cs` stays true either way and is not a
  signal for this ADR.
- ADR-120: any route that mints a handle gets a `LeakTests/LiveHandleTests.cs` row. There is no
  generic-class row today (`grep Crate\|Box LeakTests/*.cs` returns nothing, verified).
- ADR-117: entry points are `<nativePrefix>_<name>` and collide through the owner-naming check
  like any other export.
- ADR-054/078: the ABI contract pairs `[DllImport]` with `@CName`. Today the generic class is
  covered as one opaque legacy route (`GENERIC_CLASS`); once on the plan its members are covered
  per declaration by `assertMatchesPlan` (`ForwardAbiContract.kt:259`), which is stricter.

## Alternatives Considered

### 1. Boxed-handle wire on the legacy generic route, one hand-built export per method

Every `T` position crosses as one opaque handle exactly as the property getter does, emitted by
a new arm in `GenericClassExports.kt` and rendered by `renderMethod` inside `renderGenericClass`.
Four generator files, no plan change. **Rejected by the human 2026-09-16**: it leaves the generic
class as a second dialect (own translator, own renderer, own export builder, own ABI route) and
admits only the positions that dialect spells by hand (primitives, `String`, `T`); every other
position an ordinary class supports would need a second copy of plan logic, or a hand-written
filter to produce the ADR-064 diagnostic the plan would give for free.

### 2. Typed variants, mirroring the generic top-level function route

Thirteen exports per method (`crate_describe_int`, ..., `crate_describe_object`) and a
`typeof(T)` switch in the C# body, as `GenericFunctionExports.kt` does for `fun <T> identity`.
Rejected: the switch only works for a single `T` position; a method with `T` at two positions
needs a variant per combination, quadratic export growth, and the boxed wire already exists.

### 3. Move generic classes onto the ADR-062 callable plan with a `BridgeType.TypeParameter` kind (chosen)

Below.

### 4. Refuse every generic-class method with a named skip, change nothing else

Closes the "no diagnostic" half only. Rejected as the whole answer because the fixture already
shows the demand and the mechanism is nearly free. Its diagnostic half is what the plan gives
every position Alternative 3 does not admit.

## Decision

Alternative 3, sub-option (b): the generic class becomes an ordinary `CirClass` with type
parameters. `translateGenericClass`, `renderGenericClass`, `GenericClassExports.kt`,
`CirGenericClass` and `ForwardAbiLegacyRoute.GENERIC_CLASS` retire. A `BridgeType.TypeParameter`
kind carries `T` at a parameter, return, constructor-parameter and property-getter position, and
lowers as the boxed-handle wire that already ships. A public method declared on a generic class
binds as an instance method on the C# generic carrier when every position is admissible; every
other public member is dropped with the ADR-064 named skip the plan already produces.

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

### Generated C# (byte-lifted from a built consumer, 2026-09-16, elided to the load-bearing members)

CS7042 rules out a `[DllImport]` inside a generic type, so `renderClass` hoists every extern into a
sibling non-generic `{Name}Native` class and leaves a same-signature private forwarding method of
identical name in the carrier itself (`hoistDllImports`, `CirClassRenderer.kt`):

```csharp
internal static class CrateNative
{
    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "crate_create")]
    internal static extern IntPtr Native_Create(IntPtr item, out IntPtr error);

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "crate_describe")]
    internal static extern IntPtr Native_Describe(IntPtr handle, IntPtr tag, out IntPtr error);

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "crate_pick")]
    internal static extern IntPtr Native_Pick(IntPtr handle, IntPtr other, out IntPtr error);

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "crate_label")]
    internal static extern IntPtr Native_Label(IntPtr handle, [MarshalAs(UnmanagedType.LPUTF8Str)] string prefix, int count, out IntPtr error);

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "crate_dispose")]
    internal static extern void Native_Dispose(IntPtr handle);
}

public class Crate<T> : IDisposable, INugetHandle
{
    internal IntPtr _handle;

    private static IntPtr Native_Create(IntPtr item, out IntPtr error) => CrateNative.Native_Create(item, out error);

    public Crate(T item)
    {
        IntPtr itemBox = IntPtr.Zero;
        bool itemOwned = false;
        try
        {
            itemBox = NugetMarshal.Wrap<T>(item!, out itemOwned);
            IntPtr handle = Native_Create(itemBox, out IntPtr error);
            if (error != IntPtr.Zero) { throw NugetErrorNative.BuildException(error); }
            _handle = handle;
        }
        finally { if (itemOwned && itemBox != IntPtr.Zero) { NugetMarshal.Dispose(itemBox); } }
    }

    private static IntPtr Native_Describe(IntPtr handle, IntPtr tag, out IntPtr error) => CrateNative.Native_Describe(handle, tag, out error);

    public string Describe(T tag)
    {
        IntPtr tagBox = IntPtr.Zero;
        bool tagOwned = false;
        try
        {
            tagBox = NugetMarshal.Wrap<T>(tag!, out tagOwned);
            IntPtr nativeResult = Native_Describe(_handle, tagBox, out IntPtr error);
            if (error != IntPtr.Zero) { throw NugetErrorNative.BuildException(error); }
            return Marshal.PtrToStringUTF8(nativeResult)!;
        }
        finally { if (tagOwned && tagBox != IntPtr.Zero) { NugetMarshal.Dispose(tagBox); } }
    }

    private static IntPtr Native_Pick(IntPtr handle, IntPtr other, out IntPtr error) => CrateNative.Native_Pick(handle, other, out error);

    // `T` at a parameter *and* at the return: the boxed-handle wire crosses in both directions on
    // one call. A primitive instantiation pays a box mint per argument; an exported-class
    // instantiation borrows the wrapper's own live handle and mints nothing on the way in.
    public T Pick(T other)
    {
        IntPtr otherBox = IntPtr.Zero;
        bool otherOwned = false;
        try
        {
            otherBox = NugetMarshal.Wrap<T>(other!, out otherOwned);
            IntPtr nativeResult = Native_Pick(_handle, otherBox, out IntPtr error);
            if (error != IntPtr.Zero) { throw NugetErrorNative.BuildException(error); }
            return NugetMarshal.FromHandle<T>(nativeResult);
        }
        finally { if (otherOwned && otherBox != IntPtr.Zero) { NugetMarshal.Dispose(otherBox); } }
    }

    // No `T` anywhere: once the generic class is on the callable plan, an ordinary position binds
    // on it exactly as it does on a non-generic class.
    private static IntPtr Native_Label(IntPtr handle, string prefix, int count, out IntPtr error) => CrateNative.Native_Label(handle, prefix, count, out error);

    public string Label(string prefix, int count) { /* same shape as Describe, no Wrap/Dispose */ }

    // Dispose(), _handle, Handle as an ordinary class; `virtual Dispose()` when `open` (ADR-101)
}
```

Note the constructor: under (b) the twelve `create_<width>` variants and `create_object` collapse
to one `crate_create(IntPtr)` that reads the box, because a `T` constructor parameter is just a
`TypeParameter` position on the plan's constructor route. A primitive argument therefore costs a
box mint and a dispose per construction where today it is passed by value. This is the one
observable cost of (b) over today, and it is the same cost every `T` method parameter pays.

### Generated Kotlin (verified against the built KSP output, 2026-09-16)

```kotlin
@CName("crate_create")
public fun export_crate_create(item: COpaquePointer, errorOut: COpaquePointer?): COpaquePointer? = try {
  NugetHandles.retain(io.github.xxfast.kotlin.native.nuget.test.parcel.Crate<Any>(item.asStableRef<Any>().get()))
} catch (e: Throwable) { /* ADR-032 error-out */ null }

@CName("crate_describe")
public fun export_crate_describe(handle: COpaquePointer, tag: COpaquePointer, errorOut: COpaquePointer?): String = try {
  handle.asStableRef<io.github.xxfast.kotlin.native.nuget.test.parcel.Crate<Any>>().get().describe(tag.asStableRef<Any>().get())
} catch (e: Throwable) { /* ADR-032 error-out */ "" }

@CName("crate_pick")
public fun export_crate_pick(handle: COpaquePointer, other: COpaquePointer, errorOut: COpaquePointer?): COpaquePointer? = try {
  NugetHandles.retain(handle.asStableRef<io.github.xxfast.kotlin.native.nuget.test.parcel.Crate<Any>>().get().pick(other.asStableRef<Any>().get()))
} catch (e: Throwable) { /* ADR-032 error-out */ null }

// A bounded type parameter spells the bound instead of `Any` (PetCrate<T : Pet>):
@CName("petcrate_create")
public fun export_petcrate_create(value_: COpaquePointer, errorOut: COpaquePointer?): COpaquePointer? = try {
  NugetHandles.retain(io.github.xxfast.kotlin.native.nuget.test.nested.PetCrate<io.github.xxfast.kotlin.native.nuget.test.cat.Pet>(value_.asStableRef<io.github.xxfast.kotlin.native.nuget.test.cat.Pet>().get()))
} catch (e: Throwable) { /* ADR-032 error-out */ null }
```

Note `Crate<Any>`, not `Crate<Any?>`: the receiver spelling is the erased **non-null** argument
(`forwardOwnerTypeName()`, `ForwardBridgeTypeClassifier.kt:625-636`). `Crate<Any?>` would make a
`val item: T` getter's `NugetHandles.retain(it)` call `retain(Any?)`, which does not compile
against `retain(value: Any)`; a declared-nullable position (`T?`) still substitutes to `Any?` on
its own and keeps the ADR-083 null pointer. `LabelledCrate` (no type parameters of its own) keeps
the bare receiver spelling, `asStableRef<LabelledCrate>()`.

### Mechanism, per site, labelled

- **Model.** `data class TypeParameter(val name: kotlin.String, val boundQualifiedName: kotlin.String? = null) : BridgeType`
  in `ForwardMarshallingModel.kt`. `validateType` (`:697`, exhaustive, **verified by spike**)
  admits it at parameter and result positions. `bound` is the first upper bound's qualified name
  when it is not `kotlin.Any`, the `boundQualified` computation `GenericClassExports.kt:52-57`
  already does (verified).
- **Classifier.** `classifyNonNullable` (`ForwardBridgeTypeClassifier.kt:76-81`, verified)
  returns `TypeParameter(declaration.simpleName, bound)` instead of `Unsupported`. `Nullable`
  wrapping at `:70-71` is untouched, so `T?` is `Nullable(TypeParameter)` for free (verified).
  The `SpecializedProtocol("generic declaration ...")` branch for a *reference* to a generic
  class (`:251-253`) is **unchanged** in v1: `Crate<Cat>` as a parameter of another class stays
  refused-named (it is a different feature, a generic instantiation at a position).
- **Planner.** The seven exhaustive sites (above, verified by spike): `wireType()` groups
  `TypeParameter` with `ObjectHandle`, its own tagged transfer, spelled `IntPtr` on the wire;
  `skipReason()` returns `ForwardPlanSkipReason.UNSUPPORTED`, never `null`: it is only reached
  from a position `T` cannot bind at (nested in a collection, a lambda, a value class), since a
  top-level `T` plans a shape and never asks; `isBridgeableComponent()` is `false` in v1 (a `T`
  inside a collection is refused named, see admission). `isReadableComponent()`
  (`ForwardPropertyPlanner.kt:722`) is the **collection-component** gate and is `false` for
  `TypeParameter`, the same as `Throwable`/`Uuid`; the property **getter** admits `T` through a
  separate, non-exhaustive gate, `isPlannable()` (`:662`), which gained a `true` arm for
  `TypeParameter`; the getter reads the boxed handle back through the same
  `NugetMarshal.FromHandle<T>` the getter has always used, the setter refused separately (below).
  `isSupportedReceiver()` is `false` (an extension over a bare `T` is not a generic-class member;
  refused as today). Own type parameters on a method keep `ForwardPlanSkipReason.GENERIC`
  (`:916`, verified). Receiver: the planner stores the fully applied Kotlin spelling of the owner,
  `io.pkg.Crate<Any>` (one erased, non-null argument per declared class type parameter, or the
  first upper bound's qualified name when the parameter is bounded), on
  `ForwardCallableInvocation.ownerType`
  (`KSClassDeclaration.forwardOwnerTypeName()`, `ForwardBridgeTypeClassifier.kt:625-636`), so the
  emitter's `ownerTypeName()` (`ForwardKotlinPlanEmitter.kt:920-921`) prints the applied type
  instead of the bare qualified name `symbol.substringBeforeLast('.')` gives every other owner
  (which does not compile for a generic class, Kotlin requires the type arguments in a
  type-argument position). A `var` property's setter is not admitted in v1: `T` only binds at the
  getter, so a `var item: T` on a generic class renders get-only with a named
  `ForwardDroppedPropertySetter` skip, the same partial-skip shape an ineligible collection setter
  already uses.
- **Interface and function type parameters, unchanged in v1.** The classifier returns
  `TypeParameter` only when the parameter's owning declaration is a `ClassKind.CLASS`
  (`ForwardBridgeTypeClassifier.kt`); a type parameter owned by an `interface` or by a top-level/
  extension function keeps the legacy named refusal it had before this ADR. Scoping to classes
  keeps issue #112's interface dispatch working: an interface's own type parameter is a different,
  undesigned feature.
- **Four more exhaustive sites, found while implementing, not in the seven-site spike.**
  `ForwardPropertyPlan.validateType`, `ForwardPropertyPlanner.wireType`,
  `ForwardPropertyPlanner.isPlannable` and `CirTranslator.factoryEntries` all had a `TypeParameter`
  arm load-bearing enough that leaving it in the catch-all `else` produced wrong output rather
  than a compile error: `factoryEntries` in particular must **not** register an ADR-094 factory
  entry for an open generic type (`Crate<T>` itself has no witness; only a closed instantiation
  like `Crate<Cat>`'s underlying wrapper does).
- **C# projection.** `csharpType()` of `TypeParameter(name)` is `name`; of
  `Nullable(TypeParameter)` is `name?`. Parameter in: a prelude
  `IntPtr xBox = NugetMarshal.Wrap<T>(x, out bool xOwned)` and a cleanup
  `if (xOwned) NugetMarshal.Dispose(xBox)` in the `finally`, which is the `Collection` /
  `Interface` shape at `ForwardCirPlanProjection.kt:830-834` (`collectionCleanup` /
  `interfaceCleanup`, verified) with a third cleanup kind. Result out: `FromHandle<T>(result)`
  over the `checkedPointerBody` at `:838-845` (verified for `ObjectHandle`). Wire type `IntPtr`
  (`:1115`). `Nullable(TypeParameter)` out: `IntPtr.Zero -> default!` (ADR-083) then
  `FromHandle<T>`; in: `Wrap<T>` already maps `null` to `IntPtr.Zero` (verified).
- **Kotlin emitter.** Parameter in: `${name}.asStableRef<Any>().get()` (or `<Bound>`), the
  `ObjectHandle` shape at `ForwardKotlinPlanEmitter.kt:1113-1114` with the type argument swapped
  (verified). Nullable in: `${name}?.asStableRef<Any>()?.get()` (`:1135-1136` shape, verified).
  Result out: `?.let { NugetHandles.retain(it) }` (the property getter's expression, verified).
  Receiver: as above.
- **Receiver spelling and `UNCHECKED_CAST`.** `handle.asStableRef<Crate<Any>>().get()`, verified
  against the built KSP output (above): compiles with no `UNCHECKED_CAST` warning, and
  Kotlin/Native erases class type arguments so `get()`'s reified check is the class check only.
  `Crate<Any>`, not `Crate<Any?>`: a `val item: T` getter's `NugetHandles.retain(it)` call needs a
  non-null `Any` (`retain(value: Any)`); a declared-nullable position (`T?`) still substitutes to
  `Any?` on its own and keeps the ADR-083 null pointer. A bounded parameter spells the bound
  (`PetCrate<T : Pet>` renders `PetCrate<Pet>`, verified above), decoding the argument with
  `asStableRef<Pet>()`.
- **Diagnostic wording.** `diagnosticTypeName()` (`ForwardDiagnostic.kt:949`, exhaustive,
  verified by spike) prints the parameter name (`T`).
- **Renderer.** `CirClass` gains `typeParameters: List<CirTypeParameter>` (the type
  `CirGenericClass` already carries, verified `CirModel.kt:303`); `renderClass` prints
  `<T>` and `where T : Bound` from it, and `virtual Dispose()` when `isOpen` (ADR-101, already in
  the generic template). `CirRenderer.kt:109` loses its `CirGenericClass` arm.
- **Translator and processor.** `NugetProcessor.kt:1105-1107` stops splitting; `translateClass`
  takes every class; `translateGenericClass` and `addGenericClassExports` (`:1580`) retire.
  ADR-066 reachability and ADR-101 base spelling are unchanged: they already see generic classes
  (`NamedParcel : Parcel<string>` renders today, verified by `GenericBaseClassTests`).
- **ABI contract.** `ForwardAbiLegacyRoute.GENERIC_CLASS` retires (`ForwardAbiLegacyRoutes.kt:43`,
  `:96`); the class's members are covered per declaration by `assertMatchesPlan`
  (`ForwardAbiContract.kt:259`). The `ForwardAbiLegacyRoutesTest` expectation for the route
  changes accordingly. **Verified by reading** that both mechanisms exist; **inferred** that no
  other hand-listed entry is needed.
- **Error channel and entry point.** ADR-032 trailing `errorOut`, ADR-090 numbering, ADR-117
  collision check: all inherited from the plan, nothing new.
- **KDoc.** Carried by the plan (`ForwardCallablePlanner.kt:937`, verified), so `Crate.describe`'s
  KDoc becomes the C# `<summary>` with no ADR-150 work.

### Admission rule (v1)

A public member of a generic class goes through the same `planOrSkip` as an ordinary class's.
The only new admissible kind is `TypeParameter`, and it is admissible **only at top level**: as
a parameter type, a return type, a constructor-parameter type, a property-getter type, or
directly under one `Nullable`. Concretely, in v1:

- `T`, `T?` at any parameter / return / ctor / getter position, any count, any combination: bound.
- Every position an ordinary class already binds (`String`, primitives, nullable primitives,
  exported classes/objects, enums, value classes, collections of non-`T`, lambdas without `T`,
  `Instant`, `Duration`, `Uuid`, interfaces): bound, because the plan does it. **Inferred**: no
  fixture exercises these on a generic class yet; the Tier 1 test in the table adds one
  (`fun label(prefix: String, count: Int): String` on `Crate`).
- `T` nested anywhere (`List<T>`, `Map<String, T>`, `(T) -> Unit`, `Flow<T>`, `T` inside a
  value class): refused with the component-named skip the plan already produces
  (`isBridgeableComponent() = false` for the kind; the lambda and flow classifiers refuse an
  unknown component the same way). Not designed here.
- `suspend fun` on a generic class: `ForwardPlanSkipReason.SUSPEND` hands it to the ADR-118
  legacy route, whose receiver spelling is the bare owner name. **Refused named in v1** by
  filtering the legacy suspend/flow hand-off on `cls.typeParameters.isNotEmpty()`; the legacy
  route would otherwise emit a non-compiling `asStableRef<Crate>()`. Same for ADR-124 `Flow`
  members and ADR-114 stored callbacks. One `if` each, at the hand-off.
- Own type parameters on the method (`fun <R> map(f: (T) -> R): R`): refused,
  `ForwardPlanSkipReason.GENERIC`, exactly as on an ordinary class today (verified `:916`).
- Multiple class type parameters (`Pairing<A, B>`): admitted; `TypeParameter(name)` is by name
  and the receiver spelling applies one erased, non-null argument per parameter
  (`Pairing<Any, Any>`). Today's `typeParams.first()` limit in the template disappears with the
  template: a concrete-typed property `val count: Int` on a generic class is now classified
  `Primitive` by the plan, not hand-mapped to `T`, likely closing the backlog
  `concrete-typed-property-generic-class-mis-surfaced.md` as a side effect. **Not verified against
  a fixture**: no `test-library` generic class declares a concrete-typed property beside a
  `T`-typed one, so this is inferred from the mechanism, not exercised by `scripts/verify.sh`.
- A generic **subclass** (`class Sub<T> : Base<T>`, ADR-101): still renders base-less, still on
  ROADMAP; unchanged by this ADR.
- A reference to a generic instantiation at a position (`fun wrap(): Crate<Cat>`): still
  `SpecializedProtocol("generic declaration")`, refused named, unchanged.

### Sub-option fork, priced

**(a) Methods only.** `TypeParameter` exists on the plan; `translateGenericClass` calls the
callable catalog for methods and stores them on `CirGenericClass.methods`; the constructor
variants and property getters keep `renderGenericClass` / `GenericClassExports.kt`. Files:
model, classifier, planner (7 sites + receiver), diagnostic, projection, emitter,
`CirClassTranslator`, `CirModel`, `CirClassRenderer`, `GenericClassExports` (methods arm),
`ForwardAbiLegacyRoutes` (the route now covers only ctor/getter/dispose): 11 processor files.
Pins at risk: only the two `GenericBaseOverloadTests` pins and `Tier1GenericBaseOverloadTest`.
**Rejected as the recommendation** per the 2026-09-16 rule: it does not land on the end state.
The generic class would be rendered by two paths, the property getter would still not be on the
ADR-062 property plan (so ADR-111's diagnostics do not fire for it), `create_<width>` x12 would
stay, and `GENERIC_CLASS` would remain a legacy ABI route.

**(b) Whole class (recommended).** Everything in (a) plus: `ForwardPropertyPlanner` (2 exhaustive
sites), `ForwardCirPropertyProjection` / `ForwardPropertyKotlinEmitter` (getter lowering of
`TypeParameter`), the constructor route in the planner, `CirClass.typeParameters`, `renderClass`
type-parameter list + constraints + `virtual Dispose`, `CirRenderer` arm removal,
`NugetProcessor` split removal, `ForwardAbiLegacyRoutes` route removal, and three files deleted
(`GenericClassExports.kt`, `renderGenericClass`, `translateGenericClass` + `CirGenericClass`).
About 17 processor files touched, 3 retired. Pins at risk: every generic-class test, because the
C# text and the export names change (`crate_create_int` becomes `crate_create`):
`GenericTests`, `GenericConstraintTests`, `GenericConstructorExceptionTests`,
`NullableGenericPropertyTests`, `GenericBaseClassTests`, `GenericBaseOverloadTests`,
`BoxesRoundTripTests`, `NestedTypesTests` (`PetCrate<T : Pet>`, `CatCrate<T : Cat>`),
`VarianceTests`, `KeywordRoutesTests`, `Tier1GenericClassPrefixTest`,
`Tier1NullableGenericPropertyTest`, `Tier1GenericBaseClassTest`, `Tier1GenericBaseOverloadTest`,
`Tier1NestedTypesTest`, `Tier1NamedSkipDiagnosticsTest`, `Tier1UnroutedPositionsTest`,
`ForwardAbiLegacyRoutesTest`, `ForwardSkippedPropertyWarningTest`, `CirOrdinaryRendererTest`
(all found by `grep -rl "Crate<\|Box<\|Kennel\|Generic\|Parcel<"`, verified 2026-09-16). Most
should stay green unchanged, since they assert behaviour (`new Box<int>(3).Value == 3`), and the
ones that assert export names or C# text (`Tier1GenericClassPrefixTest`,
`ForwardAbiLegacyRoutesTest`) are expected to need edits; **inferred** which, since nobody has
run them against (b).

The price difference is about six files and a longer red-to-green on the Tier 1 suite. What (b)
buys: one class dialect, one renderer, one ABI coverage mechanism, ADR-064 and ADR-111
diagnostics on every generic-class member, and every future plan feature (ADR-149 arities,
ADR-150 KDoc, ADR-091 defaults on the constructor) reaching generic classes without a second
implementation.

### File table (sub-option b)

| File | Change |
|---|---|
| `forward/ForwardMarshallingModel.kt` | `TypeParameter(name, bound)`; `validateType` arm (`:697`); `else` at `:741` |
| `forward/ForwardBridgeTypeClassifier.kt` | `:76-81` returns `TypeParameter` instead of `Unsupported` |
| `forward/ForwardCallablePlanner.kt` | arms at `:3367`, `:3478`, `:3745`; receiver spelling for a generic owner; `suspend`/flow hand-off refused for generic owners |
| `forward/ForwardPropertyPlanner.kt` | arms at `:329`, `:698` |
| `forward/ForwardDiagnostic.kt` | arm at `:949` |
| `forward/ForwardCsharpTypes.kt` | `csharpType()` prints the name (`:11` `else`) |
| `forward/ForwardCirPlanProjection.kt` | `Wrap<T>` prelude + owned cleanup, `FromHandle<T>` result, nullable pair |
| `forward/ForwardCirPropertyProjection.kt` | getter `FromHandle<T>` |
| `forward/ForwardKotlinPlanEmitter.kt` | `asStableRef<Any/Bound>()` in, `retain` out, applied-type receiver |
| `forward/ForwardPropertyKotlinEmitter.kt` | getter `retain` |
| `cir/CirModel.kt` | `CirClass.typeParameters`; delete `CirGenericClass` |
| `cir/CirClassTranslator.kt` | delete `translateGenericClass`; `translateClass` fills `typeParameters` |
| `cir/CirClassRenderer.kt` | `renderClass` prints `<T>`, `where`, `virtual Dispose`; delete `renderGenericClass` |
| `cir/CirRenderer.kt` | drop the `:109` arm |
| `exports/GenericClassExports.kt` | delete |
| `NugetProcessor.kt` | drop the `:1105-1107` split and `:1580` |
| `ForwardAbiLegacyRoutes.kt` | drop `GENERIC_CLASS` |
| `test-library/.../parcel/Parcel.kt` | add `fun pick(other: T): T` and `fun label(prefix: String, count: Int): String` to `Crate` |
| `IntegrationTests/GenericBaseOverloadTests.cs` | flip the two pins |
| `IntegrationTests/GenericMethodTests.cs` (new) | sample below |
| `nuget-processor/.../tier1/Tier1GenericBaseOverloadTest.kt` | `Describe(` count 2 on `Crate<T>` (its own declared `Describe(T tag)` plus `Pick`/`Label`), `crate_describe` export; the text never spells `Describe(string`, on either side of this ADR |
| `nuget-processor/.../tier1/Tier1GenericClassPrefixTest.kt`, `ForwardAbiLegacyRoutesTest.kt` | export names / route expectations |
| `LeakTests/LiveHandleTests.cs` | row below |
| `FEATURES.md`, `ROADMAP.md`, `docs/topics/` | documenter |

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
    public void Crate_Cat_PickReturnsAFreshWrapperOverTheSameKotlinObject()
    {
        using var oreo = new Cat("Oreo", 9);
        using var crate = new Crate<Cat>(oreo);
        using Cat picked = crate.Pick(oreo);
        Assert.Equal("Oreo", picked.Name);
    }

    [Fact]
    public void Crate_NonTPositions_BindThroughThePlan()
    {
        using var crate = new Crate<string>("apple");
        Assert.Equal("lot-2:apple", crate.Label("lot", 2));
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

```csharp
// ADR-147: a T parameter and a T return on a generic-class method. `Wrap<int>` mints one
// `nuget_wrap_int` box per T argument, the Kotlin export borrows it, the C# `finally` disposes
// it; the String return mints nothing; the T return of Pick mints one retain that
// `FromHandle<int>` unwraps and disposes. The constructor's T argument is the same box shape.
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

Expected ledger per iteration: `nuget_wrap_int` +1 / dispose -1 (ctor), `crate_create` +1,
`nuget_wrap_int` +1 / -1 (Describe), `nuget_wrap_int` +1 / -1 and `retain` +1 / dispose -1
(Pick), `crate_dispose` -1. Net zero.

## Consequences

- `Crate<T>` gains its declared methods; `LabelledCrate` inherits `Describe(string)` beside its
  own `Describe(int)`. C# overload resolution picks by argument type as Kotlin does.
- The generic class stops being a dialect: one translator, one renderer, one export builder, one
  ABI coverage path. `FEATURES.md` rows for generic classes point at the plan.
- Every generic-class member the rule refuses is now **named** (ADR-064) where today it is
  invisible: new WARNING lines in `nugetReportDiagnostics` for consumers with `List<T>` methods,
  no new failures.
- Export names change for the constructor (`<prefix>_create_<width>` x13 become one
  `<prefix>_create`). Shim and native library are always regenerated together (ADR-054 hash), so
  no consumer-visible break; the Tier 1 pins that spell those names change.
- A primitive constructor argument now pays a box mint + dispose, like every `T` method
  argument. Measured nowhere; the crossing microbenchmark backlog item is where that would show.
- Verified against the built KSP output and a green `scripts/verify.sh`: the
  `asStableRef<Crate<Any>>()` receiver spelling compiles with no `UNCHECKED_CAST`, and
  `GetMethods()` without flags does return the inherited `Describe(string)`, so both pins flip as
  designed.
- A `var item: T` property on a generic class now renders get-only with a named
  `ForwardDroppedPropertySetter` skip instead of a silently absent setter (closes the *silent*
  half of the pre-existing "var property on a generic class exports no setter" gap; the setter
  itself stays unbound in v1, the same as every other member the admission rule does not name).
- A `suspend fun`, a `Flow`/`StateFlow` member, a member taking a lambda parameter, or a stored
  callback declared on a generic class is refused named on **both** halves (the Kotlin export loop
  and the C# property/method loop) through one shared predicate,
  `KSFunctionDeclaration.isForwardLegacyRoute()` for methods and the equivalent
  `cls.typeParameters.isNotEmpty()` guard ahead of the Flow/lambda property arms
  (`CirClassTranslator.kt:702-705`): every legacy route bakes the bare owner name
  (`asStableRef<Crate>()`) into its export, which does not compile for a generic owner, so neither
  half emits one rather than emitting non-compiling C#.
- The constructor parameter is now named after the Kotlin property it initializes (`item`, from
  `Crate<T>(val item: T)`) instead of the legacy template's hardcoded `value`; the property getter
  gained the ADR-032 `out IntPtr error` channel the legacy generic getter did not have.
- Deferred by name: `T` nested in collections / lambdas / `Flow` / value classes; `suspend`,
  `Flow` and stored-callback members of a generic class (refused named, one `if` each);
  generic subclasses (ADR-101, ROADMAP); generic instantiations at a position
  (`Crate<Cat>` as a parameter); own method type parameters.
