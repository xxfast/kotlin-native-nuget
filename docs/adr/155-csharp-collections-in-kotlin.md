# ADR-155: C# collections in Kotlin: BCL collection types as Kotlin collections over a slot buffer, eager copy

## Status
Accepted

## Context

Reverse direction (C# → Kotlin). The C# NuGet package declares the member; Kotlin consumes it.
A Kotlin consumer of a bound NuGet package gets a C# member that returns or takes a BCL collection
(`IReadOnlyList<T>`, `IList<T>`, `List<T>`, `IEnumerable<T>`, `IDictionary<K,V>`,
`IReadOnlyDictionary<K,V>`, sets, arrays if they fit) as the matching Kotlin collection, eagerly
copied across the bridge, instead of the member being skipped.

Today every such member is dropped:

- **Verified by reading** (`NugetMetadataReader/Program.cs:3398-3479`): a BCL generic
  instantiation reaches `SignatureDecoder.GetGenericInstantiation` with `genericType.TypeRef ==
  null` (its definition is a `TypeReference` outside the bound assemblies), falls past the
  ADR-072 branch at `:3431`, and is diagnosed `skipped_unbound_generic_instantiation` at `:3472`
  ([ADR-072](072-closed-constructed-generics-in-kotlin.md) Decision 9). The hint text there still
  points at "ROADMAP line 220", which is this item.
- **Verified by reading** (`Program.cs:3551-3555`, `:2008`): an array (`GetSZArrayType`) returns
  `(null, null, raw)`, and a null `TypeRef` with a null diagnostic makes the member vanish with no
  diagnostic at all.
- **Verified by reading**: no reverse collection vocabulary exists anywhere. `RirTypeRef`
  (`rir/RirModel.kt:205-290`) has no collection variant, and the forward `nuget_list_*` /
  `nuget_map_*` / `nuget_set_*` exports (`nuget-runtime/.../NugetRuntime.kt:145-243`) traffic in
  `StableRef`-boxed Kotlin objects, which a C# element is not.

Constraints the decision has to respect:

- Kotlin reaches managed code only through a registered function pointer
  ([ADR-041](041-kotlin-to-csharp-call-mechanism.md)); every user-code thunk carries a trailing
  `IntPtr* errOut` ([ADR-104](104-reverse-thunk-error-channel.md)).
- The shared runtime registration is a fixed 7-slot contract with its own hash
  (`RirBridging.kt:1110`). Every slot added there is a startup-contract change for every consumer.
- Strings already cross C# → Kotlin as `Marshal.StringToCoTaskMemUTF8` memory that Kotlin frees
  natively with `free` / `CoTaskMemFree` (`freeManagedString`, `NugetGenerateBindingsTask.kt:4305-4331`).
  **Verified by reading**: the bridge already lets Kotlin free C#-allocated CoTaskMem with no
  registered slot.
- Phase 13's "collection-typed slots for a Kotlin-implemented C# interface"
  (`docs/backlog/collection-typed-slots-kotlin-implemented-c-interface.md`) rides whatever wire
  this ADR picks. It is out of scope here, but the wire must not foreclose it.

## Alternatives Considered

### 1. Slot buffer: one native `long[]` per collection, in both directions (chosen)

A collection crosses as **one pointer** to a flat buffer of 8-byte slots: `[count][slot 1]...[slot
n]` for a list or set, `[count][k1][v1]...[kn][vn]` for a map. `IntPtr.Zero` is a null collection.
Each slot holds the element in the **scalar wire form the reverse bridge already uses for that
type**: an integer widened to `long`, a `double` bit-cast, a UTF-8 CoTaskMem string pointer, a
`GCHandle` `IntPtr`, an enum ordinal, `0` for a null reference element.

- Return (C# → Kotlin): the thunk materializes the collection, allocates the buffer with
  `Marshal.AllocCoTaskMem`, fills it, returns the pointer. Kotlin reads `count` and the slots,
  builds the Kotlin collection, frees string slots and the buffer with the existing
  `freeManagedString`.
- Parameter (Kotlin → C#): the Kotlin stub allocates the buffer inside the `memScoped` block it
  already opens for `.cstr.ptr` arguments, the thunk reads it into a `List<T>` / `HashSet<T>` /
  `Dictionary<K,V>` before calling the member. Nothing to free: the scope owns it, exactly like a
  string argument today.

Pros: one crossing per collection regardless of size. **Zero new runtime registration slots**, so
`NUGET_RUNTIME_CONTRACT_HASH` does not move. At the C ABI the collection is one pointer, so it is
wire-identical to a handle for arity (ADR-059's 22-argument ceiling), `cfnType`, `csAbiType` and
the out-pointer machinery. The mid-copy exception path is entirely inside one `try` on one side.
Both directions are driven by memory plus the existing member thunk, so Phase 13 inverts it with no
new mechanism (see Consequences). Spiked end to end, see Decision.

Cons: a new wire shape to document. 8 bytes per element even for a `bool`. A struct element needs a
multi-slot extension later (deferred, not foreclosed: flatten the ADR-056 leaves into k slots per
element).

### 2. Reuse the forward `nuget_list_*` / `nuget_map_*` / `nuget_set_*` exports

The C# thunk would `DllImport` the forward runtime and build a **Kotlin** list by calling into
Kotlin: `nuget_list_create`, then per element box + `nuget_list_add` + `nuget_dispose`.

Pros: no new wire; the container and boxes are counted by `nuget_live_handles`, so the leak harness
sees them for free.

Cons, each **verified by reading** `NugetRuntime.kt:145-243`: `nuget_list_add` takes a
`StableRef` to a *Kotlin* object, so a bound class element (`List<Widget>`) has nothing to add:
the Kotlin wrapper around a `GCHandle` is generated per type in the reverse bindings, which means a
new per-type `@CName` "wrap this GCHandle" export family, the exact opposite of reuse. It costs
2n+2 re-entrant crossings (Kotlin → thunk → `DllImport` → Kotlin) per collection where
Alternative 1 costs one. It couples every reverse shim to the forward `Interop.cs` vocabulary, which
the backlog item itself calls "forward-pipeline-only". For Phase 13 it is backwards: there C# is the
caller and Kotlin the callee, so the roles the forward exports assume do hold, but the element
problem is identical.

### 3. Reverse-native handle wire: `GCHandle` to the collection plus count / element-at thunks

The thunk returns `GCHandle.Alloc(snapshot)`; Kotlin calls `count(h)` then one accessor per
element through new `nuget_runtime_register` slots, and a create / add family for parameters.

Pros: the closest mirror of ADR-011's forward mechanism; no raw buffer.

Cons: n+1 function-pointer crossings per collection. Collapsing the accessors by wire kind still
needs about 10 new runtime slots (count, get long / double / string / handle, create, add × 4),
taking the shared runtime from 7 to 17 and moving `NUGET_RUNTIME_CONTRACT_HASH` for every consumer.
An exception mid-copy can now land between crossings, with the snapshot handle and a half-built
Kotlin list both live, so every accessor call needs its own `errOut` check. Nothing it can carry
that Alternative 1 cannot.

### 4. Live view: a Kotlin `AbstractMutableList` over a `GCHandle`

Write-through, no copy. Rejected on the restatement itself ("eagerly copied") and on
[ADR-011](011-collection-type-mapping.md)'s own reasoning: every `get` is a crossing, iteration
races with C# mutation, and the lifetime of the C# collection becomes the Kotlin consumer's problem.

### 5. Mirror C# mutability at return position (`IList<T>` → `MutableList<T>`)

What the ROADMAP line's wording says (`IDictionary<K,V>` → `MutableMap<K,V>`) and what forward
[ADR-011](011-collection-type-mapping.md) does read right to left: a mutable C# declared type
returns the mutable Kotlin type. Orthogonal to the wire, it only changes the rendered Kotlin type.

Pros: symmetric with the forward direction; the ROADMAP's own words.

Cons: the value is a copy, so `roster.tags.add(x)` compiles and changes nothing in C#. On a
property, where the syntax reads as "the object's own list", that is a trap the type system could
have closed. Rejected at the 2026-09-21 human gate. A consumer who wants a mutable copy writes
`.toMutableList()`, which says "copy" at the call site.

## Decision

Alternative 1.

### Type mapping

Matched on the `TypeReference`'s namespace + name only, never on its resolution scope.
**Verified by spike**: the same definitions resolve to different reference assemblies
(`IReadOnlyList\`1` to `System.Runtime`, `List\`1` / `Dictionary\`2` / `HashSet\`1` to
`System.Collections`), so an assembly-qualified match would silently miss half the table.

| C# declared type | Kotlin at return, property and parameter | C# container built for a parameter |
|---|---|---|
| `IEnumerable<T>`, `IReadOnlyCollection<T>`, `IReadOnlyList<T>`, `ICollection<T>`, `IList<T>`, `List<T>` | `List<T>` | `List<T>` |
| `IReadOnlySet<T>`, `ISet<T>`, `HashSet<T>` | `Set<T>` | `HashSet<T>` |
| `IReadOnlyDictionary<K,V>`, `IDictionary<K,V>`, `Dictionary<K,V>` | `Map<K,V>` | `Dictionary<K,V>` |

- **Every position gets the read-only Kotlin type**, for every C# definition, mutable or not. No
  `MutableList` / `MutableSet` / `MutableMap` is rendered anywhere. Decided at the 2026-09-21 human
  gate: the value is a copy, so a mutable return type would let `roster.tags.add(x)` compile and
  change nothing in C#. This **departs from the ROADMAP line's wording** (`IDictionary<K,V>` →
  `MutableMap<K,V>`) and **from forward ADR-011's mutability mirror** (Alternative 5), on purpose.
  The mapping is therefore position-independent.
- At a parameter the read-only type is also the permissive one: nothing writes back (the forward
  direction does not either, see the ROADMAP `MutableMap`/`MutableSet` write-back item), and
  `listOf(...)` must compile against `void Add(List<string>)`.
- `IEnumerable<T>` is in v1 as an eager `List<T>` (`ToArray()` in the thunk). **Documented
  constraint**: an infinite lazy sequence never returns, and a very large one is fully materialized
  in memory on both sides. The bridge cannot tell either from a finite one.
- **Verified by spike** (compiles): `List<T>` is assignable to all six list-like declared types,
  `HashSet<T>` to `ISet<T>` / `IReadOnlySet<T>`, `Dictionary<K,V>` to both dictionary interfaces.
- The shim must pass the materialized container **cast to the declared C# type**
  (`(IList<int>)list`). **Verified by spike**: given `Foo(IList<int>)` beside `Foo(List<int>)`,
  `O.Foo(list)` with a `List<int>` local prints `List overload` and `O.Foo((IList<int>)list)`
  prints `IList overload`. Without the cast both thunks of such a pair would dispatch to the
  `List<int>` overload, silently. This is why `RirCollectionType` carries the declared definition
  name and not only a kind.

### Overloads that collapse to one Kotlin signature

Collapsing six list-like definitions onto `List<T>` makes C# overloads collide in Kotlin that never
could before, because until now every such member was skipped.

- **Verified by reading** (`NugetGenerateBindingsTask.kt`, `validateKotlinSignatures`, the
  `require(false)` under "Kotlin signature collision"): two bridgeable methods of one class whose
  camel-cased name and `kotlinCollisionType()` parameter list match are a **hard generation
  failure for the whole build**, not a skip. `Foo(IList<int>)` beside `Foo(List<int>)`, or the very
  common `AddRange(IEnumerable<T>)` beside `AddRange(List<T>)`, both map to `fun foo(xs: List<Int>)`.
  Shipping this ADR without a policy turns an upgrade into a broken build for any package with such
  a pair.
- **Decided at the 2026-09-21 human gate (Q8):** when the colliding members differ only in the
  collection definition at one or more positions, drop **all** of them with a named
  `skipped_overload_set` diagnostic, never "all but one" (ADR-072 Decision 5's rule: the outcome
  must not depend on declaration order). Scoped narrowly: this rule fires only for a collision the
  collection mapping itself caused (two overloads whose non-collection parameters are otherwise
  identical); an ordinary Kotlin-signature collision unrelated to a collection stays the existing
  hard `require(false)` failure, unchanged. **Verified by test** (`Roster.AddRange(IEnumerable<string>)`
  beside `AddRange(List<string>)` in the fixture; the plugin's `NugetCollectionBindingTest` asserts
  both are absent from the generated Kotlin and every other `Roster` member still binds).
- The cast still matters for pairs that do **not** collide. **Verified by spike**: with
  `Pick(IList<int>, Cat)` beside `Pick(List<int>, IFeedable)` and `Cat : IFeedable`, the uncast call
  `O.Pick(list, cat)` is `error CS0121: The call is ambiguous`, a shim that does not compile; with
  each argument cast to its declared type the two calls print `A(IList,Cat)` and
  `B(List,IFeedable)`. So the fixture's overload pair carries a distinguishing second parameter:
  the literal `Pick(IList<int>)` / `Pick(List<int>)` pair would fail generation before it could pin
  anything.

### Element vocabulary (v1)

Exactly [ADR-072](072-closed-constructed-generics-in-kotlin.md) Decision 6's type-argument
vocabulary minus the type parameter: a primitive (`bool`, `byte`, `short`, `int`, `long`, `float`,
`double`, `char`), `string`, a bound enum, a bound class handle, a bound interface. `string`,
handle and interface elements and map values may be nullable ([ADR-053](053-nullable-reference-types.md));
the collection itself may be nullable. Map keys: primitive, `string`, enum, never nullable.

Everything else keeps the member skipped, named:

| Shape | Diagnostic |
|---|---|
| struct element (`List<Point>`, ADR-056 Scope), nested collection, `Nullable<T>` element (`List<int?>`), bound generic instance element (`List<Box<int>>`), type-parameter element inside a generic class, `object` | new `skipped_collection_element` |
| an unmapped BCL definition (`Queue<T>`, `ImmutableArray<T>`, `KeyValuePair<K,V>`, `ObservableCollection<T>`, ...) | existing `skipped_unbound_generic_instantiation`, hint text corrected |
| `Task<IReadOnlyList<T>>` ([ADR-152](152-task-to-suspend-fun.md)) | existing `info_async_not_yet_mapped` |
| arrays (`T[]`) | new `skipped_array` (today: no diagnostic at all) |
| a collection-typed slot on a Kotlin-implemented interface (Phase 13) | existing `skipped_kotlin_bridge` (`isKotlinBridgeSlotType`, `RirBridging.kt:1222`, gains a `false` arm) |

### Metadata discovery

All **verified by spike** (net10.0 SDK 10.0.300, `System.Reflection.Metadata`, probe assembly built
with `<Nullable>enable</Nullable>`; command and output below):

1. A BCL collection in a signature is a `GENERICINST` over a **`TypeReference`**, raw type kind
   `0x12` (CLASS) for every mapped definition, interfaces included. It reaches the reader exactly
   where Decision 9's diagnostic sits today, so the new branch goes in
   `GetGenericInstantiation` between the ADR-072 branch and that diagnostic, keyed on `rawName`.
2. `NullableAttribute` bytes are pre-order with the **collection node first**, and a value-type
   argument contributes **no byte**: `IReadOnlyList<string?>` is `[1,2]`, `IReadOnlyList<string>?`
   is `[2,1]`, `IReadOnlyDictionary<string, Widget?>` is `[1,1,2]`, `IDictionary<string, int>`
   (two annotatable nodes, both non-null, `int` contributing none) carries no member attribute at
   all under a class-level `NullableContext(1)`. So
   `CountAnnotatableNodes` (`Program.cs:2756`) gains `RirCollectionType c => 1 +
   c.TypeArguments.Sum(CountAnnotatableNodes)`, and `IsAnnotatable` (`:2744`) and `ApplyPreOrder`
   (`:2826`) gain the matching arm. This is the same shape as the existing
   `RirGenericInstanceType` arm, and ADR-072 Decision 7's count-mismatch fail-fast (`:2788`) covers
   a wrong guess.
3. When every node agrees, Roslyn emits no member attribute at all and hoists a
   `NullableContextAttribute` onto the method: `Dictionary<int, string?>?` has no `[2,2]`, it has
   method-level `NullableContext(2)`. The existing broadcast tier (`ResolveByteArray`, `:2877`)
   already handles this; it must not be bypassed.
4. The attribute constructor is a **`MemberReference`** on this TFM, as ADR-053 already records.
5. `List<int?>` is `GENERICINST<List\`1, GENERICINST<System.Nullable\`1 (kind 0x11), Int32>>` with no
   nullable byte. It must be refused by name, because the inner instantiation otherwise reaches
   Decision 9's diagnostic and blames the BCL.
6. `Task<IReadOnlyList<string?>>` is `[1,1,2]`: the `Task` node, then the collection, then the
   element. ADR-152's `RirAsyncType` arm composes with the new arm without change, so lifting the
   async deferral later is a generator job, not a reader one.
7. Arrays are `SZARRAY(element)`, the array node **is** annotatable (`string?[]` is `[1,2]`,
   `string[]?` is `[2,1]`, `int[]?` hoists to `NullableContext(2)`). Recorded for whoever lifts the
   array deferral.
8. A generic **value type** contributes a `0` byte for itself (`ImmutableArray<string?>` is
   `[0,2]`, `KeyValuePair<string, string?>` is `[0,1,2]`). Recorded so that nobody adds
   `ImmutableArray<T>` to the table assuming it decodes like `List<T>`.

```text
$ dotnet run -c Release -- ../probe/bin/Release/net10.0/probe.dll      (abridged)
TYPE Api: NullableContextAttribute(memberref) blob=01-00-01-00-00
Ints:     ret=GENERICINST<REF(k=18,System.Runtime):System.Collections.Generic.IReadOnlyList`1 | Int32>
NStrs:    ret=GENERICINST<REF(k=18,System.Runtime):...IReadOnlyList`1 | String>        param#0: blob=01-00-02-00-00-00-01-02-00-00
NList:    ret=GENERICINST<REF(k=18,System.Runtime):...IReadOnlyList`1 | String>        param#0: blob=01-00-02-00-00-00-02-01-00-00
NWidgets: ret=GENERICINST<REF(k=18,System.Collections):...List`1 | DEF(k=18):Widget>   param#0: blob=01-00-02-00-00-00-01-02-00-00
D1:       ret=GENERICINST<REF(k=18,System.Runtime):...IDictionary`2 | String , Int32>  (no member attribute)
D2:       ret=GENERICINST<REF(k=18,System.Runtime):...IReadOnlyDictionary`2 | String , DEF(k=18):Widget>  param#0: blob=01-00-03-00-00-00-01-01-02-00-00
D3:       ret=GENERICINST<REF(k=18,System.Collections):...Dictionary`2 | Int32 , String>   method: NullableContextAttribute blob=01-00-02-00-00
NI:       ret=GENERICINST<REF(k=18,System.Collections):...List`1 | GENERICINST<REF(k=17,System.Runtime):System.Nullable`1 | Int32>>  (no attribute)
Async:    ret=GENERICINST<...Task`1 | GENERICINST<...IReadOnlyList`1 | String>>         param#0: blob=01-00-03-00-00-00-01-01-02-00-00
A2:       ret=SZARRAY(String)   param#0: blob=01-00-02-00-00-00-01-02-00-00
A5:       ret=SZARRAY(Int32)    method: NullableContextAttribute blob=01-00-02-00-00
IA:       ret=GENERICINST<REF(k=17,System.Collections.Immutable):...ImmutableArray`1 | String>  param#0: blob=01-00-02-00-00-00-00-02-00-00
Take(a):  param#1 blob=...-01-02   Take(c: IDictionary<string, Widget?>?): param#3 blob=01-00-03-00-00-00-02-01-02-00-00
```

**Verified by the shipped build, not only inferred:** `TestDependency` targets net8.0 (its actual
TFM, unlike the net10.0 SDK the probe above used), and it decoded exactly as the net10.0 spike
predicted, with no TFM-specific branch needed anywhere in the reader. The "encoding is Roslyn's and
not the TFM's, except for which reference assembly a definition resolves to" claim above is now an
observation, not an inference.

### RIR model

```kotlin
@Serializable
enum class RirCollectionKind { @SerialName("list") LIST, @SerialName("set") SET, @SerialName("map") MAP }

@Serializable
@SerialName("collection")
data class RirCollectionType(
  val collection: RirCollectionKind,
  // CLR name of the DECLARED definition ("System.Collections.Generic.IList`1"): the shim casts the
  // container it built to this, so C# overload resolution picks the overload the thunk was made for
  val definition: String,
  val typeArguments: List<RirTypeRef>,
  val nullable: Boolean = false,
) : RirTypeRef
```

No `mutable` flag: since every position renders the read-only Kotlin type, nothing would read it.
`definition` stays, the shim cast needs it.

A new sealed variant rather than a flag on `RirGenericInstanceType`, for the reason
[ADR-076](076-instant-mapping.md) gives: the compiler then enumerates every `when`. **Verified by
counting**: 34 exhaustive `is RirEnumType ->` sites across `NugetGenerateBindingsTask.kt` (18),
`NugetGenerateShimsTask.kt` (13) and `RirBridging.kt` (3). Most arms are the pointer arm. The sites
the compiler **cannot** force are the ones to grep for by hand: `argConversion`'s boolean-guard
`when` with an `else -> name` fall-through (`NugetGenerateBindingsTask.kt:3613`, the same trap
ADR-072 recorded there), and the import collectors `typeContains` (`:186`), `referencedEnumTypes`
(`:1549`), `referencedInterfaceTypes` (`:1715`), `referencedHandleTypes` (`:1730`), which must
recurse into `typeArguments` or an element type used nowhere else loses its import (a compile error
in the generated Kotlin, loud, not silent).

`isNullable` (`RirBridging.kt:630`) gains `is RirCollectionType -> nullable`. This is the
ADR-053 failure class for the fourth time: an un-extended accessor binds every nullable collection
non-null. `describe()` (`:649`) renders `collection<args>` with each argument's own `?`, which puts
element nullability into `contractHash` for free. No `REVERSE_ABI_TAG` bump: no existing member's
signature changes, newly admitted members change `slotCount` and the hash on their own.

### Wire

**Verified by spike, end to end**: Kotlin/Native 2.4.10 `macosArm64` dylib hosted in a .NET 10
process, real `[UnmanagedCallersOnly(CallConvs = [CallConvCdecl])]` thunks passed as function
pointers, the ADR-104 `errOut` shape, `Marshal.AllocCoTaskMem` memory freed from Kotlin with
`platform.posix.free`:

```text
-- kotlin/native host half --
kotlin names=[a, null, c] err=null          IReadOnlyList<string?>  -> List<String?>, null slot = 0
kotlin names=null err=null                  List<int>? returning null -> IntPtr.Zero -> null
kotlin scores={x=1.5, y=-2.25}              IDictionary<string,double>, interleaved, Double.fromBits
-2147483599                                 List<Int> built in memScoped, read by the thunk, summed in C#
csharp got [héllo|<null>|]                  List<String?> via .cstr.ptr.toLong(), UTF-8, empty string kept
Throws ptr=0 errSet=True ex=boom mid-enumeration liveStrings=4      (C#-only half)
```

C# side, fixed helper emitted once into the runtime shim (beside `ManagedErrorMessage_Thunk`,
`NugetGenerateShimsTask.kt:2417`):

```csharp
public static IntPtr Write<T>(IEnumerable<T>? source, ToSlot<T> toSlot)
{
    if (source is null) return IntPtr.Zero;
    T[] items = source.ToArray();      // user code runs HERE, before any allocation
    long* buf = (long*)Marshal.AllocCoTaskMem(checked((items.Length + 1) * sizeof(long)));
    buf[0] = items.Length;
    for (int i = 0; i < items.Length; i++) buf[i + 1] = toSlot(items[i]);
    return (IntPtr)buf;
}
```

`WriteMap` takes `IEnumerable<KeyValuePair<K,V>>`, which both dictionary interfaces implement
(verified: compiles for `IDictionary` and `IReadOnlyDictionary`). Per member the generator emits one
expression into the shared tables, `csReturnConversion` (`NugetGenerateShimsTask.kt:364`) and
`paramConversion` (`:439`), with a slot lambda chosen by element type. `returnBodyLines` (`:1617`)
is already shared by the sync thunk and ADR-152's `End` thunk.

Slot encodings: integers and `char` widened to `long`; `bool` as 0/1; `float` widened to `double`
then bit-cast (`BitConverter.DoubleToInt64Bits` / `Double.fromBits`, exact for every `float`);
enum as its ordinal; `string` as `Marshal.StringToCoTaskMemUTF8` or 0; handle and interface as
`GCHandle.ToIntPtr(GCHandle.Alloc(x))` or 0.

Kotlin side, fixed helpers in `:nuget-runtime` (the ADR-152 `NugetAwait.kt` precedent, with a
`nativeTest` beside it), taking the release function as a parameter so the module needs no
per-target `free` split; the generated stub passes `::freeManagedString`.

### Ownership and the exception path

- **Return, class-handle elements**: each slot is a fresh strong `GCHandle`, owned by the Kotlin
  wrapper the stub builds from it, freed by that wrapper's `close()` / `Cleaner` exactly like a
  scalar handle return (ADR-051). n elements, n wrappers, n frees. The list itself owns nothing.
- **Return, mid-copy throw in C#**: `source.ToArray()` is the only place user code runs (a lazy
  `IEnumerable<T>` that throws on the third `MoveNext`), and it runs before any allocation.
  **Verified by spike**: the throw lands in `errOut`, the thunk returns 0, zero strings or handles
  were allocated. After that point every `toSlot` in the v1 vocabulary can only fail by OOM. The
  helper still wraps the fill loop in a `catch` that frees the partial buffer (strings via
  `FreeCoTaskMem`, handles via `GCHandle.Free`, by element kind) and rethrows into the thunk's
  ADR-104 `catch`.
- **Return, Kotlin side**: the stub checks `errOut` before touching the pointer (ADR-104 order),
  then reads inside `try { } finally { release(buffer) }`. A string slot is freed immediately after
  `toKString()`. **Inferred**: nothing in the read loop can throw except OOM; if it did, the unread
  string slots and unwrapped handles leak. Accepted, same as every other OOM path in the bridge.
- **Parameter**: the buffer and every `.cstr` live in the stub's `memScoped`; handle slots are
  borrowed (`handle.require(...)`, the raw pointer, no new `GCHandle`). The thunk resolves each
  with `GCHandle.FromIntPtr(p).Target` and copies into a managed container before the member runs.
  Nothing to free on either side, on either path.
- **Proving a leak**: reverse handles are uncounted by `NugetMarshal.LiveHandles` (ROADMAP,
  Performance), so the counter cannot see these. The proof is the ADR-121 collectability shape,
  mirrored: the C# fixture keeps a `WeakReference` to each element object it hands out and exposes
  an alive-count; Kotlin closes every wrapper; the test polls `GC.Collect()` until the count reads
  0. A leaked slot `GCHandle` is a strong root, so the count stays above 0 and the row fails.

### Consumer API

```csharp
// TestDependency
public class Roster
{
    public IReadOnlyList<string> Names { get; }
    public IList<Tag> Tags();
    // overload pair that pins the shim cast (Tag : ILabelled). The second parameter is what keeps
    // the two from colliding in Kotlin, see "Overloads that collapse" above
    public string Pick(IList<int> xs, Tag tag);
    public string Pick(List<int> xs, ILabelled labelled);
    public IReadOnlyDictionary<string, int> Scores();
    public IReadOnlyList<string?> Nicknames();
    public IReadOnlyList<string>? MaybeNames();
    public int Enroll(IEnumerable<string> names);
    public void Rank(IDictionary<string, int> scores);
}
```

```kotlin
val roster = Roster()
val names: List<String> = roster.names
val tags: List<Tag> = roster.tags()                 // IList<Tag> in C#, a read-only copy here
val scores: Map<String, Int> = roster.scores()
val nicknames: List<String?> = roster.nicknames()
val maybe: List<String>? = roster.maybeNames()
roster.enroll(listOf("Oreo", "Mylo"))
roster.rank(mapOf("Oreo" to 9))
```

## Amendments found during implementation (2026-09-21)

Everything below was wrong or missing in the Decision as originally drafted, and each is corrected
here (not only in the implementing agent's report) because an implementer following this ADR
literally must not reproduce the same bug.

- **A third diagnostic kind exists beyond the two in "Element vocabulary" above:
  `skipped_collection_position`.** The vocabulary table only names a bad *element*
  (`skipped_collection_element`) and an unmapped *definition* (`skipped_unbound_generic_instantiation`);
  it says nothing about a collection sitting in a position that never reaches the shared conversion
  tables at all. A struct member (ADR-056's flattened out-pointers) and a bound-interface member
  (ADR-070's hand-written dispatch body) are exactly that: `isV1Type` refuses a collection there
  (`allowCollections = false`), and `collectionPositionDiagnostics` (`RirBridging.kt`) emits the
  named skip so the member does not vanish silently, the ADR-043 diagnostics rule. **Verified by
  reading and by a plugin test** pinning the diagnostic for both owner kinds.
- **The shim needs `global::`-qualification on the collection's element type, not only on the
  collection itself.** The Decision's "Wire" section only fully-qualifies the declared collection
  type (`(IList<int>)`-style casts); an element declared in a *different* bound C# namespace from
  the member's own type (`IReadOnlySet<CatMood>` on `Test.Roster.Roster`, with `CatMood` in
  `Test.Enums`) is `CS0246: type or namespace not found` without the same treatment, because the
  shim carries no `using` for a namespace nothing else on the type mentions. **Verified by compile
  error**, fixed by `csCollectionArgument` (`NugetGenerateShimsTask.kt`) qualifying every enum,
  class, and interface element with `global::{namespace}.{name}`.
- **The shim's parameter cast needs an explicit `!` or `?`, not a bare cast.** `paramConversion`
  reads the slot buffer through `NugetCollections.Read*`, which returns `null` for a null buffer;
  casting that straight to the non-nullable declared type (e.g. `(IEnumerable<string>)`) is
  `CS8604: possible null reference argument` under `#nullable enable`. A non-null parameter needs
  the buffer-read result forced with `!` (the same "this can't actually be null, the bridge already
  enforced it" reasoning ADR-053's string/handle sites use elsewhere); a nullable parameter needs
  `?` on the container-type cast instead, so a genuine null buffer passes straight through as
  `null`. **Verified by compile error**, both arms now in `paramConversion`'s `RirCollectionType`
  case.
- **The Kotlin side reaches the runtime's slot helpers through an expect/actual pair, exactly like
  `freeManagedString`, and for the same reason.** A generated `nativeMain` stub file cannot see the
  `:nuget-runtime` klib directly: the runtime is `api` only on the per-target `{target}Main` source
  set (ADR-127), not on the shared `nativeMain` a generated stub lives in. So `nugetReadSlots` and
  `NativePlacement.nugetWriteSlots` are declared `internal expect` in the shared generated
  `NugetInterop.kt` and `internal actual` per target, delegating one line each to
  `readSlotsForKotlin`/`writeSlotsForKotlin` in `:nuget-runtime`'s `NugetCollections.kt`, the same
  seam `nugetAwaitTask` (ADR-152) already established. **General rule, verified by this and the
  ADR-152 precedent**: any runtime helper a generated stub needs to call costs an expect/actual
  pair, never a direct klib import.
- **The "34 exhaustive `when` sites" count above (Metadata discovery / RIR model) overstates what
  actually had to change.** It counts every existing `is RirEnumType ->`-shaped site as a proxy for
  "the compiler will force a `RirCollectionType` arm here", but several of those sites were already
  covered by a catch-all `else` branch that a new sealed variant does not disturb. **Verified by
  compile error**, the real count implementing this ADR was **24** compiler exhaustiveness errors:
  10 in `NugetGenerateBindingsTask.kt`, 10 in `NugetGenerateShimsTask.kt`, 4 in `RirBridging.kt`.
  The non-exhaustive sites this ADR already flagged by hand (`argConversion`'s boolean-guard
  `when`, the four import collectors) still needed the same manual grep-and-fix treatment the
  original text describes; that part was accurate.
- **`describe()`'s blind spot to a collection's `definition` is a stated Decision, not a defect
  found later, but is called out here because it is easy to miss in the Wire section it lives in:**
  `describe()` renders a `RirCollectionType` as `collection<args>`, never including `definition`.
  So `IList<int>` and `List<int>` produce the **same** ADR-054 `contractHash` for an otherwise
  identical member, even though the shim's cast differs between them. A C# API change from one
  declared collection shape to the other therefore moves the generated shim's cast but does not
  move the contract hash a consuming build checks at startup. **Accepted, not a bug**: both halves
  of the shim (Kotlin registration export and C# thunk) always regenerate together from the same
  `reverse-ir.json`, so there is no scenario where a stale shim with the old cast could pass a
  contract check meant to catch exactly that kind of staleness. Recorded explicitly so a future
  reader does not "fix" `describe()` into carrying `definition` without re-deriving why it was left
  out.

## Consequences

- `List<int>` and friends stop being `skipped_unbound_generic_instantiation` for the mapped
  definitions. Every consumer's diagnostics report and generated surface grows on upgrade.
- `RirCollectionType` is additive in `reverse-ir.json`, but an old plugin reading a new reader's
  JSON fails on the unknown `kind`. Both ship together, as with every prior variant.
- Positions in v1: static and instance method return and parameter, and (decided 2026-09-21)
  constructor parameter and property get and set **if they ride the shared paths**. **Verified by
  reading, corrected from "inferred" above**: the constructor and property paths do **not** ride
  one shared helper the way an ordinary method parameter does. A method-parameter argument gets its
  `memScoped` block from `buildStubMethod`'s own `hasStringParam`/`isScopedRef` check; the
  constructor path needed its own separate `hasStringParam`/`isScopedRef` check added to
  `buildConstructHelper` (`NugetGenerateBindingsTask.kt`), and the property setter needed the same
  check added to `buildStubProperty`'s setter. All three now scope-wrap a collection argument
  identically, but as three call sites reading the shared `isScopedRef` predicate, not as one
  helper the other two delegate to; a fourth hand-written call site that forgot the check would
  silently allocate the slot buffer outside any `memScoped` block instead of failing to compile.
- The fixture carries one `IList<int>` / `List<int>` overload pair, so the shim cast is pinned end
  to end and not only by the C# spike.
- The ROADMAP line's `MutableMap<K,V>` wording is superseded by this ADR; the documenter rewrites
  it when the item closes.
- Phase 13 is not foreclosed. A slot parameter is this ADR's return path run inside the callback;
  a slot return is the parameter path with the allocation side flipped, which needs Kotlin to
  allocate with the allocator C# frees (`malloc` on Unix, `CoTaskMemAlloc` on Windows, the
  expect/actual mirror of `freeManagedString`). **Inferred, not verified.**
- Windows: the buffer is freed with `CoTaskMemFree` through the same `freeManagedString` actual
  strings use (`NugetGenerateBindingsTask.kt:4316`). **Inferred** from strings working on Windows
  CI; only macOS arm64 was spiked.
- Deferred: mutable Kotlin return types and any write-back, struct elements (multi-slot elements), nested collections (a slot holding a nested
  buffer pointer), `Nullable<T>` elements (needs a presence slot), arrays (and with them `byte[]` →
  `ByteArray`, the reverse of ADR-151, which wants a blit and not slots; named `skipped_array` until
  then), `Task<collection>` (stays `info_async_not_yet_mapped`), immutable and concurrent collections, non-generic `IList` / `IEnumerable`.
