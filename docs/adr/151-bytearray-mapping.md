# ADR-151: `kotlin.ByteArray` maps to `byte[]` over a materialized `StableRef` handle, the collection wire with a `memcpy`

## Status
Accepted

## Context

Issue #248 (split out of #247): `data class Bar(val code: Int, val data: ByteArray)` loses both the
property and its constructor, and the constructor skip is reported as `UNEXPORTED_DEPENDENCY_TYPE`
with nothing third-party in the signature. Two defects, one feature:

1. `kotlin.ByteArray` has no mapping. `ForwardBridgeTypeClassifier.knownScalarType`
   (`ForwardBridgeTypeClassifier.kt:565-579`) and the known-stdlib block (`:131-166`) have no
   `ByteArray` line, so the type reaches the class/object fall-through (`:278-321`), which sets
   `isUnexportedDependency = classDeclaration.containingFile == null` (`:310`), true for every klib
   declaration. **Verified** by reading.
2. The kind is wrong for any unmapped `kotlin.*` type. The closure records `NOT_INCLUDED` for a
   public klib class outside the export scope (`ForwardReachabilityClosure.kt:262-265`), and
   `ForwardCallablePlanner.kt:3873-3888` turns `NOT_INCLUDED, null` into
   `UNEXPORTED_DEPENDENCY_TYPE`. The hint at `ForwardDiagnostic.kt:709-719` already says "a Kotlin
   stdlib type with no first-class C# mapping yet" via `isStdlibPackage()` (`:484-485`), so the
   sentence is right and the kind, which is what the `Cannot be constructed from C#` remark shows
   the consumer, is wrong. **Verified** by reading.

The consumer goal is the same as ADR-076 (`Instant`), ADR-103 (`Duration`) and ADR-106 (`Uuid`): a
fourth first-class stdlib type surfacing as the .NET type C# developers already hold, `byte[]`, at
every ordinary position, including `ByteArray?` → `byte[]?`.

**Why this is not a clone of ADR-106.** `Uuid` copied the `String` row because a `Uuid` has a
canonical fixed-width text form. A `ByteArray` has a length and arbitrary content, and the forward
`String` wire's result pointer is runtime-owned with no repo-side free (`NugetGenerateBindingsTask
.kt:4238`: "C# reads with Marshal.PtrToStringUTF8 and never frees"; **verified**). ADR-106
Alternative 3 rejected a "16-byte binary buffer" for exactly that reason. So the neighbour row here
is not `String`; it is `Collection`, the one row that already carries a variable-length Kotlin
value out and in with settled ownership.

### How a collection crosses today (verified by reading)

- One `POINTER` slot at every position (`ForwardPropertyPlanner.kt:766-767`), a `StableRef` minted
  by `NugetHandles.retain` (`NugetRuntime.kt:51`), `MATERIALIZED` ownership.
- Results: C# reads the handle through `NugetListNative.Count`/`Get` and disposes it in `finally`
  (`CirMarshalRenderer.kt:424-436`; exports `nuget_list_count`/`nuget_list_get`/`nuget_dispose` at
  `NugetRuntime.kt:132-146`). A nullable result is the null pointer
  (`ForwardCirPlanProjection.kt:1327-1344`: `if ($handle == IntPtr.Zero) return null;`).
- Inputs: C# builds the Kotlin container through `nuget_list_create`/`nuget_list_add`
  (`NugetRuntime.kt:148-155`), passes `${name}Handle` (`ForwardCirPlanProjection.kt:595`, `:644`)
  and disposes it after the call (`:746`, `:810`). A nullable input is `IntPtr.Zero`
  (`asNullableAwareCollection`, `:681-684`). Kotlin unwraps with `asStableRef<...>().get()`
  (`ForwardKotlinPlanEmitter.kt:566-567`, `:1133-1137` for handles).
- Every runtime export name is enumerated in `ForwardAbiContract.kt:500-560`.
- Property getters are single-call (`ForwardPropertyGetter` is `Direct`/`LegacyTwoCall`; ADR-106
  Alternative 2 records that no getter-with-OUT-parameter path exists). **Verified via ADR-106**,
  not re-read this session.

### The Kotlin/Native side of a byte buffer (verified by spike)

konanc 2.4.10 (`~/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.10/bin/konanc`, the Kotlin the
repo pins per ADR-106), scratch directory, this exact source:

```kotlin
@CName("nuget_bytes_create")
fun create(src: CPointer<ByteVar>?, count: Int): COpaquePointer {
  val bytes: ByteArray = if (src == null || count == 0) ByteArray(0) else src.readBytes(count)
  val ref = StableRef.create(bytes as Any); refs += ref; return ref.asCPointer()
}
@CName("nuget_bytes_count")
fun count(handle: COpaquePointer): Int = (handle.asStableRef<Any>().get() as ByteArray).size
@CName("nuget_bytes_copy")
fun copy(handle: COpaquePointer, dest: CPointer<ByteVar>?) {
  val bytes = handle.asStableRef<Any>().get() as ByteArray
  if (bytes.isEmpty() || dest == null) return
  bytes.usePinned { memcpy(dest, it.addressOf(0), bytes.size.convert()) }
}
```

driven from `main` with a 4-byte buffer, an empty buffer, and an unguarded `addressOf(0)` on an
empty array:

```
$ konanc probe.kt -o probe && ./probe.kexe
count=4
copied=[1, 2, 3, 4]
emptyCount=0
emptyCopyOk
emptyAddressOf=ArrayIndexOutOfBoundsException
```

Consequences the design takes from this:

- `kotlinx.cinterop.CPointer<ByteVar>.readBytes(count)`, `ByteArray.usePinned`, `Pinned.addressOf`
  and `platform.posix.memcpy` all exist under `ExperimentalForeignApi` on 2.4.10 and do what the
  exports need. **Verified.**
- **`addressOf(0)` on an empty `ByteArray` throws `ArrayIndexOutOfBoundsException`.** The
  `isEmpty()` guard in `nuget_bytes_copy` is load-bearing. Without it an empty `byte[]` crossing
  out of Kotlin (`byteArrayOf()`, the issue's own repro) throws at the read.
- A `@CName` export takes `CPointer<ByteVar>?` and `Int` parameters directly. **Verified.**

### The C# side (inferred, no dotnet run this session)

- A `[DllImport]` parameter declared `byte[]` is a one-dimensional array of a blittable primitive:
  the marshaller pins it and passes its address for the duration of the call, no copy, no `unsafe`
  ([Blittable and non-blittable types](https://learn.microsoft.com/en-us/dotnet/framework/interop/blittable-and-non-blittable-types)).
  With `[Out]` the marshaller guarantees the callee's writes are visible in the managed array.
  **Inferred** from documentation. If wrong, the symptom is loud (the `EmptyArray`/`RoundTrip`
  integration rows fail), not silent.
- The generated projects set `AllowUnsafeBlocks` (`PackNugetTask.kt:184`,
  `NugetCompileInteropTask.kt:94`, **verified**), so `fixed (byte* p = dest)` with an `IntPtr`
  parameter is an available fallback; `CirMarshalRenderer.kt` has no `unsafe` today (grep,
  **verified**), which is why the `[Out] byte[]` spelling is preferred.

## Alternatives Considered

### 1. `BridgeType.ByteArray` on the `Collection` wire: one `StableRef` handle, materialized by a count + `memcpy` (chosen)

At every seam `ByteArray` takes the branch `Collection` takes: `POINTER`, `MATERIALIZED`, null
pointer for `null`. Three runtime exports replace the per-element `nuget_list_*` family:
`nuget_bytes_create(byte* src, int count) -> handle`, `nuget_bytes_count(handle) -> int`,
`nuget_bytes_copy(handle, byte* dest)`. C# reads a result with `NugetMarshal.ReadBytes(handle)`
(count, allocate, copy, dispose in `finally`) and sends an input with
`NugetMarshal.CreateBytes(value)` (one P/Invoke, the handle disposed after the call by the existing
`forwardCirHandleScope` cleanup).

Pros:
- **Zero new plan shapes.** Inputs, results, nullable results, nullable inputs, property getters
  (`Direct`, single call), property setters, ctor parameters and `copy` all exist for
  `Collection`; the implementer greps `is BridgeType.Collection` and adds a neighbour.
- Ownership is the settled ADR-073/099/120 model: the receiving side materializes and disposes;
  the `LiveHandles` counter and the `LeakTests` harness cover it with one new row.
- `ByteArray?` rides the null-pointer sentinel, no has-value channel.
- One `memcpy` per crossing instead of `List<Byte>`'s per-element box and two P/Invokes.
- The Kotlin side never exposes a raw address outside `usePinned`, so no lifetime rule for a
  pointer into the Kotlin heap has to be invented.

Cons:
- Two copies per crossing in the worst case (Kotlin `readBytes` in, `memcpy` out) plus one
  `StableRef`. A `byte[]` is copied, never shared: mutating the C# array after a call does not
  change the Kotlin array, and vice versa. Same contract as `List<T>` today.
- Three P/Invokes for a result (`count`, `copy`, `dispose`) where a raw pointer wire would take
  one plus a free.

### 2. Raw `pointer + length`, copy on each side

Inputs: C# pins with `fixed`/`byte[]`, passes `(byte* data, int length)`, Kotlin `readBytes`.
Results: Kotlin `nativeHeap.allocArray`, copy, return the pointer and write the length to an OUT
`CPointer<IntVar>`; C# `Marshal.Copy` then a `nuget_bytes_free` export (the ADR-085
`nuget_kotlin_string_free` shape, `NugetGenerateBindingsTask.kt:4685-4703`, **verified** to exist
for the reverse direction). Rejected: the result needs a second slot, and property getters have no
OUT-slot shape (ADR-106 Alternative 2's cost, "new machinery in three property files"), and
a two-slot input cannot be a `SETTER_VALUE` (one slot, `ForwardAbiRole`,
`ForwardMarshallingModel.kt:343-360`). Identical consumer C#. Recorded so a later zero-copy or
hot-path ADR can pick it up.

### 3. Classify `ByteArray` as `Collection(LIST, Primitive(BYTE))`

Zero new code: the ADR-098 narrow-primitive component path would carry it today. Rejected on the
consumer surface (`List<sbyte>` where every C# developer expects `byte[]`) and on cost (a boxed
`StableRef` per byte, two P/Invokes per byte).

### 4. Opaque `KotlinByteArray : IDisposable`

What Kotlin/Native ObjC export does (`KotlinByteArray`, not `NSData`; **inferred** from the
Kotlin docs' Swift/ObjC type-mapping table). Rejected as ADR-076/106 Alternative 5 was: a wrapper
around the other language, which GOALS.md forbids.

## Decision

### 1. New `BridgeType.ByteArray`, a sealed variant that copies `BridgeType.Collection`'s row at every seam

```kotlin
// ForwardMarshallingModel.kt
internal sealed interface BridgeType {
  /**
   * ADR-151: kotlin.ByteArray. Wires as [Collection] does, one POINTER slot holding a StableRef
   * to the array, MATERIALIZED ownership, with the per-element loop replaced by a count + memcpy;
   * public C# type is byte[]. ByteArray? is the null pointer.
   */
  data object ByteArray : BridgeType
}

internal enum class ForwardConversion { /* ... */ BYTES_TO_HANDLE, HANDLE_TO_BYTES }
internal enum class ForwardHelperRequirement { /* ... */ BYTES }   // drives NugetBytesNative + ReadBytes/CreateBytes in CirMarshalRenderer
```

A sealed variant, not `Collection` with a flag, for the ADR-070/076 reason: every `when` that
admits `Collection` must decide about `ByteArray` explicitly, and a fall-through would publish a
`List<sbyte>` or drop the member. Recognition goes at `ForwardBridgeTypeClassifier.kt:146`,
directly after the `kotlin.uuid.Uuid` line (`if (qualifiedName == "kotlin.ByteArray") return
BridgeType.ByteArray`), and `"kotlin.ByteArray"` joins `ForwardReachabilityClosure
.SCALAR_TERMINALS` (`:348-361`). `ByteArray` is a plain `final class`, not a value class
(**verified**: `UByteArray` is the value class, and is out of scope), so ordering against
`isValueClass()` is irrelevant.

### 2. Wire form: the `Collection` wire, three runtime exports

`nuget-runtime/.../NugetRuntime.kt`, beside the `nuget_list_*` family, with the spike's exact
bodies (the `isEmpty()` guard is mandatory, see Context):

```kotlin
@NugetRuntimeApi @CName("nuget_bytes_create")
public fun export_nuget_bytes_create(src: CPointer<ByteVar>?, count: Int): COpaquePointer =
  NugetHandles.retain(if (src == null || count == 0) ByteArray(0) else src.readBytes(count))

@NugetRuntimeApi @CName("nuget_bytes_count")
public fun export_nuget_bytes_count(handle: COpaquePointer): Int =
  handle.asStableRef<ByteArray>().get().size

@NugetRuntimeApi @CName("nuget_bytes_copy")
public fun export_nuget_bytes_copy(handle: COpaquePointer, dest: CPointer<ByteVar>?) {
  val bytes: ByteArray = handle.asStableRef<ByteArray>().get()
  if (bytes.isEmpty() || dest == null) return
  bytes.usePinned { memcpy(dest, it.addressOf(0), bytes.size.convert()) }
}
```

All three names join `ForwardAbiContract.kt`'s export list. Disposal is the existing
`nuget_dispose`. Whether an export addition needs an ADR-129 `nuget_runtime_version` bump is an
open question for the implementer (grep what ADR-147's `T` mint did).

`CirMarshalRenderer.kt`, gated on `ForwardHelperRequirement.BYTES`:

```csharp
internal static class NugetBytesNative
{
    [DllImport(LIB, CallingConvention = CallingConvention.Cdecl, EntryPoint = "nuget_bytes_create")]
    internal static extern IntPtr Create(byte[] src, int count);          // blittable array: pinned, not copied (inferred)
    [DllImport(LIB, CallingConvention = CallingConvention.Cdecl, EntryPoint = "nuget_bytes_count")]
    internal static extern int Count(IntPtr handle);
    [DllImport(LIB, CallingConvention = CallingConvention.Cdecl, EntryPoint = "nuget_bytes_copy")]
    internal static extern void Copy(IntPtr handle, [Out] byte[] dest);   // [Out]: callee writes are visible (inferred)
    [DllImport(LIB, CallingConvention = CallingConvention.Cdecl, EntryPoint = "nuget_dispose")]
    internal static extern void Dispose(IntPtr handle);
}

// NugetMarshal
public static byte[] ReadBytes(IntPtr handle)
{
    try
    {
        int count = NugetBytesNative.Count(handle);
        var result = new byte[count];
        if (count > 0) NugetBytesNative.Copy(handle, result);
        return result;
    }
    finally { NugetBytesNative.Dispose(handle); }
}

public static IntPtr CreateBytes(byte[] value) => NugetBytesNative.Create(value, value.Length);
```

If the `[Out] byte[]` spelling does not copy back (the inferred claim above), the fallback is
`fixed (byte* p = result) NugetBytesNative.Copy(handle, (IntPtr)p)`, legal because the generated
projects already allow unsafe blocks (**verified**).

The seam table, `Collection` today versus `ByteArray`:

| seam | `Collection` today | `ByteArray` |
| --- | --- | --- |
| Kotlin export return | `NugetHandles.retain(<expr>)` with the lowering suffix (`ForwardKotlinPlanEmitter.kt:152-157`) | `NugetHandles.retain(<expr>)`, no suffix |
| Kotlin export nullable return | `?.let { NugetHandles.retain(it) }` (null pointer) | same |
| Kotlin export parameter | `(param as MutableList<*>)...` (`:566-567`) | `param.asStableRef<ByteArray>().get()` |
| Kotlin nullable parameter | `param?.let { ... }` | `param?.asStableRef<ByteArray>()?.get()` |
| Kotlin property setter | the collection lowering | `value.asStableRef<ByteArray>().get()` |
| `csharpType()` | `List<T>` / `IReadOnlyList<T>` | `byte[]` |
| C# argument prelude | `CreateList(...)` into `${name}Handle` (`ForwardCirPlanProjection.kt:691-695`) | `NugetMarshal.CreateBytes(param)` into `${name}Handle`; `param is null ? IntPtr.Zero : ...` when nullable |
| C# argument cleanup | `NugetListNative.Dispose(handle)` (`:810`) | `NugetBytesNative.Dispose(handle)` |
| C# result lift | `ReadList(handle, read)` in `collectionMaterializingCore` (`:1327-1344`) | `NugetMarshal.ReadBytes(handle)`; `if (handle == IntPtr.Zero) return null;` when nullable, after the error check |
| C# property getter | the collection read | `ReadBytes(nativeResult)` |
| `isCSharpReferenceType()` | `true` | `true` (so `ByteArray?` renders `byte[]?`) |

Ownership: identical to a collection. Kotlin mints and forgets; the C# side that receives a handle
disposes it (`ReadBytes`'s `finally`); the C# side that mints an input handle disposes it after the
call. Kotlin never holds a pointer into a C# array outside the marshaller's pin, and C# never holds
a pointer into the Kotlin heap.

### 3. `ByteArray?` → `byte[]?` over the null-pointer sentinel

Exactly `List<T>?`: the null pointer at results (`:1341`) and `IntPtr.Zero` at inputs. **Not free**:
today a `Nullable(Unsupported)` is refused by the nullable-combination arm before the dependency
reason is consulted (ADR-113 note 6, `113-...md:473-475`), so every arm that admits
`Nullable(Collection)` (`asNullableAwareCollection`, the planner's nullable admission, the
property planner's `unwrapNullable() as? Collection` at `ForwardPropertyPlanner.kt:494`, `:592`)
gains a `ByteArray` neighbour. The empty array is not null: `byteArrayOf()` crosses as a handle to
a zero-length array and comes back as `Array.Empty<byte>()`-equivalent (`new byte[0]`).

### 4. An unmapped `kotlin.*` type is `SKIPPED_UNSUPPORTED_TYPE`, not `UNEXPORTED_DEPENDENCY_TYPE`

In the classifier fall-through, ahead of the `isUnexportedDependency` computation at `:310`:

```kotlin
// ADR-151: a stdlib type that reaches this gate is merely unmapped. No include(...) repairs it.
if (qualifiedName.isStdlibPackage()) {
  return BridgeType.Unsupported(qualifiedName, "a Kotlin stdlib type with no first-class C# mapping yet")
}
```

Placed inside each `containingFile == null` fall-through, not once before the shape branches: `specializedProtocol`/`collectionType` (`:167-168`) and the `isValueClass()` branch (`:223`) all legitimately claim `kotlin.*`/`kotlinx.*` names after the known-stdlib block, so a single early gate would swallow `List`, `Flow`, lambdas and value classes. The sites are the class/object fall-through (`:310`) and the interface one (`:419`), plus the enum branch if it computes the same flag (grep `containingFile == null`). With no dependency flags set, `ForwardCallablePlanner.kt:3888`'s `else -> UNSUPPORTED` reports it as
`SKIPPED_UNSUPPORTED_TYPE`. The `isStdlibPackage()` branch inside the `UNEXPORTED_DEPENDENCY_TYPE`
hint (`ForwardDiagnostic.kt:711-719`) moves to the new reason's sentence (the "include(...) is not
the fix" clause stays, it is the useful half). `isStdlibPackage` (`:484-485`, private to
`ForwardDiagnostic.kt`) becomes shared. The closure is untouched: its `NOT_INCLUDED` entry for the
stdlib name is only ever read through the flag the classifier no longer sets.

**As shipped (2026-09-17).** One thing this decision left out: with no flag set, the skip carried no
`detail`, so the moved hint said "it is a Kotlin stdlib type" and never named the type. The
implementation adds a `BridgeType.stdlibTypeDetail()` extractor (last in the chain, so every flagged
refusal above it keeps its own wording) and chains it at the three callable skip sites and in
`skipDetail()`. The reason's *sentence* stays generic on purpose: giving
`ForwardPlanSkipReason.UNSUPPORTED` a sentence of its own would flip `ownsSentence()` and reword
every unsupported property skip.

### 5. The C# the consumer sees

```csharp
public sealed class Payload : IDisposable
{
    public Payload(int code, byte[] data) { ... }       // data class ctor; copy() takes the same row
    public int Code { get; }
    public byte[] Data { get; set; }                     // var property: getter copies out, setter copies in
    public byte[]? Checksum { get; }                     // ByteArray?
    public byte[] Slice(int from, int to) { ... }
    public byte[]? Maybe(byte[]? input) { ... }
}

public static class PayloadKt                            // ADR-007 file class; PascalCase members
{
    public static byte[] Reverse(byte[] data) { ... }
    public static byte[] Empty() { ... }
    public static byte[]? Maybe(byte[]? data) { ... }
}
```

## Consequences

### Fixture surface in `test-library`

New file `test-library/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/test/cat/Payload.kt`
(the ADR-076/103/106 precedent of one file per feature; the name must not collide with an existing
fixture, grep first):

```kotlin
data class Payload(val code: Int, var data: ByteArray) {     // the issue's exact repro, plus a var
  val checksum: ByteArray? get() = if (data.isEmpty()) null else byteArrayOf(data.sum().toByte())
  fun slice(from: Int, to: Int): ByteArray = data.copyOfRange(from, to)
  fun maybe(input: ByteArray?): ByteArray? = input
}

fun reverse(data: ByteArray): ByteArray = data.reversedArray()
fun empty(): ByteArray = byteArrayOf()                         // the empty guard's integration row
fun maybe(data: ByteArray?): ByteArray? = data
```

Boundary rows the C# tests must cover: `{1,2,3}` through ctor, property and `reverse`; the empty
array through `empty()`, `reverse(Array.Empty<byte>())` and the ctor (asserting `Empty`, not
`Null`); `null` through every nullable position; `0xFF`/`0x80` bytes (sign is irrelevant on a
`memcpy`, but a Tier 1 cell that ever swaps to a `sbyte` loop would catch it); a 1 MiB array
round trip; the copy contract (mutate the C# input after the call, assert Kotlin's copy is
unchanged; mutate a `byte[]` handed out by a getter, assert the next getter read is unchanged). One
`LeakTests` row for the materialized result handle (ADR-120).

### The `Issue112Sample` stand-in

`Issue112Sample.collarTag(code: Int): ByteArray?` exists *because* it is unbridgeable
(`Issue112Sample.kt:82-87`, `:100`). It becomes bridgeable under this ADR, which would make ADR-113's
CS0102 collision guard fire on the fixture (`val collarTag` vs `CollarTag(int)`). It changes to
`fun collarTag(code: Int): Sequence<Int>`: ADR-064 refuses `Sequence` by name
(`ForwardBridgeTypeClassifier.kt:155-162`) as a decision, not a gap, so the stand-in cannot be
mapped out from under the fixture again. Files that pin the old shape: `Issue112Sample.kt:82-87,
:100`; `Tier1Issue112InterfaceProjectionTest.kt:31, :65-68, :94-99, :204-208, :297, :306`;
`IntegrationTests/Issue112Tests.cs:72-80`; ADR-113 (`:18, :295, :329, :338, :473-475`) keeps its
historical text and gains an "as shipped" note like ADR-106's.

### What changes

- `BridgeType.ByteArray`, `BYTES_TO_HANDLE`/`HANDLE_TO_BYTES`, `ForwardHelperRequirement.BYTES`,
  validator pairing; classifier line, stdlib gate, `SCALAR_TERMINALS` entry.
- Every seam that mentions `is BridgeType.Collection` gains a `ByteArray` neighbour (the file list
  in the research memo, `docs/research/roadmap/bytearray-mapping.md`), with **no new plan shape**.
- Three runtime exports, three contract names, one `NugetBytesNative` class and a
  `ReadBytes`/`CreateBytes` pair in `CirMarshalRenderer`.
- The hint move in `ForwardDiagnostic`, and a Tier 1 cell asserting `SKIPPED_UNSUPPORTED_TYPE` for
  `kotlin.Array<String>` (the next unmapped stdlib type).
- Per-crossing cost: one `StableRef`, one `readBytes` or one `memcpy`, two or three P/Invokes. A
  `byte[]` is a copy, never a view.

### Deferred

- ~~Collections of `ByteArray` (`List<ByteArray>`, `Map<String, ByteArray>`): the component
  read/write helpers need a `ByteArray` arm each.~~ Shipped 2026-09-20; see the amendment below.
- `UByteArray` (a value class over `ByteArray`, ADR-103 ordering trap applies), `IntArray`,
  `Array<T>` and the other primitive arrays: same wire, `sizeof`-aware `memcpy`, a follow-up.
- `ByteArray` as an extension-property receiver (the ROADMAP:27 deferral class).
- Reverse direction (`byte[]` in a NuGet API): the mirror of this ADR.
- Zero-copy `Span<byte>`/`Memory<byte>` views: `usePinned` cannot hand an address across calls.

## Amendment (2026-09-20): `ByteArray` as a collection component

A `ByteArray` component crosses as its own `StableRef` handle in the pointer-shaped slot every
component already uses (the ADR-099 nested-collection arm, one handle kind over). **No new runtime
export.**

- C# writes `NugetMarshal.CreateBytes(x)` per element through the existing `Select` projection;
  `Wrap<IntPtr>` reports `owned = true` for that handle and the fill loop disposes it right after
  `Add`/`Put` has dereferenced it into the Kotlin container.
- C# reads `NugetMarshal.ReadBytes(h)` per element, which copies the bytes out and disposes the
  per-element box in its own `finally`. The box is a `StableRef` to the `ByteArray`: `nuget_list_get`
  and the map/set readers mint it with the same `NugetHandles.retain` that `nuget_bytes_create` uses.
- Kotlin casts `it as kotlin.ByteArray` on the way in and boxes the container untouched on the way
  out (`componentNeedsProjection()` stays `false` for bytes, `componentNeedsWireProjection()`
  becomes `true`, the same split ADR-099 made).
- A **nullable** component writes as `IntPtr?`, not `IntPtr` with a zero sentinel. That is
  load-bearing: `Wrap<IntPtr>(IntPtr.Zero)` reports `owned = true` and the fill loop would then call
  `Dispose(IntPtr.Zero)`, whose non-nullable `COpaquePointer` export takes the host process down.
  `Wrap<IntPtr?>(null)` returns at its `value == null` guard with `owned = false` instead.
- Arbitrary nesting (`List<List<ByteArray>>`) follows from the recursion, with no further arm.
- The legacy async routes share the component gates, so `suspend fun (): List<ByteArray>` and
  `Flow<List<ByteArray>>` open with them. A **bare** `ByteArray` at those two positions binds as
  `Task<byte[]>` and `KotlinFlow<byte[]>` through the same `ReadBytes`: the Kotlin half already
  boxes the result with `NugetHandles.retain`, so only the C# spelling and read had to change.
  `MutableStateFlow<ByteArray>` keeps the read-only `KotlinStateFlow<byte[]>` mapping: its ADR-071
  write seam has no arm that mints a bytes handle.

**Declined, not deferred:** a `Set<ByteArray>` element and a `ByteArray` **map key**. Both are
equality slots, and an array compares by identity in Kotlin and in C# alike; every crossing of this
bridge copies, so `set.Contains(bytes)` and `map[bytes]` would compile, run, and silently never
match. They stay `ForwardPlanSkipReason.BYTE_ARRAY` skips, with a hint that says so and points at a
`List` or a `String`/value-class key.

**Still not supported:** a bare `ByteArray` parameter on a `Flow`-, `StateFlow`-, or
`suspend`-returning member (only the return/element position opened here); `suspend fun ():
StateFlow<ByteArray>` (the shared `nuget_stateflow_value` export has no per-member projection seam,
[ADR-123](123-collection-elements-on-the-flow-routes.md)). The `StateFlow<ByteArray>` **property**
route itself binds.

**Known imprecise hint.** A parameter the legacy generic route now refuses reclassifies with the
`UNROUTED_POSITION` `GENERIC` sentence ("binds at a top-level function return ... but not at this
position"), even though the position is a parameter and the type is not generic. Strictly better
than the silent public `IntPtr` it replaces, but the wording should eventually name the parameter
case directly.

### Three pre-existing defects, fixed along the way

- `Flow<ByteArray>` on a class **crashed** `packNuget` outright (`IllegalStateException: Kotlin
  builtin kotlin.ByteArray reached the user-type C# speller`), not a skip. Dated pointer in
  [ADR-123](123-collection-elements-on-the-flow-routes.md).
- A bare `suspend fun (): ByteArray` bound silently to `Task<ByteArray>`, a C# type nothing
  declares (CS0246 in every consumer's build). Same ADR-123 pointer.
- General, not `ByteArray`-specific: `hasLegacyGenericReturnRoute()` (`exports/FunctionExports.kt`)
  was `true` for any generic return, so a collection return the ADR-062 plan skipped was still
  emitted by the pre-ADR-062 list/map/set branches in `cir/CirFunctionTranslator.kt`, spelling the
  component by Kotlin simple name and dropping its type arguments (`IReadOnlyList<ByteArray>`,
  `IReadOnlyList<List>`; `List<Instant>`, `List<Uuid>`, `List<Sequence<Int>>` took the same path).
  It also inspected only the *return*, so a skip caused by a *parameter* left the route open and
  degraded that parameter to a public `IntPtr`. The route now refuses a collection return and any
  parameter it cannot spell, and the dead branches carry a `check(...)` that fails the build if the
  gate regresses; pinned by `tier1/Tier1SkipMeansAbsentTest.kt`. Issue #126's class, which
  [ADR-122](122-handle-parameters-on-the-legacy-routes.md) fixed on the async routes only; dated
  pointer added there.
- The collection skip reason named whichever component came first, not the one that actually
  failed (`Map<String, ByteArray>` was reported as `STRING`); it now names the failing component.

### Leak coverage

Five new `LeakTests/LiveHandleTests.cs` rows, after Row 3d/4c/4d: `ListOfByteArrayParameter_
ReturnsToBaseline` (3e), `NullInsideANonNullListOfByteArray_ThrowsMidFill_ReturnsToBaseline` (3f,
the throw-mid-fill path), `ListOfByteArrayReturn_ElementHandlesDisposed_ReturnsToBaseline` (4e),
`MapOfByteArrayValues_RoundTrip_ReturnsToBaseline` (4f), and
`NullableByteArrayElements_BothDirections_ReturnsToBaseline` (4g).
