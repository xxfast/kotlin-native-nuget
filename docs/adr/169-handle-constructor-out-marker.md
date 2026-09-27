# ADR-169: An `out` marker on the internal handle constructor, so no argument list can bind it

## Status

Accepted (2026-09-26)

## Context

`Interop.cs` compiles into the consumer's own assembly (ADR-001), so the generated `internal
X(IntPtr handle)` every class wrapper carries is not hidden from overload resolution: `internal` is
assembly-visible, not file-visible. ROADMAP line 26 named the loud subset of the consequence: a
single nullable-numeric constructor parameter collides with it outright.

```csharp
public Tag(char? initial) { /* ... */ }
internal Tag(IntPtr handle) { /* ... */ }
```

`new Tag('O')` is `CS0121`: a `char` literal converts to both `char?` (lifted) and `IntPtr`/`nint`
(`char` widens to `int`, then to `nint`), and neither conversion is better.

A spike (`docs/backlog/nullable-numeric-constructor-parameter-cs0121.md`, superseded here) found a
larger silent subset with no diagnostic at all, because C#'s better-conversion-target rule prefers
`nint` over `long`, `float`, `double`, and over any unsigned integral type: `new Circle(5)` for a
`Circle(double radius)` constructor does not error, it silently binds the internal handle
constructor and wraps stable-ref address `0x5`, corrupting or crashing on first member access. The
same silent bind reaches a required (non-nullable) `long`/`float`/`double`/`double?`/typed
`byte?`/`ushort?` parameter, a generic class's own type argument (`Box<long>(5)`,
`Box<double>(5)`), and, worse, `new Flag(5)` for a `Flag(bool value)` or `Flag(char value)`
constructor compiles at all today, where it should be `CS1503`.

A verified matrix over one class per C# primitive `T`/`T?`, each called with its own typed literal
and with a bare `5`, found: `CS0121` for `sbyte?`/`short?`/`int?`/`char?`; a silent handle bind for
`byte?`/`ushort?` (typed) and for `uint`/`uint?`/`long`/`long?`/`ulong`/`ulong?`/`float`/`float?`/
`double`/`double?` (bare `5`); and a wrong-code-accepted bind for `char`/`char?`/`bool`/`bool?`
given `5`. Every other primitive resolves to the public constructor already.

## Alternatives Considered

### 1. `out` marker: `internal X(IntPtr handle, out NugetHandleTag tag)` (chosen)

An `out` parameter has no implicit conversion and cannot be supplied by a literal, a `default`
expression, or any ordinary argument: the only legal call is `new X(h, out _)` (or a named `out`
variable), which nothing but generated code ever writes. Verified over the same matrix: 0 silent
handle binds, 44 of 44 public binds, and the 4 previously-wrong-code cells (`char`/`bool` given a
bare `5`) now correctly fail as `CS1503`. A follow-up two-argument probe (`A2(5, default)` for a
class with a real `(long, int)` or `(long, Optional<string>)` constructor) also resolves PUBLIC.

### 2. By-value marker: `internal X(IntPtr handle, NugetHandleTag _)`

Closes the one-argument matrix identically (0 handle binds), but opens a new two-argument hole: a
class with a real `(T a, T2 b)` constructor now has `new A(5, default)` silently bind the marker
constructor, since `default` converts to any value type including the marker struct. `out` cannot
be supplied by `default` at all, so this hole does not exist there. Rejected.

### 3. An optional marker: `NugetHandleTag _ = default`

Fixes only the CS0121 rows (18 of the matrix): a caller can still omit the optional argument, so
every silent single-argument bind (`FoodBowl(5)`, `Circle(5)`, ...) is unaffected. Rejected.

### 4. `[OverloadResolutionPriority(-1)]` on the handle constructor

Verified on real projects: reduces the silent-bind count to 4 (`char`/`bool` given `5`) on
`net10.0`, but the attribute requires C# 13 (LangVersion 13); building the generated `Interop.cs`
at `net8.0`'s default LangVersion 12 (`GeneratedBindingsCheck`'s own target) fails every use with
`CS9202: Feature 'overload resolution priority' is not available in C# 12.0`, bricking every
net8.0 consumer. Rejected: the fix cannot ship if it breaks the TFM floor.

### 5. A static `FromHandle(IntPtr)` factory instead of a constructor

Rejected by reasoning, not spiked: a derived or sealed-arm constructor still has to chain to some
*constructor* on its base, so a colliding constructor would still need fixing underneath; a
per-class static `FromHandle` also hides the base class's own (`CS0108` under
`GeneratedBindingsCheck`'s warnings-as-errors, unless every level adds `new`) and can collide with
a Kotlin companion member PascalCased to `FromHandle`.

### 6. A single-parameter `NugetHandle` wrapper struct in place of `IntPtr`

Rejected: `new Box(default)` for a class whose sole public parameter is itself a value type becomes
`CS0121` again, the same ambiguity this ADR closes, since a value-type `default` converts to both.

## Decision

Every generated class's internal constructor becomes `internal X(IntPtr handle, out
NugetHandleTag tag)`, where `NugetHandleTag` is a new `internal readonly struct` emitted once per
assembly beside `internal interface INugetHandle`:

```csharp
/// <summary>Marks the generated internal constructors that adopt an existing Kotlin handle, so no ordinary call can bind one.</summary>
internal readonly struct NugetHandleTag
{
}
```

A root constructor assigns the `out` parameter (`tag = default;`); a derived or sealed-arm one
forwards it: `internal Deep(IntPtr handle, out NugetHandleTag tag) : base(handle, out tag)`. Every
public derived or sealed-arm constructor that used to chain `: base(IntPtr.Zero)` now chains
`: base(IntPtr.Zero, out _)`. Every generated construction site (`Factories` lookup lambdas, the
sealed `FromHandle` discriminator, a value class's `this(new Cat(...))` delegation, and so on)
becomes `new X(h, out _)`.

This is a rendering change only: the constructor collision check
(`CirClassTranslator.kt`'s existing signature-collision logic) needs no new logic, since the
`out`-marked internal constructor was never a real collision candidate to begin with, and isn't
one now either.

### Generated shape

```C#
public class LitterTray : IDisposable, INugetHandle
{
    public LitterTray(int? scoops) { /* ... */ }

    internal LitterTray(IntPtr handle, out NugetHandleTag tag)
    {
        tag = default;
        _handle = handle;
    }
}

public class TallScratcher : Scratcher
{
    public TallScratcher(double height) : base(IntPtr.Zero, out _) { /* ... */ }

    internal TallScratcher(IntPtr handle, out NugetHandleTag tag) : base(handle, out tag)
    {
    }
}
```

`new LitterTray(5)` now binds the public constructor unambiguously (no `CS0121`); `new
Issue54Shape.Circle(5)` binds the exported `Circle(double)` instead of silently wrapping handle
`0x5`; `new Flag(5)` for a `bool`-typed constructor is now `CS1503` instead of compiling.

### Scope

Applies to every class route this project generates a wrapper for: ordinary classes, generic
classes, sealed bases and arms (nested and sibling), and value-class delegating constructors.
Covers every primitive/nullable-primitive shape in the matrix above, not only the nullable-numeric
one ROADMAP line 26 named.

ADR-164's `handleDisambiguation` (the `public Settings(int level) : this((int?)level)` delegating
overload emitted only for a defaulted widened leading integer parameter) is deleted: it exists
solely to work around the same hazard this ADR closes structurally, and `new Settings(3)` still
binds `Settings(int? level = null)` directly with no extra overload needed.

## Consequences

- `new Tag('O')`, `new LitterTray(5)`, `new FoodBowl(5)`, `new Issue54Shape.Circle(5)`, `new
  Box<long>(5)` and every other cell in the matrix now bind their public constructor, with no cast
  needed. `IntegrationTests/CharPositionMarshallingTests.cs`'s `(char?)'O'` cast is removed.
- **Tightening**: `new Flag(5)` for a `bool`- or `char`-typed constructor parameter is now
  `CS1503` (argument type mismatch) instead of silently compiling and wrapping a fabricated
  handle. A caller relying on that silent bind (none exists in this codebase) would need to fix
  the call.
- A hand-written C# subclass of a generated class or sealed arm must chain
  `: base(IntPtr.Zero, out _)` instead of `: base(IntPtr.Zero)`; two `IntegrationTests` fixtures
  (`PaperBeanbag : Lounger`, `PaperWren : Nester`) needed this update.
- Consumer IntelliSense on the internal constructor now shows `X(IntPtr handle, out
  NugetHandleTag tag)` rather than `X(IntPtr handle)`. It was already visible in the same
  assembly before this change (`internal` there is not `private`); the new shape simply cannot be
  called by accident.
- This also fully closes the older, broader ROADMAP item ("a missing constructor overload can
  silently resolve to the always-emitted internal handle constructor instead of failing to
  compile"), which [ADR-148](148-sealed-subclass-constructors.md) had only narrowed: an `out`
  parameter cannot be supplied by any argument list regardless of arm shape, so the hazard is
  closed for `object` arms and all-refused arms too, not only a `CLASS`-kind arm with an
  exact-match first parameter.
- `LeakTests/LiveHandleTests.cs`'s hand-written `Factories` replacement for `TopStory` is updated
  to `new TopStory(handle, out _)`; no new leak row, since the handle lifecycle itself is
  unchanged.
- No Kotlin/Native ABI change: the C ABI, `ForwardAbiContract` (a C# constructor is not a
  `DllImport`), and every `@CName` export are untouched.

## Verified claims

- The full one-argument collision matrix (48 calls, one class per C# primitive `T`/`T?`) before
  and after: 0 silent handle binds and 0 `CS0121` after, versus 18 loud/silent-or-wrong-code binds
  before.
- A two-argument probe (`A2(5, default)` against a real `(long, int)`/`(long,
  Optional<string>)` constructor) resolves PUBLIC; a by-value marker (Alternative 2) resolves
  HANDLE on the same probe.
- `[OverloadResolutionPriority(-1)]` (Alternative 4) fails with `CS9202` at `net8.0` default
  LangVersion 12.
- Real `test-library` `Interop.cs`, rewritten mechanically and built green end to end: 338
  `out NugetHandleTag tag` declarations, 1150 changed lines, `Build succeeded.`
- `new Settings(3)` (ADR-164's widened defaulted first-parameter case) still binds
  `Settings(int? level = null)` directly with `handleDisambiguation` deleted.

## Inferred claims

- A static `FromHandle(IntPtr)` factory (Alternative 5) was rejected by reasoning, not spiked.
