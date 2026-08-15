# ADR-098: Forward, narrow-primitive and `Char` collection components: six new `nuget_wrap_*` exports, and a `ushort`-shaped `Char` wire fixed at every position

## Status
Accepted

## Context

A consumer writing `fun f(x: List<Short>)` or `fun f(x: Set<Char>)` gets no C# member at all today.
Since [ADR-097](097-enum-collection-components.md) narrowed `List`'s callable-input gate to
`isWrappableComponent()`, all three collection kinds route these shapes to a named
`SKIPPED_UNSUPPORTED_INPUT`. Correct, but the member is missing.

Two independent gaps sit behind that one symptom.

**Gap 1, the six narrow primitives** (`Byte`, `UByte`, `Short`, `UShort`, `UInt`, `ULong`). The
write side is the asymmetric half. **Verified** by reading `cir/CirMarshalRenderer.kt`:
`FromHandle<T>` dispatches all ten narrow kinds, in both the `Nullable.GetUnderlyingType` pre-block
(`:96-160`) and the plain `typeof(T)` block (`:169-236`), calling `nuget_unwrap_byte/_ubyte/_short/
_ushort/_uint/_ulong`; `Wrap<T>` (`:241-258`) has exactly six branches (`string`, `int`, `long`,
`float`, `double`, `bool`) and then a `INugetHandle` type test and a `throw new
NotSupportedException`. **Verified** by reading `exports/GenericClassExports.kt:432-455`: the
`nuget_wrap_*` family is generated from one six-entry `types` list; the ten `nuget_unwrap_*` come
from `addNugetHelperExports` (`:314-430`).

**Gap 2, `Char`**. There is no `nuget_wrap_char` and no `nuget_unwrap_char` (**Verified**: neither
appears in `GenericClassExports.kt`, nor in the real generated header, see below), so a `Char`
component has no wire at all. Minting one forces the UTF-16-vs-1-byte-ANSI decision `ROADMAP.md:146`
owns.

While pricing gap 2 this research spiked `ROADMAP.md:148`, the open question of whether the
*already-shipped* bare `Char` parameter mismarshals a non-ASCII character. **It does. Verified,
against the real built `libtest.dylib` and the real generated signature.** That turns gap 2 from
"a new component kind" into "a shipped correctness bug that the component kind must not be built
on top of".

### The `Char` wire, verified

**Verified**, from the real Kotlin/Native-generated C header
`test-library/build/bin/macosArm64/releaseShared/libtest_api.h`:

```
11:typedef unsigned short     libtest_KChar;
12:typedef signed char        libtest_KByte;
13:typedef short              libtest_KShort;
16:typedef unsigned char      libtest_KUByte;
17:typedef unsigned short     libtest_KUShort;
18:typedef unsigned int       libtest_KUInt;
19:typedef unsigned long long libtest_KULong;
```

So a Kotlin `Char` crosses as a 2-byte unsigned UTF-16 code unit. The shipped C# side declares it as
a bare `char` with no `CharSet` and no `MarshalAs` (**Verified**, generated
`contentFiles/cs/any/Interop.cs:12466`):

```csharp
[DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "patient_tag")]
private static extern IntPtr Native_Tag(IntPtr handle, char initial, out IntPtr error);
```

#### Spike: the shipped bare `Char` parameter silently corrupts non-ASCII

Scratch console app in `mktemp -d`, P/Invoking the **real** `libtest.dylib` copied out of
`IntegrationTests/bin/Debug/net10.0/osx-arm64/`, calling `patient_tag` five ways against the same
live `Patient("Oreo")` handle. Kotlin side is `fun tag(initial: Char): String = "$initial-$name"`
(`clinic/ClinicSample.kt:231`). Real output:

```
--- input U+00E9 'é'
  shipped(char, default) : "Ã-Oreo" [U+00C3,U+002D]
  ansi(char, Ansi)       : "Ã-Oreo" [U+00C3,U+002D]
  unicode(char, Unicode) : "é-Oreo" [U+00E9,U+002D]
  marshalas U2           : "é-Oreo" [U+00E9,U+002D]
  ushort wire            : "é-Oreo" [U+00E9,U+002D]
--- input U+65E5 '日'
  shipped(char, default) : "æ-Oreo" [U+00E6,U+002D]
  ansi(char, Ansi)       : "æ-Oreo" [U+00E6,U+002D]
  unicode(char, Unicode) : "日-Oreo" [U+65E5,U+002D]
  marshalas U2           : "日-Oreo" [U+65E5,U+002D]
  ushort wire            : "日-Oreo" [U+65E5,U+002D]
```

**Verified**, four ways:

1. The shipped signature marshals `char` as **one byte**: `'é'` (U+00E9) is UTF-8 encoded to
   `C3 A9`, truncated to `0xC3`, and Kotlin reads it back as `'Ã'`. `'日'` (U+65E5) → `E6 97 A5` →
   `0xE6` → `'æ'`. No exception, no diagnostic. **Silent corruption of every non-ASCII `Char`
   argument, in shipped code.**
2. `CharSet.Ansi` explicitly is byte-identical to the default, confirming the default *is* Ansi for
   a `DllImport` with no `CharSet`.
3. `CharSet.Unicode`, `[MarshalAs(UnmanagedType.U2)]` on the `char` parameter, and a `ushort`
   parameter are **all three exactly correct and mutually identical** for every BMP input.
4. The assembly carries no `DisableRuntimeMarshallingAttribute` (printed by the same spike), so this
   is ordinary runtime marshalling, not the blittable-only path.

#### Spike: the shipped `Char` *return* is broken too, lossily

Same app, calling `patient_initial` (`fun initial(): Char = name.first()`, `ClinicSample.kt:311`)
on patients whose names start with a non-ASCII letter:

```
name=Oreo  expected=U+004F shipped=U+004F('O') unicode=U+004F ushort=U+004F u2=U+004F
name=Émile expected=U+00C9 shipped=U+FFFD('�') unicode=U+00C9 ushort=U+00C9 u2=U+00C9
name=日向  expected=U+65E5 shipped=U+FFFD('�') unicode=U+65E5 ushort=U+65E5 u2=U+65E5
name=Ωmega expected=U+03A9 shipped=U+FFFD('�') unicode=U+03A9 ushort=U+03A9 u2=U+03A9
```

**Verified**: the return path loses the character to `U+FFFD` (the low byte is decoded as a lone
invalid ANSI byte), and the same three fixes all restore it exactly. The bug therefore spans the
parameter position, the return position, and (by construction, same renderer, **Inferred**, no
fixture: `Patient.grade` is `'A'`) the property-getter position.

Only the shipped ASCII test cells (`Tag('O')`, `Tag('X')`) hid this. **Verified** by grep: no
non-ASCII `Char` cell exists anywhere in the corpus.

The remaining spike case, a lone surrogate (`'\uD83D'` passed alone), does **not** round-trip under
any of the five wire shapes; it comes back as a different supplementary character. That is a Kotlin
string-encoding artefact on the *return* leg, not a parameter-wire question, and is out of scope
(see Consequences).

## Alternatives Considered

### 1. Six `typeof(T)` branches + six exports; `Char` on `[MarshalAs(UnmanagedType.U2)]` at every position (chosen)

The six primitives get one `nuget_wrap_*` export and one `Wrap<T>` branch each, mirroring the
`FromHandle<T>` branches that already exist. `Char` gets both halves of the wire minted
(`nuget_wrap_char` / `nuget_unwrap_char`) plus a `FromHandle<T>` branch, and every `char` slot the
generator emits, old and new, carries `[MarshalAs(UnmanagedType.U2)]`.

Pros: the six are purely additive and decide nothing; `Char` rides the exact precedent
[ADR-069](069-nullable-boolean-marshalling.md) set for the same class of bug (Kotlin's 1-byte
`Boolean` against C#'s 4-byte default, fixed with a per-slot `[MarshalAs(UnmanagedType.I1)]`); the
attribute is per-slot, so it cannot perturb the `LPUTF8Str` string marshalling on the same
signature; spike-verified correct.

Cons: touches the already-shipped ordinary `Char` positions, which is a behaviour change (from
corrupt to correct) beyond the collection-component headline.

### 2. `ROADMAP.md:136`'s "type-specialized boxing call per element type"

Replace the runtime-generic `Wrap<T>` with a per-type boxing call chosen by codegen at the call
site, since codegen already knows the static element type.

Pros: removes a runtime `typeof` chain the generator could have resolved statically.

Cons: a strictly larger refactor of a shared helper for zero behavioural gain. ADR-097 already
priced this reframing as unnecessary for a *closed* type set, and `FromHandle<T>` (**Verified**)
proves the `typeof(T)` shape carries all ten narrow kinds today. Rejected, and `ROADMAP.md:136`'s
reframing should be marked wrong for the six exactly as ADR-097 marked it wrong for enums.

### 3. `CharSet = CharSet.Unicode` on the `DllImport` instead of a per-slot `MarshalAs`

Spike-verified equally correct.

Cons: `CharSet` is signature-wide, and its documented job is to select *string* marshalling
([Default Marshalling for Strings](https://learn.microsoft.com/en-us/dotnet/standard/native-interop/default-marshalling-for-strings)).
Every generated string parameter currently carries an explicit
`[MarshalAs(UnmanagedType.LPUTF8Str)]` and every string return comes back as `IntPtr` +
`PtrToStringUTF8`, so today it would be inert. Relying on that staying true is a trap for whoever
next changes string marshalling. Rejected in favour of the narrower, ADR-069-shaped fix.

### 4. A `ushort` wire with a `char` public surface

Declare the extern as `ushort` and cast at the call site (`ROADMAP.md:146`'s stated proposal).
Spike-verified correct.

Cons: two extra casts per crossing in generated code, and a second convention for "narrow value
needing a width directive" alongside ADR-069's attribute. Same result, more machinery. Rejected;
`ROADMAP.md:146`'s "needs a `UShortVar` + `ushort` narrowing on the wire, not a `MarshalAs`
attribute" is **wrong on the Kotlin half too**: the Kotlin side already emits `KChar` =
`unsigned short` with no change needed (**Verified** in the header above). The whole fix is on the
C# side.

### 5. Split `Char` into its own ADR, ship only the six here

Pros: the six are a zero-decision change; `Char` carries a real behaviour change.

Cons: leaves the restatement's `Set<Char>` unmet, and worse, a `Char` component *cannot* be shipped
without the width decision anyway, so splitting only defers it. Rejected, but see Consequences: the
two parts are independently landable in that order if the human gate prefers it, and part A does not
depend on part B in either direction.

### 6. Admit `Char` components without touching the shipped ordinary positions

Cons: the same generated file would then contain a `List<char>` that round-trips `'é'` correctly and
a `Tag(char)` that corrupts it. Rejected outright.

## Decision

Two parts, one ADR. Part A decides nothing new; part B is the width decision.

### Part A: the six narrow primitives

**A1.** Six entries appended to `addNugetWrapHelperExports`'s `types` list
(`exports/GenericClassExports.kt:433-440`). Kotlin type per suffix, matching the existing
`nuget_unwrap_*` returns (**Verified** at `GenericClassExports.kt:324-393`):

| suffix   | Kotlin  | C header (**Verified**) | C# |
|----------|---------|--------------------------|----|
| `byte`   | `Byte`   | `KByte` = `signed char`        | `sbyte`  |
| `ubyte`  | `UByte`  | `KUByte` = `unsigned char`     | `byte`   |
| `short`  | `Short`  | `KShort` = `short`             | `short`  |
| `ushort` | `UShort` | `KUShort` = `unsigned short`   | `ushort` |
| `uint`   | `UInt`   | `KUInt` = `unsigned int`       | `uint`   |
| `ulong`  | `ULong`  | `KULong` = `unsigned long long`| `ulong`  |

An unsigned Kotlin type is legal as a `@CName` export **parameter**, not only as a return.
**Verified** in the real generated header: `extern void* box_create_ubyte(libtest_KUByte value,
void* errorOut);` and `extern libtest_KUByte identity_ubyte(libtest_KUByte value, void* errorOut);`
already ship. No spike needed.

**A2.** Six `typeof(T)` branches in `Wrap<T>` (`cir/CirMarshalRenderer.kt:247-253`) plus their six
`[DllImport]` declarations alongside the existing `nuget_wrap_*` ones (`:40-63`), e.g.

```csharp
if (type == typeof(short)) return nuget_wrap_short((short)(object)value!);
```

The dispatch variable is already `Nullable.GetUnderlyingType(typeof(T)) ?? typeof(T)`
(**Verified**, `:248`), so `short?` hits the same branch with no extra work.

**A3.** Six `PrimitiveKind`s added to `isWrappableComponent()`'s set
(`forward/ForwardCallablePlanner.kt:2408-2411`): `BYTE`, `UBYTE`, `SHORT`, `USHORT`, `UINT`,
`ULONG`. (`PrimitiveKind` also has `BOOLEAN`, `INT`, `LONG`, `FLOAT`, `DOUBLE`, already admitted, so
after this the enum is fully admitted and the `kind in setOf(...)` test can become `true`; that
simplification is optional and should be weighed against `ROADMAP.md:138`'s note that the
narrow arms are already under-exercised.)

**A4.** Zero Kotlin-side lowering changes. **Verified** by source reading:
`elementKotlinTypeName` (`forward/ForwardKotlinPlanEmitter.kt:500-507`) already routes
`is BridgeType.Primitive -> "kotlin.${type.kind.simpleKotlinName()}"`, and
`simpleKotlinName()` already has arms for all six (`ROADMAP.md:138`). `componentLowering`'s
`else -> "$name as ${elementKotlinTypeName(type)}"` therefore emits `it as kotlin.UShort` with no
new branch. Unlike ADR-097's enum projection, **no C#-side per-element projection is needed**: the
C# static element type (`short`, `ushort`, …) is already the wire type, so `CreateList<short>` boxes
directly.

**A4b.** The public C# type rendering needs nothing either. **Verified**:
`PrimitiveKind.csharpType()` (`forward/ForwardCirPlanProjection.kt:1345-1355`) is total over the
enum, so `List<UShort>` renders `IEnumerable<ushort>` / `IReadOnlyList<ushort>` today. No fixture
currently exercises a narrow-primitive collection at any position (**Verified** by grep: no
`IReadOnlyList<short>`-family occurrence in the generated `Interop.cs`), which is why this is worth
stating rather than assuming.

**A5.** Read side needs nothing. **Verified**: `FromHandle<T>` already has all six branches in both
the nullable-underlying and plain blocks.

**A6.** Nullable spellings (`List<Short?>`, `Set<UInt?>`, `Map<String, ULong?>`) come along **for
free and unavoidably**: `isWrappableComponent()`'s `Nullable` branch is
`type !is BridgeType.Nullable && type.isWrappableComponent()` (**Verified**,
`ForwardCallablePlanner.kt:2432`), so admitting `SHORT` admits `Short?` in the same edit. Both the
write side (A2's `GetUnderlyingType` normalization) and the read side (A5) already handle it. This
is not a separate scope fork; it is a fact about the predicate, and it must carry a fixture cell so
that "free" is proven rather than assumed.

### Part B: `Char`, at every position

**B1.** Mint `nuget_wrap_char` (`addNugetWrapHelperExports`, `"char" to Char::class`) and
`nuget_unwrap_char` (`addNugetHelperExports`, `.returns(Char::class)`), matching the existing shapes
exactly.

**B2.** Every `char` slot the generator emits carries `[MarshalAs(UnmanagedType.U2)]`, both new and
already-shipped:

```csharp
[DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "nuget_wrap_char")]
private static extern IntPtr nuget_wrap_char([MarshalAs(UnmanagedType.U2)] char value);

[DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "nuget_unwrap_char")]
[return: MarshalAs(UnmanagedType.U2)]
private static extern char nuget_unwrap_char(IntPtr handle);

// and the already-shipped ordinary positions, which are the actual bug:
[DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "patient_tag")]
private static extern IntPtr Native_Tag(IntPtr handle, [MarshalAs(UnmanagedType.U2)] char initial,
                                        out IntPtr error);

[DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "patient_initial")]
[return: MarshalAs(UnmanagedType.U2)]
private static extern char Native_Initial(IntPtr handle, out IntPtr error);
```

Both attribute placements are **Verified** working by the spikes above (rows `marshalas U2` and
`u2`). The public C# surface stays `char`; nothing about the consumer API changes, only its
correctness.

The attribute follows ADR-069's exact implementation shape, which the implementer should copy rather
than invent (**Verified** by source reading): a per-import boolean flag rendered as
`[return: MarshalAs(...)]` at `cir/CirClassRenderer.kt:271` and `:468` (`marshalBooleanReturn`), and
a parameter-prefix string at `forward/ForwardCirPlanProjection.kt:745`. `Char` needs the same two
seams, driven off `ForwardAbiWireType.CHAR16` (the wire enum member is already correct and already
renders `"char"` at `ForwardCirPlanProjection.kt:1363` and
`ForwardCirPropertyProjection.kt:611`; only the attribute is missing).

**Watch the ABI contract normalizer.** `ForwardAbiContract`'s `csharpType(parameter.nativeType)`
path (`:238-255`) strips a leading `[MarshalAs(...)] ` only inside the `out `-prefix test
(`substringAfterLast("] ")`), not on the ordinary by-value branch (**Verified** by source reading).
A by-value `[MarshalAs(UnmanagedType.U2)] char` parameter will therefore reach `csharpType` with the
attribute still attached. ADR-069 never hit this because its `I1` only ever sat on an `out`
parameter or a return. Expect to hoist the strip out of the `out` test. This fails **loudly** at
generation time, not silently, but it will be the first thing that breaks.

**B3.** One `Wrap<T>` branch and one `FromHandle<T>` branch for `char`, the latter in **both** the
`nullableUnderlying` pre-block and the plain `typeof(T)` block, matching every other kind.

`FromHandle<T>` having no `char` branch today means a **`List<Char>` return binds and throws**:
`isBridgeableComponent()` admits `Char` (**Verified**, `ForwardCallablePlanner.kt:2359`), result
positions use that wider gate, and `FromHandle<char>` would fall through to `Materialize<char>` and
`throw new NotSupportedException("No generated factory materialises System.Char …")`. **Inferred**
from source reading, not runtime-reproduced: no fixture returns a `Char` collection (**Verified** by
grep, no `char>` occurrence in the generated `Interop.cs`). B3 closes it; the fixture below covers
it.

**B4.** `isWrappableComponent()` gains `BridgeType.Char -> true`. `Char` is its own `BridgeType`
member, not a `PrimitiveKind` (**Verified**, `elementKotlinTypeName:502` handles it separately), so
this is a distinct arm from A3. `elementKotlinTypeName` already returns `kotlin.Char` for it, so
`componentLowering` needs no branch (A4's reasoning).

### Consumer-side C# API

```csharp
// Kotlin: class Readings { fun chart(samples: List<Short>): String }
var readings = new Readings();
Assert.Equal("1,-2,32767", readings.Chart(new short[] { 1, -2, short.MaxValue }));

// Kotlin: fun weigh(grams: Set<ULong>): String
Assert.Equal("1,18446744073709551615", readings.Weigh(new HashSet<ulong> { 1, ulong.MaxValue }));

// Kotlin: fun trail(samples: List<Short?>): String   -- free via ADR-083's Nullable branch
Assert.Equal("1,null,3", readings.Trail(new short?[] { 1, null, 3 }));

// Kotlin: fun initials(marks: List<Char>): String    -- part B
Assert.Equal("é日A", readings.Initials(new[] { 'é', '日', 'A' }));

// Kotlin: fun marks(): List<Char>                    -- part B, read side
Assert.Equal(new[] { 'é', '日' }, readings.Marks());

// part B, the shipped ordinary positions this fixes
Assert.Equal("é-Oreo", new Patient("Oreo").Tag('é'));   // today: "Ã-Oreo"
Assert.Equal('É', new Patient("Émile").Initial());      // today: U+FFFD
```

### ABI / registration contract: additive, no breakage

**Verified** by source reading, three ways:

1. `contractHash` is a **reverse**-bridge construct only. Its two overloads live in
   `nuget-plugin/.../rir/RirBridging.kt:878-897` and hash `name + "|" + registrables.joinToString("|")
   { it.contractSignature(structs) }` over an `RirClass`/interface's registration thunks. No forward
   export name feeds it; `slotCount` is likewise the count of that class's reverse thunks. Six new
   forward exports cannot move either number.
2. There is no persisted *forward* hash (already recorded on `ROADMAP.md:134`;
   `ForwardAbiContract.assertMatches` is a generation-time structural check within one build).
3. The generated `Interop.cs` and the `.dylib` ship in the **same** `.nupkg` (**Verified** from the
   packed layout: `contentFiles/cs/any/Interop.cs` and `runtimes/osx-arm64/native/libtest.dylib`
   under one `TestLibrary.1.0.0-fixture.*` directory), so a published consumer package can never
   pair an old shim with a new library.

The `nuget_wrap_*` family is emitted as a unit under one gate (`NugetProcessor.kt:1053-1070`,
**Verified**), so widening the family adds symbols to exactly the builds that already export it.

### Fixture

Minimal, and it reuses the anchor ADR-097 already left behind.

**Flip, don't add** (`clinic/EnumComponentCollectionsSample.kt:100`): `MoodBoard.logSpans(spans:
List<Short>)` exists today purely as ADR-097's gate-narrowing cell, asserted **absent** from the
generated C#. It becomes a working round-trip cell. `Tier1EnumCollectionComponentTest.kt:241-252`
(the `"export_moodledger_logSpans" !in kotlin` and `SKIPPED_UNSUPPORTED_INPUT` assertions) inverts
with it, and the KDoc at `:30` and `:96-99` must be rewritten, not left describing a skip.

New file `test-library/src/nativeMain/kotlin/.../clinic/NarrowComponentCollectionsSample.kt`:

```kotlin
class Readings {
  /** Signed narrow, no conversion at the seam: C# `short` is the wire type. `List` position. */
  fun chart(samples: List<Short>): String = samples.joinToString(",")

  /** Unsigned narrow: Kotlin's inline-class box on one side, C# `ulong` on the other, and the
   *  widest of the six. `Set` position, so the shared predicate is proven at a second kind. */
  fun weigh(grams: Set<ULong>): String = grams.sorted().joinToString(",")

  /** `Map` position, narrow in BOTH slots, unsigned key + signed value. */
  fun census(byGrade: Map<UByte, Short>): String =
    byGrade.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }

  /** ADR-083's Nullable branch admits this the moment `Short` is wrappable. The cell exists to
   *  prove "free" rather than assume it. */
  fun trail(samples: List<Short?>): String = samples.joinToString(",") { it?.toString() ?: "null" }

  /** Part B, write side. The element that forces the UTF-16 wire. */
  fun initials(marks: List<Char>): String = marks.joinToString("")

  /** Part B, read side: `FromHandle<char>` has no branch today, so this binds and throws. */
  fun marks(): List<Char> = listOf('é', '日')
}
```

Test cells against **existing** fixture members, no new Kotlin needed, covering the shipped bug:
`Patient.Tag('é')`, `Patient.Tag('日')`, and `new Patient("Émile").Initial()`.

Nothing else. Specifically **not** in the fixture: a `Byte`/`UShort`/`UInt` cell each (the six share
one code path and one `typeof` shape; `Short` covers signed, `ULong` covers unsigned-widest, `UByte`
covers unsigned-narrowest at a key slot), a `MutableList<Short>` cell (mutable variants ride the
same gate, unchanged), a bare `Char?` property (see Consequences: still crashes, deliberately), and
a lone-surrogate cell.

### Unverified claims that would silently produce wrong output if wrong

Named explicitly, because nobody re-checks these after this document.

1. **Inferred, not spiked**: that `StableRef.create(value as Any)` on an unsigned Kotlin type boxes
   an object that `it as kotlin.UShort` later casts back successfully. The unwrap side already does
   `get() as UShort` on such a box and ships (**Verified** in `GenericClassExports.kt:352`), so a
   `UShort` box demonstrably round-trips through a `StableRef` today; what is unproven is the
   `wrap`-then-`nuget_list_add`-then-`componentLowering` sequence specifically. If wrong, the
   failure is a loud `ClassCastException` on the first call, not silent corruption.
2. **Inferred, not spiked**: that `[MarshalAs(UnmanagedType.U2)]` behaves identically on Windows
   (`win-x64`) and Linux. The spike ran on `osx-arm64` only. ANSI code-page differences make the
   *broken* behaviour platform-dependent; the fixed behaviour should not be, since `U2` is an
   explicit width with no code page involved. Worth one CI run on the Windows lane before Accepted.
3. **Inferred, not runtime-reproduced**: that a `List<Char>` **return** throws
   `NotSupportedException` out of `Materialize<char>` today (B3). Read from source; no fixture
   exercises it. If wrong, part B is smaller than described, never larger.
4. **Verified, and stated as the sharpest edge**: the `Char` fix must be applied to *every* `char`
   slot the generator emits, not just the new collection ones. Missing one leaves silently corrupted
   characters, which is exactly the failure mode this ADR exists to close, and no compiler on either
   side will say a word. The implementer should grep the generated `Interop.cs` for every bare
   `char` occurrence and assert on the count.

## Consequences

- `List`/`Set`/`Map` parameters and collection property setters over `Byte`/`UByte`/`Short`/
  `UShort`/`UInt`/`ULong`, their nullable spellings, and `Char` all bind instead of skipping. Some
  collection properties currently planned get-only (because a component failed
  `isWrappableComponent()`) flip to settable, the same way ADR-097 flipped `Chart.moods`. The
  implementer should expect and assert that diff rather than be surprised by it.
- **Behaviour change on shipped members**: every `char` parameter, `char` return and `char` property
  getter changes from ANSI-truncated to correct UTF-16. ASCII call sites are byte-identical;
  non-ASCII call sites go from corrupt to correct. `ROADMAP.md:148` closes.
- Six (part A) plus two (part B) new native exports, additive, with no contract-hash or
  registration-slot impact (see above).
- **Not fixed, and must not be assumed fixed:** a bare `Char?` property/return still aborts
  `packNuget` with no route (`ROADMAP.md:147`): `hasValueFanOutInner()` has no `Char` case. This ADR
  gives that work its missing *wire* but not its has-value fan-out, which is
  [ADR-079](079-nullable-primitive-enum-underlying-value-classes.md)'s template applied to `Char`.
  `ROADMAP.md:146` should be re-scoped after this lands: the width question is answered
  (`[MarshalAs(UnmanagedType.U2)]`, and the Kotlin side needs nothing), what remains is purely the
  nullable fan-out. Note that `List<Char?>` **does** work after this ADR, via ADR-083's pointer-null
  component slot; it is only the *ordinary* nullable position that stays open.
- Deferred, unchanged: nested-collection components (`ROADMAP.md:137`), `Map<String?, Int>` returns
  (`:135`), the `Wrap<T>` per-element box leak (`:144`), and the two ADR-097 coverage gaps (`:138`),
  one of which this ADR narrows further, since `isWrappableComponent()` now admits every
  `PrimitiveKind`.
- Deferred deliberately: **lone surrogates**. A `Char` holding an unpaired surrogate does not
  round-trip (**Verified** by spike: `'\uD83D'` alone came back as U+D806 U+DC2D through
  `tag`'s returned string). That is a Kotlin/Native string-encoding question on the return leg, not
  a parameter-wire question, and it is degenerate input in both languages (C# `char` and Kotlin
  `Char` are both single UTF-16 code units, so neither can hold an astral character to begin with).
  Recorded as a new ROADMAP item, not fixed here, and explicitly not fixture-covered.
- Part A and part B are independently landable in that order if the gate prefers two changes. Part B
  is the one carrying a behaviour change on shipped members.
