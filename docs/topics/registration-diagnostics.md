# Registration diagnostics

Consuming a bound C# package (see [Consuming C# in Kotlin](reverse-overview.md)) starts with a
registration step: every C# `[ModuleInitializer]` hands its function pointers to the matching
Kotlin register export at process startup. This page is what to read when that step doesn't
behave: a missing native library, a stale half of the package, or a type that never registered. A
type or member excluded when Kotlin bindings were generated is a different problem, a Gradle build
warning rather than a registration failure; see [The bridgeable subset](bridgeable-subset.md).

## Stale build: registration contract mismatch

Before storing anything, each register export compares a `slotCount` and a `contractHash` the C#
shim passes against this native library's own compile-time values. For a struct-typed reference,
the hash covers each component's **name** as well as its type, so reordering two same-typed fields
is source-compatible for C# callers but still changes the hash and is caught here:

```
[nuget] FATAL: registration contract mismatch for {Type} ({Package}). The C# shim passed {N}
slots (contract {H1}); this native library expects {M} slots (contract {H2}). The compiled C#
shim and the native library were generated from different builds. One of them is stale. No
pointers were stored (a mismatched table would corrupt memory).
```

The C# shim ships as source (`contentFiles/cs/<tfm>/`) compiled into *your* assembly, while the
register export lives in the separately built native library. NuGet caches by version, so it's
routine for one half to lag the other. Fix: purge the cached package
(`~/.nuget/packages/<packageId>`), delete the consuming project's `obj/`/`bin/`, and rebuild both
sides.

## Native library or export missing

Each `[ModuleInitializer]` also wraps its register call, so a load failure names its cause before
it rethrows the original exception:

```
[nuget:shim] FATAL: native library 'test' not found: <DllNotFoundException.Message>
```

```
[nuget:shim] FATAL: export 'nuget_sample_enums_cat_mood_service_register' missing from 'test'.
The native library predates this shim (stale build state). <EntryPointNotFoundException.Message>
```

The first means the native library itself never loaded. The second means the library loaded but
doesn't contain this export, an older native library paired with a newer C# shim, the same
stale-build fix as the contract mismatch above. Both messages print unconditionally, not only
under `NUGET_INTEROP_TRACE`.

## Nothing registered, or one type missing

When a generated stub finds its function pointer still null, it throws with one of two messages,
distinguishing zero registrations from a partial result, since they're different bugs:

```
[nuget] Test.Text.Template bindings are not registered (TestDependency). 0 of 7 expected
registrations have fired. NOTHING has registered. Missing: <runtime>, MimeMapping.MimeUtility,
Test.Enums.CatMoodService, Test.Nullability.Nickname, Test.Nullability.NicknameBook,
Test.Nullability.LegacyNicknameBook, Test.Text.Template.

No [ModuleInitializer] in any *Registration.cs ran, so those files are not compiled into any
assembly the host has loaded. This is almost never a codegen bug. In order of likelihood:
  1. Stale build state: the consuming project's obj/project.assets.json was not re-resolved, so
     NuGet never handed contentFiles/cs/<tfm>/*Registration.cs to the compiler. Delete obj/ and
     bin/, purge the NuGet cache at ~/.nuget/packages/TestDependency, restore, rebuild.
  2. The consuming project does not reference the packed package at all.
  3. The shim files compiled, but the assembly containing them was never loaded.
Verify with: NUGET_INTEROP_TRACE=1 (each [ModuleInitializer] logs as it fires).
```

```
[nuget] Test.Text.Template bindings are not registered (TestDependency). 6 of 7 expected
registrations have fired: <runtime>, MimeMapping.MimeUtility, Test.Enums.CatMoodService,
Test.Nullability.Nickname, Test.Nullability.NicknameBook, Test.Nullability.LegacyNicknameBook.
Missing: Test.Text.Template.

Other shims DID register, so the shim source IS compiled in and the native library IS loaded.
Scope this to Test.Text.Template alone: its TemplateRegistration.cs is absent from the compiled
output, or its [ModuleInitializer] threw before reaching the register call.
Verify with: NUGET_INTEROP_TRACE=1.
```

Zero registrations point at a stale `obj/project.assets.json`, fixed the same way as the contract
mismatch above. A partial count proves the shim compiled and the native library loaded, so look
only at the missing type's own `{Type}Registration.cs` and its `[ModuleInitializer]`.

## Tracing registration

Set `NUGET_INTEROP_TRACE=1` (also `true` or `all`) to log a line per registration on both sides of
the bridge, off by default. Redirect it to a file with `NUGET_INTEROP_TRACEFILE=<path>` (opened in
append mode and flushed per line, so it survives a crashed host); otherwise it goes to stderr,
since some test runners (xunit v2 included) don't capture stdout/stderr.

```
[nuget:shim] register enter Test.Enums.CatMoodService -> nuget_sample_enums_cat_mood_service_register(7 slots) dll=sample
[nuget] registered Test.Enums.CatMoodService (7 slots) [1/7]
[nuget:shim] register ok    Test.Enums.CatMoodService
[nuget:shim] register enter <runtime> -> nuget_runtime_register(7 slots) dll=sample
[nuget] registered <runtime> (7 slots) [2/7]
[nuget:shim] register ok    <runtime>
```

`[nuget:shim]` lines come from C#, printed before and after the register P/Invoke; `[nuget]` lines
come from inside the Kotlin export. If the process dies inside a P/Invoke, the last `enter` line
with no matching `ok` names the type that killed it. `[ModuleInitializer]` order is up to the CLR,
so each Kotlin line carries its own running `[m/N]` count rather than assuming a position.

This line fires for every consumer, including one that never binds a C# package, gated by the same
two variables:

```
[nuget:interop] runtime <version> loaded from <library>
```

`<library>` is the `DllImport` library name your generated bindings use; `<version>` is read at
load time through a dedicated `nuget_runtime_version` export rather than the version the generator
expected, so a stale native library shows up here even when the C# shim compiled clean. If the
library predates that export, the line instead reads
`[nuget:interop] runtime version unavailable from <library>: <ExceptionType>: <message>`.

There is no trace of successful calls: apart from the exception line below, every diagnostic on
this page is registration-granularity, checked once per bound type at process start, not on the
bridge-call path.

## Tracing exceptions {id="tracing-exceptions"}

With the same two variables, each Kotlin exception that crosses the bridge into C# writes one line,
where it becomes a .NET exception. It names the C# member, the Kotlin class, the .NET type it
became and the mapping row that decided it, so a `KotlinException` you expected to be a
`KotlinIOException` shows up as `(row none)`:

```
[nuget:interop] error Rake: io.github.xxfast.kotlin.native.nuget.test.cat.LitterBoxJammedException -> KotlinIOException (row kotlinx.io.IOException): Oreo buried the rake
```

The variables are read when an error occurs, so a successful call pays nothing and you can switch
tracing on or off from the environment of a running test. Newlines in the message become spaces.

- A call made inside a generated helper or lambda reports the enclosing generated method, not
  your member.
- A `Throwable` that Kotlin returns rather than throws, and a `Result` failure returned through a
  `TryX` twin, also print an `error` line when tracing is on, although your call does not throw.
- A managed exception that went to Kotlin and was rethrown back to you prints the same format with
  its original .NET type.

When C# calls a Kotlin implementation of a C# interface and Kotlin throws, the reverse shim writes
the matching line with the `[nuget:shim]` prefix and no member name:

```
[nuget:shim] error <kotlinType> -> <type> (row <row>): <message>
```

Unlike the `[nuget:interop]` line, the `[nuget:shim]` line honours the variables as they were when
the process started.

## Checking for a handle leak

`NugetMarshal.LiveHandles`, generated into the same shim, reports how many Kotlin `StableRef`
handles are currently retained: originally the forward bridge only (a Kotlin object passed to C#),
and, since a reverse `suspend fun` call retains a pending-continuation handle for the duration of
the await (see [Async methods](instance-members.md#async-methods)), the reverse bridge too. It's
`internal`, so code you write in the same consuming assembly can read it: snapshot the count, run
the operation you suspect leaks, then compare. `NugetMarshal.GcCollect()` (also internal, in the
same shim, generated for every library) runs one Kotlin garbage collection round before it returns,
so call it before you re-read the count; a release can still land on a later cycle, not promptly.
The count is process-global, so isolate the check from anything else running in the process that
crosses a handle at the same time.

Both exports are part of the stable runtime interface. Only the delta is guaranteed: a balanced
sequence of crossings returns `LiveHandles` to its earlier reading. Compare readings, never assert
an absolute number, and do not rely on which handle kinds are counted.

A boxed [`enum class` sealed arm](interfaces-abstract-sealed.md#an-enum-class-arm) counts here too:
its constructor mints a `StableRef` to a Kotlin enum entry, and reading one back through a holder's
property mints a second, independent handle, so both must come back to baseline on `Dispose`.

The same holds for a `class` or `object` sealed arm: each read mints its own handle, and disposing
the arm is the only release, whether you read it through the base type or as a concrete arm.
`LeakTests/LiveHandleTests.cs` rows 1c-read
(`SealedArm_ReadThroughTheBaseDiscriminator_ReturnsToBaseline`), 1c-sibling
(`SealedSiblingArm_RepeatedReadsOfOneHolder_ReturnsToBaseline`) and 1c-arm
(`SealedArm_ConcreteArmReturn_ReturnsToBaseline`) pin it.

A value that comes back as an [abstract class or abstract sealed arm](interfaces-abstract-sealed.md#an-abstract-class-as-a-return-type)
mints one handle behind its internal subclass, released by the `Dispose` the subclass inherits (an
arm) or implements (an ordinary abstract class), including each element of a returned list.
`LeakTests/LiveHandleTests.cs`'s `AbstractBacking_BaseArmAndClassTypedReturns_ReturnToBaseline` pins
a sealed-base return, an arm return, a class return and a list of class elements. An abstract class
below another abstract class or the abstract arm mints the same single handle behind its own
internal subclass; `AbstractBacking_ClassesBelowAbstractBases_ReturnToBaseline` pins both. An
abstract `Flow` member collected through that subclass and then drained by `DisposeAsync` is pinned
by `AbstractBacking_AbstractFlowMember_CollectedThroughWrapper_ReturnsToBaseline`.
A generic abstract class returned at a closed type, including the abstract class below one, mints the
same single handle behind its internal subclass; `GenericAbstractBacking_ClosedReturnRoutes_ReturnToBaseline`
pins both type arguments, a concrete generic subclass, a two-parameter class and the class below.

A [generic sealed hierarchy](interfaces-abstract-sealed.md#generic-sealed-hierarchy) follows the same
rule: each `Outcome<T>` Kotlin hands back, including a phantom arm, an intermediate arm and an
abstract arm's internal subclass, owns one handle released by that arm's `Dispose`, and reading an
erased `T` mints a box on top. An arm you construct in C# holds its handle from the constructor and
lends it to Kotlin at a parameter. `LeakTests/LiveHandleTests.cs`'s
`GenericSealed_ReturnsAndListElements_ReturnToBaseline` pins the returns and list elements,
`GenericSealed_CSharpBuiltArmsPassedBack_ReturnToBaseline` the arms built in C#, and
`GenericSealed_ConsumerChosenErasedSlot_ReturnsToBaseline` an instantiation no Kotlin signature
names (`Hamper<Outcome<long>>`), read back through a second handle to the same Kotlin object that you
dispose as well.

A [generic value class](value-classes.md#a-generic-value-class) owns no handle of its own. A
`Sticker<Cat>` wraps the `Cat`'s handle, so you dispose the `Cat` you read out of `.Value`, and the
count returns to baseline. `LeakTests/LiveHandleTests.cs`'s
`GenericValueClass_Instantiations_ReturnToBaseline` pins the closed, own-`T` and nullable shapes.
`GenericValueClass_BoundedOwnTypeParameter_ReturnsToBaseline` pins an own `T` with a `Comparable`, `Enum` or intersection bound. `GenericValueClassMembers_ReceiverParameterAndResultBoxes_ReturnToBaseline` pins the receiver, parameter and result boxes of a member, `GenericValueClassMembers_FaultPaths_ReturnToBaseline` the throwing paths, and `GenericValueClassSuspendMembers_ReturnToBaseline` a `suspend` member. `GenericValueClass_ErasedPositions_ReturnToBaseline`, `GenericValueClass_ErasedPositionInitThrows_ReturnsToBaseline` and `GenericValueClass_AsyncErasedPositions_ReturnToBaseline` pin a generic value class at an erased position: a `Box`, a lambda, a flow element, an `init` that throws on the re-wrap, and the awaited and `Flow` parameter shapes. `GenericValueClass_NullableAndCollections_ReturnToBaseline` and `GenericValueClass_NullableAndCollectionInitThrows_ReturnsToBaseline` pin the box handle for `Sticker<T>?` and `Nick?`, per-element collections, and an `init` that throws as a box is minted or a set element is re-wrapped.

A [generic value class over a collection of its own `T`](value-classes.md#a-generic-value-class-over-a-collection) (`Bundle<T>(List<T>)`) mints a list handle per crossing and a box per erased position, all released, including when `init` refuses the re-wrap. `GenericValueClassOverCollection_OrdinaryPositions_ReturnToBaseline`, `GenericValueClassOverCollection_Members_ReturnToBaseline`, `GenericValueClassOverCollection_ErasedPositions_ReturnToBaseline` and `GenericValueClassOverCollection_AsyncPositions_ReturnToBaseline` pin them.

A [value class over `ByteArray`, a collection or an interface](value-classes.md#over-instant-bytearray-collection-interface)
crosses on that underlying's own wire, so it releases what the bare underlying would: the bytes and
list copies once read, a Kotlin-backed `IRumbler` when you dispose it, and a C#-implemented one once
Kotlin drops it. `LeakTests/LiveHandleTests.cs`'s `ValueClassOverByteArray_ParamReturnAndElement_ReturnsToBaseline`,
`ValueClassOverCollection_ParamReturnAndElement_ReturnsToBaseline` and
`ValueClassOverInterface_KotlinBackedAndCSharpImplemented_ReturnsToBaseline` pin the crossings,
`ValueClassMembers_HandleWiredReceivers_ReturnToBaseline` the receiver of a member (released even
when the call throws), and `NarrowStdlibComponents_ListSetAndMap_ReturnToBaseline` a `List`, `Set` or
`Map` of `Instant`, `Duration` and `Uuid`.

The `suspend` and `Flow` routes carry `Instant`, `Duration`, `Uuid`, `ByteArray`, a collection, a plain
interface and a value class over each as parameters, results and receivers
([details](coroutines-and-flow.md#async-wire-types)). A minted handle is released when the extern
returns, so completion, a fault and cancellation all return to baseline.
`LeakTests/LiveHandleTests.cs`'s `AsyncWireUnderlyings_HandleBacked_Completion_ReturnsToBaseline`,
`AsyncWireUnderlyings_HandleBacked_Fault_ReturnsToBaseline` and
`AsyncWireUnderlyings_HandleBacked_Cancellation_ReturnsToBaseline` pin the three. The class owner
mints its coroutine scope on its first `suspend` call, so the rows warm it before taking the baseline.

The [members of a generic arm](interfaces-abstract-sealed.md#generic-sealed-arm-members) add no new
handle kind either. Each element a flow collects from `Dish.Hot<string>.Portions` mints one owned
crossing, and the count returns to baseline once you `DisposeAsync` the arm. A stored-callback or
interface-listener subscription holds a Kotlin bridge that its `Dispose()` releases, and a call after
that reaches no listener. `LeakTests/LiveHandleTests.cs`'s `GenericSealedArm_FlowMembers_ReturnToBaseline`
covers the flow members and a phantom arm's `StateFlow` and `suspend` member,
`GenericSealedArm_PerCallLambda_ReturnsToBaseline` the per-call lambda,
`GenericSealedArm_StoredCallbackPair_SubscribeFireUnsubscribe_ReturnsToBaseline` and
`GenericSealedArm_InterfaceBridgePair_SubscribeFireUnsubscribe_ReturnsToBaseline` the two pairs.

A [closed generic class at a member position](generics.md#returning-an-instantiated-generic-class)
(`Box<String>`, `Box<Box<Int>>`) follows the same rule: every `Box<T>` Kotlin hands back, from a
property read, a member, companion, object or top-level return, a nullable return, a list element,
a `suspend` result, or a `Flow` or `StateFlow` element, owns one handle released by its `Dispose`,
and `Box<Box<int>>.Value` mints a second wrapper you dispose separately. A `Box<T>` you pass (a
parameter, a `var` setter, a constructor argument, an extension receiver) is borrowed and mints
nothing. `LeakTests/LiveHandleTests.cs`'s `GenericInstance_ReturnedBoxes_ReturnToBaseline`,
`GenericInstance_PassedBoxes_ReturnToBaseline` and `GenericInstance_SuspendAndFlow_ReturnToBaseline`
pin the three groups. A `T` you assign to a generic class's `var` is boxed for the call and the box released after it: `GenericInstance_SetterBoxes_ReturnToBaseline`.

The flows of a [generic class](coroutines-and-flow.md#flow-type-parameter) follow it: each element
of `Brook<T>.Updates`, `Level` and `Babble()` that Kotlin hands back is one owned crossing, and the
count returns to baseline once the flow is drained and the class disposed.
`LeakTests/LiveHandleTests.cs`'s `GenericClass_FlowElements_ReturnToBaseline` pins it.

A [`Flow<T>` parameter and a `suspend (T) -> T` lambda](coroutines-and-flow.md#flow-and-suspend-lambda-type-parameter)
mint one owned crossing per element read and per lambda result, and release it when Kotlin has
read it. A source Kotlin stops early, or cancels half-way, is disposed like any other. The count
returns to baseline after the call; `MemberGeneric_FlowParameterAndSuspendResult_ReturnToBaseline` pins it.

A [sealed interface whose arms extend a class](interfaces-abstract-sealed.md#sealed-interface-over-arms)
follows the same rule: an `I<Name>` Kotlin hands back is the arm wrapper, owns one handle, and its
`Dispose` is the only release, for each element of a returned list too. A C#-built arm passed at a
parameter lends its handle and mints nothing. `LeakTests/LiveHandleTests.cs` rows 1c-iface
(`SealedInterfaceOverDeclaredArms_Returns_ReturnToBaseline`), 1c-iface-in
(`SealedInterfaceOverDeclaredArms_Parameters_ReturnToBaseline`) and 9-iface
(`SealedInterfaceOverDeclaredArms_Suspend_TightLoop_ReturnsToBaseline`, a `suspend` return that can
complete before the call that started it returns) pin the returns, the parameters and the `suspend`
route.

A class-typed [enum member property](enums.md) getter counts here too: every read mints a fresh
owned wrapper the caller must dispose, the same as any other class-typed property getter.
`LeakTests/LiveHandleTests.cs` row 1h,
`EnumMemberClassTypedGetter_UsingDispose_ReturnsToBaseline`, measures repeated reads and disposes
of one such member returning to baseline. Row 1i,
`EnumMemberFunctionClassTypedReturn_UsingDispose_ReturnsToBaseline`, pins the same for a class-typed
enum member function return.

An [inner class](classes-and-objects.md#inner-classes) constructor borrows its outer handle and mints
one of its own. Row 1j, `InnerOfInnerConstructor_IntermediateOuterDisposedFirst_ReturnsToBaseline`,
pins that at depth 2 with the intermediate outer disposed first, and row 1k,
`InnerUnderSealedOwnerConstructor_UsingDispose_ReturnsToBaseline`, pins it with a sealed base and a
sealed arm as the outer.

A [generic nested class](classes-and-objects.md#nested-generic-owner) mints through the same
boxed-`T` constructor as any generic class. Row 1l,
`GenericNestedConstructor_UsingDispose_ReturnsToBaseline`, pins `Tote.Purse<T>`, row 1m,
`HolderNestedConstructor_UsingDispose_ReturnsToBaseline`, a class on a generic owner's holder
(`Teapot.Lid`) read back through `teapot.LidAt(...)`, row 1n,
`GenericInnerConstructor_OuterDisposedFirst_ReturnsToBaseline`, a generic inner class with its outer
disposed first, and row 1o, `InnerOfGenericOwnerConstructor_OuterDisposedFirst_ReturnsToBaseline`,
the same for an inner class flattened onto a generic owner's holder.

An [interface method overload](interfaces-abstract-sealed.md#method-overloads-on-an-interface) adds
no new handle kind either: every numbered dispatch export or bridge slot a call reaches still
borrows the one handle its route already mints (the returned interface's own handle, or the
transfer handle a C# implementation mints once per crossing), never one of its own per overload.

An [interface extending another interface](interfaces-abstract-sealed.md#interface-super-interfaces)
adds no new handle kind either: a derived interface returned from Kotlin still mints exactly one
handle behind its backing wrapper, no matter how many inherited members a caller reaches through
it or which ancestor-typed reference it's read through; a C# implementation of the deepest
interface in a hierarchy still mints exactly one transfer handle per parameter crossing, reused for
every inherited member the call reaches on it, not one per ancestor interface.

Writing an [interface `var` property](interfaces-abstract-sealed.md#an-interface-s-own-var-property)
adds no new handle kind either: a setter only ever borrows the receiver it's called on and, for a
handle-typed value, the argument's own handle; it consumes both and returns neither. This holds for
the explicit-interface-implementation shape too, so a class whose public property stays get-only and
whose setter is only reachable by casting to the interface still returns to baseline once every
handle the call borrowed is disposed, the same as any other setter.

A top-level function returning a [nullable scalar](primitives-and-strings.md#nullable-values) with
a `List`, `Map`, `Set`, `ByteArray` or Kotlin interface parameter adds no new handle kind either: it
now takes the same single-call route as a member function with the same signature, so those
parameter handles release on every exit path, including a throw, the same as that route already
does. A bound C# interface parameter on the same route still transfers its handle to Kotlin with no
release on the C# side, unchanged from the member route. `LeakTests/LiveHandleTests.cs`'s
`TwoCallCollectionParam_ListArgument_ReturnsToBaseline` and
`TwoCallCollectionParam_ThrowingListArgument_ReturnsToBaseline` cover the null-return, non-null-return
and throwing cases for a `List` parameter.

An [interface as a `List`/`Set`/`Map` component](collections.md#interfaces-as-collection-components)
adds no new handle kind either: the returned collection's own handle is released on every path, each
Kotlin-backed element is an owned wrapper you dispose, and a C#-implemented element you pass in is
never disposed for you, even when a later element's factory throws. `LeakTests/LiveHandleTests.cs`'s
`InterfaceListReturn_ElementsDisposed_ReturnsToBaseline`,
`InterfaceListParameter_MixedElements_ReturnsToBaseline`,
`InterfaceListEcho_CSharpElementResolved_ReturnsToBaseline` and
`InterfaceListReturn_ThrowingElementFactory_DoesNotDisposeCSharpElement` cover them.

A `List` of Kotlin class elements releases an element's handle even when materializing that element
fails before any wrapper exists, and a wrapper that was constructed first still releases once however
often you dispose it. `LeakTests/LiveHandleTests.cs`'s
`ListReturn_ThrowingElementFactory_ReleasesTheListHandle` and
`ListReturn_ConstructThenThrowFactory_SavedWrapperDisposalReturnsToBaseline` cover them.

A `Flow` item that cannot be materialized releases its handle as the enumeration faults.
`LeakTests/LiveHandleTests.cs` row 14d, `FlowItemMaterialisationFailure_ReleasesTheItemHandle`,
swaps in a factory that throws without disposing and returns to baseline.

A top-level [`suspend fun` returning `StateFlow<T>`](coroutines-and-flow.md#suspend-fun-returning-stateflow-t)
adds no new handle kind either: the awaited holder owns the flow's own handle and releases it on
`Dispose`, each `.Value` and each collected emission mints one element handle the caller releases,
and a collection's job handle is freed by its enumerator. There is no owning object, so no scope
handle is minted for it. `LeakTests/LiveHandleTests.cs`'s
`TopLevelSuspendStateFlow_AwaitReadCollectDispose_ReturnsToBaseline` covers the await, read,
collect and dispose cycle for an `Int` and a class element.

A [`suspend fun <T>`](coroutines-and-flow.md#suspend-fun-generic) boxes each `T` argument for the
call. The box is released once the native call returns, before the `Task` completes, so passing a
builtin, a string or a wrapper leaves no handle behind, a pre-canceled token included. The wrapper
you pass is borrowed. `LeakTests/LiveHandleTests.cs`'s
`TopLevelSuspendGeneric_BoxedTypeParameter_ReturnsToBaseline` loops over an `int`, a `string` and a
wrapper plus one pre-canceled call.

A [generic class's `suspend` members](coroutines-and-flow.md#suspend-generic-owner) box the owner `T`
argument and release the box when the native call returns, a trampolined `RateAsync` and a pre-canceled
call included. `LeakTests/LiveHandleTests.cs`'s `GenericOwner_SuspendMembers_ReturnToBaseline` loops
over a generic class, a bounded and a multi-bound one, and a generic sealed arm.

A nullable [`StateFlow<T?>` or `StateFlow<T>?` from a `suspend fun`](coroutines-and-flow.md#suspend-stateflow-nullable)
mints nothing for a null: a null `.Value` or null emission frees nothing, and a null holder owns no
handle. `LeakTests/LiveHandleTests.cs`'s `SuspendStateFlowNullableElementAndMember_ReturnsToBaseline`
covers both shapes.
A [`KotlinStateFlowObservable<T>`](coroutines-and-flow.md#data-binding) from `AsNotifying()` runs one
collection that only `Dispose()` ends: the job cell, the rooted callbacks and the enumerator stay
live until then, and `Completion` finishing means they are released. `LeakTests/LiveHandleTests.cs`
row 6g-notifying, `StateFlowNotifyingAdapter_UpdatesThenDispose_ReturnsToBaseline`, starts an
adapter, takes two updates, disposes it and returns to baseline.

A class [`suspend fun` returning `MutableStateFlow<T>`](coroutines-and-flow.md#suspend-fun-returning-stateflow-t)
awaits to a holder that owns the flow's handle and frees it on `Dispose`. Its write borrows the
value's handle and mints nothing; each `.Value` read mints one element handle the caller releases.
`LeakTests/LiveHandleTests.cs` row 8f-suspend, `SuspendMutableStateFlow_AwaitWriteReadDispose_ReturnsToBaseline`,
covers an `Int` and a class element. A nullable `MutableStateFlow` element or member write adds no
handle, so it has no row of its own.

A `MutableStateFlow` whose element is a generic class instantiation (`Box<string>`) or a generic
sealed type borrows the written box too, on set and `CompareAndSet`, so only your `Dispose` releases
it. `MutableStateFlowGenericElement_Writes_ReturnToBaseline` writes through the property, held,
awaited and nullable routes and a sealed arm, and returns to baseline. The `Update` family has no
row: its retry loop reads each `.Value` into a wrapper it never disposes, which the GC releases.

An absent [nullable `Flow<T>?` member](coroutines-and-flow.md#flow-nullable-member) mints nothing:
the presence check on a property or method, and a `suspend` completion with no flow, leave no
handle behind, and a present one is the ordinary holder you dispose.
`LeakTests/LiveHandleTests.cs`'s `NullableFlowMember_AbsentAndPresent_ReturnToBaseline` covers the
property, method and `suspend` (class and top-level) shapes.
An awaited [`StateFlow<List<T>>`](coroutines-and-flow.md#suspend-stateflow-collection-element)
reads each `.Value` and each emission through a per-member pair keyed on the awaited flow. Every
read mints a collection handle (plus one boxed underlying per element for a value class such as
`CatId`), and the holder's own handle is freed by `Dispose`. `LeakTests/LiveHandleTests.cs` row
8d-suspend, `SuspendStateFlowCollectionElement_AwaitReadEnumerateDispose_ReturnsToBaseline`, covers
it.

A collection parameter on a held `MutableStateFlow` method or a nullable `StateFlow<T>?` method
wraps its acquire, and the `_has_value` probe, in a call-scoped collection handle. Row 8f-collection
covers both: `CollectionParameterOnHeldAcquireAndHasValueProbe_ReturnsToBaseline`.

A [`default(V)` of a `String`- or class-backed value class](value-classes.md) throws
`ArgumentException` while the call's arguments are evaluated, after earlier arguments have built
their handles. `LeakTests/LiveHandleTests.cs` row 8d-valueclass-default,
`DefaultValueClass_RefusedAfterAHandleWasBuilt_ReturnsToBaseline`, refuses one after a list
argument was built, and one inside a half-filled `List<CatId>`; both return to baseline.

A `null` for a non-null [`string`](primitives-and-strings.md#null-for-a-non-null-string) throws
`ArgumentNullException` the same way: while the arguments are evaluated, after earlier arguments
have built their handles. `LeakTests/LiveHandleTests.cs` row 8d-nullstring,
`NullString_RefusedAfterAHandleWasBuilt_ReturnsToBaseline`, refuses one after a `List<string>`
argument was built, and one inside a half-filled `List<string>`; both return to baseline.

For a [cancellation-token-taking async call](instance-members.md#async-cancellation), this count
only proves the pending-continuation and `Task` handles came back to baseline: the bridge-owned
`CancellationTokenSource` is a plain .NET `GCHandle`, not one of the Kotlin `StableRef`s
`nuget_live_handles` counts, so it can't tell you whether that handle itself was released.

A `null` argument at a [generic class's bare `T` position](generics.md#nullable-type-arguments)
adds no new handle kind either: `new Crate<string?>(null)` mints nothing on the way in (`Wrap<T>`
reports the box as unowned for null), so the count never moves for the argument itself.

A [concrete-typed property on a generic class](generics.md#nullable-properties) adds no new handle
kind either: reading a handle-returning property such as `Slot<T>.Keeper` mints exactly one
wrapper handle, released by disposing it, the same as any other exported-class property getter.
`LeakTests/LiveHandleTests.cs`'s `GenericClassConcreteProperty_KeeperRead_ReturnsToBaseline` covers
the read-and-dispose cycle.

A [dependency-module type reached only through a top-level `suspend fun`](coroutines-and-flow.md#suspend-fun-returning-a-dependency-type)
adds no new handle kind either: the returned handle and any handle-typed parameter release the same
way any other suspend-route handle does, and a value class's box comes back to baseline the same
way an ordinary value-class unbox does. `LeakTests/LiveHandleTests.cs`'s
`TopLevelSuspendDependencyClass_ReturnAndParameter_ReturnsToBaseline`,
`SuspendValueClassReturn_UnboxedOnce_ReturnsToBaseline`, and
`SuspendDependencyClassAndValueClass_TightLoop_ReturnsToBaseline` cover the class return-and-parameter
round trip, the value-class unbox-exactly-once path, and the same pair under the completion race a
suspend call with no suspension point can hit.

A nullable class or sealed handle parameter on a legacy route borrows the caller's handle, or crosses
as no handle for `null`, so nothing new is minted. `LeakTests/LiveHandleTests.cs`'s
`NullableHandleParameter_SuspendNullAndValue_ReturnsToBaseline` covers a `suspend` call with `null`
and with a value.

An [enum element read through `StateFlow`, `Flow` or a generic class](coroutines-and-flow.md#enum-elements)
mints one handle per `.Value` read and per emission, released as the ordinal is read, so the count
returns to baseline with nothing for you to dispose. `LeakTests/LiveHandleTests.cs`'s
`EnumStateFlowElement_RepeatedValueReads_ReturnToBaseline`,
`EnumFlowElement_Emissions_ReturnToBaseline` and
`ValueClassFlowElement_ValueReadsAndEmissions_ReturnToBaseline` cover the enum `.Value` loop, the enum
emission loop and the value-class element on the same two routes. The enum `.Value` loop also
writes a settable `MutableStateFlow<E>` once and a `MutableStateFlow<E?>` through a property, a
`CompareAndSet` to `null` and a held reader; a write crosses as an ordinal (behind a has-value
flag when nullable) and mints nothing.
The value-class loop also writes a settable `MutableStateFlow<V>` through the property, a
`CompareAndSet` and a held reader. A `String` underlying mints nothing on the write, so the only
handles are the read boxes and the held reader's flow, which you dispose.

An [enum written into a generic slot](generics.md) (`new Crate<Mood>(Mood.Grumpy)`,
`Helpers.Identity(Mood.Sleepy)`) mints one handle over the Kotlin entry per write and releases it
when the call returns, so nothing is left for you to dispose. An out-of-range value (`(Mood)99`)
throws before any handle is minted. `LeakTests/LiveHandleTests.cs`'s
`EnumErasedWrite_GenericClassAndFunction_ReturnsToBaseline` and
`EnumErasedWrite_OutOfRangeOrdinalThrows_ReturnsToBaseline` cover both.

A [generic class from a dependency module](generics.md#limitations) adds no new handle kind: its
constructor and `Value` read follow the module-local generic route.
`LeakTests/LiveHandleTests.cs`'s
`DependencyGenericOwner_PrimitiveAndStringPayloadsReturnToBaseline` constructs, reads and disposes
`Parcel<int>` and `Parcel<string>` and checks the count returns to baseline.

A [builtin as `T` on a generic function](generics.md#generic-functions) mints two boxes per call:
the argument's, which the call releases, and the result's, which the read releases as it unwraps
it, so there is nothing for you to dispose. A `T` that fails the bound's check still releases the
argument box. `LeakTests/LiveHandleTests.cs`'s
`BuiltinGenericFunction_BoxedPrimitiveAndString_ReturnsToBaseline` and
`BuiltinGenericFunction_BoundCastFails_ReturnsToBaseline` cover the success and failure paths.

A [generic method on a class](generics.md#generic-methods) or a top-level `fun <T>` mints the same boxes: one per builtin
argument, which the call releases, and one per `T` result, which the read releases as it unwraps
it. A member that throws, or a `T` that fails its bound's check, after the argument box crossed
still releases the box. `LeakTests/LiveHandleTests.cs`'s
`MemberGenericMethod_BoxedTypeParameter_ReturnsToBaseline`,
`MemberGenericMethod_ThrowingMember_ReturnsToBaseline` and
`MemberGenericMethod_BoundCastFails_ReturnsToBaseline` cover the success, throwing and bound-failure
paths, and `TopLevelGenericMethod_BoxedTypeParameter_ReturnsToBaseline` covers the top-level success
path, also driving `Shuffle`. `TopLevelGenericMethod_BoxedBox_ReturnsToBaseline` covers `WrapInBox`,
`UnwrapBox` and `Identity<string>`: a returned `Box<T>` is yours to dispose, a passed one is borrowed. `MemberGeneric_CollectionElements_ReturnToBaseline` covers a `T` as a list
element: passed wrappers stay borrowed, returned ones are disposed, and `Pad` round-trips a null element.
`MemberGeneric_BoxedTypeParameter_ReturnsToBaseline` covers a `T` inside a generic class: a returned
`Basket<T>` is yours to dispose, a passed one is borrowed, and `Basket(Basket(1))` disposes both.
`MemberGeneric_ResultListOfT_ReturnsToBaseline` covers a `Result<List<T>>` payload: wrapper elements
are disposed and a failed result's error handle is released.
`OpenSlot_NestedThroughGetter_ReturnsToBaseline` covers a nested instantiation read through a getter:
both baskets are yours to dispose and the count returns to baseline.

A `T` in a non-suspend lambda mints one box per payload, consumed by the C# read (a wrapper payload is
yours to dispose), and one owned box per result, which Kotlin releases after it reads it. A wrapper
the lambda returns is retained first, so the lambda's own handle still ends with you.
`MemberGeneric_LambdaPayload_ReturnToBaseline` and `MemberGeneric_LambdaResult_ReturnToBaseline`
cover builtin, wrapper and `null` payloads, and a borrowed wrapper, a fresh one dropped inside the
lambda and a `null` as results. All return to baseline.

A [bound with no closed Kotlin spelling](generics.md#an-enum-bound) (`T : Enum<T>`, an invariant
`T : Node<T>`) mints the handles of any other generic crossing: an enum argument is boxed per
write and released by the call, a `T` result is released as it is read (an enum) or disposed by you
(a wrapper), and a null `T?` mints nothing. A Kotlin throw inside the call, and a foreign C# enum the
constraint admits but the call refuses, strand nothing. `LeakTests/LiveHandleTests.cs`'s
`EnumSelfBound_ClassAndFunctionRoute_ReturnsToBaseline`,
`EnumSelfBound_ThrowPaths_ReturnToBaseline` and `InvariantRecursiveBound_ReturnsToBaseline` cover
the success, throw and wrapper paths. `EnumSelfBound_CollectionElements_ReturnToBaseline` covers the
same `T` as a list, set or map element and in a generic class: a returned element's box is released
by the read, and a null element mints nothing.

A [sealed base's own async members](interfaces-abstract-sealed.md#sealed-method-suspend-generated-c)
add no new handle kind either: the scope handle moves from the arm to the base, and the count returns
to baseline once the base-typed reference is disposed. `LeakTests/LiveHandleTests.cs` row 9l covers
base-typed `suspend`, `Flow` and `StateFlow` calls, and row 9l-race repeats a call with no
suspension point (`Loaf.area`) 5000 times in a tight loop.

A `suspend fun` returning a [nullable collection or a collection of a sealed base](coroutines-and-flow.md#suspend-fun-returning-a-collection)
adds no new handle kind: the returned list handle is released as any collection return is, and the
null case never reads. `LeakTests/LiveHandleTests.cs` row 9p
(`Suspend_ReturningAListOfTheSealedBase_ReturnsToBaseline`) and row 9q
(`Suspend_ReturningANullableCollection_ReturnsToBaseline`) cover them.

A nullable `List`/`Set`/`Map` [parameter on a `suspend`, `Flow` or `StateFlow` member](coroutines-and-flow.md#parameters-on-flow-stateflow-and-suspend-members)
mints one wire container per non-null argument and disposes it in the call's `finally`. A `null`
argument is `IntPtr.Zero`, mints nothing, and the dispose skips it. `LeakTests/LiveHandleTests.cs`
row 9r (`LegacyRoute_NullableCollectionParameter_ReturnsToBaseline`) covers null and non-null
arguments on a `suspend` and a `Flow` member.

A [function returning a lambda](lambdas-and-callbacks.md#a-top-level-function-that-returns-a-lambda)
adds one handle, owned by the returned delegate, which the GC releases when it collects the delegate;
there is nothing to `Dispose`. A parameter the lambda captures is not released when the call returns:
a C#-implemented interface stays pinned until the delegate is collected and Kotlin's cleaner runs, so
a delegate you keep keeps it alive. The `LambdaReturn_*` rows of `LeakTests/LiveHandleTests.cs`
cover a captured C#-implemented pet, a captured Kotlin `Cat`, value-only parameters, Kotlin throwing
before it makes the lambda, and a throwing `IPet` factory on the returned lambda, and all return to
baseline.

A Kotlin interface handed to a per-call lambda parameter adds one handle per invocation, released
when the callback disposes the payload. `LeakTests/LiveHandleTests.cs` row 13-iface,
`CallbackMemberInterfacePayload_EachInvocation_ReturnsToBaseline`, runs 5000 invocations and
returns to baseline.

A [late callback invocation](lambdas-and-callbacks.md#exceptions-from-a-callback) that finds its
subscription disposed releases any handle-passed argument it was given before it drops the call or
reports `ObjectDisposedException`. `LeakTests/LiveHandleTests.cs` row 14e,
`DroppedLateCallbackArgument_ReturnsToBaseline`, runs fifty dropped `void` calls carrying a
`String`, and row 14f, `ReportedLateCallbackArgument_ReturnsToBaseline`, runs the value-returning
shape; both return to baseline.

A [listener `val`](lambdas-and-callbacks.md#a-listener-val) read by Kotlin adds no handle you
dispose: a `String` it returns is released by Kotlin after each read.
`LeakTests/LiveHandleTests.cs` row 8j-val,
`InterfaceBridge_ListenerStringProperty_ReturnsToBaseline`, reads one repeatedly and returns to
baseline.

A `var` on a C# class implementing a Kotlin interface, written by Kotlin, adds no handle you
dispose: the `String` Kotlin passes to the setter is released after C# reads it.
`LeakTests/LiveHandleTests.cs` row 8j-var, `InterfaceBridge_SetterSlot_ReturnsToBaseline`, writes
and reads back a `String` and an `Int` repeatedly, and row 8j-listener-var,
`InterfaceBridge_ListenerSetterSlot_ReturnsToBaseline`, does the same through an `add`/`remove`
listener; both return to baseline.

A C# class implementing a member-less Kotlin interface crosses at a plain parameter through a
bridge with no callback slots, and comes back as the same instance. `LeakTests/LiveHandleTests.cs`
row 8j-marker, `InterfaceBridge_CSharpMarkerInterface_ReturnsToBaseline`, passes one repeatedly and
returns to baseline.

A C# class implementing a generic Kotlin interface (`IKeepsake<string>`) adds no handle you dispose:
the `T` Kotlin reads or writes through a slot is released after the call, and a Kotlin-backed value
you hold stays yours. `LeakTests/LiveHandleTests.cs` row 8j-generic,
`InterfaceBridge_CSharpGenericKeepsake_ReturnsToBaseline`, passes closed, open, bounded and
receiver positions repeatedly, and row 8j-generic-cat, `InterfaceBridge_GenericSlotCatPayload_ReturnsToBaseline`,
carries a `Cat` in the `T` slot; both return to baseline. `LeakTests/CollectabilityTests.cs`
`CSharpGenericKeepsake_ReleasedAfterKotlinGc` holds a `WeakReference` to the implementation and
asserts Kotlin's garbage collection releases it.

A generic interface at an output or erased position adds no new obligation: a Kotlin-backed `Keepsake<T>` you receive is yours to dispose, and a C# implementation Kotlin returns is your own object, with the transfer handle released. `LeakTests/LiveHandleTests.cs`'s `GenericInterfaceOutput_ReturnedKeepsakes_ReturnToBaseline` and `GenericInterfaceOutput_ErasedInputs_ReturnToBaseline` return to baseline, and `LeakTests/CollectabilityTests.cs`'s `CSharpGenericKeepsake_StoredThenDropped_ReleasedAfterKotlinGc` asserts a stored C# implementation is released after Kotlin's garbage collection once you overwrite and drop it.

A C# class implementing a Kotlin interface with `suspend` and `Flow` members adds no handle you
dispose: each awaited result, each flow element and each hot-flow handle Kotlin reads is released
by Kotlin, and no per-call state outlives the call. `LeakTests/LiveHandleTests.cs` rows 19a to 19d
return to baseline:

- Row 19a, `CSharpAsyncInterface_Completion_ReturnsToBaseline`: every result wire, a `Unit` member
  awaited twice, a flow made with `KotlinFlow<T>.From` collected to the end, and a Kotlin-made flow
  passed back through the C# implementation.
- Row 19b, `CSharpAsyncInterface_Fault_ReturnsToBaseline`: a C# fault caught in Kotlin, and the same
  fault left uncaught.
- Row 19c, `CSharpAsyncInterface_Cancellation_ReturnsToBaseline`: a Kotlin timeout cancelling a
  pending C# call, and the C# caller's token cancelling the outer call.
- Row 19d, `CSharpHotFlowInterface_ReturnsToBaseline`: `KotlinMutableStateFlow<T>.Create` and
  `KotlinMutableSharedFlow<T>.Create` flows read, written, collected and emitted from both sides, and
  a `suspend` member returning a `Flow`.

`LeakTests/CollectabilityTests.cs` `CSharpAsyncFeeder_ReleasedAfterKotlinGc` holds a `WeakReference`
to the implementation and asserts Kotlin's garbage collection releases it once Kotlin drops its bridge.

A C# class implementing a Kotlin interface whose members take or return classes, collections,
lambdas and flows also adds no handle you dispose: a class a slot returns is Kotlin's, a class Kotlin
passes is borrowed, and a lambda or flow a slot returns is released by Kotlin. `LeakTests/LiveHandleTests.cs`
rows 20a to 20g return to baseline:

- Row 20a, `CSharpInterfaceSlotVocabulary_Completion_ReturnsToBaseline`: a handle returned and passed,
  a nullable and a generic handle, collections, a Kotlin lambda in, a C# lambda out, a suspend lambda,
  a generic interface result, and `Uuid` and `ByteArray` values.
- Row 20b, `CSharpInterfaceSlotVocabulary_Fault_ReturnsToBaseline`: a C# fault from a handle slot, a
  collection slot, a passed-handle slot and a generic-interface slot.
- Row 20c, `CSharpInterfaceFlowSlots_Completion_ReturnsToBaseline`: flows of a handle, an enum, a
  nullable string and a value class, a `var` flow replaced by Kotlin, nullable flows and a `suspend`
  member returning a hot flow.
- Row 20d, `CSharpInterfaceFlowSlots_Fault_ReturnsToBaseline`: a handle flow faulting after its first
  element.
- Row 20e, `CreatedHotFlows_DisposeReleasesTheirScope_WithoutAGc`: `Dispose` on a `Create`d hot flow
  releases its scope with no garbage collection.
- Row 20f, `ClassMemberReturnedLambda_DroppedDelegate_ReleasedByTheGc`: a delegate a class member
  returns is released by the GC once dropped.
- Row 20g, `CSharpInterfaceSlotVocabularyAmendment_Completion_ReturnsToBaseline`: a Kotlin-backed
  member function's hot flows handed back, a `var` flow set to a C#-made flow, a `var` flow slot and
  a `Flow<Throwable>`.

`LeakTests/CollectabilityTests.cs` `CSharpSitter_ReleasedAfterKotlinGc` asserts Kotlin's garbage
collection releases such an implementation once Kotlin drops its bridge.

A Kotlin `Any` crossing as `object?` mints a Kotlin box for each C# value that goes in, and a
wrapper or `KotlinObject` for each handle-backed value that comes out. `LeakTests/LiveHandleTests.cs`
rows 6d2 and 21a to 21c return to baseline:

- Row 6d2, `ErasedTypeParameterInterface_Argument_ReturnsToBaseline`: a C# implementation of an
  interface whose member has its own `<R>`, erased to `object?`, released with the call.
- Row 21a, `AnyWire_HandleBackedValues_ReturnToBaseline`: an exported class wrapper passed in and
  read back, a C#-implemented interface object, a list holding a handle, a class owner's `var` and
  member, a Kotlin-backed interface, and a star-projected generic class result.
- Row 21b, `AnyWire_StarProjectedFlows_ReturnToBaseline`: a `Flow<*>` whose elements are handles,
  read from Kotlin and handed from C# to Kotlin.
- Row 21c, `AnyWire_GenericValueClass_ReturnsToBaseline`: a generic value class on the `Any` wire in
  both directions, and a Kotlin-made one around a handle.

An exception thrown from Kotlin allocates one error handle per call, and the
[exception mapping](exceptions.md#catching-a-specific-exception-type) reads a mapped type from it
for the exception and each cause. `LeakTests/LiveHandleTests.cs`'s
`KotlinxIoIOException_Throws_ReturnsToBaseline` throws a `kotlinx.io.IOException` repeatedly and
returns to baseline, so a mapped throw releases its error handle.

A [`Result<T>` member's `TryX` twin](exceptions.md#result-try) mints the same one error handle per
failing call, and hands the exception back instead of throwing it. `LeakTests/LiveHandleTests.cs`'s
`ResultTry_ModelledFailureAndSuccess_ReturnToBaseline` covers a `false` return and a `true` return
that owns a `Cat`, and `ResultTry_ThrownException_ReturnsToBaseline` covers a body that throws, so
both exits release the error handle.

A [`Throwable` read out of Kotlin](exceptions.md#throwable-values) is one error handle that C#
releases as it builds the exception, so a getter, a return, each `List` element and each `Map` value
leave the count where it was. `LeakTests/LiveHandleTests.cs`'s
`ThrowableProperty_Read_ReturnsToBaseline`, `ThrowableReturn_NullAndNonNull_ReturnsToBaseline`,
`ThrowableReturn_ThrowPath_ReturnsToBaseline` and `ThrowableList_ReadEveryElement_ReturnsToBaseline`
pin it. An exception passed in crosses as one string and mints no handle:
`ThrowableParameter_ReportAndSet_ReturnsToBaseline` covers a parameter, a nullable parameter and a
setter.

The async, callback and interface-slot positions use the same two encodings and also leave the
count where it was. `LeakTests/LiveHandleTests.cs` pins each:
`ThrowableListInput_ReturnsToBaseline` and `ThrowableListInput_ThrowPaths_ReturnToBaseline` for a
`List` input, `ThrowableBridgeSlot_AcceptAndLast_ReturnsToBaseline` for a C#-implemented interface
slot, `ThrowableSuspendParameter_ReturnsToBaseline` for a `suspend` parameter,
`ThrowableCallbackPayload_InvokedAndThrowing_ReturnsToBaseline` and
`ThrowableListenerParameter_Announce_ReturnsToBaseline` for a lambda payload and a listener
parameter, `ThrowableSuspendResult_NullAndNonNull_ReturnsToBaseline` for a `suspend` result
(including a member that never suspends, in a tight loop),
`ThrowableFlow_CollectEveryItem_ReturnsToBaseline` for a `Flow` element (an early break and a
throw path too) and `ThrowableStateFlow_ValueReads_ReturnsToBaseline` for a `StateFlow` element.

A [value class's own member](value-classes.md#nullable) that returns an object, a `List` or a
`Throwable` mints one handle that C# releases or hands to you, null or not.
`LeakTests/LiveHandleTests.cs`'s `ValueClassMemberObjectResult_NullAndNonNull_ReturnsToBaseline`,
`ValueClassMemberListResult_NullAndNonNull_ReturnsToBaseline` and
`ValueClassMemberThrowableResult_NullAndNonNull_ReturnsToBaseline` pin it. There is no throw-path
row, because these members have no error slot.

### A `suspend` lambda parameter

Each time Kotlin invokes a [`suspend` lambda parameter](coroutines-and-flow.md#suspend-lambda-parameter)
it mints a counted pending-continuation handle and a task handle, so these
`LeakTests/LiveHandleTests.cs` rows pin the count after a call settles:

| Row | Test | Pins |
|---|---|---|
| 17a | `SuspendLambdaParameter_SuspendingLambda_ReturnsToBaseline` | a lambda that really suspends, invoked three times |
| 17b | `SuspendLambdaParameter_AlreadyCompletedTask_TightLoop_ReturnsToBaseline` | thousands of already-completed tasks in one call, where completion can land before the start thunk returns |
| 17c | `SuspendLambdaParameter_SynchronousThrow_ReturnsToBaseline` | a throw before any `Task` exists, caught in Kotlin |
| 17d | `SuspendLambdaParameter_FaultedTask_ReturnsToBaseline` | a faulted `Task` that propagates and faults the outer call |
| 17e | `SuspendLambdaParameter_OuterTokenCancelledWhilePending_ReturnsToBaseline` | the outer token cancelled while a lambda that ignores its token is pending |
| 17f | `SuspendLambdaParameter_StoredByAConstructor_ReturnsToBaseline` | a lambda a constructor stores, released after its owner is disposed |

These rows cannot see the C# half. The table that holds the delegate until Kotlin releases it is
not counted by `nuget_live_handles`, so a delegate that is never released leaves the count
unchanged. `LeakTests/CollectabilityTests.cs` covers that half by holding a `WeakReference` to an
object the lambda captured and asserting it is collected:

- `SuspendLambdaParameter_AfterTheCallCompletes_ItsClosureIsCollected`: a `suspend fun` host.
- `SuspendLambdaParameter_StoredByAConstructor_IsReleasedWithItsOwner`: alive while the owner is
  alive and still callable, collected after the owner is disposed and dropped.
- `SuspendLambdaParameter_CallOnADisposedWrapper_ItsClosureIsCollected`: the call throws before
  reaching Kotlin.
- `SuspendLambdaParameter_LaterArgumentFailsItsNullGuard_ItsClosureIsCollected`: a later argument
  is refused after the lambda was prepared.
- `SuspendLambdaParameter_LambdaBeforeAString_AfterTheCallCompletes_ItsClosureIsCollected`: the
  control for the row above, with a valid later argument.

### A plain lambda on a `suspend` or `Flow` member

A [plain lambda parameter](coroutines-and-flow.md#plain-lambda-on-suspend-member) on a `suspend` or
`Flow`-returning member registers a callback context that Kotlin releases when the coroutine or the
collection ends. These `LeakTests/LiveHandleTests.cs` rows pin the handle count after the call
settles:

| Test | Pins |
|---|---|
| `SuspendPlainLambda_EveryOwnerAndPayload_Completion_ReturnsToBaseline` | every owner and payload kind, completing |
| `SuspendPlainLambda_Fault_ReturnsToBaseline` | a Kotlin throw, and the delegate itself throwing |
| `SuspendPlainLambda_Cancel_ReturnsToBaseline` | cancellation through the token |
| `SuspendPlainLambda_TightLoop_CompletionBeforeReturn_ReturnsToBaseline` | a member that never suspends, so the release can beat the native call's return |
| `SuspendPlainLambda_SealedArmEnumCompanionValueClass_ReturnsToBaseline` | a sealed arm, an enum companion and a value class |
| `FlowPlainLambda_EveryArm_ReturnsToBaseline` | a `Flow` member completing, faulting, cancelled and abandoned |
| `SuspendReturningFlowPlainLambda_ReturnsToBaseline` | a `suspend` member returning a `Flow` |

The context table is not counted by `nuget_live_handles`, so
`LeakTests/CollectabilityTests.cs` holds a `WeakReference` to state the delegate captured and
asserts it is collected: `SuspendPlainLambda_AfterCompletion_ItsDelegateIsCollected`,
`..._AfterFault_...`, `..._DelegateThrows_...`, `..._AfterCancellation_...`,
`..._StaticOwner_PreCanceled_...`, `..._TightLoop_CompletionBeforeReturn_EveryDelegateIsCollected`,
`..._LaterArgumentFailsItsNullGuard_...` and `..._SealedArmEnumCompanionValueClass_...`; the
`FlowPlainLambda_*` rows for completion, fault, cancellation, early `break` and a flow disposed
without enumerating; and `SuspendReturningFlowPlainLambda_*` for a disposed flow, a flow disposed
uncollected, a fault, a cancellation or a `null` flow, and a static owner.

### A returned lambda and the widened callback vocabulary

A lambda a function [returns](lambdas-and-callbacks.md#a-top-level-function-that-returns-a-lambda) is a
delegate whose target releases a Kotlin lambda handle when it is collected. A delegate a C#
implementation returns is minted into a Kotlin lambda that Kotlin drops after the call, and its
cleaner frees the delegate's key. A widened [payload or result](lambdas-and-callbacks.md#callback-vocabulary)
mints one box that the reading side consumes, and a bound C# interface crosses as a `GCHandle` that
the receiving side takes.

The owner that awaits a `suspend` delegate a C# implementation returned creates its coroutine scope
handle on the first `suspend` call and releases it on `Dispose`, so the live count rises by two at the
first crossing and stays flat after it.

| Test | Pins |
|---|---|
| `ReturnedLambda_EveryOwner_ReturnsToBaseline` | a returned lambda on every owner, plus a C# implementation of a Kotlin interface returning delegates |
| `ReturnedLambda_SuspendResults_ReturnsToBaseline` | `suspend` returned lambdas |
| `ReturnedAsyncDelegate_FromACSharpImplementation_IsFlatAfterTheOwnerScope` | Kotlin awaiting an async delegate a C# member returned: +2 at the first call (the owner and its scope handle), flat after, back to baseline on `Dispose` |
| `SealedArmDelegate_SetCallClearAndReturn_ReturnsToBaseline` | a lambda property written, read and cleared on a sealed arm |
| `ListenerDelegate_KotlinObjectStoresAndClearsIt_ReturnsToBaseline` | a delegate a Kotlin `object` stores and clears itself |
| `StoredListenerWithAResult_SubscribeAndDispose_ReturnsToBaseline` | a stored pair whose listener returns an enum |
| `WidenedCallbackPayloadsAndResults_ReturnToBaseline` | `Char`, nullable, collection and `ByteArray` payloads and results, and generic-owner pairs |
| `WidenedSuspendPayloadsAndGenericPairs_ReturnToBaseline` | widened `suspend` payloads, a nullable `suspend` lambda, and pairs on a generic sealed arm and a generic owner |
| `ThrowableCallbacks_ReturnToBaseline` | a `Throwable` as a `suspend` lambda result, a nullable `Throwable?` payload and a returned `suspend` lambda's result |
| `BoundInterfaceCallbacks_ReturnToBaseline` | a bound C# interface as a plain and a `suspend` payload and result |
| `BoundInterfaceErasedPositions_ReturnToBaseline` | a bound C# interface in a stored pair, a returned lambda and a lambda property |

Collectability, in `LeakTests/CollectabilityTests.cs`: `StoredListenerWithAResult_Disposed_IsCollected`,
`BoundInterfaceStoredListener_Disposed_IsCollected`,
`ReturnedDelegate_FromACSharpImplementation_IsCollectedOnceKotlinDropsIt`,
`ReturnedAsyncDelegate_FromACSharpImplementation_IsCollectedOnceKotlinDropsIt`,
`SealedArmDelegate_ClearedByCSharp_IsCollected`, `SealedArmDelegate_ClearedByKotlin_IsCollected`,
`ListenerDelegate_KotlinObjectClearsIt_IsCollected` and
`ListenerInterfaceDelegate_RemovedListener_IsCollected` prove a replaced, cleared or removed delegate
is collected.

### A lambda-typed property

Reading a [lambda property](lambdas-and-callbacks.md#kotlin-c-lambda-properties-and-returns) mints one
handle that is released when the delegate is collected; writing a C# delegate mints one Kotlin lambda
whose callback key `nuget_live_handles` does not count. The ledger rows return to baseline; the
collectability rows prove a replaced or cleared delegate is collected.

| Row | Test | Pins |
|---|---|---|
| LeakTests | `LambdaProperty_EveryOwner_ReadAndWrite_ReturnsToBaseline` | read and write on an `object`, companions, top level, enum, generic class and class |
| LeakTests | `LambdaProperty_Interface_BothHalves_ReturnsToBaseline` | a Kotlin-backed and a C#-implemented interface |
| LeakTests | `LambdaProperty_SuspendStateAndSharedFlowResult_ReturnsToBaseline` | a delegate kept by a returned `StateFlow` or `SharedFlow` |

Collectability: `LambdaProperty_ObjectNullableVar_ClearedDelegateIsCollected`,
`LambdaProperty_CompanionVar_ReplacedDelegateIsCollected`,
`LambdaProperty_TopLevelNullableVar_ClearedDelegateIsCollected`,
`LambdaProperty_EnumBodyVar_ReplacedDelegateIsCollected`,
`LambdaProperty_GenericClassVar_ReplacedDelegateIsCollected`,
`LambdaProperty_KotlinBackedInterfaceVar_ClearedDelegateIsCollected` and
`LambdaProperty_SuspendStateFlowResult_DelegateIsCollectedOnceTheFlowIsDisposed`.

### A `Flow` parameter

Each pull of a [`Flow` parameter](coroutines-and-flow.md#flow-parameter) mints a counted
enumeration handle and a task handle, and a `String` element is boxed once per element, so these
`LeakTests/LiveHandleTests.cs` rows pin the count after a call settles. Each awaits the iterator's
own `finally` first, because the enumerator is disposed on a queued work item after the call
completes.

| Row | Test | Pins |
|---|---|---|
| 18a | `FlowParameter_FullCollect_ReturnsToBaseline` | a full collect of an `Int` flow and of a `String` flow |
| 18b | `FlowParameter_EarlyAbort_ReturnsToBaseline` | Kotlin stops after two of five, so the iterator is disposed parked at a `yield` |
| 18c | `FlowParameter_IteratorThrowsMidStream_ReturnsToBaseline` | an iterator that throws mid-stream, once caught in Kotlin and once propagated |
| 18d | `FlowParameter_OuterTokenCancelledMidStep_ReturnsToBaseline` | the outer token cancelled while a pull is pending and the iterator ignores its token |
| 18e | `FlowParameter_SourceThatNeverSuspends_TightLoop_ReturnsToBaseline` | thousands of already-completed pulls in one collect, where completion can land before the start thunk returns |
| 18f | `FlowParameter_StoredByAConstructor_ReturnsToBaseline` | a flow a constructor stores and a later call enumerates twice |
| 18g | `FlowParameter_CollectorThrows_ReturnsToBaseline` | a throw from Kotlin's own collector, with no pull pending |

Row 18d holds its handles until the iterator really finishes, which is a bounded hold and not a
leak, so it releases the iterator and awaits its `finally` before asserting the baseline.

As for a suspend lambda, the C# half is invisible to `nuget_live_handles`.
`LeakTests/CollectabilityTests.cs` asserts through a `WeakReference` to an object the source
captured:

- `FlowParameter_AfterTheCallCompletes_ItsSourceIsCollected`: a `suspend fun` host.
- `FlowParameter_StoredByAConstructor_IsReleasedWithItsOwner`: alive and still enumerable while
  the owner is alive, collected after the owner is disposed and dropped.

### A suspend call that throws before reaching Kotlin

Every `...Async` wrapper roots the closure that completes its `Task` until Kotlin's completion
callback fires. A call that throws first, such as a `null` for a non-null `string` argument, must
free that root itself. The root is a plain .NET `GCHandle`, so `nuget_live_handles` cannot see it.
`LeakTests/CollectabilityTests.cs` holds a `WeakReference` to the `CancellationTokenSource` passed
to the call, and to the wrapper where there is one, and asserts they are collected:

- `SuspendCompletion_MemberArgumentFailsItsNullGuard_ItsStateIsCollected`: an instance member
  refuses a `null` `string` argument.
- `SuspendCompletion_TopLevelArgumentFailsItsNullGuard_ItsStateIsCollected`: the same for a
  top-level function.
- `SuspendCompletion_MemberCallOnADisposedWrapper_ItsStateIsCollected`: a call on a disposed
  wrapper, which throws before the root exists.
- `SuspendCompletion_MemberCallCompletes_ItsStateIsCollected` and
  `SuspendCompletion_TopLevelCallCompletes_ItsStateIsCollected`: the controls, with a valid
  argument and a call that completes.

### A callback that throws on a Kotlin thread

When a C# callback throws, the bridge keeps the exception in a per-thread slot so the original object
can come back if the fault reaches your C# caller uncaught. Once Kotlin catches the throw and the
call or `Task` that carried it completes, the slot is cleared and the exception can be collected,
including a throw on a Kotlin dispatcher thread from a suspend lambda, a `Flow` parameter or a
faulted `Task` a lambda returns. `LeakTests/CollectabilityTests.cs` holds a `WeakReference` to the
thrown exception and asserts it is collected:

- `ManagedFault_ThrownOnAKotlinDispatcherThread_AndCaughtByKotlin_IsCollected`: a suspend lambda that
  throws before its first suspension.
- `ManagedFault_ThrownOnTheCallingThread_AndCaughtByKotlin_IsCollected`: the control, a throw on the
  calling thread.
- `ManagedFault_FromAFlowParameterIteratorThatThrowsMidStream_AndCaughtByKotlin_IsCollected` and
  `ManagedFault_FromAFlowParameterMoveNextThatThrowsSynchronously_AndCaughtByKotlin_IsCollected`: a
  `Flow` parameter whose iterator throws mid-stream, and one whose `MoveNextAsync` throws synchronously.
- `ManagedFault_FromAFaultedTask_AndCaughtByKotlin_IsCollected`: a suspend lambda whose returned `Task` faults.
- `ManagedFault_NotCaughtByKotlin_IsCollectedOnceTheFaultedTaskIsDropped`: Kotlin does not catch, so
  the outer `Task` faults with the original exception, before and after an `await`.

Two bounded holds remain, held and not leaked. A coroutine that suspends on another thread after
Kotlin catches the throw keeps that one exception until the stashing thread's next completion or
fault. On a synchronous call, if Kotlin catches the throw and then escapes with a different error
(its own wrapper, or an unrelated one), the exception stays until that thread's next successful call.

### Dropped wrappers are released by the GC

A wrapper dropped without `Dispose()` returns `LiveHandles` to baseline once the .NET GC finalizes it
([ADR-187](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/187-forward-finalizer-contract.md)).
In a check, drop the wrapper in a method the JIT cannot inline, then loop `GC.Collect()` and
`GC.WaitForPendingFinalizers()` until the count settles; it can take several rounds. These
`LeakTests/LiveHandleTests.cs` rows pin it:

The suspend-returning-Flow path is covered by `SuspendFlow_AcquireCollectDispose_AllOwnerRoutesReturnToBaseline`,
`SuspendFlow_AcquisitionAndEmissionFaults_ReturnToBaseline`,
`SuspendFlow_CanceledAndLateSuccessfulAcquisitions_ReturnToBaseline`, and
`SuspendFlow_ImmediateAcquisitionAndCompletion_ThousandsReturnToBaseline`. The acquired holder's
GC fallback is covered by `UndisposedSuspendFlowHolder_IsReleasedByTheGc`.

A producer parked on an unread credit ([backpressure](coroutines-and-flow.md#flow-backpressure)) holds
the item it already minted. `Flow_AbandonedWhileProducerParked_ReturnsToBaseline` (row 8l) reads one
item, waits, disposes the enumerator, and returns to baseline: the cancel releases the held item.

A hot [`SharedFlow<T>`](coroutines-and-flow.md#shared-flow-t) never completes, so its leak row is the
abandoned shape: `SharedFlow_AbandonedAfterReplay_ReturnsToBaseline` (row 8k) takes the replayed
item from a property and a `suspend` return, disposes each enumerator, and returns to baseline.

The [`SharedFlow` surface](coroutines-and-flow.md#shared-flow-emit) (`ReplayCache`, `EmitAsync`,
`TryEmit`, `SubscriptionCount`) has six more rows in `LeakTests/LiveHandleTests.cs`:

- Row 8m, `SharedFlow_ReplayCache_ObjectElement_ReturnsToBaseline`: a `ReplayCache` read consumes the
  list handle and each item's box.
- Row 8n, `MutableSharedFlow_SubscriptionCount_DisposedAndAbandoned_ReturnsToBaseline`: the owned
  `StateFlow<Int>` handle, disposed and dropped for the GC.
- Row 8o, `MutableSharedFlow_EmitAsync_CompletedAndCancelled_ReturnsToBaseline`: the emit job
  handle of a completed emit; an already-cancelled one crosses nothing.
- Row 8p, `MutableSharedFlow_ParkedEmitAsync_CancelledByOwnerDispose_ReturnsToBaseline`: an emit
  parked behind a stalled collector, cancelled by the owner's `Dispose()`.
- Row 8q, `MutableSharedFlow_TryEmit_ObjectAndEnumElements_ReturnsToBaseline`: a borrowed object
  argument and an enum ordinal.
- Row 8r, `MutableSharedFlow_HeldMethodReturn_DisposedAndAbandoned_ReturnsToBaseline`: a held
  `MutableSharedFlow<T>` method return, disposed and dropped for the GC.
- Row 8s, `MutableSharedFlow_ValueClassElement_EmitsAndReplayCache_ReturnsToBaseline`: a value-class
  element emitted by its underlying and read back through `ReplayCache`.

A `Flow` or `StateFlow` held as a generic type argument ([A flow inside a generic
class](coroutines-and-flow.md#flow-type-argument)) mints a holder per `.Value` read that owns a flow
handle and its own scope. `BoxedFlow_CollectAndDispose_ReturnsToBaseline` covers a collection run to
completion, an enum element and an enumerator disposed mid-collection;
`BoxedFlow_EachValueReadMintsAFreshHolder_ReturnsToBaseline` covers repeated reads;
`BoxedFlow_OwnerOrHolderDisposedMidCollection_ReturnsToBaseline` disposes the producing owner and
then the holder while a never-ending collection is parked.

| Row | Test | Pins |
|---|---|---|
| 16 | `UndisposedClassWrapper_IsReleasedByTheGc` | a class wrapper |
| 16a | `UndisposedCallbackObjectPayload_LambdaParameterRoute_IsReleasedByTheGc` | a callback object payload, lambda-parameter route |
| 16b | `UndisposedCallbackObjectPayload_CallbackMemberRoute_IsReleasedByTheGc` | a callback object payload, callback-member route |
| 16c | `AbandonedWrapperTypedFlowItem_AfterDisposeAsync_IsReleasedByTheGc` | a wrapper-typed `Flow` item abandoned after `DisposeAsync` |
| 16d | `UndisposedSealedArm_IsReleasedByTheGc` | a sealed arm |
| 16e | `UndisposedInterfaceTypedReturn_IsReleasedByTheGc` | an interface-typed return |
| 16f | `UndisposedKotlinFuncAndAction_IsReleasedByTheGc` | a returned `Func` and `Action` delegate |
| 16g | `UndisposedKotlinSuspendFunc_IsReleasedByTheGc` | a `suspend` lambda property delegate |
| 16h | `UndisposedKotlinStateFlow_IsReleasedByTheGc` | `KotlinStateFlow` |
| 16i | `DiscardedSubscription_KeepsDeliveringAfterTheGc_AndIsReleasedWithItsOwner` | a discarded `AddX` subscription keeps delivering and is released with its owner |
| 16j | `UndisposedWrapperWithASuspendScope_IsReleasedByTheGc` | a wrapper that owns a suspend scope |
| 16k | `DisposedWrappers_ThenFinalized_AreNotReleasedTwice` | no double release after `Dispose()` |
| 16l | `AbandonedCollectionFlowItems_AreReleasedByTheEnumerator` | a `List`, `Set` or `Map` `Flow` item abandoned after `DisposeAsync`, released by the enumerator |
| 16m | `UndisposedSuspendFlowHolder_IsReleasedByTheGc` | an acquired suspend-returning `Flow` holder |
| 16n | `UndisposedOwnerWithDiscardedSubscriptions_IsReleasedByTheGc` | an undisposed owner whose `AddX` subscriptions were discarded |

The subscription is the exception to the per-wrapper rule: a discarded subscription keeps delivering
while its owner lives, and releasing the owner (rows 16i and 16n) unregisters it and frees its token.
The two collectability checks, `StoredCallbackReceiver_DiscardedToken_IsReleasedWithItsOwner` and
`StoredCallbackReceiver_TokenHeldPastItsOwner_DoesNotRootTheSource`, cover a discarded token and a
token kept past its owner.
The finalizer rows have been run on macOS with the JIT only.

## Forward direction has no registration step

Kotlin exports called from C# resolve by symbol name through an ordinary P/Invoke: no contract
check and no register table. A mismatch there surfaces as a plain `DllNotFoundException` (the
native library didn't load) or `EntryPointNotFoundException` (the symbol isn't in it) at the first
call, rather than one of the messages above. The `[nuget:interop]` line is the only added signal on
this path; tracing which native asset the .NET host actually resolved is outside what this feature
covers.

The static suspend route (an `object`, a companion, a suspend extension, an enum or a value class)
starts each call on its own scope, so it holds no owner state to leak. `LeakTests/LiveHandleTests.cs`
pins the count after a call settles: `StaticSuspend_EveryOwner_Completion_ReturnsToBaseline`,
`StaticSuspend_Fault_ReturnsToBaseline`, `StaticSuspend_Cancellation_ReturnsToBaseline` and
`StaticSuspend_TightLoop_CompletionBeforeReturn_ReturnsToBaseline`. `LeakTests/CollectabilityTests.cs`
holds a `WeakReference` to the call's state in `StaticSuspend_ObjectCallCompletes_ItsStateIsCollected`,
`StaticSuspend_ExtensionCallCompletes_ItsStateAndReceiverAreCollected` and
`StaticSuspend_ArgumentFailsItsNullGuard_ItsStateIsCollected`.

A [static flow](coroutines-and-flow.md#flow-static-owners) (the top level, an `object`, a companion,
an enum or a value class) collects on its own scope, so a cold one mints only its job handle, which
completion releases. `LeakTests/LiveHandleTests.cs` pins the count after the enumeration ends:
`StaticFlow_EveryOwner_Completion_ReturnsToBaseline`, `StaticFlow_Fault_ReturnsToBaseline`,
`StaticFlow_Cancellation_ReturnsToBaseline`, `StaticFlow_TightLoop_SynchronousCompletion_ReturnsToBaseline`
and, for the held `MutableStateFlow` and `MutableSharedFlow` holders, `StaticFlow_HotHolders_ReturnToBaseline`.
`LeakTests/CollectabilityTests.cs` holds a `WeakReference` to an undisposed flow and to the token
source in `StaticFlow_CollectedToCompletion_TheFlowAndTokenAreCollected` and
`StaticFlow_CancelledByTheToken_TheFlowAndTokenAreCollected`.

A `MutableStateFlow` or `MutableSharedFlow` of a nullable `List`, `Set` or `Map`
([details](coroutines-and-flow.md#mutable-nullable-collection-element)) mints the handle for a write
and releases it after the call; `null` mints nothing. Each `.Value` read, replay-cache read and
emission is a collection copied out, so no handle outlives it. `LeakTests/LiveHandleTests.cs`'s
`NullableCollectionFlows_SettableHolders_ReturnToBaseline` writes, reads, emits and awaits through a
class, an `object` and the top level, and returns to baseline.

A [flow property on an enum](coroutines-and-flow.md#flow-static-owners) follows the static-owner
rules above: a cold one holds nothing, a held `Mutable*` one is yours to dispose.
`EnumFlowProperties_EveryKind_ReturnToBaseline` pins every kind, plain and hot. The row needs
`using static TestLibrary.Purrclock.TailExtensions;`, because a C# 14 extension property is not
reached through a namespace alias.

A top-level extension on any receiver ([Extensions](extensions.md#supported-receivers)) holds no owner state, so the
handle count returns to baseline once a call settles. `LeakTests/LiveHandleTests.cs` pins it:
`GenericExtension_Unpack_ReturnsToBaseline`, `GenericExtensionProperty_Lid_ReturnsToBaseline`,
`SyncExtension_HandleMintingReceivers_ReturnToBaseline`,
`SuspendCollectionReceiver_TotalAsync_ReturnsToBaseline`,
`SuspendBoundInterfaceReceiver_ReturnsToBaseline`,
`SuspendEveryReceiver_Completion_ReturnsToBaseline`, `SuspendResult_Failure_ReturnsToBaseline`
(a `Result<T>` failure faulting the task), `FlowResultExtension_EveryReceiver_ReturnsToBaseline` and
`FlowParameterExtension_GenericAndNullableReceivers_ReturnToBaseline`, and, for member extension properties on a class, `MemberExtensionProperties_ReturnToBaseline`. A member extension inside every other owner has a row each: `MemberExtensions_ObjectOwner_ReturnToBaseline`, `MemberExtensions_CompanionOwner_ReturnToBaseline`, `MemberExtensions_EnumOwner_ReturnToBaseline`, `MemberExtensions_ValueClassOwner_ReturnToBaseline`, `MemberExtensions_SealedOwner_ReturnToBaseline` and `MemberExtensions_InterfaceOwner_BothSides_ReturnToBaseline`.
`LeakTests/CollectabilityTests.cs` holds a `WeakReference` to the call's state in
`SuspendInterfaceReceiver_IsCollectedOnceTheTaskCompletes`,
`SuspendBoundInterfaceReceiver_IsCollectedOnceTheTaskCompletes` and
`SuspendCollectionReceiver_IsCollectedOnceTheTaskCompletes`.

<seealso>
    <category ref="related">
        <a href="reverse-overview.md">Consuming C# in Kotlin</a>
        <a href="bind-nuget-package-for-kotlin.md">Bind a NuGet package for Kotlin</a>
        <a href="objects-and-handles.md">Objects and handles</a>
        <a href="bridgeable-subset.md">The bridgeable subset</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/054-reverse-bridge-registration-observability.md">ADR-054: Reverse-bridge registration observability</a>
    </category>
</seealso>
