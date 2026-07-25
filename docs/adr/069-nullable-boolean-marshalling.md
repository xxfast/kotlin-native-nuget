# ADR-069: Nullable `Boolean` marshalling: closing ADR-061's deferred width at every forward position

## Status

Accepted

## Context

ADR-061 shipped nullable-primitive **method returns** as a single native call: the export returns
`Boolean` (has-value) and writes the unwrapped value through a `valueOut: COpaquePointer?`
out-parameter. It explicitly deferred `Boolean?` and `Char?`:

> `Boolean?` and `Char?` values are the marshalling-sensitive cases (Kotlin `Boolean`/`Char` width vs
> default C# `bool`/`char` out-param marshalling, the same fragility ADR-056 found for these two
> primitives); v1 scopes nullable-primitive to the blittable numerics and defers `Boolean?`/`Char?`.

ROADMAP line 82 carries the deferral, and ADR-064 gave it a `SKIPPED_UNSUPPORTED_RETURN` diagnostic.
Downstream (NYTimes-KMP BUG-008) reports `Boolean?` as unsupported generally and works around it with
presence pairs (`hasSavedState` / `isSaved`).

**"Deferred width" was a real defect, not caution.** This ADR settles it with a spike (below) and
then closes the hole. It also establishes that the deferral was *narrower* than the ROADMAP and
BUG-008 both imply: three of the four `Boolean?` positions already work, one is skipped, and one
**crashes the build**.

### Current behaviour, position by position (all verified by reading repo code)

| Position | Kotlin shape | Today | Evidence |
|---|---|---|---|
| Constructor / data-class ctor parameter | `data class Foo(val flag: Boolean? = null)` | **Works** | `ForwardCallablePlanner.kt:1221-1224`. `inputSkipReason()` returns `null` for `Nullable(Primitive)` with no Boolean carve-out; the fan-out to `${name}HasValue` + `${name}` is `:898-917`; C# call site `flag.HasValue, flag.GetValueOrDefault()` is `ForwardCirPlanProjection.kt:461`; Kotlin re-assembly `if (flagHasValue) flag else null` is `ForwardKotlinPlanEmitter.kt:675` |
| Any input parameter (`fun set(flag: Boolean?)`) | same | **Works** | same rows as above |
| Property getter/setter (`val`/`var flag: Boolean?`), class / companion / top-level / extension | ADR-002 two-call | **Works** | `ForwardPropertyPlanner.kt:116-135` builds `LegacyTwoCall` + `NullableDispatch` with **no** Boolean carve-out; Kotlin exports `ForwardPropertyKotlinEmitter.kt:94-121`; C# `ForwardCirPropertyProjection.kt:189-201`; both `bool`-returning imports get `[return: MarshalAs(UnmanagedType.I1)]` via `CirNativeImports.kt:45` and `:80` |
| Class method / extension function / `object` method / companion method return | `fun check(): Boolean?` | **Skipped**, `SKIPPED_UNSUPPORTED_RETURN` | `ForwardCallablePlanner.kt:1009`, `if (type.kind != PrimitiveKind.BOOLEAN)` … `else null`; reason mapping `ForwardDiagnostic.kt:131`; asserted by `Tier1NamedSkipDiagnosticsTest.kt:96-122` |
| **Top-level function return** | `fun check(): Boolean?` | **Hard build failure** | `staticEntry` routes *every* top-level `Nullable(Primitive)` return to `topLevelNullablePrimitivePlan` with **no** Boolean carve-out (`ForwardCallablePlanner.kt:551-563`), and the plan validates; the Kotlin emitter then throws `IllegalArgumentException("Legacy two-call value export must return the primitive wire type: <fqName>")` at `ForwardKotlinPlanEmitter.kt:173`, aborting `packNuget`. Not a skip, not a warning |

So the ROADMAP line ("skipped rather than fallthrough-emitted") is correct for the four *method*
positions and **wrong for the top-level position**, where nothing skips it and the run dies. There is
no fixture at any position (`grep "Boolean?" test-library/src` is empty), which is why the crash is
unseen.

The BUG-008 claim that data-class constructor `Boolean?` is skipped does **not** match current repo
code. It likely predates the ADR-062 plan migration (before which constructors ran through the legacy
CIR translator). The fixture below is what settles it.

### The width question, settled by spike

**Verified.** Kotlin/Native's generated C header types `Boolean` as C `_Bool`:

```
$ grep -n "KBoolean" test-library/build/bin/macosArm64/releaseShared/libtest_api.h
7:typedef bool            libtest_KBoolean;
9:typedef _Bool           libtest_KBoolean;
```

**Verified.** `kotlinx.cinterop.BooleanVar` exists, is 1 byte wide, and its `value` setter writes
exactly one byte (Kotlin/Native 2.2.10 stdlib source, `~/.konan/kotlin-native-prebuilt-macos-aarch64-2.2.10/sources/kotlin-stdlib-native-sources.zip`,
`nativeMain/kotlinx/cinterop/Types.kt`):

```kotlin
public class BooleanVarOf<T : Boolean>(rawPtr: NativePtr) : CPrimitiveVar(rawPtr) {
    public companion object : Type(1)          // <- size 1
}
public typealias BooleanVar = BooleanVarOf<Boolean>
public var <T : Boolean> BooleanVarOf<T>.value: T
    get() { val byte = nativeMemUtils.getByte(this); return byte.toBoolean() as T }
    set(value) = nativeMemUtils.putByte(this, value.toByte())   // <- writes 1 byte
```

**Verified by spike.** A scratch clang dylib + `dotnet run` (net10 SDK 10.0.300, osx-arm64) measuring
what the C# marshaller actually does with `bool`:

```c
// probe.c: emulates a Kotlin export writing a 1-byte Boolean through a void* valueOut
void fill_low_zero_upper_one(void* p) { unsigned int x = 0x00000100u; memcpy(p, &x, 4); }
void write_one_byte(void* p, unsigned char v) { *(unsigned char*)p = v; }
int  ret_dirty_true_in_upper(void) { return 0x100; }   // AL = 0, upper bits dirty
_Bool ret_real_bool_false(void) { return false; }
```

```
Marshal.SizeOf<bool>()                                      = 4
out bool (default), native wrote 0x00000100                 = True    <-- 4-byte read; WRONG
[MarshalAs(UnmanagedType.I1)] out bool, same native write   = False   <-- 1-byte read; correct
out byte, same native write                                 = 0       <-- 1-byte read; correct
bool return, eax = 0x100, no MarshalAs                      = True    <-- 4-byte read
bool return, eax = 0x100, [return: MarshalAs(I1)]           = False   <-- 1-byte read
bool return, genuine _Bool false, no MarshalAs              = False
[MarshalAs(I1)] out bool, native wrote 1 byte 1 / 0         = True / False
```

That is the whole deferred-width question, answered: **`out bool` under default P/Invoke marshalling
reads 4 bytes.** ADR-061's shape (`Kotlin BooleanVar` writes 1 byte → C# `out bool` reads 4) would
have returned `true` for a Kotlin `false` whenever the marshaller's stack temp held garbage in bytes
1–3. Non-deterministic, and a `Boolean?` fixture that only tested `true`/`null` would never see it.
The deferral was correct.

The same spike shows the fix is one attribute, and it is already this repository's house convention:
ADR-049 documents `[return: MarshalAs(UnmanagedType.I1)]` as "the existing forward-direction fix" and
mirrors it as `byte` narrowing on the reverse side, citing
[Blittable and Non-Blittable Types](https://learn.microsoft.com/en-us/dotnet/framework/interop/blittable-and-non-blittable-types)
(`Boolean` is excluded from the blittable set precisely because its marshalled size varies) and
[Default marshalling for Boolean types](https://learn.microsoft.com/en-us/dotnet/framework/interop/default-marshalling-behavior#boolean-types)
(default is 4-byte Win32 `BOOL`).

### Why `bool` *parameters* already work

**Verified in repo.** `renderDllImport` (`CirClassRenderer.kt:389-404`) emits no `MarshalAs` on
parameters, so every existing `bool` parameter marshals as a 4-byte 0/1 into a register that
Kotlin's `_Bool` parameter reads one byte of. On little-endian arm64/x86-64 the low byte carries the
value, so it is correct. Both polarities are exercised green today:
`HouseholdRoundTripTests.cs:47-61` calls `findToyLabel(..., indoorOnly: true, ...)` and
`indoorOnly: false` against `extern const char* findToyLabel(..., libtest_KBoolean indoorOnly, ...)`
(header line 492). Nothing in this ADR changes the parameter path.

## Alternatives Considered

### 1. Keep each position's existing ABI shape; fix Boolean's width explicitly with `MarshalAs(I1)` (chosen)

`Boolean?` fills the hole in the matrix rather than getting a shape of its own:

- **Method/extension/object/companion return**, ADR-061's single-call `valueOut`, with the Kotlin
  side writing through `BooleanVar` (1 byte, verified) and the C# `DllImport` declaring
  `[MarshalAs(UnmanagedType.I1)] out bool valueOut` (1-byte read, verified).
- **Top-level return**, ADR-002's two-call `_has_value` + `_value`, with
  `[return: MarshalAs(UnmanagedType.I1)]` on the `_value` import (the `_has_value` import already has
  it: `ForwardCirPlanProjection.kt:273`).
- **Property, constructor, parameter**: unchanged; already correct.

**Pros:** no new wire shape; one attribute that the repo already uses in three places and that
ADR-049 already names as the house convention; each position stays consistent with the other ten
primitives at that same position, so the planner keeps one rule per position rather than one rule per
(position × primitive). Verified end-to-end by the spike.

**Cons:** relies on an attribute rather than on a type whose width is self-evident, so a future
contributor can delete it and get code that passes a `true`/`null` test suite. Mitigated by the
fixture's mandatory `false` cell (below) and a code comment.

### 2. Narrow the wire type to `byte` on both sides

Kotlin writes `ByteVar` (`if (result) 1 else 0`), C# declares `out byte valueOut` and lifts with
`valueOut != 0`. Verified by the spike to read 1 byte.

**Pros:** width is in the type, not an attribute; nothing to delete. Precedent exists forward
(`CirClassTranslator.kt:1567`, `:1747`, `:1862` already widen Kotlin `Boolean` to `Byte` at the
callback/lambda seam) and reverse (ADR-049's `byte` thunk narrowing).

**Cons:** introduces a *third* Boolean convention forward (`bool` + `MarshalAs` for returns, raw
`bool` for parameters, `byte` for this one out-parameter). Needs a lift/lower step in the C# body and
in the Kotlin export that no other nullable primitive has, i.e. a Boolean-shaped special case in
exactly the code path this ADR is trying to de-special-case. Rejected as the *primary* choice, but it
is the correct fallback if `MarshalAs` on an `out` parameter ever proves unreliable on a target this
project adds.

### 3. Box the value as a `StableRef` handle (the ObjC/Swift/JNI precedent)

ADR-061 alternative 2, re-evaluated for `Boolean` specifically. Kotlin/Native's ObjC export maps
`Boolean?` to `KotlinBoolean *` (`nil` = null), Swift Export to `KotlinBoolean?`, JNI to
`java.lang.Boolean` as a nullable `jobject`. Every reference platform boxes, because none of them
has a nil channel for a primitive. *Inferred from docs, not spiked:*
[Interoperability with Swift/Objective-C](https://kotlinlang.org/docs/native-objc-interop.html).

**Rejected for the same reason ADR-061 rejected it:** a `StableRef` allocation, a second crossing to
read the value, and a disposal, for one bit. We control both sides of this boundary and are not
constrained to an object-only nil channel the way ObjC is.

### 4. Keep deferring; keep the presence-pair workaround

**Rejected.** The deferral currently costs a hard `packNuget` crash at the top-level position, which
is worse than either a skip or the fix. And the workaround it forces on consumers (`hasSavedState` /
`isSaved`) is exactly the two-field encoding the bridge exists to hide.

### 5. Move top-level `Boolean?` returns onto the single-call `valueOut` shape

Would make top-level consistent with the other four return positions, and ADR-061's side-effect
argument ("a function may not be idempotent") applies to a top-level function as much as to a method.

**Rejected for this ADR.** It would make top-level `Boolean?` differ in ABI shape from top-level
`Int?`/`Long?`/`Double?`, which are shipped on two-call, and one primitive with a different shape at one
position is a worse rule than one position with a different shape. Unifying *all* top-level nullable
primitives onto the single call is a defensible follow-up, but it is an ABI break for shipped exports
and belongs in its own ADR.

## Decision

`Boolean?` is a **matrix extension, not a new shape**. Each position keeps the ABI it already uses
for the other ten primitives; the only thing `Boolean` adds is an explicit 1-byte contract on the two
seams where the C# marshaller's default is 4 bytes.

The public C# surface is `bool?` (`System.Nullable<bool>`) at every position. Nothing prevents it:
`bool?` is an ordinary nullable value type, distinct from `bool` in an overload signature (which
`CirClassTranslator.kt:95-101`'s ADR-034 collision check already accounts for via
`CirParameter.isReferenceType`), and it reads in IntelliSense exactly as a C# developer expects.

### 1. Method / extension / `object` / companion return: single call + `valueOut`

Remove the Boolean carve-out at `ForwardCallablePlanner.kt:1009` (`nullableResultShape` handles every
`PrimitiveKind`) and give `cVarType` a `BOOLEAN -> "BooleanVar"` arm in place of today's
`error("Boolean nullable return is not a Phase 4 route")` (`ForwardKotlinPlanEmitter.kt:497`).

Kotlin export (**inferred**, not compiled: this is the ADR-061 body with `IntVar` → `BooleanVar`;
`BooleanVar`'s 1-byte `putByte` setter is verified, the `@CName` export compiling around it is not):

```kotlin
@CName("patient_isEligible")
fun export_patient_isEligible(
  handle: COpaquePointer,
  valueOut: COpaquePointer?,
  errorOut: COpaquePointer?,
): Boolean = try {
  val result: Boolean? = handle.asStableRef<Patient>().get().isEligible()
  if (result != null && valueOut != null) valueOut.reinterpret<BooleanVar>().pointed.value = result
  result != null
} catch (e: Throwable) {
  if (errorOut != null) errorOut.reinterpret<COpaquePointerVar>().pointed.value =
    StableRef.create(buildError(e)).asCPointer()
  false
}
```

C# side: the `valueOut` `DllImport` parameter **must** carry `[MarshalAs(UnmanagedType.I1)]`
(**verified**: without it the marshaller reads 4 bytes and a Kotlin `false` can surface as `true`):

```csharp
[DllImport("libtest", CallingConvention = CallingConvention.Cdecl, EntryPoint = "patient_isEligible")]
[return: MarshalAs(UnmanagedType.I1)]
private static extern bool Native_IsEligible(
    IntPtr handle,
    [MarshalAs(UnmanagedType.I1)] out bool valueOut,
    out IntPtr error);

public bool? IsEligible()
{
    bool hasValue = Native_IsEligible(_handle, out bool valueOut, out IntPtr error);
    if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);
    return hasValue ? valueOut : null;
}
```

Mechanically this is `nativeOutCirParameters` (`ForwardCirPlanProjection.kt:487-494`) emitting
`nativeType = "[MarshalAs(UnmanagedType.I1)] out bool"` when the out-parameter's transfer type is
`Primitive(BOOLEAN)`. `renderDllImport` interpolates `nativeType` verbatim
(`CirClassRenderer.kt:396`), and a parameter-level attribute in that position is legal C#.
**Verified**: the spike's `fill_i1([MarshalAs(UnmanagedType.I1)] out bool v)` compiled and ran. The
call site (`nativeOutParameters`, `:482-485`) stays `out bool valueOut`, no attribute.

The `[return: MarshalAs(UnmanagedType.I1)]` on the has-value return is already emitted:
`marshalBooleanReturn = result.nativeReturnType == "bool"` (`:196`, `:380`).

### 2. Top-level return: two-call, with the missing return attribute

Relax `ForwardKotlinPlanEmitter.kt:173` from `require(value.result != BOOLEAN && != VOID)` to
`require(value.result != VOID)`, and set `marshalBooleanReturn = csharpReturnType == "bool"` on the
`_value` `DllImport` at `ForwardCirPlanProjection.kt:275-283` (the `_has_value` import at `:273`
already sets it).

That second change is a **latent correctness fix beyond `Boolean?`**: today the `_value` import
returns whatever `mapReturnType` yields with no `MarshalAs`, and for `Boolean` that is a 4-byte read
of a 1-byte `_Bool` return. The spike shows a genuine clang `_Bool false` return still reads as
`False` under default marshalling (LLVM zero-extends), so this is a latent-risk fix rather than an
observed failure, but the has-value import next to it already defends against it, and the
inconsistency is not defensible.

The same missing attribute exists on the pre-plan legacy route (`CirFunctionTranslator.kt:882-900`,
both imports). That route is dead for top-level functions (`CirTranslator.kt:103-124` prefers the
plan), so it is out of scope; do not "fix" it, delete-when-dead.

### 3. Constructor, parameter, property positions: no change

Verified working today, at every receiver. The fixture below locks them in so a later refactor of the
nullable fan-out cannot silently drop `Boolean` back out.

### Claim labelling

- **Verified by spike (commands and real output above):** `Marshal.SizeOf<bool>() == 4`; default
  `out bool` reads 4 bytes; `[MarshalAs(UnmanagedType.I1)] out bool` reads 1 byte; `out byte` reads 1
  byte; default `bool` return reads 4 bytes; `[return: MarshalAs(I1)]` reads 1; a parameter-level
  `[MarshalAs]` on an `out` parameter compiles and works.
- **Verified from the Kotlin/Native 2.2.10 distribution:** `BooleanVar` is `Type(1)` and its `value`
  setter is `nativeMemUtils.putByte`, i.e. a 1-byte write.
- **Verified from a generated artifact:** `libtest_KBoolean` is `_Bool` (`libtest_api.h:7,9`). This is
  a build output, so it is evidence of what *that* Kotlin/Native version emitted, not a guarantee for
  all versions.
- **Verified in repo (code read, `file:line` cited):** every row of the position table; that
  parameters carry no `MarshalAs`; that both polarities of a `bool` parameter are green in
  `HouseholdRoundTripTests`.
- **Inferred, NOT spiked:** that the Kotlin export body above compiles on the real Kotlin/Native
  toolchain with `BooleanVar`. No `konanc` build was run. Adjacent evidence is strong (`IntVar`/
  `LongVar`/`FloatVar` in the identical position are shipped and green, and `BooleanVar` is the same
  `CPrimitiveVar` subclass with the same `.value` accessor shape), but **if this is wrong, nullable
  `Boolean` method returns fail to compile**, a loud failure, not a silent one. The implementing
  agent must confirm it through the walking-skeleton integration test before relying on it.
- **Inferred from docs, not spiked:** ObjC/Swift/JNI box `Boolean?`.

## Consequences

- `Boolean?` becomes supported at all five forward positions: constructor parameter, ordinary
  parameter, property getter/setter, method/extension/object/companion return, and top-level return.
  `SKIPPED_UNSUPPORTED_RETURN` stops firing for it, and `Tier1NamedSkipDiagnosticsTest.kt:96-122`
  must be inverted (from "asserts the skip" to "asserts the binding") rather than deleted.
- The top-level `packNuget` crash is removed. It was never reachable in this repository because no
  fixture had a top-level `fun f(): Boolean?`; it *was* reachable by any consumer.
- `Char?` stays deferred. ADR-061 bracketed `Boolean?` and `Char?` together, but they are not the
  same problem: Kotlin `Char` is 2-byte UTF-16 against C#'s default 1-byte ANSI `char` marshalling
  (ADR-049's second correction), so it needs `UShortVar` + `ushort` narrowing, not `MarshalAs(I1)`.
  Same-shaped follow-up, different wire type, out of scope here.
- **Not changed:** the parameter path (no `MarshalAs`, correct by little-endian low-byte read); the
  two-call/single-call split between the top-level and method return positions; the legacy
  `CirFunctionTranslator` nullable route (dead).

### Fixture surface

The fixture must cross **every** seam and must include a `false` cell at every one of them. `false`
is the whole point: a two-state type plus null is exactly where a sloppy sentinel (reusing the
has-value flag as the value, or a 4-byte read of a 1-byte write) passes for the wrong reason on the
`true`/`null` cells. A fixture without `false` proves nothing.

```kotlin
// test-library/src/nativeMain/kotlin/.../cat/NullableBooleanSample.kt

// 1. data-class constructor parameter + read-only property getter (two-call)
data class CatChip(val name: String, val implanted: Boolean? = null)

// 2. class with a mutable property (two-call getter + NullableDispatch setter)
//    and 3. an instance-method return (single-call valueOut)
class CatChecklist(var vaccinated: Boolean? = null) {
  fun isGroomed(state: Int): Boolean? = when (state) { 0 -> true; 1 -> false; else -> null }
  // 4. nullable Boolean input, non-null return: proves the fan-out both ways
  fun record(flag: Boolean?): String = flag?.toString() ?: "unknown"
}

// 5. object method return, 6. companion method return
object ChipRegistry { fun isKnown(state: Int): Boolean? = ... }
class CatRecord { companion object { fun isArchived(state: Int): Boolean? = ... } }

// 7. top-level function return (two-call route)
fun chipImplanted(state: Int): Boolean? = ...
// 8. top-level function with a nullable Boolean input
fun describeChip(flag: Boolean?): String = ...
// 9. top-level mutable property
var chipRegistryOnline: Boolean? = null

// 10. extension function return, 11. extension property getter
fun Cat.isPurringNow(state: Int): Boolean? = ...
val Cat.hasChip: Boolean? get() = ...
```

Each of cells 1, 3, 5, 6, 7, 10, 11 needs three integration assertions (`true`, `false`, `null`);
cells 2 and 9 need three round-trips through the setter; cells 4 and 8 need three inputs. Expected
C# surface:

```csharp
// 1
var chip = new CatChip("Oreo", implanted: false);
Assert.False(chip.Implanted);            // bool?, must not be null
Assert.Null(new CatChip("Mylo").Implanted);

// 2 + 3 + 4
var list = new CatChecklist();
Assert.Null(list.Vaccinated);
list.Vaccinated = false; Assert.False(list.Vaccinated);
list.Vaccinated = true;  Assert.True(list.Vaccinated);
list.Vaccinated = null;  Assert.Null(list.Vaccinated);
Assert.False(list.IsGroomed(1));         // the cell a 4-byte read fails
Assert.Equal("false", list.Record(false));

// 5 / 6 / 7 / 10 / 11
Assert.False(ChipRegistry.IsKnown(1));
Assert.False(CatRecord.IsArchived(1));
Assert.False(NullableBooleanSample.chipImplanted(1));
Assert.False(oreo.IsPurringNow(1));
Assert.False(oreo.HasChip);
```

`false` must be asserted with `Assert.False(x)` on a `bool?`, not `Assert.NotNull`. The failure mode
being guarded is precisely "a Kotlin `false` arrives as `true`".
