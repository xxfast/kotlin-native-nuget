# ADR-073: `Map`/`Set` (and mutable variants) as method parameters: copy-in over the existing list write-side

## Status

Accepted

## Context

Forward direction, Kotlin to C#. A Kotlin method, extension function or constructor whose *parameter*
is `Map<K,V>`, `MutableMap<K,V>`, `Set<T>` or `MutableSet<T>` is today omitted from the generated C#
entirely, with a `SKIPPED_UNSUPPORTED_INPUT` diagnostic. Property and method *returns* of all four
kinds already work (Phase 8). ROADMAP.md line 92 is the item; [ADR-062](062-forward-callable-plan.md)
named it as an intentional gap and [ADR-064](064-forward-unsupported-declaration-diagnostics.md)
gave it a named diagnostic, but neither closed it.

### What already exists (all Verified by reading repo source)

The `List`/`MutableList` parameter path is complete and is the template:

| Piece | Where | Verified |
|---|---|---|
| Native write exports `nuget_list_create` / `nuget_list_add` | `exports/GenericClassExports.kt:161-181` | Yes |
| C# `NugetMarshal.CreateList<T>(IEnumerable<T>)` | `cir/CirMarshalRenderer.kt:294-314` | Yes |
| C# prelude `IntPtr xHandle = NugetMarshal.CreateList(x);` | `forward/ForwardCirPlanProjection.kt:480-485` | Yes |
| C# cleanup `NugetListNative.Dispose(xHandle);` | `forward/ForwardCirPlanProjection.kt:487-492` | Yes |
| Call argument `xHandle` for `BridgeType.Collection` | `forward/ForwardCirPlanProjection.kt:465` | Yes |
| Kotlin lowering `.map { it as T }` / `.mapTo(mutableListOf()) { it as T }` | `forward/ForwardKotlinPlanEmitter.kt:658-670` | Yes |
| Wrap-helper gate keyed on `ForwardHelperRequirement.COLLECTION` | `NugetProcessor.kt:847-861`, requirement set at `forward/ForwardCallablePlanner.kt:814` | Yes |
| ABI parameter shape (POINTER / IN / `HANDLE_TO_COLLECTION`) | `forward/ForwardCallablePlanner.kt:904-919` | Yes |

Map and Set already have their **read** side and their **dispose**:

- `nuget_map_count` / `nuget_map_key_at` / `nuget_map_value_at` at `exports/GenericClassExports.kt:208-243`
- `nuget_set_count` / `nuget_set_element_at` at `exports/GenericClassExports.kt:184-206`
- `NugetMapNative.Dispose` / `NugetSetNative.Dispose` at `cir/CirMarshalRenderer.kt:369` and `:384`,
  both bound to entry point `nuget_dispose`, which is `handle.asStableRef<Any>().dispose()`
  (`exports/GenericClassExports.kt:354-360`) and therefore works for any StableRef, list or map or set.
- The C#-side helper-class gate (`CollectionHelperTracker.trackPlan`, `cir/CirTypeMapping.kt:111-114`)
  and the Kotlin-side export gate (`plannedCollectionKinds()`, `NugetProcessor.kt:726-745`) **already
  scan plan parameters**, not just results. Neither needs widening.

What is missing is narrow: the native **write** side for map and set, the C# `CreateMap`/`CreateSet`
helpers, a kind-aware prelude/cleanup, and the planner clause.

### The one skip clause

`BridgeType.inputSkipReason()`, `forward/ForwardCallablePlanner.kt:1267-1275` (Verified):

```kotlin
is BridgeType.Collection -> when {
  kind != CollectionKind.LIST && kind != CollectionKind.MUTABLE_LIST ->
    ForwardPlanSkipReason.COLLECTION
  !isBridgeableComponent() -> element?.skipReason() ?: ForwardPlanSkipReason.UNSUPPORTED
  else -> null
}
```

`ForwardPlanSkipReason.COLLECTION` maps to `SKIPPED_UNSUPPORTED_INPUT`
(`forward/ForwardDiagnostic.kt:130`, Verified). The diagnostic must keep firing for the component
types this ADR leaves out, so the clause is narrowed, not deleted.

### Does a `MutableList` parameter write back today? No.

**Verified** at `forward/ForwardKotlinPlanEmitter.kt:663-665`: a `MutableList<T>` parameter lowers to

```kotlin
name.asStableRef<MutableList<Any?>>().get().mapTo(mutableListOf()) { it as T }
```

which is a **fresh Kotlin list**. Anything Kotlin appends is appended to that copy, and the C# caller's
collection is unaffected. The public C# parameter type is nonetheless `IList<T>`, because
`publicParameters()` (`forward/ForwardCirPlanProjection.kt:411-418`) and the return position share one
`BridgeType.csharpType()` (`:837-848`). So the API *already* declares a mutable interface at an input
position with no write-back, for lists. ADR-011 documented the one-way copy for the return direction
("The eager copy is one-way: Kotlin to C#"); it never said it for inputs, and no ADR does.

### Prior art

**Kotlin/Native ObjC export (Verified by spike, Kotlin/Native 2.2.10, macos_arm64).** Built a scratch
framework from

```kotlin
object Coll {
  fun takeMap(m: Map<String, Int>): Int = m.size
  fun takeMutableMap(m: MutableMap<String, Int>): Int { m["added"] = 1; return m.size }
  fun takeSet(s: Set<String>): Int = s.size
  fun takeMutableSet(s: MutableSet<String>): Int { s.add("added"); return s.size }
  fun takeNullableValueMap(m: Map<String, Int?>): Int = m.size
}
```

with `konanc coll.kt -produce framework -target macos_arm64`. The generated header says:

```objc
- (int32_t)takeMapM:(NSDictionary<NSString *, SpikeCollInt *> *)m;
- (int32_t)takeMutableMapM:(SpikeCollMutableDictionary<NSString *, SpikeCollInt *> *)m;
- (int32_t)takeSetS:(NSSet<NSString *> *)s;
- (int32_t)takeMutableSetS:(SpikeCollMutableSet<NSString *> *)s;
- (int32_t)takeNullableValueMapM:(NSDictionary<NSString *, id> *)m;
```

with, earlier in the same header,

```objc
__attribute__((swift_name("KotlinMutableSet")))
@interface SpikeCollMutableSet<ObjectType> : NSMutableSet<ObjectType> @end
__attribute__((swift_name("KotlinMutableDictionary")))
@interface SpikeCollMutableDictionary<KeyType, ObjectType> : NSMutableDictionary<KeyType, ObjectType> @end
```

Three things follow, all Verified:

1. **The read-only kinds wrap, they do not copy.** A second spike kept the argument in a Kotlin
   `object` and re-read its size after the ObjC caller mutated the original:
   `before extra put, kotlin sees 1` / `after extra put, kotlin sees 2` for both `Map` and `List`.
   Kotlin/Native's ObjC export gives Kotlin a live view of the caller's collection.
2. **The mutable kinds propagate mutation, for `MutableList`.** An ObjC driver passing an
   `NSMutableArray{a,b}` to `fun mutateList(l: MutableList<String>) { l.add("fromKotlin") }` printed
   `kotlin saw 3, objc array now has 3 -> (a, b, fromKotlin)`.
3. **The mutable map/set kinds are not usable with the platform's own types.** Passing a plain
   `NSMutableDictionary` (or `NSMutableSet`) where the header declares `SpikeCollMutableDictionary`
   compiled with a cast and then **segfaulted at runtime, exit 139**. This is documented behaviour, not
   a spike artifact: [the Kotlin docs](https://kotlinlang.org/docs/native-objc-interop.html) say
   *"`NSMutableSet` isn't converted to a Kotlin's `MutableSet`. To pass an object to Kotlin
   `MutableSet`, explicitly create this kind of Kotlin collection ... use, for example, the
   `mutableSetOf()` function in Kotlin or the `KotlinMutableSet` class in Swift."*

So the closest Kotlin analogue achieves write-back only by refusing the foreign language's own mutable
collection type. That is the cost of write-back, and it is a bad trade for a C# consumer.

**Kotlin/JVM.** No conversion at all: `kotlin.collections.Map` *is* `java.util.Map` at runtime, so a
Java caller passes its own map by reference and mutation is trivially bidirectional. The read-only vs
mutable distinction is compile-time-only and unenforced from Java. Not reachable over a C ABI.
(Inferred, from the Kotlin [Java interop](https://kotlinlang.org/docs/java-interop.html#mapped-types)
mapped-types documentation; not spiked.)

**Swift Export.** Alpha, and collections are explicitly not there yet: types inheriting from `List`,
`Set` or `Map` are ignored during export
([KT-80416](https://youtrack.jetbrains.com/issue/KT-80416)), and inheritors cannot be instantiated on
the Swift side ([KT-80417](https://youtrack.jetbrains.com/issue/KT-80417)). See
[native-swift-export](https://kotlinlang.org/docs/native-swift-export.html). (Inferred, from docs.)

**Kotlin/JS `@JsExport` and Kotlin/Wasm.** `List`, `MutableList`, `Map`, `MutableMap`, `Set`,
`MutableSet` are **not exportable at all**; a declaration using one is a compile error. See
[js-to-kotlin-interop](https://kotlinlang.org/docs/js-to-kotlin-interop.html). (Inferred, from docs.)

**CsWinRT / WinRT.** Mirrors mutability at both positions:
`IMap<K,V>` to `IDictionary<K,V>`, `IMapView<K,V>` to `IReadOnlyDictionary<K,V>`,
`IVector<T>` to `IList<T>`, `IVectorView<T>` to `IReadOnlyList<T>`, `IIterable<T>` to `IEnumerable<T>`
([.NET mappings of WinRT types](https://learn.microsoft.com/en-us/windows/apps/develop/platform/csharp-winrt/net-mappings-of-winrt-types)).
It gets live write-back because a WinRT collection is a COM interface with a cross-boundary vtable,
which our C ABI does not have for an arbitrary `Dictionary<K,V>`. (Inferred, from docs.)

The directly relevant WinRT precedent is the *other* projection, C++/WinRT, which faces our exact
problem (a native language handing a foreign collection to a projected API) and resolves it the way
this ADR proposes, including the documentation wording
([Passing parameters to projected APIs](https://learn.microsoft.com/en-us/windows/uwp/cpp-and-winrt-apis/pass-parms-to-abi)):

> **std::map&lt;K, V&gt;&&** ... Contents are moved into a temporary map. Results are *not* moved back.
>
> If the method mutates the temporary map, then those changes are not reflected in the original
> parameters. To observe the changes, pass an **IMap&lt;K, V&gt;**.

(Inferred, from docs.)

## Alternatives Considered

### 1. Copy-in, mirror the return-position C# types, document no write-back (chosen)

`Map<K,V>` to `IReadOnlyDictionary<K,V>`, `MutableMap<K,V>` to `IDictionary<K,V>`,
`Set<T>` to `IReadOnlySet<T>`, `MutableSet<T>` to `ISet<T>`, exactly as `BridgeType.csharpType()`
already renders them. The C# side drains the caller's collection into a fresh Kotlin
`mutableMapOf<Any?, Any?>` / `mutableSetOf<Any?>` through new create/put/add exports, the Kotlin export
re-types it into a fresh correctly-typed Kotlin collection, and the handle is disposed after the call.

**Pros.**
- Zero change to `csharpType()`. The four kinds already render exactly these spellings, at both
  positions, from one function. Nothing diverges.
- Consistent with the shipped `List`/`MutableList` parameter path, which is the same trade already
  made and already tested (`Tier1StructuralInteropCsTest.kt:325` asserts
  `public void AddTags(IReadOnlyList<string> tags)`, Verified).
- Consistent with ADR-011's eager-copy decision, extended to the input direction.
- Matches the C++/WinRT precedent (copy in, no write-back, say so).
- A caller holding the concrete BCL type can pass under every kind. **Verified** by spike (project
  compiled against net8.0 reference assemblies, executed on .NET 10.0.8):
  `IReadOnlyDictionary <= Dictionary: True`, `IDictionary <= Dictionary: True`,
  `IReadOnlySet <= HashSet: True`, `ISet <= HashSet: True`,
  `IReadOnlyDictionary <= SortedDictionary: True`, `IReadOnlySet <= SortedSet: True`,
  `IReadOnlyDictionary <= ImmutableDictionary: True`, `IReadOnlySet <= ImmutableHashSet: True`.

**Cons.**
- `IDictionary<K,V>` and `ISet<T>` at an input position *imply* to a C# reader that the callee may
  mutate and the caller will observe it. It will not. This is a documented limitation, not a fixed
  behaviour, and it is the same lie `IList<T>` already tells.
- A caller holding only an `IDictionary<K,V>` static type cannot call a `Map<K,V>` parameter without
  an explicit conversion, and a caller holding only an `IReadOnlyDictionary<K,V>` cannot call a
  `MutableMap<K,V>` parameter. **Verified** by the same spike:
  `IReadOnlyDictionary <= IDictionary: False`, `IDictionary <= IReadOnlyDictionary: False`,
  `IReadOnlySet <= ISet: False`, `ISet <= IReadOnlySet: False`,
  `IReadOnlyList <= IList: False`. The two dictionary interfaces are unrelated in the BCL, as are the
  two set interfaces. Every option below pays some version of this.

### 2. Read-only C# parameter types for all four kinds

`Map` and `MutableMap` both to `IReadOnlyDictionary<K,V>`; `Set` and `MutableSet` both to
`IReadOnlySet<T>`.

**Pros.** Honest: a read-only parameter type never promises write-back, so the limitation stops being
a limitation and becomes the type.

**Cons.** Requires `csharpType()` to become position-aware, splitting a function that is currently one
mapping used by the plan projection, the property projection and the legacy CIR translators. It also
diverges from `List`/`MutableList`, which would keep rendering `IList<T>` at the same position, so the
generated API would be internally inconsistent within one feature family. And it does not remove the
assignability problem, it moves it: a caller holding `ISet<T>` could no longer call a `MutableSet<T>`
parameter (`IReadOnlySet <= ISet: False`, Verified above), which is a *worse* case than the one it
fixes, because `ISet<T>` is what a Kotlin `MutableSet` return already hands the caller. Rejected on
consistency, not on principle: if the project later decides to make every input position read-only, it
should do so for `List` at the same time, as one deliberate change.

### 3. Widest-accepting input types: `IEnumerable<KeyValuePair<K,V>>` and `IEnumerable<T>`

**Pros.** Accepts everything, including a LINQ projection, an array of pairs, or an `IDictionary`
(`IEnumerable<KeyValuePair<K,V>> <= IDictionary: True` and `<= IReadOnlyDictionary: True`, Verified;
`IEnumerable<T> <= ISet` and `<= IReadOnlySet`, both True). Solves the assignability problem outright.
Matches what `CreateList<T>` already takes internally.

**Cons.** Destroys the signal. IntelliSense on a `Map<String,Int>` parameter would read
`IEnumerable<KeyValuePair<string,int>>`, which is not what a C# developer expects for a dictionary,
and it silently accepts duplicate keys (last-wins, invisibly) and duplicate set elements. It also
erases the Kotlin read-only/mutable distinction that ADR-011 deliberately preserves. Rejected.

### 4. True write-back for the mutable kinds

After the Kotlin call returns, read the (mutated) Kotlin collection back through the existing
`nuget_map_count`/`key_at`/`value_at` read exports and re-populate the caller's `IDictionary`/`ISet`
in place.

**Pros.** The `IDictionary`/`ISet` parameter type would mean what it says. It is mechanically possible
today: the read side already exists, and the C# `Clear()` + repopulate loop is straightforward.

**Cons.** It is a *different semantic from `MutableList`*, which does not write back, so the feature
family would be split down the middle for no principled reason. It is still not the Kotlin semantic:
Kotlin's `MutableMap` parameter is a live reference, so a Kotlin implementation that stores the map and
mutates it later (very common) would still not be observed, and one that mutates during a callback
would be observed out of order. It doubles the crossing cost for every mutable parameter. And it does
not compose with exceptions, since a throwing callee leaves the caller's collection in an
unspecified state. Rejected; if write-back is ever wanted it needs the ObjC-export design (a
Kotlin-owned collection type handed to the consumer), which is option 5.

### 5. The ObjC-export design: a Kotlin-backed `KotlinMutableDictionary<K,V>` C# type

Generate a C# class implementing `IDictionary<K,V>` over a live Kotlin handle, and make it the only
accepted argument type for a `MutableMap` parameter.

**Pros.** Real write-back, real Kotlin semantics, and it is what Kotlin's own ObjC export does.

**Cons.** ADR-011 rejected exactly this shape (the `NugetList<T>` lazy wrapper) for the return
position, for reasons that all still apply: a custom type in the public API, a hidden `IDisposable`
requirement, and a bridge call per element access. Worse, at an *input* position it means a C# caller
cannot pass their own `Dictionary<K,V>` at all, which the spike shows is not a theoretical cost:
passing the platform's own mutable type to the ObjC analogue **segfaults** (Verified, exit 139). This
is the single strongest argument in this ADR for not chasing write-back. Rejected.

## Decision

Adopt **option 1**. Ship `Map`, `MutableMap`, `Set` and `MutableSet` parameters together, in one
change, over the existing list write-side pattern.

### Public C# surface

| Kotlin parameter | C# parameter | A caller holding... |
|---|---|---|
| `Map<K,V>` | `IReadOnlyDictionary<K,V>` | `Dictionary`, `SortedDictionary`, `ImmutableDictionary`, `ReadOnlyDictionary` passes directly; a bare `IDictionary<K,V>` does not (Verified) |
| `MutableMap<K,V>` | `IDictionary<K,V>` | `Dictionary`, `SortedDictionary`, `ImmutableDictionary` passes directly; a bare `IReadOnlyDictionary<K,V>` does not (Verified) |
| `Set<T>` | `IReadOnlySet<T>` | `HashSet`, `SortedSet`, `ImmutableHashSet` passes directly; a bare `ISet<T>` does not (Verified) |
| `MutableSet<T>` | `ISet<T>` | `HashSet`, `SortedSet`, `ImmutableHashSet` passes directly; a bare `IReadOnlySet<T>` does not (Verified) |

No change to `BridgeType.csharpType()` (`forward/ForwardCirPlanProjection.kt:837-848`) is required;
it already produces all four spellings.

### Write-back: documented limitation, explicitly

**A `MutableMap`/`MutableSet` parameter does not write back.** Kotlin receives a fresh copy; anything
it puts, adds or removes is invisible to the C# caller. This matches the shipped `MutableList`
parameter behaviour exactly (Verified, `forward/ForwardKotlinPlanEmitter.kt:663-665`), and it matches
C++/WinRT's documented behaviour for the same shape. This must be stated in the generated XML doc
comment for any parameter of the two mutable kinds, and in the Writerside docs, in the same words:
*"Contents are copied into Kotlin. Changes Kotlin makes are not reflected back in the collection you
passed."*

### Native write-side exports

Add to `addNugetMapHelperExports` and `addNugetSetHelperExports`
(`exports/GenericClassExports.kt:184-243`), unconditionally alongside the existing read exports,
exactly as `addNugetListHelperExports` already emits create/add unconditionally alongside count/get
(Verified, `:135-182`):

```kotlin
@CName("nuget_map_create")
fun export_nuget_map_create(): COpaquePointer =
  StableRef.create(mutableMapOf<Any?, Any?>()).asCPointer()

@CName("nuget_map_put")
fun export_nuget_map_put(handle: COpaquePointer, key: COpaquePointer, value: COpaquePointer) {
  handle.asStableRef<MutableMap<Any?, Any?>>().get()[key.asStableRef<Any>().get()] =
    value.asStableRef<Any>().get()
}

@CName("nuget_set_create")
fun export_nuget_set_create(): COpaquePointer =
  StableRef.create(mutableSetOf<Any?>()).asCPointer()

@CName("nuget_set_add")
fun export_nuget_set_add(handle: COpaquePointer, element: COpaquePointer) {
  handle.asStableRef<MutableSet<Any?>>().get().add(element.asStableRef<Any>().get())
}
```

**Verified end to end by spike.** These exact declarations were compiled with
`konanc exports.kt -produce dynamic -target macos_arm64` (Kotlin/Native 2.2.10) and driven from a C
program via `dlopen`/`dlsym`. Real output:

```
map size seen by Kotlin: 2 (expect 2)
mutable map size after Kotlin put: 3 (expect 3)
map size back in C after Kotlin mutated its copy: 2 (expect 2 => no write-back)
set size seen by Kotlin: 2 (expect 2)
obj-value map sum: 42 (expect 42)
```

Three sub-claims are settled by that run and by a second probe reading the raw native counts:

- **Nothing extra is needed for `put` versus `add`.** The map case is `nuget_list_add` with one more
  boxed pointer. Both key and value cross as StableRef handles produced by the existing
  `nuget_wrap_*` exports, and both are unwrapped with the same `asStableRef<Any>().get()`.
- **Boxed values compare structurally in the intermediate collection.** Two independently created
  `StableRef` boxes of the same value collapse. Verified: a native set fed `wrap_int(7)`,
  `wrap_int(7)`, `wrap_int(8)` reports count **2**; a native set fed `wrap_string("q")` twice reports
  count **1**; a native map given the same boxed-`Int` key twice reports count **1**. So neither
  duplicate keys nor duplicate set elements can appear as an artifact of boxing.
- **No copy leaks back.** The third line above is the `MutableMap` no-write-back claim, observed.

### `null` is not representable, and that is fine for v1

`nuget_list_add`'s `element` is a non-nullable `COpaquePointer` and is dereferenced with
`element.asStableRef<Any>().get()` (Verified, `exports/GenericClassExports.kt:172-181`). The proposed
`nuget_map_put` and `nuget_set_add` keep that shape, so **a null map value, null map key, or null set
element cannot cross**. This is not a new restriction, it is the reason nullable components must stay
out of scope (below), and the planner must enforce it rather than letting a `Map<String,Int?>` through.

### Component (K / V / T) coverage: an explicit allow-list, not `isBridgeableComponent()`

`isBridgeableComponent()` (`forward/ForwardCallablePlanner.kt:1189-1214`, Verified) is **too permissive
for an input position**. It admits `Nullable`, `ValueClass`, `Char`, nested `Collection`, `Enum` and
every `PrimitiveKind`. The C# write side only knows how to box a strict subset. Two of those
overshoots are already latent defects on the shipped `List` parameter path:

| Component | `isBridgeableComponent()` | What actually happens for a `List<T>` parameter today |
|---|---|---|
| `String`, `Int`, `Long`, `Float`, `Double`, `Boolean` | admits | works; `nuget_wrap_*` exists for exactly these six (`exports/GenericClassExports.kt:363-386`, Verified) and `CreateList` branches on exactly these six (`cir/CirMarshalRenderer.kt:299-304`, Verified) |
| `ObjectHandle` | admits | works; `CreateList`'s reflective `_handle` fallback (`cir/CirMarshalRenderer.kt:305-311`, Verified). Covered by `Tier1StructuralInteropCsTest.kt:344` (Verified) |
| `Byte`, `UByte`, `Short`, `UShort`, `UInt`, `ULong`, `Char` | admits | **runtime `NotSupportedException`**: no `nuget_wrap_*` for them, so `CreateList` falls to the `_handle` branch, finds no field, and throws (Inferred from reading both sites; not spiked) |
| `Enum` | admits | same runtime `NotSupportedException` (Inferred, same reasoning) |
| `ValueClass`, nested `Collection` | admits | **`packNuget` crash**: `elementKotlinTypeName` has no branch and calls `error(...)` (`forward/ForwardKotlinPlanEmitter.kt:398-405`, Verified) |
| `Nullable` of anything | admits | **`packNuget` crash**, same `error(...)` line (Verified) |
| `Interface` | rejects | skipped, per ADR-040's deferral |

There is no fixture for any of the broken rows (`grep` for a nullable-component collection in
`test-library/src` returns nothing, Verified), which is why they have never been seen.

Therefore v1 introduces a narrower predicate for **input-position** collection components, and uses it
for map and set only:

```kotlin
/** The component types the C# write side can actually box: the six `nuget_wrap_*` primitives plus
 *  an object handle (via CreateList/CreateSet/CreateMap's reflective `_handle` fallback). */
private fun BridgeType.isWrappableComponent(): Boolean = when (this) {
  BridgeType.String -> true
  is BridgeType.Primitive -> kind in setOf(
    PrimitiveKind.INT, PrimitiveKind.LONG, PrimitiveKind.FLOAT,
    PrimitiveKind.DOUBLE, PrimitiveKind.BOOLEAN,
  )
  is BridgeType.ObjectHandle -> true
  else -> false
}
```

and `inputSkipReason()`'s Collection branch becomes:

```kotlin
is BridgeType.Collection -> when {
  !isBridgeableComponent() -> (element ?: key ?: value)?.skipReason() ?: ForwardPlanSkipReason.UNSUPPORTED
  kind == CollectionKind.LIST || kind == CollectionKind.MUTABLE_LIST -> null   // unchanged
  // ADR-073: map/set inputs are admitted only for components the write side can box.
  kind == CollectionKind.MAP || kind == CollectionKind.MUTABLE_MAP ->
    if (key?.isWrappableComponent() == true && value?.isWrappableComponent() == true) null
    else ForwardPlanSkipReason.COLLECTION
  else ->
    if (element?.isWrappableComponent() == true) null else ForwardPlanSkipReason.COLLECTION
}
```

The `SKIPPED_UNSUPPORTED_INPUT` diagnostic therefore keeps firing, and firing correctly, for
`Map<String, Mood>`, `Set<Char>`, `Map<String, Int?>`, `Set<List<String>>`, `Map<String, ChartId>` and
so on. `List` is left exactly as it is; narrowing it is a separate behaviour change (see Scope).

### C# helpers

**As shipped**, corrected from this ADR's original unconditional sketch: `CreateSet`/`CreateMap`
are gated on new `CirMarshalHelper.includesSet`/`includesMap` flags (`CirMarshalRenderer.kt:317-337`,
`CirModel.kt:147-148`, set from `CirTypeMapping.kt:106-107` via `CirTranslator.kt:373`), each
emitted only when the tracker actually saw a `Set`/`MutableSet` or `Map`/`MutableMap` collection
somewhere in the file. Emitting them unconditionally, as originally proposed here, would `CS0103`
for any consumer whose file uses `List` parameters but has zero `Map`/`Set` usage, since
`NugetMapNative`/`NugetSetNative` (the classes `Put`/`Add` dispatch to) are themselves gated the
same way and would never be emitted alongside an unconditional `CreateMap`/`CreateSet`. Factor the
boxing switch out of `CreateList` into one shared `Wrap<T>`, then express all three constructors
over it, so a future component type is added in exactly one place:

```csharp
internal static IntPtr Wrap<T>(T value)
{
    if (typeof(T) == typeof(string)) return nuget_wrap_string((string)(object)value!);
    if (typeof(T) == typeof(int)) return nuget_wrap_int((int)(object)value!);
    if (typeof(T) == typeof(long)) return nuget_wrap_long((long)(object)value!);
    if (typeof(T) == typeof(float)) return nuget_wrap_float((float)(object)value!);
    if (typeof(T) == typeof(double)) return nuget_wrap_double((double)(object)value!);
    if (typeof(T) == typeof(bool)) return nuget_wrap_bool((bool)(object)value!);
    var field = typeof(T).GetField("_handle",
        System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Public);
    if (field == null) throw new NotSupportedException($"Cannot pass {typeof(T).Name} to a Kotlin collection");
    return (IntPtr)field.GetValue(value)!;
}

public static IntPtr CreateList<T>(IEnumerable<T> values)
{
    IntPtr listHandle = NugetListNative.Create();
    foreach (T value in values) NugetListNative.Add(listHandle, Wrap(value));
    return listHandle;
}

// Emitted only when helper.includesSet is true.
public static IntPtr CreateSet<T>(IEnumerable<T> values)
{
    IntPtr setHandle = NugetSetNative.Create();
    foreach (T value in values) NugetSetNative.Add(setHandle, Wrap(value));
    return setHandle;
}

// Emitted only when helper.includesMap is true.
public static IntPtr CreateMap<TKey, TValue>(IEnumerable<KeyValuePair<TKey, TValue>> values)
{
    IntPtr mapHandle = NugetMapNative.Create();
    foreach (var pair in values) NugetMapNative.Put(mapHandle, Wrap(pair.Key), Wrap(pair.Value));
    return mapHandle;
}
```

`CreateList` itself stays unconditional, matching its pre-existing behaviour; see the ROADMAP item
this ADR files for the latent `CS0103` risk that leaves open for a `List`-free consumer.

One `CreateMap` overload serves both map kinds: **Verified** that
`IEnumerable<KeyValuePair<K,V>>` is assignable from both `IReadOnlyDictionary<K,V>` and
`IDictionary<K,V>`, and `IEnumerable<T>` from both `IReadOnlySet<T>` and `ISet<T>`.

`renderMapHelper` / `renderSetHelper` (`cir/CirMarshalRenderer.kt:357-388`) gain the matching
`DllImport`s for `nuget_map_create` / `nuget_map_put` / `nuget_set_create` / `nuget_set_add`.

### Call-site wiring

`collectionPrelude` and `collectionCleanup` (`forward/ForwardCirPlanProjection.kt:480-492`) currently
hardcode `CreateList` and `NugetListNative.Dispose`. Both must switch on `CollectionKind`:

```kotlin
private fun ForwardCallablePlan.collectionPrelude(parameter: ForwardPublicParameter): String? {
  val type = parameter.type as? BridgeType.Collection ?: return null
  val factory: String = when (type.kind) {
    CollectionKind.LIST, CollectionKind.MUTABLE_LIST -> "CreateList"
    CollectionKind.MAP, CollectionKind.MUTABLE_MAP -> "CreateMap"
    CollectionKind.SET, CollectionKind.MUTABLE_SET -> "CreateSet"
  }
  return "IntPtr ${parameter.name}Handle = NugetMarshal.$factory(${parameter.name});"
}
```

**The cleanup switch is load-bearing, not cosmetic.** All three `Dispose` members bind to the same
`nuget_dispose` entry point (Verified), so emitting `NugetListNative.Dispose(mHandle)` for a map handle
would be *runtime*-correct. It would still be a `CS0103` compile error, because a callable whose only
collection is a `Map` sets `tracker.needsMap` and not `tracker.needsList`
(`cir/CirTypeMapping.kt:104-108`, Verified), so the `NugetListNative` class is never emitted. Switch on
kind for cleanup too.

`callArgument`'s `is BridgeType.Collection -> listOf("${parameter.name}Handle")` branch
(`forward/ForwardCirPlanProjection.kt:465`) needs no change: it is already kind-agnostic.

### Kotlin lowering

`loweredArgument` (`forward/ForwardKotlinPlanEmitter.kt:658-670`) gains four branches. All four
expressions below were **Verified** to compile under real `konanc` and to produce the printed results
above:

```kotlin
CollectionKind.MAP ->
  "$name.asStableRef<MutableMap<Any?, Any?>>().get()" +
      ".entries.associate { (k, v) -> (k as $K) to (v as $V) }"

CollectionKind.MUTABLE_MAP ->
  "$name.asStableRef<MutableMap<Any?, Any?>>().get()" +
      ".entries.associateTo(mutableMapOf()) { (k, v) -> (k as $K) to (v as $V) }"

CollectionKind.SET, CollectionKind.MUTABLE_SET ->
  "$name.asStableRef<MutableSet<Any?>>().get().mapTo(mutableSetOf()) { it as $T }"
```

where `$K`/`$V`/`$T` come from the existing `elementKotlinTypeName` (`:398-405`).

Two deliberate notes for the implementing agent:

- **`SET` and `MUTABLE_SET` share one lowering.** `mapTo(mutableSetOf())` yields a `MutableSet<T>`,
  which satisfies a `Set<T>` parameter as well. Unlike the list pair, there is no reason to split them,
  and both are copies either way. Do not read the list pair's `.map {}` / `.mapTo(mutableListOf()) {}`
  asymmetry as a rule.
- **Iteration order is deterministic.** `mutableSetOf()` and `mutableMapOf()` are
  `LinkedHashSet`/`LinkedHashMap`, so Kotlin sees the elements in the C# caller's enumeration order.

### Helper gating: nothing to widen

Both gates already read plan **parameters**:

- Kotlin exports: `plannedCollectionKinds()` yields from `publicSignature.parameters` as well as the
  result (`NugetProcessor.kt:732-739`, Verified), and `needsMapSupport`/`needsSetSupport` consume it
  (`:774-776`, `:800-802`, Verified).
- The `nuget_wrap_*` gate keys on `ForwardHelperRequirement.COLLECTION` being in a plan
  (`NugetProcessor.kt:851-861`, Verified), and the planner adds that requirement for **any**
  `BridgeType.Collection` input regardless of kind (`forward/ForwardCallablePlanner.kt:814`, Verified).
- C# helper classes: `CollectionHelperTracker.trackPlan` tracks parameter types
  (`cir/CirTypeMapping.kt:111-114`, Verified).

`nativeInputParameters`' `require(kind == LIST || kind == MUTABLE_LIST)`
(`forward/ForwardCallablePlanner.kt:904-907`, Verified) is the one remaining guard and must be dropped;
the POINTER/IN/`HANDLE_TO_COLLECTION` shape it builds is already correct for all six kinds.

### Claim labelling

**Verified** (repo source read, cited above): every row of the "what already exists" table; the
`MutableList` parameter copy semantics; the skip clause and its diagnostic mapping;
`isBridgeableComponent()`'s admitted set; `elementKotlinTypeName`'s `error(...)` for
`Nullable`/`ValueClass`/`Collection`; `NugetListNative`/`NugetMapNative`/`NugetSetNative.Dispose` all
binding `nuget_dispose`; the `nuget_wrap_*` set being exactly six types; `CreateList`'s six branches
plus reflective fallback; the parameter-aware helper gates; `csharpType()`'s four collection spellings;
`Tier1StructuralInteropCsTest.kt:325`/`:344`.

**Verified by spike** (commands and real output above): the four proposed native exports compile under
Kotlin/Native 2.2.10 `-produce dynamic` and work end to end from C; boxed `Int`/`String` values dedup
structurally in `mutableSetOf<Any?>`/`mutableMapOf<Any?,Any?>`; a duplicate boxed key overwrites; the
mutable-map lowering does not write back; the four proposed Kotlin lowering expressions compile and
produce correct results including for an `ObjectHandle` map value; Kotlin/Native's ObjC export maps
the four kinds as tabulated, wraps rather than copies at the input position, propagates mutation for
`MutableList`, and **segfaults** when handed a plain `NSMutableDictionary`/`NSMutableSet`; the eight
BCL assignability relations quoted in options 1 and 2.

**Inferred** (documentation only, nobody ran it):

1. That the narrow-primitive (`Byte`/`UByte`/`Short`/`UShort`/`UInt`/`ULong`/`Char`) and `Enum` rows of
   the component table throw `NotSupportedException` at runtime today for a `List<T>` parameter. This
   follows from reading `CreateList`'s branch set and its reflective fallback, but no test exercises
   it. **If this is wrong, the only consequence is that the "latent defects" framing in Scope
   overstates the problem; the v1 allow-list is correct either way**, because those types genuinely
   have no `nuget_wrap_*` export.
2. Kotlin/JVM's zero-conversion collection identity, Swift Export's collection status, `@JsExport`'s
   non-exportability of collections, and the CsWinRT mapping table. All four are prior-art context and
   none of them constrain the implementation.
3. That C++/WinRT's `winrt::param::map` behaves as its documentation describes. Prior-art context only.

**Verified, as shipped** (was the red-register open question at proposal time): whether the generated
`CreateMap`'s reflective `_handle` fallback finds the field on a C# wrapper class reached through the
`IReadOnlyDictionary<string, Foo>` *value* position specifically. The `Patient.linkWard(ward: Map<String,
Patient>)` fixture cell exercises exactly this, and
`Patient_LinkWard_CountsPatientsWithNonBlankNames` (`IntegrationTests/MapSetParameterMarshallingTests.cs`)
passes against `scripts/verify.sh`.

## Consequences

### Fixture surface

Project rule: the fixture crosses every mechanism, not the fewest types. For this feature that means
at least one component needing boxing conversion (`String`), one behaving differently on the wire
(`Int`), and one object handle, so a generator that open-codes a single conversion cannot pass by
accident. Add to `test-library/.../clinic/ClinicSample.kt`:

```kotlin
class Patient(val name: String) {
  /** ADR-073. Class method x `Map<String, Int>` parameter: String key (boxed via nuget_wrap_string),
   *  Int value (boxed via nuget_wrap_int). */
  fun recordScores(scores: Map<String, Int>): Int = scores.values.sum()

  /** ADR-073. Class method x `MutableMap<String, Int>` parameter. The Kotlin body mutates, and the
   *  test asserts the C# caller's dictionary is UNCHANGED: this is the no-write-back regression. */
  fun tallyScores(scores: MutableMap<String, Int>): Int {
    scores["total"] = scores.values.sum()
    return scores.size
  }

  /** ADR-073. Class method x `Map<String, Patient>` parameter: the object-handle value cell, which
   *  exercises CreateMap's reflective `_handle` fallback on the value side. */
  fun linkWard(ward: Map<String, Patient>): Int = ward.values.count { it.name.isNotBlank() }

  /** ADR-073. Class method x `Set<String>` parameter. */
  fun addLabels(labels: Set<String>): Int = labels.size

  /** ADR-073. Class method x `MutableSet<Int>` parameter: Int element (not String), and the second
   *  no-write-back assertion. */
  fun addCodes(codes: MutableSet<Int>): Int { codes.add(0); return codes.size }

  companion object {
    /** ADR-073. Companion method x Map parameter, mirroring the existing `batchAdmit(List<String>)`
     *  companion cell, so the static call position is covered too. */
    fun batchScore(scores: Map<String, Int>): Int = scores.size
  }
}

/** ADR-073. Top-level function x `Set<String>` parameter. */
fun countLabels(labels: Set<String>): Int = labels.size

/** ADR-073. Extension function x `Map<String, Int>` parameter, over an object-handle receiver. */
fun Patient.mergeScores(extra: Map<String, Int>): Int = extra.size

/** ADR-073. Constructor x `Set<String>` parameter. */
class Ward(val name: String, val tags: Set<String>)
```

The generated C# a consumer then sees, and the call sites the tests exercise:

```csharp
public int RecordScores(IReadOnlyDictionary<string, int> scores);
public int TallyScores(IDictionary<string, int> scores);
public int LinkWard(IReadOnlyDictionary<string, Patient> ward);
public int AddLabels(IReadOnlySet<string> labels);
public int AddCodes(ISet<int> codes);
public static int BatchScore(IReadOnlyDictionary<string, int> scores);
```

```csharp
var patient = new Patient("Mylo");
Assert.Equal(7, patient.RecordScores(new Dictionary<string, int> { ["a"] = 3, ["b"] = 4 }));

var scores = new Dictionary<string, int> { ["a"] = 3 };
Assert.Equal(2, patient.TallyScores(scores));
Assert.Single(scores);                              // no write-back: still just "a"
Assert.False(scores.ContainsKey("total"));

Assert.Equal(2, patient.LinkWard(new Dictionary<string, Patient>
{
    ["north"] = new Patient("Oreo"),
    ["south"] = new Patient("Bean"),
}));

Assert.Equal(2, patient.AddLabels(new HashSet<string> { "calm", "indoor" }));

var codes = new HashSet<int> { 7, 8 };
Assert.Equal(3, patient.AddCodes(codes));
Assert.Equal(2, codes.Count);                       // no write-back: 0 was not added here

Assert.Equal(0, patient.RecordScores(new Dictionary<string, int>()));   // empty
Assert.Equal(0, patient.AddLabels(new HashSet<string>()));              // empty
```

The generated body for `RecordScores` will be, per `directCustomBody`
(`forward/ForwardCirPlanProjection.kt:787-814`, Verified):

```csharp
public int RecordScores(IReadOnlyDictionary<string, int> scores)
{
    IntPtr scoresHandle = NugetMarshal.CreateMap(scores);
    int nativeResult = Native_patient_record_scores(_handle, scoresHandle, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    NugetMapNative.Dispose(scoresHandle);
    return nativeResult;
}
```

A negative fixture is required too, so the diagnostic is proved to still fire. A `Tier1` skip-diagnostic
cell (alongside `Tier1NamedSkipDiagnosticsTest`'s existing cells, with
`Tier1CollectionElementSkipTest` as the closest structural precedent) for **all four** of
`Map<String, Mood>` (enum value), `Set<Char>`, `Map<String, Int?>` (nullable value) and
`Set<List<String>>` (nested collection), each asserting `SKIPPED_UNSUPPORTED_INPUT` and the parameter
name. Without these, a later widening of `isWrappableComponent()` would silently produce the
`packNuget` crash or the runtime `NotSupportedException` described above.

### What changes

- Six files: `exports/GenericClassExports.kt` (four new exports),
  `cir/CirMarshalRenderer.kt` (`Wrap<T>`, `CreateSet`, `CreateMap`, four new `DllImport`s),
  `forward/ForwardCirPlanProjection.kt` (kind-aware prelude and cleanup),
  `forward/ForwardCallablePlanner.kt` (`isWrappableComponent`, the narrowed `inputSkipReason` clause,
  dropping the `require` in `nativeInputParameters`),
  `forward/ForwardKotlinPlanEmitter.kt` (four lowering branches),
  plus fixtures and tests.
- `csharpType()`, the helper gates, `callArgument`, the ABI parameter shape, and every `Dispose`
  binding are untouched.
- Four new native exports change the ADR-055 forward ABI contract hash, so a stale C# shim against a
  new library (or vice versa) will be rejected at startup with the usual mismatch message. Expected;
  purge and rebuild.
- `CreateList`'s internals are refactored onto `Wrap<T>`. Behaviour-preserving, but it does move a
  shipped code path, so `MethodParameterMarshallingTests.Patient_AddTags_*` and
  `MarshallingCoverageTests` are the regression gate.

### What breaks

Nothing that currently compiles. Every affected callable is skipped from the generated C# today, so no
existing consumer signature changes. The `IList<T>`-style "mutable interface, no write-back" honesty
gap widens from one kind to three; that is the accepted cost of option 1 and the reason the doc-comment
requirement above is part of the decision rather than a follow-up.

### Deferred, and why (put these on the ROADMAP)

1. **`List<T>` input components are not narrowed.** `List<Mood>`, `List<Short>` and `List<Char>`
   parameters keep planning and keep failing at runtime; `List<String?>`, `List<StoryCode>` and
   `List<List<String>>` parameters keep crashing `packNuget`. Applying `isWrappableComponent()` to the
   list branch too would fix all six, but it removes callables that bind today, which is a behaviour
   change outside this item's scope and needs its own decision.
2. **Enum components** for any collection at an input position. Needs a `nuget_wrap_enum` (or an
   ordinal-plus-qualified-name export) that does not exist. The ordinal alone is not enough to
   reconstruct the enum on the Kotlin side from an erased `Any`.
3. **Narrow primitive components** (`Byte`, `UByte`, `Short`, `UShort`, `UInt`, `ULong`, `Char`). Needs
   seven more `nuget_wrap_*` exports and seven more `Wrap<T>` branches. Mechanical, but it is width
   work of the ADR-069/`Char?` family and should be decided with them, not here.
4. **Nullable components** (`Map<String, Int?>`, `Set<String?>`, `List<Foo?>`). Needs a nullable
   `COpaquePointer?` on `nuget_map_put` / `nuget_set_add` / `nuget_list_add` plus a null-tolerant
   lowering (`it as? T`). Cheap for the value/element position, and it would let the generated API stop
   lying about `Map<String, Int?>`. Not free: it also changes `nuget_list_add`'s signature, hence the
   ABI hash, so it belongs with item 1.
5. **Value-class and nested-collection components.** Both currently crash `packNuget`; both need
   `elementKotlinTypeName` branches and a `Wrap<T>` story. Value-class components are the collection
   facet of the still-open "value class at an ordinary parameter position" ROADMAP item.
6. **Interface components** (`Map<String, IPet>`, `Set<IPet>`). Already deferred by ADR-040's
   "collections of interfaces"; unchanged here.
7. **Write-back for `MutableMap`/`MutableSet` parameters.** Options 4 and 5 above, both rejected for
   v1. Revisit only together with `MutableList`, and only if a real consumer asks.
8. **Handle leak on a throwing callee.** `collectionCleanup` runs *after* the error check
   (`directCustomBody`, Verified), so a Kotlin exception leaks the collection handle. Pre-existing for
   lists, inherited unchanged by map and set. A `try`/`finally` around the native call is the fix, and
   it applies to every collection parameter at once.
