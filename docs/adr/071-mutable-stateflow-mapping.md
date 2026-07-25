# ADR-071: `MutableStateFlow<T>` mapping: settable `.Value` via `KotlinMutableStateFlow<T> : KotlinStateFlow<T>`, an ordinary forward-direction setter export (not Phase 7)

## Status

Accepted

## Context

ADR-065 mapped `StateFlow<T>` to `KotlinStateFlow<T> : KotlinFlow<T>` with a synchronous, get-only
`T Value { get; }`. It folded `MutableStateFlow<T>` into that same read-only mapping and deferred the
write:

> **Settable `.Value`** (true `MutableStateFlow<T>` write): needs a C#→Kotlin value-**setter**
> export (a reverse-direction write of `T` into `stateFlow.value`), which is a Phase 7 bidirectional
> concern, not a forward-only mapping.
> See [ADR-065](065-stateflow-mapping.md), "Deferred"; repeated verbatim at ROADMAP.md:115.

**That rationale is wrong, and correcting it is the first job of this ADR.**

### This is not Phase 7. It is ordinary forward-direction parameter marshalling.

Phase 7 ("Bidirectional support (C# → Kotlin)", ROADMAP.md:131-143) is about *control* flowing from
Kotlin into C#: delegates, `Marshal.GetFunctionPointerForDelegate`, `GCHandle`, stored callbacks,
interface bridging (ADR-036, ADR-037, ADR-039). None of that is involved in writing a value into a
`MutableStateFlow`. The call still originates in C#, still enters Kotlin through a `@CName` export,
and still returns to C#. The `T` travels as an ordinary **input parameter**, exactly like the
parameter of any generated method or `var` property setter.

The repository already does this, today, in the shipped StateFlow test suite:

- **Verified-in-repo**, `test-library/.../CatMoodTracker.kt:60-62`:
  ```kotlin
  fun setMood(newMood: String) {
    _mood.value = newMood
  }
  ```
- **Verified-in-repo**, `IntegrationTests/StateFlowTests.cs:57`: `tracker.SetMood("zoomies");`

That is a C#-originated write of a `String` into a Kotlin `MutableStateFlow.value`, crossing the C
ABI as an ordinary method parameter, passing today. The only thing missing is that the write is
spelled as a hand-written Kotlin method instead of being generated from the declared
`MutableStateFlow<T>` member. **No new bridge machinery of any kind is required.**

The generic setter machinery also already exists and is already exercised for every ordinary `var`
property. **Verified-in-repo**, `ForwardPropertyKotlinEmitter.kt:123-146` (`addSetter`) emits

```kotlin
@CName("cat_set_name")
fun export_cat_set_name(handle: COpaquePointer, value: String, errorOut: COpaquePointer?) {
  try {
    handle.asStableRef<Cat>().get().name = value
  } catch (e: Throwable) { /* errorOut = StableRef.create(buildError(e)) */ }
}
```

and `ForwardPropertyKotlinEmitter.kt:182-205` (`valueExpression` / `kotlinInputType`) already covers
every input shape this ADR needs: `Primitive` and `Char` and `String` pass by value; `Enum` passes as
`Int`; `ObjectHandle` passes as `COpaquePointer` and is unwrapped with
`value.asStableRef<Cat>().get()`; `Nullable(ObjectHandle)` as a nullable pointer. A
`MutableStateFlow` write is that same emitter's assignment with `.value` spliced into the access
expression.

**The boundary where the "reverse" framing would actually have bitten** is narrower than ADR-065
claimed, and this ADR routes around it rather than into it: a *generic, handle-keyed* setter (one
shared export taking the `MutableStateFlow<*>` handle plus a boxed `Any` value) would need a C#-side
generic `ToHandle<T>` and an unchecked Kotlin-side cast. That shape is required only for the
ADR-068 `suspend fun`-returning variant, and is deferred (see Alternative 3 and Deferred scope).

### What Kotlin's `MutableStateFlow` actually guarantees

**Verified** from `kotlinx.coroutines` source
([`StateFlow.kt`](https://github.com/Kotlin/kotlinx.coroutines/blob/master/kotlinx-coroutines-core/common/src/flow/StateFlow.kt),
fetched during this research):

```kotlin
public interface MutableStateFlow<T> : StateFlow<T>, MutableSharedFlow<T> {
    /**
     * Setting a value that is [equal][Any.equals] to the previous one does nothing.
     * This property is **thread-safe** and can be safely updated from concurrent coroutines
     * without external synchronization.
     */
    public override var value: T
    public fun compareAndSet(expect: T, update: T): Boolean
}
```
(lines 158-181)

and the implementation (lines 321-344):

```kotlin
set(value) { updateState(null, value ?: NULL) }
override fun compareAndSet(expect: T, update: T): Boolean = updateState(expect ?: NULL, update ?: NULL)

private fun updateState(expectedState: Any?, newState: Any): Boolean {
    synchronized(this) {
        val oldState = _state.value
        if (expectedState != null && oldState != expectedState) return false
        if (oldState == newState) return true   // <- calls Any.equals on the OLD value
        _state.value = newState
        ...
```

Three consequences that shape the design, all **verified from that source**:

1. The setter is `synchronized` and explicitly documented thread-safe from concurrent callers.
2. The setter **invokes `Any.equals` on the previous value** (line 332). A user `equals` that throws
   makes the `value` setter throw. So the setter export is not provably non-throwing, unlike
   ADR-065's `_value` *getter*, and must carry `errorOut`.
3. `update { }` / `updateAndGet { }` are **inline extension functions** (lines 197-237) implemented
   as a `compareAndSet` retry loop, not interface members. They are not exportable as-is.

### How other interop targets surface a settable observable value

#### SKIE (Touchlab): the closest precedent, and **verified from source this session**

ADR-065 listed "`SkieSwiftMutableStateFlow<T>` exposes a settable `value`" as *inferred, not
verified*. It is now **verified**, from SKIE's own acceptance-test fixture
(`SKIE/acceptance-tests/functional/src/test/resources/tests/coroutines/flow/mapping/subtypes/mutablestateflow/nonnull/_.swift`,
retrieved via `gh api repos/touchlab/SKIE/contents/...`):

```swift
func sum(flow: SkieSwiftMutableStateFlow<KotlinInt>) async throws -> Int32 { ... }

try! await AKt.flow.emit(value: KotlinInt(1))
let sum1 = AKt.flow.value
AKt.flow.value = KotlinInt(2)               // <-- settable `value`
let emitResult = AKt.flow.tryEmit(value: KotlinInt(3))
let cacheSum = AKt.flow.replayCache...
let subscriptionCount = AKt.flow.subscriptionCount.value
let setResult = AKt.flow.compareAndSet(expect: 3, update: 4)
```

So SKIE: (a) uses a **distinct type** `SkieSwiftMutableStateFlow<T>` rather than making
`SkieSwiftStateFlow<T>` settable, (b) surfaces the write as a **settable `value` property**, not a
`setValue` method, and (c) *additionally* exposes `compareAndSet`, `emit`, `tryEmit`, `replayCache`,
`subscriptionCount`. This ADR follows (a) and (b) and defers (c).

#### Kotlin/Native ObjC Export and Swift Export (official)

Unchanged from ADR-026/065's survey: neither treats any `Flow` type specially; a `MutableStateFlow`
appears as an opaque Kotlin object with no bridged `value`. *Inferred; no official StateFlow mapping
is documented for either.*

#### Java (JVM)

A Kotlin `var` in an interface compiles to a `getValue()`/`setValue()` pair, so Java sees
`flow.setValue(x)`. The write is an ordinary synchronous setter call, and only `collect` is hard,
the same data point ADR-065 recorded for the read. *Inferred (standard Kotlin-to-JVM property
lowering); not spiked.*

#### Kotlin/JS `@JsExport`

No `StateFlow`/`MutableStateFlow` support; developers hand-wrap. *Inferred, per ADR-026.*

#### .NET's own idiom, and the one place it disagrees

Rx.NET's `BehaviorSubject<T>` is the semantic analogue of `MutableStateFlow<T>`, and its `Value` is
**get-only**; writes go through `OnNext(T)`. **Verified** from
[`BehaviorSubject.cs`](https://github.com/dotnet/reactive/blob/main/Rx.NET/Source/src/System.Reactive/Subjects/BehaviorSubject.cs)
(fetched this session):

```csharp
public T Value
{
    get { lock (_gate) { CheckDisposed(); _exception?.Throw(); return _value; } }
}
```

That is a real counter-datapoint, but it is a consequence of `BehaviorSubject<T>`'s `IObserver<T>`
conformance (`OnNext` already *is* the write path), not of a C# preference for methods over settable
properties. Elsewhere C# is unambiguous: mutable state on an observable object is a settable property
(every `INotifyPropertyChanged` view-model, every `ObservableProperty` in the MVVM Toolkit). Kotlin's
own spelling is `var value`, SKIE's is `value =`, and a settable `.Value` is what a C# developer
reading `KotlinMutableStateFlow<T>` in IntelliSense will expect. We take the settable property.

`INotifyPropertyChanged` remains deferred and out of scope (ROADMAP.md:120); this ADR does not
reopen it.

## Alternatives Considered

### 1. `KotlinMutableStateFlow<T> : KotlinStateFlow<T>` with a `new`-shadowed settable `Value` (chosen)

A `MutableStateFlow<T>`-**declared** member maps to a generated `KotlinMutableStateFlow<T>`, which
extends `KotlinStateFlow<T>` (and therefore `KotlinFlow<T>` / `IAsyncEnumerable<T>`) and adds a
settable `Value`. The write is backed by a per-member, monomorphic `@CName` setter export whose
parameter is the element type's ordinary wire type.

```csharp
using var tracker = new CatMoodTracker("Mylo");
tracker.TreatCount.Value = 7;                    // C# -> Kotlin write
Assert.Equal(7, tracker.TreatCount.Value);       // read back through the same flow
```

**Pros:**
- Mirrors Kotlin's own `MutableStateFlow : StateFlow : Flow` hierarchy exactly, so a
  `KotlinMutableStateFlow<T>` is assignable to `KotlinStateFlow<T>`, `KotlinFlow<T>` and
  `IAsyncEnumerable<T>`, the same honesty argument ADR-065 made for `KotlinStateFlow : KotlinFlow`.
- Mirrors SKIE's verified `SkieSwiftMutableStateFlow<T>` + settable `value`.
- Read-only `StateFlow<T>` members are completely untouched: no existing generated API changes
  shape, no existing test changes.
- The setter export is byte-for-byte the shape `ForwardPropertyKotlinEmitter.addSetter` already
  emits for every ordinary `var` property, including its `errorOut` (verified, `:123-146`). No new
  marshalling primitive is introduced. Primitives cross by value, with **no boxing at all**, cheaper
  than the `.Value` *read*, which must box into a `StableRef`.

**Cons:**
- C# does **not** permit an `override` to add a `set` accessor, so the derived `Value` must use
  `new` hiding. **Verified by spike** (below). `new` hiding is a smell in general; here it is
  benign because the base getter and the derived getter read the same underlying `_readValue`
  delegate, so a base-typed reference observes the write (also verified by spike).
- One extra native export per mutable StateFlow member, on top of ADR-065's `_collect` and `_value`.

### 2. `SetValue(T)` method on `KotlinStateFlow<T>` (no new type)

Add `public void SetValue(T value)` directly to `KotlinStateFlow<T>`, throwing
`NotSupportedException` when the underlying flow is read-only.

**Pros:** no new type, no `new` hiding, no CS0546 problem.

**Cons:** it is a **lying API**: `IntelliSense` offers `SetValue` on every `StateFlow<T>` including
genuinely read-only ones, and the failure is a runtime exception rather than a compile error. That
directly contradicts GOALS #2 (idiomatic) and the whole reason ADR-065 rejected its own Alternative 3.
It also loses the type-level distinction Kotlin itself draws and SKIE preserves. Rejected.

### 3. One shared, handle-keyed generic setter export (`nuget_mutablestateflow_set_value`)

Mirror ADR-068's `nuget_stateflow_collect` / `nuget_stateflow_value`: a single export taking the
`MutableStateFlow<*>` `StableRef` handle plus a boxed `Any` value handle, with a C#-side generic
`NugetMarshal.ToHandle<T>` as the inverse of the existing `FromHandle<T>`.

**Pros:** one export for the whole program regardless of member count; the only shape that can also
serve the ADR-068 `suspend fun`-returning variant, whose flow handle is only known at runtime.

**Cons:**
- Needs a Kotlin-side `handle.asStableRef<MutableStateFlow<Any?>>().get().value = boxed` with an
  **unchecked** cast: the compiler can no longer reject a type mismatch at generation time, and a
  wrong `T` would corrupt the flow silently rather than failing to compile.
- Needs a new generic `ToHandle<T>` and `nuget_wrap_*` coverage for every primitive. Today
  `nuget_wrap_*` exists only for `string`/`int`/`long`/`float`/`double`/`bool` (**verified-in-repo**,
  `GenericClassExports.kt:363-386` (`addNugetWrapHelperExports`, six types)), so `byte`, `short`,
  `uint`, `ulong`, `ubyte`, `ushort` would all need new exports.
- Boxes every primitive write into a `StableRef` for no benefit; option 1 passes an `Int` in a
  register.
- The ADR-065 property/method routes never obtain the flow's own handle (their exports are keyed on
  the *owner* handle (verified, `ClassExports.kt:150-164`), so this option would additionally
  require a new `_flow_handle` export just to get the receiver.

Rejected for v1. It remains the right shape for the deferred `suspend fun`-returning-MutableStateFlow
item, where the receiver genuinely is a runtime flow handle.

### 4. `Update(Func<T,T>)` / `CompareAndSet(T, T)` in v1

Export `compareAndSet` alongside the setter and build `Update` on top of it in C#, matching Kotlin's
`update { }` and SKIE's verified `compareAndSet`.

**Pros:** atomic read-modify-write; `Value = f(Value)` is not atomic.

**Cons:** `compareAndSet` needs a second monomorphic export per member with two value parameters,
and for object elements its semantics depend on `Any.equals` being invoked on a Kotlin object
reached through a C#-held handle (correct, but a distinct thing to specify and test). It is additive
and non-breaking: adding `CompareAndSet` later cannot change the meaning of `Value =`. Deferred to
keep v1 to one new export shape.

## Decision

Use **option 1**.

### KSP detection: key on the DECLARED type, split the existing set

**Verified-in-repo**: detection is already an exact `qualifiedName` match against
`STATE_FLOW_TYPES = { "kotlinx.coroutines.flow.StateFlow", "kotlinx.coroutines.flow.MutableStateFlow" }`
(`CirTypeMapping.kt:70-73`), consulted at `CirClassTranslator.kt:153,310,316,332,548,726`,
`ClassExports.kt:115,205,211,257`, `ForwardBridgeTypeClassifier.kt:206`,
`ForwardReachabilityClosure.kt:193`, `NugetProcessor.kt:553,561,845,878`. It is **never**
`isAssignableFrom`; ADR-065 made that a load-bearing invariant.

That invariant is exactly what makes this feature safe, and it answers the "common idiom" question
directly. Split the set, keep the union for every existing call site:

```kotlin
internal val MUTABLE_STATE_FLOW_TYPES = setOf("kotlinx.coroutines.flow.MutableStateFlow")
internal val READ_ONLY_STATE_FLOW_TYPES = setOf("kotlinx.coroutines.flow.StateFlow")
internal val STATE_FLOW_TYPES = READ_ONLY_STATE_FLOW_TYPES + MUTABLE_STATE_FLOW_TYPES  // unchanged
```

Only the *new* branch (emit a setter export, render `KotlinMutableStateFlow<T>`) tests
`in MUTABLE_STATE_FLOW_TYPES`. Every existing site keeps testing `in STATE_FLOW_TYPES` and is
unchanged.

Consequences for the idiomatic Kotlin pattern, all following from "declared type only":

| Kotlin declaration | C# surface |
|---|---|
| `private val _x = MutableStateFlow(0)` | **nothing**: private members are not exported (verified, `ClassExports.kt:79` filters `Visibility.PUBLIC`) |
| `val x: StateFlow<Int> = _x.asStateFlow()` | `KotlinStateFlow<int>`, get-only `.Value` (**unchanged from ADR-065**) |
| `val x: StateFlow<Int> = _x` (upcast, no `asStateFlow`) | `KotlinStateFlow<int>`, get-only; declared type wins, runtime type is irrelevant |
| `val x: MutableStateFlow<Int> = MutableStateFlow(0)` | `KotlinMutableStateFlow<int>`, settable `.Value` |
| `var x: MutableStateFlow<Int>` (mutable *member*) | `KotlinMutableStateFlow<int>` for the element write; reassigning the whole flow member stays out of scope |

So the ubiquitous `private val _x` / `val x: StateFlow<T> = _x` pattern keeps producing exactly
today's read-only binding, which is the correct and honest answer: the library author deliberately
published a read-only view. A settable `.Value` appears only when the author published
`MutableStateFlow<T>` itself.

### Kotlin export signature (new, one per mutable StateFlow member)

For `val treatCount: MutableStateFlow<Int>` on `CatMoodTracker` (native prefix `catmoodtracker`):

```kotlin
// existing, unchanged: catmoodtracker_get_treatCount_collect  (ADR-026 shape)
// existing, unchanged: catmoodtracker_get_treatCount_value    (ADR-065 shape, no errorOut)

// NEW: ADR-071
@CName("catmoodtracker_set_treatCount_value")
fun export_catmoodtracker_set_treatCount_value(
  handle: COpaquePointer,
  value: Int,
  errorOut: COpaquePointer?,
) {
  try {
    handle.asStableRef<CatMoodTracker>().get().treatCount.value = value
  } catch (e: Throwable) {
    if (errorOut != null) {
      errorOut.reinterpret<COpaquePointerVar>().pointed.value =
        StableRef.create(buildError(e)).asCPointer()
    }
  }
}
```

Element-type wire shapes, reusing `ForwardPropertyKotlinEmitter.kotlinInputType` / `valueExpression`
verbatim (verified, `:182-205`):

| element `T` | export parameter | assignment |
|---|---|---|
| `Int`/`Long`/`Boolean`/`Double`/… | the Kotlin primitive | `.value = value` |
| `String` | `String` | `.value = value` |
| object (e.g. `Cat`) | `COpaquePointer` | `.value = value.asStableRef<Cat>().get()` |

The `errorOut` is **not** defensive boilerplate: `MutableStateFlow.value`'s setter calls
`Any.equals` on the previous value (verified, `StateFlow.kt:332`), so a throwing `equals` propagates
out of the setter. This is consistent with [ADR-030](030-property-exception-propagation.md)'s
wrap-all-setters policy and with `addSetter`'s existing `errorOut` parameter, and it is a deliberate
*asymmetry* with ADR-065's `_value` getter, which correctly omits `errorOut` because a pure read
cannot throw.

Method-return form (`fun moodDial(): MutableStateFlow<String>`) mirrors ADR-065's method variant:
export `catmoodtracker_moodDial_set_value(handle, <method params…>, value, errorOut)`, body
`obj.moodDial(args).value = value`.

### Generated C# helper: `KotlinMutableStateFlow<T>`

Emitted once, immediately after `KotlinStateFlow<T>` in `CirFlowRenderer.kt`, gated by a new
`tracker.needsMutableStateFlow` (which implies `needsStateFlow`, which already implies `needsFlow`):

```csharp
public class KotlinMutableStateFlow<T> : KotlinStateFlow<T>
{
    private readonly Action<T> _writeValue;

    internal KotlinMutableStateFlow(
        NugetFlowCollectDelegate startCollect,
        Func<IntPtr> readValue,
        Action<T> writeValue,
        IntPtr ownedHandle = default)
        : base(startCollect, readValue, ownedHandle)
    {
        _writeValue = writeValue;
    }

    // `new`, not `override`: C# forbids an override from adding a set accessor (CS0546).
    public new T Value
    {
        get => base.Value;
        set => _writeValue(value);
    }
}
```

`KotlinStateFlow<T>` is a non-sealed `public class` with an `internal` constructor and a
non-virtual `public T Value => NugetMarshal.FromHandle<T>(_readValue());`, **verified-in-repo**,
`CirFlowRenderer.kt:137-159`. So this subclass compiles in the same generated `Interop.cs`, and
`_readValue` is reached through `base.Value` without changing the base at all.

**Verified by spike** (see "Spikes" below) that (a) an `override` adding a setter is `error CS0546`,
and (b) the `new`-shadowed form compiles with **zero warnings** and a base-typed reference observes
a write made through the derived setter.

### Generated C# property getter

```csharp
public KotlinMutableStateFlow<int> TreatCount
{
    get
    {
        if (_handle == IntPtr.Zero)
            throw new ObjectDisposedException(nameof(CatMoodTracker));
        return new KotlinMutableStateFlow<int>(
            (onNext, onComplete, onError, userData) =>
                Native_GetTreatCountCollect(_handle, GetOrCreateScope(), onNext, onComplete, onError, userData),
            () => Native_GetTreatCountValue(_handle),
            v =>
            {
                Native_SetTreatCountValue(_handle, v, out IntPtr error);
                if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);
            });
    }
}

[DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "catmoodtracker_set_treatCount_value")]
private static extern void Native_SetTreatCountValue(IntPtr handle, int value, out IntPtr error);
```

This is the existing ADR-065 getter (verified shape, `CirClassTranslator.kt:236-245`) plus a third
constructor argument. For an **object** element the write lambda passes the handle and guards null:

```csharp
v =>
{
    if (v is null) throw new ArgumentNullException(nameof(v));
    Native_SetFavouriteToyValue(_handle, v._handle, out IntPtr error);
    if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);
}
```

(Corrected post-implementation: the write lambda's parameter is named `v`, not `value`; `value` is
not in scope inside this lambda, it belongs to the enclosing property's `set` accessor, so
`nameof(value)` here is `CS0103`. The shipped code correctly uses `nameof(v)` at both generated
sites, property and function-return.)

`_handle` is `internal` on every generated wrapper (**verified-in-repo**,
`CirClassRenderer.kt:40,150` (`internal IntPtr _handle;`)), so no reflection is needed (unlike
`NugetMarshal.CreateBox<T>`, which needs it because `T` is generic there; here the lambda is
generated at a site where the element type is concrete).

### Threading

**Verified** from kotlinx source (`StateFlow.kt:164-165, 329`): the `value` setter is documented
thread-safe and its body runs under `synchronized(this)`. **Verified-in-repo** that Kotlin `@CName`
exports are already invoked from arbitrary .NET threads: `StructuredConcurrencyTests.cs:86-91`
calls `service.Dispose()` (a `_dispose` export) from ten `Task.Run` threadpool threads, and every
`await foreach` continuation in `StateFlowTests.cs` resumes on a threadpool thread before calling
back into Kotlin. At design time this was **inferred, not spiked**: a `.Value` write from an
arbitrary .NET threadpool thread was expected to be safe on the strength of Kotlin/Native's new
memory model (no thread confinement, no freezing) plus the two verified data points above, but it
was the one claim in this ADR whose failure mode is a rare crash rather than a compile error, so the
v1 test set was required to include a write issued from `Task.Run`. **Confirmed post-implementation:**
`IntegrationTests/MutableStateFlowTests.cs`'s
`SettableValue_WriteFromThreadpoolThread_MyloGetsHisTreatFromABackgroundThread` issues a `.Value`
write from `Task.Run` and passes; the claim is now verified, not inferred.

### Claim labelling

**Verified (repo source read this session, cited above):** `STATE_FLOW_TYPES` is an exact-declared-
qualifiedName match, not `isAssignableFrom` (`CirTypeMapping.kt:70-73` and all call sites); only
public properties are exported (`ClassExports.kt:79`); `KotlinStateFlow<T>` is non-sealed with an
`internal` ctor and non-virtual `Value` (`CirFlowRenderer.kt:137-159`); `_handle` is `internal`
(`CirClassRenderer.kt:40,150`); the ADR-065 property getter/lambda shape
(`CirClassTranslator.kt:236-245`) and `_value` export shape (`ClassExports.kt:150-164`,
`:513-543`); `ForwardPropertyKotlinEmitter.addSetter`'s existing errorOut-carrying setter export and
its per-type input marshalling (`:123-146, 182-205`); nullable-primitive setters use a two-export
`NullableDispatch` (`ForwardPropertyPlanner.kt:124-134`); `nuget_wrap_*` covers only six types
(`GenericClassExports.kt:363-386`); a C#-originated write into a Kotlin `MutableStateFlow.value`
already works end to end (`CatMoodTracker.kt:60-62` + `StateFlowTests.cs:57`); exports are already
called from .NET threadpool threads (`StructuredConcurrencyTests.cs:86-91`).

**Verified (external source fetched this session):** `MutableStateFlow`'s `var value` /
`compareAndSet` / thread-safety kdoc / `equals`-based conflation / `update` being an inline
extension (kotlinx.coroutines `StateFlow.kt:158-237, 321-344`); `SkieSwiftMutableStateFlow` exposes
a **settable** `value` plus `compareAndSet`/`emit`/`tryEmit` (SKIE acceptance-test fixture, path
cited above), this **upgrades ADR-065's explicitly-inferred SKIE `.value` claim to verified**;
Rx.NET `BehaviorSubject<T>.Value` is get-only (dotnet/reactive `BehaviorSubject.cs`).

**Verified by spike (commands and output below):** C# rejects an `override` that adds a `set`
accessor with `error CS0546`; the `new`-shadowed settable `Value` compiles warning-free and a
base-typed reference observes the derived write.

**Inferred at design time, since confirmed post-implementation:**
- That the generated Kotlin setter export and the `KotlinMutableStateFlow<T>` subclass compile and
  round-trip on the real Kotlin/Native toolchain in the dylib-in-.NET-host topology. Evidence was
  strong before implementation (the export body is the already-shipped `addSetter` shape with
  `.value` appended to the access expression; the C# subclass is the already-shipped
  `KotlinStateFlow<T>` plus one delegate), but no konanc build had been run at design time. **Now
  verified:** `scripts/verify.sh` is green (konanc build via `packNuget`, plus `GeneratedBindingsCheck`,
  Roslyn warnings-as-errors, on the generated `Interop.cs`), so both the Kotlin export and the C#
  subclass compile and round-trip on the real toolchain.
- That a `.Value` write from an arbitrary .NET threadpool thread is safe. **Now verified** (see
  Threading above).

**Inferred, not spiked (unrelated to the two items above, still open):**
- Java's `setValue()` lowering, and ObjC/Swift/JS Export having no `MutableStateFlow` treatment.

### Spikes run for this ADR

```
$ cd <mktemp -d>/probe && dotnet new classlib && cat > P.cs   # override adding a setter
$ dotnet build -v q --nologo
P.cs(11,52): error CS0546: 'Derived1<T>.Value.set': cannot override because
             'Base<T>.Value' does not have an overridable set accessor
```

```
$ # same file, `public new T Value { get => base.Value; set => _write(value); }`
$ dotnet build -v q --nologo
Build succeeded.          # zero warnings
$ dotnet run               # console variant: d.Value = 42; Base<int> asBase = d;
derived=42 base=42
```

(`dotnet --version` = `10.0.300`; the language rule exercised is not version-specific.)

**Not spiked at design time:** anything requiring the Kotlin/Native toolchain (konanc build cost),
consistent with ADR-026/065/068, which likewise relied on the walking-skeleton integration test as
the real ABI validator. That toolchain run happened during implementation instead, via
`scripts/verify.sh` (`packNuget` runs konanc; `GeneratedBindingsCheck` compiles the generated C#
with warnings as errors), so the claim is confirmed, not merely inferred, as of this ADR's
Accepted status.

## Consequences

### ROADMAP correction (do this)

ROADMAP.md:115 and ADR-065's "Deferred" bullet both state that a settable `.Value` "needs a C#→Kotlin
value-setter export … which is a Phase 7 bidirectional concern". **That rationale is wrong** and must
be corrected when this ships: the write is ordinary forward-direction parameter marshalling using
machinery that has been in the repo since ADR-030. The item belongs in Phase 6 next to its ADR-065 /
067 / 068 siblings, not behind Phase 7. Nothing in Phase 7 (ADR-036/037/039) is a prerequisite.

### New / changed nodes

- `CirTypeMapping.kt`: `MUTABLE_STATE_FLOW_TYPES` / `READ_ONLY_STATE_FLOW_TYPES`; `STATE_FLOW_TYPES`
  becomes their union so no existing call site changes. `CollectionHelperTracker` gains
  `needsMutableStateFlow`.
- `CirModel.kt`: `CirProperty` / `CirMethod` gain `isMutableStateFlow: Boolean` and
  `stateFlowSetValueNativeName: String`; `CirFlowHelper` gains `includesMutableStateFlow`.
- `CirFlowRenderer.kt`: emit `KotlinMutableStateFlow<T>` after `KotlinStateFlow<T>`; the state-flow
  property/method renderers construct it (with the third `Action<T>` argument) when the flag is set.
- `CirClassRenderer.kt`: emit the `Native_Set{X}Value` `[DllImport]` alongside the existing
  `Native_Get{X}Value` when `isMutableStateFlow`.
- `ClassExports.kt`: inside the existing `if (isStateFlowProperty)` / `if (isStateFlowMethod)`
  blocks, add a `set_value` export when the declared type is in `MUTABLE_STATE_FLOW_TYPES`
  (`buildStateFlowSetValuePropertyBody` / `…MethodBody`, siblings of the existing
  `buildStateFlowValue*Body` at `:513-543`).
- `ForwardAbiContract`'s duplicate-export fail-fast already covers the new export name; no new
  collision class is introduced (`…_set_{prop}_value` cannot collide with an ordinary `var` setter's
  `…_set_{prop}`).

### Behavioural notes for docs (`coroutines-and-flow.md`)

- `.Value = x` is **conflated by `equals`**: assigning a value equal to the current one is a no-op
  and emits nothing to collectors. Verified from kotlinx source; surprising to a C# developer who
  expects a property setter to always "do something".
- `.Value = f(.Value)` is **not atomic** (two crossings). Atomic update needs the deferred
  `CompareAndSet`/`Update`.
- The setter can throw `KotlinException` (ADR-024 family), because the Kotlin setter calls
  `Any.equals` on the previous value.
- Writing an object element transfers the *Kotlin* object behind the C# wrapper's handle; the C#
  wrapper's own lifetime (ADR-005 new-wrapper-per-access, `IDisposable`) is unchanged and the caller
  still owns it.

### v1 scope

- `MutableStateFlow<T>` as a **public class property** → `KotlinMutableStateFlow<T>` with settable
  `.Value`.
- `MutableStateFlow<T>` as a **non-suspend function return** → the same.
- Element types `T`: **primitives** (the `PrimitiveKind` set `ForwardPropertyKotlinEmitter` already
  handles), **`String`**, and **object types**. This is the minimum that forces every marshalling
  seam to exist on the write path: `Int` needs no conversion, `String` needs conversion, an object
  needs handle unwrapping.
- Setter exception propagation via `errorOut` → `KotlinException` (ADR-024/030 family).
- Everything inherited from ADR-065/067/068 on the read/collect side is unchanged.

### Deferred (each becomes its own ROADMAP sub-item)

- **`CompareAndSet(T expect, T update)`** and **`Update(Func<T,T>)`**: Alternative 4. Additive and
  non-breaking. `Update` is pure C# once `CompareAndSet` exists (Kotlin's own `update` is exactly
  that retry loop).
- **`Emit`/`TryEmit`/`ReplayCache`/`SubscriptionCount`**: the `MutableSharedFlow` half of
  `MutableStateFlow`'s supertype list. SKIE exposes all of them; they belong with the deferred
  `SharedFlow<T>` mapping (ROADMAP.md:108), not here.
- **Nullable element writes** (`MutableStateFlow<T?>`): a nullable *primitive* element needs the
  two-export `NullableDispatch` shape (verified, `ForwardPropertyPlanner.kt:124-134`); a nullable
  *reference* element needs only a nullable parameter. ADR-067 shipped the nullable **read**; the
  write is deferred. Until then, a declared `MutableStateFlow<T?>` keeps ADR-067's read-only
  `KotlinStateFlow<T?>` mapping (no `KotlinMutableStateFlow` is generated); it must **not** silently
  degrade to a non-nullable write.
- **Nullable member writes** (`MutableStateFlow<T>?`): the Kotlin body would be
  `obj.x?.value = value`, a silent no-op when absent. Deferred rather than shipping a write that
  can silently do nothing.
- **`suspend fun` returning `MutableStateFlow<T>`** (ADR-068's variant): its receiver is a runtime
  flow handle, not the owner handle, so it needs Alternative 3's handle-keyed generic setter (or a
  per-element-type monomorphic shared export such as `nuget_mutablestateflow_set_value_int`). A
  `suspend fun` returning `MutableStateFlow<T>` keeps ADR-068's read-only `KotlinStateFlow<T>`
  mapping in the meantime.
- **Enum element types** (`MutableStateFlow<SomeEnum>`): out of scope in **both** directions. Note
  a pre-existing gap: `NugetMarshal.FromHandle<T>` has no enum branch and would fall through to its
  `Activator.CreateInstance` route (verified, `CirMarshalRenderer.kt:104-259`), so
  `StateFlow<SomeEnum>` is already unsupported/untested on the ADR-065 **read** path. Fixing that is
  its own item; this ADR does not make it worse.
- **Reassigning the whole flow member** (`var x: MutableStateFlow<Int>` in Kotlin →
  `tracker.X = otherFlow` in C#): a `StateFlow` in an input parameter position, already deferred by
  ADR-065 / ROADMAP.md:119.
- **`INotifyPropertyChanged` adapter**: unchanged, still deferred (ROADMAP.md:120).

### Proposed `test-library/` fixture additions

Extend `CatMoodTracker` (it already owns the StateFlow story, and having the read-only and mutable
shapes side by side in one class is exactly the contrast the mapping turns on):

```kotlin
// --- ADR-071: MutableStateFlow<T> declared PUBLICLY -- settable .Value from C#. Contrast with
// [mood]/[energyLevel] above, which are MutableStateFlow-backed but declared as read-only
// StateFlow views and must keep their get-only .Value. ---

/** MutableStateFlow<Int> -- primitive element, no conversion at the write seam. */
val treatCount: MutableStateFlow<Int> = MutableStateFlow(0)

/** MutableStateFlow<String> -- needs conversion (string marshalling) at the write seam. */
val collarColour: MutableStateFlow<String> = MutableStateFlow("red")

/** MutableStateFlow<Cat> -- object element; crosses as a handle in both directions. */
val favouriteToy: MutableStateFlow<Cat> = MutableStateFlow(Cat("Mittens"))

/** MutableStateFlow<T> as a non-suspend function return, sharing [treatCount]'s storage so a
 *  write through one surface position is observable through the other. */
fun treatJar(): MutableStateFlow<Int> = treatCount

/** Kotlin-side read-back: proves a C# write really landed in Kotlin, not just in a C# cache. */
fun treatsGivenSoFar(): Int = treatCount.value

/**
 * An element type whose `equals` throws, so the Kotlin `value` setter itself throws
 * (MutableStateFlow conflates by Any.equals -- StateFlow.kt:332). Forces the ADR-030 errorOut
 * path on the setter export to be genuinely reachable rather than defensive-only.
 */
class Grudge(val reason: String) {
  override fun equals(other: Any?): Boolean = error("Cats never forgive: $reason")
  override fun hashCode(): Int = reason.hashCode()
}

val grudge: MutableStateFlow<Grudge> = MutableStateFlow(Grudge("the vet"))
```

### Expected C# consumer API (the failing-test contract)

`IntegrationTests/MutableStateFlowTests.cs`:

```csharp
// 1. Settable .Value -- primitive element, no conversion
using var tracker = new CatMoodTracker("Mylo");
tracker.TreatCount.Value = 7;
Assert.Equal(7, tracker.TreatCount.Value);
Assert.Equal(7, tracker.TreatsGivenSoFar());     // the write really landed in Kotlin

// 2. Settable .Value -- string element, needs conversion
tracker.CollarColour.Value = "tartan";
Assert.Equal("tartan", tracker.CollarColour.Value);

// 3. Settable .Value -- object element, crosses as a handle
using var mouse = new Cat("Mouse");
tracker.FavouriteToy.Value = mouse;
using var toy = tracker.FavouriteToy.Value;      // fresh wrapper per read (ADR-005)
Assert.Equal("Mouse", toy.Name);

// 4. A write is observed by a live collector (hot, replay-1, conflated)
var seen = new List<int>();
var cts = new CancellationTokenSource();
tracker.TreatCount.Value = 3;
await foreach (var n in tracker.TreatCount.WithCancellation(cts.Token))
{
    seen.Add(n);
    cts.Cancel();                                 // StateFlow never completes on its own
}
Assert.Equal(3, seen[0]);

// 5. Both surface positions share storage
tracker.TreatJar().Value = 11;
Assert.Equal(11, tracker.TreatCount.Value);

// 6. It IS-A KotlinStateFlow<T> / KotlinFlow<T> / IAsyncEnumerable<T>
KotlinStateFlow<int> ro = tracker.TreatCount;
KotlinFlow<int> flow = tracker.TreatCount;
IAsyncEnumerable<int> seq = tracker.TreatCount;
Assert.Equal(tracker.TreatCount.Value, ro.Value); // base-typed read sees the derived write

// 7. A read-only StateFlow member is STILL get-only -- this must not compile:
//    tracker.Mood.Value = "zoomies";             // CS0200 (no setter)
//    asserted by a compile-time-negative note in the test file, not a runtime assert.

// 8. Setter exception propagation (ADR-024/030 family)
using var g = new Grudge("the carrier");
Assert.Throws<KotlinInvalidOperationException>(() => tracker.Grudge.Value = g);
// Corrected post-implementation: `KotlinIllegalStateException` does not exist in this codebase.
// kotlin.IllegalStateException maps to KotlinInvalidOperationException under ADR-029, which is
// what the shipped test (IntegrationTests/MutableStateFlowTests.cs) asserts.

// 9. Write from an arbitrary .NET threadpool thread (pins the one inferred threading claim)
await Task.Run(() => tracker.TreatCount.Value = 99);
Assert.Equal(99, tracker.TreatCount.Value);

// 10. After Dispose the property getter throws (parity with ADR-065)
tracker.Dispose();
Assert.Throws<ObjectDisposedException>(() => { var _ = tracker.TreatCount; });
```

Item 7 is worth writing as an actual commented-out line with the expected compiler error, because the
whole point of the declared-type keying is that `StateFlow`-declared members do **not** gain a setter.
