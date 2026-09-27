# ADR-171: Value classes at erased generic positions cross as the boxed Kotlin value, through a `Boxers` table and a per-value-class box/unbox export pair

## Status

Accepted (2026-09-27)

## Context

A Kotlin `value class` renders as a C# `readonly record struct` (ADR-014, ADR-035). At an ordinary
position it crosses as its underlying wire value and Kotlin re-wraps it (ADR-077), and as a
collection component it is projected to its underlying per element before `NugetMarshal.Wrap<T>` is
ever instantiated (ADR-081). Neither route reaches an **erased** generic position, where the C#
side only has `T` and the Kotlin side only has `Any?`:

- the ADR-147 generic class (`Box<T>`, `Slot<T>`, `Crate<T>`): the constructor and every `T`
  parameter go through `NugetMarshal.Wrap<T>`, every `T` result through `NugetMarshal.FromHandle<T>`;
- a lambda (`KotlinFunc`/`KotlinAction`, and the suspend `KotlinSuspendFunc`/`KotlinSuspendAction`):
  each argument through `Wrap<T{i}>`, the result through `FromHandle<TResult>`;
- the generic-return route (`fun chartBox(): Box<ChartId>`), whose `.Value` reads through
  `FromHandle<T>`.

ROADMAP Phase 4 line 29 recorded the write half for a reference underlying. Research
(`docs/research/roadmap/wrap-value-class-arg.md`) found the defect is wider, on both halves and for
every underlying kind (all verified by reading at `be64fbf9`):

- **Write.** `Wrap<T>` (`cir/CirMarshalRenderer.kt:306-340`) dispatches on `IntPtr`, `string`, the
  primitives and `char`, then `value is INugetHandle`, then throws `NotSupportedException`. A record
  struct matches none of those, whatever its underlying: `ChartId` (String), `Dosage` (Double; the
  record struct is hand-written, `cir/CirObjectRenderer.kt:38`, so it is not a `double`),
  `Temperament` (enum), `ChartRef` (object). `new Box<ChartId>(new ChartId("C-1"))` compiles
  against today's fixtures and throws at the argument. (The throw is inferred from reading, not
  run.)
- **Read.** `FromHandle<T>` falls through to `Materialize<T>` (`CirMarshalRenderer.kt:123-127`), a
  `Factories[typeof(T)]` lookup, and `factoryEntries` (`cir/CirTranslator.kt:979-1010`) registers
  no value class, on the stated premise that a value class "is not a closed type reachable from a
  handle". The spike below shows that premise is false.
- **Nullable read.** `FromHandle<ChartId?>`'s `Nullable` block (`CirMarshalRenderer.kt:137-`) only
  dispatches primitives, and `Materialize<ChartId?>` looks up `typeof(Nullable<ChartId>)`, which
  misses a `typeof(ChartId)` entry (verified by spike).
- **Lambda arm unreachable.** `csTypeArgument` (`cir/CirTypeMapping.kt:436-468`) admits only names
  in `exportedTypes` (`cir/CirTranslator.kt:133-143`), and `NugetProcessor.kt:1257` filters value
  classes out of `classes` into `rootValueClasses` (`:1259-1264`). So `val onChart: (ChartId) ->
  String` is skipped named (`SKIPPED_UNSUPPORTED_PROPERTY`, `cir/CirClassTranslator.kt:921-941`)
  instead of reaching `Invoke`. The gate is verified by reading. The diagnostic text is inferred,
  because no Tier 1 cell covers it.

Build-time refusal is not available. `Box<T>` is an open C# generic, and no C# constraint excludes
a record struct, so the only fix that removes the runtime throw is to make the crossing work.

### The load-bearing constraint: Kotlin needs the boxed value, never the raw underlying

The erased Kotlin halves pass the dereferenced handle straight into the generic slot:
`nuget_func1_invoke` does `arg0?.asStableRef<Any>()?.get()` and then `fn.invoke(param0)`
(`nuget-runtime/.../NugetRuntime.kt:322-334`, verified by reading). **Verified by spike**
(Kotlin/Native 2.4.10, the repo's version per `gradle/libs.versions.toml:2`, mingwX64 executable,
scratch directory, `kotlinc-native spike.kt -o spike`):

```kotlin
value class Name(val s: String) { init { require(s.isNotEmpty()) { "empty" } } }
value class Id(val v: Int)
class Box<T>(val value: T)
// (Name) -> String cast to Function1<Any?, Any?> and invoked with a boxed Name, then with "Rex";
// () -> Name read through Function0<Any?>; (Id) -> Int with Id(41) and with 41;
// Box<Any?>("raw") as Box<Name> then .value.s; (Name?) -> String with null and with a Name.
```

```
1 boxed: got Rex
2 raw threw: kotlin.ClassCastException: class kotlin.String cannot be cast to class Name
3 return class: Name isString=false isName=true
4 boxed Id: 42
4 raw Int threw: kotlin.ClassCastException: class kotlin.Int cannot be cast to class Id
5 box raw threw: kotlin.ClassCastException: class kotlin.String cannot be cast to class Name
5b boxed: ok
6 any is Name: true cast s=Z
7 null: k null
7 boxed: k Q
```

So the tempting one-line fix, where `Wrap<T>` unwraps `.Value` and reuses `nuget_wrap_string`,
compiles and crosses, then throws `ClassCastException` inside the Kotlin callee. On the lambda
route that happens in an export with no error slot. That is inferred to terminate the host: the
runtime's own comment at `NugetRuntime.kt:327-330` records it for an NPE, and it was not
re-measured here. On the generic-class route, the raw value is stored silently and only throws at
the first Kotlin use. Going the other way, a generic slot hands back a boxed `Name`, so the read
needs a Kotlin-side unbox. A null value class is plain `null` in the slot, so `IntPtr.Zero` stays
the in-band null (ADR-083). The enum-underlying and object-underlying cases were not spiked. They
are inferred to behave the same way, because Kotlin boxes every inline class at a generic
position regardless of its underlying.

## Alternatives Considered

### 1. A `Boxers` table beside `Factories`, plus a Kotlin box/unbox export pair per value class (chosen)

One statically written `NugetMarshal.Boxers` line per value class on the write side, one
`Factories` line per value class on the read side, and two new Kotlin exports per value class.
Write and read sit in the same place and follow the same ADR-094 static-table pattern. The public
record struct header does not change. Details under Decision.

### 2. An internal `INugetValueClass` interface explicitly implemented by each record struct

This is the `INugetHandle` precedent: `Wrap<T>` would test `value is INugetValueClass v` and call
`v.Box()`. Verified to compile: a `public readonly record struct` may implement an internal
interface under net8.0 with warnings as errors, and the type test dispatches for `T = ChartId`,
`T = ChartId?` and `T = object`. Rejected for three reasons. It changes the public record struct
header, which 16 test lines across 9 files pin (verified by grep). It adds the interface to the
type's reflected `GetInterfaces()` list. And the read side needs a `Factories` entry either way,
because there is no instance to dispatch on, so it splits one mechanism across two places.

### 3. Unwrap to the underlying inside `Wrap<T>` and reuse the existing `nuget_wrap_*` boxes

Rejected by the spike above: Kotlin throws `ClassCastException` on the raw underlying.

### 4. Pin and close with a clearer runtime message

Rejected: `new Box<ChartId>(id)` would keep compiling and throwing, which is the defect itself, and
a build-time refusal is impossible on an open generic.

## Decision

### A shared predicate decides which value classes are declared at all

`forward/ForwardValueClassUnderlying.kt` classifies every declared value class's underlying once,
read by `valueClassEntries`, `ValueClassExports.kt` and `NugetProcessor` alike, into:

- **VALUE**: `String`, a primitive, an enum, or a nullable `String`/primitive underlying. The
  record struct is hand-written and runs `init` in C#.
- **REFERENCE**: an object handle, or a sealed base/eligible sealed interface (ADR-105). The
  record struct is positional (ADR-035); C# never runs `init`.
- **REFUSED**: `Char`, a plain interface, a nullable non-scalar, `Instant`, `Duration`, `Uuid`,
  `ByteArray`, a collection, another value class, or a non-exported type. The value class is not
  declared at all (`SKIPPED_UNSUPPORTED_TYPE` on the value class itself, a named skip on every
  member typed with it), replacing the previous `ERROR_INTERNAL_GENERATOR_FAILURE` abort.

Only VALUE and REFERENCE value classes exist on either wire; this ADR's box/unbox pair below is
scoped to those.

### Kotlin: a box/unbox export pair per exported value class

`exports/ValueClassExports.kt` emits two exports (plan origin `VALUE_CLASS_BOX`) for every
exported VALUE or REFERENCE value class, root or nested (ADR-134), named `<prefix>_box` /
`<prefix>_unbox` off the value class's own export prefix:

- **`<prefix>_box`**: takes the underlying wire value (parameter `unboxed`) plus the error slot
  and returns `NugetHandles.retain(V(lowered))`. The lowering is the one an ordinary value-class
  parameter already uses (`forward/ForwardKotlinPlanEmitter.kt:1129-1131` for the wire type,
  `:1322-1326` for `V(<underlying lowering>)`). It re-runs `init` inside the same error-slot `try`
  (ADR-033, ADR-077). An enum underlying takes its int ordinal and an object underlying takes a
  handle, exactly as on the ordinary route.
- **`<prefix>_unbox`**: takes a handle and an error slot, and returns
  `(handle.asStableRef<Any>().get() as V).<underlying>` through the result emission an ordinary
  value-class result already uses (`ForwardKotlinPlanEmitter.kt:188-215`).

No pair is minted for a VALUE class whose underlying is itself nullable, nor for a generic value
class, nor for an ineligible or out-of-scope sealed interface: those stay VALUE/REFERENCE for
every ordinary position, but have no crossing at an erased generic position (see Consequences).

No new wire shape is involved, only two new export shapes per eligible value class. Both go into
`ForwardAbiContract.kt` and through the ADR-117 C-symbol collision check: a user member literally
named `box`/`unbox` on the value class collides with these export names, and ADR-117 reports it
by owner.

A reference-underlying (REFERENCE) record struct is positional (ADR-035), so C# never ran `init`.
The box export is the first place it runs, as on the ordinary parameter route:
`new Box<WardBand>(new WardBand(new Patient("")))` throws the same `ArgumentException` that
`fun take(band: WardBand)` throws. (`CatId`, a VALUE class, cannot show this: its hand-written
constructor already runs `init` in C# before the box export is ever reached.)

### C#: the box/unbox DllImports live on the record struct, beside `CreateChecked`

`CreateChecked`'s DllImport is already rendered inside the record struct
(`cir/CirObjectRenderer.kt:129`, `renderDllImport(cls.constructorNativeImport(ctor))`, verified by
reading). The box/unbox DllImports go beside it as private externs. The tables in `NugetMarshal`
cannot reach a private member, so each record struct also gains two `internal static` helpers,
`NugetBox(V)` and `NugetUnbox(IntPtr)`. The first calls the box export and throws via
`NugetErrorNative.BuildException(error)`, the same error path `CreateChecked` uses. The second calls
the unbox export, disposes the incoming handle in a `finally`, and returns `new V(underlying)`.
`Factories` (below) calls `NugetUnbox` directly; it does not go through `Wrap<T>`/`FromHandle<T>`
a second time.

### C#: `Wrap<T>` gains one branch above the `INugetHandle` tail, keyed on the runtime type

```csharp
internal static readonly Dictionary<Type, Func<object, IntPtr>> Boxers = new()
{
    [typeof(global::Clinic.ChartId)] = static v => global::Clinic.ChartId.NugetBox((global::Clinic.ChartId)v),
    // one statically written line per value class
};

internal static IntPtr Wrap<T>(T value, out bool owned)
{
    owned = false;
    if (value == null) return IntPtr.Zero;
    // ... unchanged primitive / string / char / IntPtr branches (owned = true) ...
    if (Boxers.TryGetValue(((object)value).GetType(), out Func<object, IntPtr>? box))
    {
        IntPtr handle = box(value!);   // owned stays false until this returns
        owned = true;
        return handle;
    }
    if (value is INugetHandle wrapper) return wrapper.Handle;   // untouched
    throw new NotSupportedException(...);                       // untouched
}
```

The lookup uses the **runtime** type, not the normalised `typeof(T)`. That matches the tail's
runtime `is INugetHandle` test (a `Box<object>` holding a `Cat` already works), and one lookup
covers `T = ChartId`, `T = ChartId?` (a boxed `Nullable<ChartId>` with a value reports `ChartId`)
and `T = object`. **Verified by spike** (scratch console project, compiled for net8.0 with
`TreatWarningsAsErrors`, run on net10.0 because the machine has no net8.0 runtime):

```
T=ChartId: 1003
T=ChartId?: 1004
T=object: 1005
T=ChartId? null: 0
```

`owned` is an `out` parameter and must keep reading `false` until the box export actually returns:
setting it `true` before the call would leave it `true` over `IntPtr.Zero` if the box export
throws (a failed `init`, see above), and the caller's `finally` would then hand that zero pointer
to `nuget_dispose`, which NPEs uncatchably. Once set, the box is minted per call and is the
caller's to dispose (ADR-099). A `Boxers` line is a statically written lambda over a named method,
so it is trimmer- and AOT-safe by the same argument ADR-094 makes for `Factories` (inferred; the
ADR-094 publish gate is what proves it).

The `INugetHandle` tail and its message are not touched. They belong to the interface-identity item
(ROADMAP line 69, `docs/research/roadmap/generic-route-fromhandle-interface-identity.md`). This ADR
lands first. That item then rebases onto one extra branch above its tail, one line in
`Materialize<T>`, and the value-class arm of `factoryEntries`, which are the three functions the
two items share.

### C#: `Factories` registers every eligible value class, and `Materialize<T>` normalises `Nullable`

`factoryEntries` gains a value-class arm for every VALUE or REFERENCE value class:
`[typeof(global::Clinic.ChartId)] = static handle => global::Clinic.ChartId.NugetUnbox(handle)`,
calling `NugetUnbox` directly rather than routing back through `Wrap<T>`/`FromHandle<T>`. It is
keyed by the consumer's own `typeof(...)` spelling, with a nested value class spelled
`Outer.Inner` in the way the sealed `isNested` branch does it (`cir/CirTranslator.kt:997-1004`).
The doc comment at `:979-980` is corrected.

A primitive- or enum-underlying record struct's constructor re-runs `CreateChecked`, so the read
re-runs `init`. That follows the ADR-081 collection-read precedent. Kotlin already validated the
value, so it cannot throw, and the cost is one P/Invoke.

`Materialize<T>` looks up `Nullable.GetUnderlyingType(typeof(T)) ?? typeof(T)`. **Verified by
spike**: without it, `Materialize<ChartId?>` throws
`NotSupportedException System.Nullable`1[Probe.ChartId]`; with it, it returns
`ChartId { Value = from9 }`.

### Lambdas admit a value class as a payload or result

`csTypeArgument` admits a value class by reading a per-catalog set of value classes that have a
box/unbox pair (not `exportedTypes`, which still excludes every value class for the unrelated
reasons ADR-066 filters on), and spells it through `qualifiedElementCsType`
(`cir/CirTypeMapping.kt:339-`). That speller is inferred to handle a value class correctly, since
it spells classes by package and nesting and not by kind. Use-site nullability rides the existing
`?` suffix. So `val onChart: (ChartId) -> String` renders
`KotlinFunc<global::Clinic.ChartId, string>`, `() -> ChartId` renders
`KotlinFunc<global::Clinic.ChartId>`, and `(ChartId?) -> Unit` renders
`KotlinAction<global::Clinic.ChartId?>`. The Invoke body needs no value-class code, because
`Wrap<T>` and `FromHandle<T>` above cover it. The spike's line 3 confirms that
`nuget_func0_invoke` retains a boxed `ChartId` for `() -> ChartId`. The suspend lambdas pick this
up through the same two helpers. A lambda over a value class with no pair (REFUSED, or a VALUE
class excluded from the pair by the previous section) is refused by name
(`SKIPPED_UNSUPPORTED_PROPERTY`), the same diagnostic an unnameable type argument already uses; a
generic-class `T` has no such build-time gate, since `Box<T>` is an open C# generic, so
`new Box<V>(v)` for a pairless `V` still compiles and throws `NotSupportedException` at the
argument, exactly as every unsupported `T` did before this ADR.

A sealed arm's own lambda property and a top-level function's lambda return admit a value-class
payload or result the same way a class-method lambda property does; neither needed a mechanism of
its own.

`KotlinFunc.Invoke` and `KotlinAction.Invoke` (`cir/CirFunctionRenderer.kt:74-90` and the action
twin) box every argument, call, and dispose the owned boxes, all in straight-line code with no
`try/finally`. A value-class argument makes a box throw for the first time, for example when a
`WardBand` wrapping a nameless `Patient` fails `init` in the box export. At arity 2 or more, the
boxes already minted for earlier arguments would leak. The body therefore moves the boxing and the
call inside `try`, and the owned-dispose loop into `finally`, with each box local initialised to
`IntPtr.Zero` and `owned` to `false` before the `try`. That is the declaration-then-assign shape
`ForwardCirPlanProjection.typeParameterPrelude` already uses. The suspend `InvokeAsync` bodies mint
every argument box before `GCHandle.Alloc`, inside the same `try`, so a box-export throw on a later
argument still unwinds through the earlier boxes' `finally` disposal rather than leaking them past
the point the continuation is parked.

### What the consumer sees

```csharp
var id = new ChartId("C-1");
using var box = new Box<ChartId>(id);
Assert.Equal(id, box.Value);                       // round trip, was NotSupportedException

Assert.ThrowsAny<ArgumentException>(
    () => new Box<WardBand>(new WardBand(new Patient(""))));  // init runs at the boundary

using KotlinFunc<ChartId, string> onChart = courier.OnChart;       // was skipped named
Assert.Equal("chart C-1", onChart.Invoke(id));
```

`new Slot<ChartId?>(null)` — constructing a generic class with a `null` value-class argument — is
deferred to the generic-ctor-nullable-arg item stacked on this one, and is not shown above.
Reading a nullable value-class `T` back out (`.Value`, `.Current`) already works today, through the
`Materialize<T>` normalisation below.

## Consequences

- `Box<V>`, `Slot<V>`, `Crate<V>` and any ADR-147 generic class work for every declared VALUE or
  REFERENCE value class, in both directions, with no fixture change: `ChartId`, `CatId`, `Dosage`,
  `Temperament`, `ChartRef`, `WardBand` and the sealed-underlying `ObservationResult` all exist. A
  nullable `T` read (`.Value`, `.Current`) works too; constructing with a `null` value-class
  argument is deferred (see above). The generic-return route's `Box<ChartId>` read works too.
- Lambdas and suspend lambdas over a VALUE or REFERENCE value class are now emitted, at a
  class-method lambda property, a sealed arm's own lambda property, and a top-level lambda return,
  where they were skipped named before. This is a new public surface, not a behaviour change of an
  existing one.
- A value class whose underlying is `Char`, a plain interface, a nullable non-scalar, `Instant`,
  `Duration`, `Uuid`, `ByteArray`, a collection, another value class, or a non-exported type
  (REFUSED) is now a named `SKIPPED_UNSUPPORTED_TYPE` skip, with a named skip on every member typed
  with it, instead of aborting the whole build with `ERROR_INTERNAL_GENERATOR_FAILURE`. This applies
  to every declared value class, not only one reaching an erased generic position.
- Two new Kotlin exports per VALUE or REFERENCE value class, whether or not anything reaches an
  erased position. This is a small, unconditional ABI growth, recorded in `ForwardAbiContract`. A
  user member literally named `box` or `unbox` on the value class collides with these export names;
  ADR-117 reports the collision by owner.
- Each such record struct gains two `internal static` helpers, `NugetBox` and `NugetUnbox`. Because
  `Interop.cs` compiles into the consumer's own assembly (ADR-001), they appear in the consumer's
  completion list after `ChartId.`, the same visibility ADR-169 accepted for the internal handle
  constructor. The public API and the record struct header are unchanged.
- `Materialize<T>`'s `Nullable` normalisation also benefits any future nullable `T` with a factory.
  Today no `T?` of a reference wrapper reaches it as `Nullable<>`.
- `KotlinFunc`/`KotlinAction` `Invoke` bodies change shape (a `try/finally`). Tier 1 pins on their
  text move.
- New `LeakTests/LiveHandleTests.cs` rows beside Row 10/10a (`:1380-1410`):
  - `WrapValueClass_GenericClassArgument_ReturnsToBaseline` is `new Box<ChartId>(id)` plus
    `.Value`, net zero.
  - `WrapValueClass_GenericClassArgumentInitThrows_ReturnsToBaseline` is the `WardBand` init-throws
    construction, net zero: nothing is minted when the box export fails.
  - `WrapValueClass_LambdaSecondArgumentThrows_ReturnsToBaseline` is
    `KotlinFunc<ChartId, WardBand, string>.Invoke(ok, nameless)`, net zero only with the `finally`
    above.
- Deferred:
  - The legacy `fun <T>` route's write cast `((INugetHandle)value!).Handle`
    (`cir/CirFunctionTranslator.kt:916`, `:920`) still throws `InvalidCastException` for a value
    class. Whoever reroutes it (the line-69 item plans `HandleOf`) should route it through
    `Wrap<T>` instead so this branch covers it, and must add an owned-dispose after the native call
    at the same time, or it leaks one StableRef per call. Its read, `Materialize<T>`, is fixed here
    for free.
  - Whether the `Flow<V>`/`StateFlow<V>` element route admits a value class was not checked. If it
    does, its default `FromHandle<T>` read (`cir/CirFlowRenderer.kt:85`, `:110`) is fixed by the
    same `Factories` entry.

## Verified claims

- Kotlin/Native 2.4.10: an erased `(Name) -> R`, `(Id) -> R` or `Box<Name>` slot needs the boxed
  value class, and the raw underlying throws `ClassCastException`. `() -> Name` hands back a boxed
  `Name`, and a null value class is `null`. The output is quoted in Context.
- C# (compiled for net8.0 with warnings as errors, run on net10.0): a runtime-type `Boxers` lookup
  dispatches `T = ChartId`, `ChartId?` and `object`, and returns `IntPtr.Zero` with `owned = false`
  for null. `Materialize<ChartId?>` misses a `typeof(ChartId)` entry unless normalised. A public
  record struct may implement an internal interface (Alternative 2).
- By reading at `be64fbf9`: the `Wrap<T>`/`FromHandle<T>`/`Materialize<T>`/`factoryEntries` shapes
  and line numbers cited above, the `exportedTypes` exclusion of value classes, the ordinary-route
  value-class lowering and result emission, `CreateChecked`'s DllImport rendered inside the record
  struct, and 16 test pins on the record struct header.

## Inferred claims

- `new Box<ChartId>(id)` throws `NotSupportedException` today. That comes from reading `Wrap<T>`;
  it was not run.
- An uncaught `ClassCastException` inside `nuget_func1_invoke` terminates the host. That comes from
  the runtime's comment about the NPE case.
- Enum-underlying, object-underlying and nested value classes behave like `Name`/`Id` at an erased
  Kotlin position.
- `qualifiedElementCsType` spells a value class correctly as a lambda type argument, and the
  skipped-lambda diagnostic text for a value-class type argument today.
- `Boxers` is trimmer- and AOT-safe (the ADR-094 argument, and no publish run).
- The suspend lambda `InvokeAsync` bodies dispose argument boxes after a synchronous native call,
  as the plain `Invoke` does.
- The box/unbox export symbols derived from the value class's export prefix are collision-free
  (the ADR-117 check will say if not).
