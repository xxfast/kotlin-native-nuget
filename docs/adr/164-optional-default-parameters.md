# ADR-164: Default parameters: one widened signature with nullable-means-unset, replacing trailing omitting overloads

## Status

Accepted (2026-09-24)

## Context

[ADR-091](091-constructor-default-parameters.md) and [ADR-096](096-function-default-parameters.md)
surface Kotlin default parameters to C# as `@JvmOverloads`-style trailing-omitting overloads. That
only drops arguments from the right. For `Config(id: Uuid = Uuid.random(), timeout: Duration =
1.minutes, retries: Int = 3, mode: Mode = Mode.Never)`, setting only `mode` from C# means passing
every earlier argument, which means reading them off a default instance first (issue #297). A C#
consumer expects `new Config(mode: Mode.Always)`.

ADR-091 rejected C# optional parameters because KSP exposes only `hasDefault: Boolean` and never the
default expression (**Verified** by ADR-091 against KSP 2.3.10 sources), so `int retries = 3` cannot
be generated. That objection does not apply if C# never carries the value, only "not set". ADR-091
also rejected the combinatorial named-subset overload set as exponential in C#. That objection does
not apply either if the combinatorics move to the Kotlin side of the boundary, where the compiler
resolves named arguments and evaluates the defaults it already owns.

Constraints, in force order:

- **Kotlin evaluates a default only at a call site that omits the argument.** There is no
  Kotlin/Native reflection route around that: `(::f).callBy(mapOf())` fails to compile on konanc
  2.4.10 with `unresolved reference 'callBy'` (**Verified** by spike, 2026-09-24; `callBy` is
  `kotlin.reflect.full`, JVM only). The compiler's `f$default` mask stub is an IR synthetic that
  source cannot name (**Inferred**). So a wrapper that forwards "only the set arguments" must
  contain one literal call per subset.
- **The expansion is cheap at a cap of 8.** A data class with 8 `Int` defaults and a `@CName`
  wrapper dispatching `when (mask)` over 256 named-argument arms compiled with `konanc 2.4.10
  -produce dynamic -opt` in 7.37 s versus 7.91 s for a one-arm baseline, and grew the dylib from
  586 240 B to 621 952 B (+35.7 KB; 16 arms: +17.5 KB) (**Verified** by spike, 2026-09-24, arm64
  macOS).
- **C# optional parameters must be trailing, and `null` cannot mean "unset" for a parameter that
  is already nullable in Kotlin.** A `readonly struct Optional<T>` with an implicit conversion from
  `T` resolves `F()` as unset, `F(owner: null)` and `F(null)` as set-to-null, `F(owner: "x")` as
  set; `Optional<int?>` behaves the same; `virtual`/`override` pairs both declaring `int? lives =
  null` compile; and a class with both `C(string)` and `C(string, int? lives = null)` resolves
  `new C("x")` to `C(string)` without CS0121 (**Verified** by spike, dotnet 10.0.300, 2026-09-24).
- **The nullable input encoding already exists for almost every type.** `nativeInputParameters`
  (`ForwardCallablePlanner.kt:2703-3086`) lowers `Nullable(inner)` for String/Uuid (null pointer),
  ObjectHandle/Interface/TypeParameter (null pointer), ByteArray, Collection, ValueClass, and the
  `${name}HasValue` BOOLEAN pair for Primitive/Char/Enum/Instant/Duration and primitive-underlying
  value classes; `loweredArgument` (`ForwardKotlinPlanEmitter.kt:1224-1288`) mirrors it. The
  route-agnostic `inputSkipReason` (`ForwardCallablePlanner.kt:3694-3776`) declines nullable
  Callback (lambda), SpecializedProtocol (Flow, StateFlow, suspend lambda, sealed), BoundInterface
  and Unsupported (**Verified** in source).
- **Eight synthesis sites read a trailing count.** Top-level `:755-769`, extension `:777-799`,
  class `:1217-1227`, sealed subclass `:1338-1345`, object `:1467-1486`, constructor `:1604-1628`,
  companion `:1906-1911`, sealed base `:1949-1954`; `copy` at `:1629-1665` synthesizes nothing
  (**Verified** in source). The three flag readers (`defaultFlags`, `memberDefaultFlags`,
  `topLevelDefaultFlags`) are unchanged by this ADR; only what consumes the flags changes.
- **Overrides must match the base signature in C#.** `memberDefaultFlags` already walks
  `findOverridee()` to the root (`ForwardCallablePlanner.kt:1711-1716`, **Verified**), so an
  override's optional shape equals its base's.

Prior art: SKIE generates Kotlin overloads for Swift, caps at 5 defaulted parameters (31 extra
functions), is opt-in and calls its own exponential approach deprecated
(https://skie.touchlab.co/features/default-arguments, **Inferred** from docs). Kotlin/JS guards each
exported defaulted parameter with `if (x === VOID) x = <default>`, so `undefined` (not `null`)
means unset and a nullable parameter can still receive an explicit null (**Inferred** from compiler
behaviour, not documented on the interop page). This ADR is the C# analogue of the JS approach: one
signature, a presence sentinel distinct from null, with `Optional<T>` standing in for the
`undefined` that C# lacks.

## Alternatives Considered

### 1. One widened signature: nullable-means-unset, `Optional<T>` for already-nullable, Kotlin-side `when (mask)` dispatch (chosen)

Every defaulted parameter with a routable nullable form renders as its C# nullable type with
`= null` when trailing; already-nullable ones render as `Optional<T> = default`; the wrapper
computes a presence mask and dispatches to one named-argument call per subset. Pros: any subset by
name in one call; one C# signature per callable, so no overload numbering churn; Kotlin still owns
every default expression; reuses the shipped nullable ABI. Cons: `2^d` Kotlin arms (bounded by a
cap); `Optional<T>` is a new generated type consumers meet in IntelliSense; a defaulted parameter
before a required one is "required but nullable", which is a convention to learn.

### 2. Keep ADR-091/096 overloads and add them beside the widened signature

Source-compatible with reflection over `GetConstructors()`. Rejected: every existing call site
(`Tally(3)`, `Tally(3, "kittens")`) already compiles against the widened signature (C# prefers
the candidate with no omitted optionals, so a real shorter constructor is also safe; **Verified**
by spike), so the overloads add only IntelliSense noise and keep the `_$n` numbering churn.

### 3. Named-subset C# overloads (ADR-091's Alternative 3)

`2^d` C# overloads. Rejected again: same-typed parameters collide (`Config(int)` for `retries`
and for a second `Int`), and the count lands on the consumer's IntelliSense.

### 4. `Optional<T>` for every defaulted parameter

Uniform; no "nullable means unset" convention. Rejected: `new Config(retries: 3)` must still work
without a wrapper for the surface to read as C#; `int?` is what a .NET developer already writes for
"maybe absent" on a value type.

### 5. Keep already-nullable defaulted parameters required

Smallest surface, no `Optional<T>`. Rejected: `String? = null` is the most common Kotlin default,
and it would be the one shape a caller could not omit.

## Decision

Replace the trailing-omitting overloads on all eight synthesis sites and on `copy` with one
widened signature per callable.

### The rule

For a planned entry with parameters `p1..pn`, let `D` be the set of parameters with a default
(from the route's existing flag reader). For each `p` in `D`, in declaration order:

1. If `p`'s Kotlin type is non-nullable and its `Nullable` form is routable
   (`inputSkipReason(Nullable(type)) == null`, **Verified** list in Context), `p` renders as the
   C# nullable form (`int?`, `string?`, `Mode?`, `TimeSpan?`, `DateTime?`, `Guid?`, `Cat?`,
   `IReadOnlyList<T>?`, `byte[]?`, value class `?`). `null` means unset.
2. If `p`'s Kotlin type is already nullable, `p` renders as `Optional<T>` where `T` is today's
   nullable C# type (`Optional<string?>`, `Optional<int?>`). `default` means unset; a `null` or a
   value converts through the implicit operator and means set.
3. If `p`'s type routes non-null but has no nullable encoding (lambda, suspend lambda), `p`
   renders as its nullable C# delegate form (`Action? cb = null`) behind a `${name}IsSet` BOOLEAN
   slot, the same mechanism as `Optional<T>`'s slot below; when unset, C# passes the static thunk
   with a zero context so no `GCHandle` is minted, and the Kotlin dispatch does not name `p`.
   **Verified** by the implementer's build: fixture `notify` (`test-library/.../issue297/Issue297Sample.kt`)
   widens `onDone: (String) -> Unit = {}` to `Action<string>? onDone = null` and runs the passed
   lambda (`Issue297Tests.Notify_PassedOnDone_RunsTheLambda`). A lambda a **constructor stores**
   past the call is the one carve-out from this rule: ADR-160 frees the per-call handle once the
   constructor returns, so a later invocation (`Button.Click()`) would run through a handle that
   no longer points at anything. `p` is therefore dropped from the C# signature entirely, the same
   as rule 4 below, rather than widened; Kotlin always evaluates its default (fixture `Button`,
   `Issue297Tests.Button_OnClickIsStored_SoItStaysOffTheConstructor`).
4. Otherwise (Flow/StateFlow, sealed, bound interface, unrouted: no non-null routing either) `p`
   is **dropped from the C# signature** when it sits in the trailing all-defaulted suffix; the
   Kotlin dispatch never names it, so Kotlin always evaluates its default. Rationale: this is
   exactly what binds today through the omitting overload, so it is behaviour-preserving.
   **Verified** by reading the fixtures: ADR-149's `hubWithEvents(settings: Settings = Settings(), events:
   Flow<Int>? = null)` (`test-library/.../issue131/HubSample.kt:62`) binds as `HubWithEvents()` and
   `HubWithEvents(Settings)` with the `events` arity absent, and ADR-115's `GroomingLog(note:
   String = "clean", @property:CatteryInternalApi ledger: String = "ledger-0001")`
   (`test-library/.../issue128/Issue128Sample.kt:65-70`) binds as `GroomingLog()` and
   `GroomingLog(string)` with `ledger` absent. An unroutable defaulted parameter that precedes a
   required one keeps today's outcome: the callable is unroutable and reports the same diagnostic
   it does now.
5. A widened `p` gets a C# default (`= null` / `= default`) if and only if every parameter after
   it is also widened. A widened `p` followed by a required one stays required-but-nullable.
6. **Cap**: at most 8 widened parameters per callable. If more qualify, the **last** 8 in
   declaration order widen and the rest stay required; a new WARNING diagnostic
   (`DEFAULT_PARAMETER_CAP_EXCEEDED`) names the parameters left required.

The full-signature entry is the only entry. No `_$n` synthesized numbers are produced for defaults
any longer; declared overload numbering (ADR-034/090/095) is untouched.

### ABI

- A widened non-nullable parameter uses the shipped nullable input encoding verbatim, through
  `nativeInputParameters(name, Nullable(type))` and `loweredArgument` (**Verified** the encoders
  exist for every type admitted by rule 1; **Inferred** that switching the public type to
  `Nullable` at the plan level is sufficient on every route, since `nativeInputParameters` is
  route-agnostic, but the top-level two-call comment at `ForwardCallablePlanner.kt:1988` concerns
  results, not inputs, and an implementer should confirm no input-side route special-cases
  nullable primitives).
- An `Optional<T>` parameter adds one BOOLEAN slot `${name}IsSet` immediately before that
  parameter's existing nullable encoding (so `owner: Optional<string?>` becomes `ownerIsSet:
  Boolean, owner: CPointer<ByteVar>?`; `n: Optional<int?>` becomes `nIsSet, nHasValue, n`). C#
  passes `owner.HasValue` and, when unset, the encoding's own null/`false` filler.
- `ForwardPublicParameter` gains `hasDefault: Boolean` and `CirParameter` gains
  `defaultValue: String?` (`"null"` / `"default"`), rendered as `= <defaultValue>`. `validate()`
  learns the `IsSet` role.

### Kotlin side

The wrapper binds every lowered argument to a local as today, then:

```kotlin
var mask = 0
if (retriesHasValue) mask = mask or 1
if (modeHasValue) mask = mask or 2
if (ownerIsSet) mask = mask or 4
val result = when (mask) {
  0 -> Config()
  1 -> Config(retries = retries)
  2 -> Config(mode = mode)
  3 -> Config(retries = retries, mode = mode)
  4 -> Config(owner = owner)
  // ...
  7 -> Config(retries = retries, mode = mode, owner = owner)
  else -> error("unreachable")
}
```

Bit `i` is the `i`-th widened parameter in declaration order. For a non-nullable widened
parameter the presence test is the encoding's own null/`HasValue` check; for `Optional<T>` it is
`IsSet`. Required parameters appear in every arm. A callable with zero widened parameters emits
today's single positional call unchanged. `copy` uses the same dispatch with `receiver.copy(...)`
as the target; unset means "the receiver's current value", which is Kotlin's own `copy` default.

### C# side

```csharp
public readonly struct Optional<T>          // once per generated assembly, in Interop.cs
{
    public bool HasValue { get; }
    public T Value { get; }
    private Optional(T value) { HasValue = true; Value = value; }
    public static Optional<T> None => default;
    public static implicit operator Optional<T>(T value) => new Optional<T>(value);
}

public Config(Guid? id = null, TimeSpan? timeout = null, int? retries = null, Mode? mode = null,
              Optional<string?> owner = default)
public Config Copy(Guid? id = null, TimeSpan? timeout = null, int? retries = null,
                   Mode? mode = null, Optional<string?> owner = default)
public string Book(string name, int? capacity, string city)      // middle default: required-but-nullable
```

```csharp
new Config(mode: Mode.Always);                       // Kotlin evaluates Uuid.random(), 1.minutes, 3, null
Registry.Describe("Momo");                           // owner unset  -> Kotlin default
Registry.Describe("Momo", owner: null);              // owner set to null
original.Copy(mode: Mode.Always);                    // every other field is the receiver's
Announcers.Book("Paws", null, "Colombo");            // capacity defaulted, positionally
```

### Overrides and containers

An `override` renders the same widened signature as its root overridee (`memberDefaultFlags`
walks to the root; **Verified**), so base and derived compile together (**Verified** by spike).
The ADR-096 interface-route exclusion no longer applies: its rationale was that a synthesized
overload added to a generated C# interface obliges every implementer to carry it, and this ADR
synthesizes no overloads. The interface declaration widens exactly like the class routes
(`IGreeter.Greet(string name, int? times = null)`), so interface and implementer signatures agree
and CS0535 cannot arise (**Verified** by the implementer's build: fixture `Greeter`/`Parrot` in
`test-library/.../issue297/Issue297Sample.kt:78-89` and
`Issue297Tests.Greeter_ThroughTheInterface_OmittedTimes_UsesTheInterfaceDefault`).

### CS0121 workaround for a leading widened integer parameter

`Interop.cs` compiles into the consumer, and every generated class carries an internal
`Settings(IntPtr)` handle constructor. Once the first public parameter widens to an integer
nullable (`Settings(int? level = null)`), `new Settings(3)` is ambiguous (CS0121): an `int`
converts implicitly to both `int?` and `IntPtr`. The implementation therefore emits one
delegating constructor, `public Settings(int level) : this((int?)level)`, only when the first
parameter is a widened integer type and every parameter after it is optional (**Verified** by
the implementer's build, 2026-09-24). The same `IntPtr` clash was seen in the #222-#224 bug stack
(the internal `IntPtr` constructor swallowing a positional `int`); no ADR number records it, so it
is described here. The constructor collision check
(`CirClassTranslator.kt:685-712`) needs no logic change; its hint's ADR-091 clause about a
synthesized omitting overload is removed, since a widened `Cat(string, int?)` no longer collides
with a real `Cat(string)`.

### Docs

`<param>` rendering (ADR-149/150) needs nothing: `CirDocRenderer.kt:30` keys on the bridge
parameter name, which is unchanged (**Verified** in source).

## Consequences

- `new Config(mode: Mode.Always)` and `original.Copy(mode: Mode.Always)` compile and Kotlin
  evaluates every other default. Every existing positional call site still compiles.
- Every `_$n` export previously synthesized for a default disappears; the shipped ADR-091/096
  fixtures and 22 test files pinning "omitting" overloads (11 Tier-1, 11 IntegrationTests, plus
  `GetConstructors()` pins in four files) are rewritten to the new shape. ADR-091 and ADR-096 become
  Superseded.
- A defaulted per-call lambda or suspend lambda widens to `Action? cb = null` behind an `IsSet`
  slot, minting no handle when unset (**Verified** by build, fixture `notify`). A lambda a
  constructor stores past the call is dropped from the C# signature instead, since ADR-160 frees
  the per-call handle before a later invocation could run it, so Kotlin always evaluates its
  default (**Verified** by build, fixture `Button`). A trailing defaulted Flow, sealed or
  bound-interface parameter is dropped from the C# signature and Kotlin evaluates its default
  (today's omitting-overload outcome, preserved); one preceding a required parameter leaves the
  callable unroutable as today.
- A class whose first parameter widens to an integer nullable with every later parameter
  optional gains one delegating `(int)` constructor, so `new Settings(3)` is not CS0121 against
  the internal `IntPtr` constructor (**Verified** by the implementer's build).
- Interface-route defaults widen like every other route; ADR-096's interface exclusion is
  retired (**Verified** by the implementer's build, `Greeter`/`Parrot`).
- The Kotlin named-argument call in each arm resolves against a real shorter Kotlin overload if
  one exists (`Foo(name)` beside `Foo(name, lives = 9)`), which is the same resolution the old
  omitting overload's positional call produced (pre-existing, **Inferred**).
- Beyond 8 defaults per callable, the earlier ones stay required with a WARNING.
- The fallback `Copy` built directly from constructor parameters (`CirClassRenderer.renderDataClassMethods`,
  previously also referenced from `CirNativeImports.kt:224`) had no Kotlin export behind it and is
  deleted; `Copy`'s widened signature is now built the same way every other member's is.
- `LeakTests/LiveHandleTests.cs` gains `WidenedDefaultConstructorAndCopy_ReturnsToBaseline` (`Config`/`Copy`
  through the `when (mask)` dispatch), `WidenedDefaultHandleParameter_UnsetAndSet_ReturnsToBaseline`
  (`greet`'s `cat` handle parameter, unset and set), and `WidenedDefaultLambda_UnsetAndSet_ReturnsToBaseline`
  (`notify`'s per-call lambda, unset mints no context, set registers and removes one), each returning
  the live handle count to baseline over 50 iterations.
- Consumers meet one new generated type, `Optional<T>`, only on already-nullable defaulted
  parameters.
- **Inferred claims an implementer may hit**: (1) that swapping the plan's public type to
  `Nullable(type)` reaches the same encoder on every route including the legacy top-level and
  extension ones; (2) that the `$default` IR stub is unreachable from source (only matters if
  someone wants to avoid the expansion); (3) the Kotlin/JS `VOID` sentinel description. Everything
  labelled Verified was either read at the cited lines or produced by the 2026-09-24 spikes recorded
  in `docs/research/roadmap/issue-297-optional-default-parameters.md`.
