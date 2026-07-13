# ADR-056: C# structs (value types) in Kotlin — component decomposition on the wire, immutable `data class` in Kotlin

## Status

Accepted

## Context

Phase 9, ROADMAP line 160: "Map C# structs (value types) — blittable pass-by-value vs boxed handle
(needs ADR, mirror of ADR-014)".

A C# struct is a general value type: N fields, optional methods/properties/constructors, blittable
or not. It is the last basic-type gap in the reverse bridge — today a struct cannot appear in any
bridged signature, and (verified below) the metadata reader silently misclassifies the struct type
itself as a class.

The ROADMAP frames the decision as **blittable pass-by-value vs. boxed handle**. Five spikes (all
run, all reproduced below) show that framing is a **false dichotomy**: pass-by-value is not
expressible in this project's architecture, and the boxed handle is semantically wrong for a value
type. The right answer is a third option the ROADMAP did not name — **decompose the struct into its
components and never put a struct on the wire at all** — and that option is both cheaper and
*strictly more capable* than pass-by-value.

### Is this the mirror of ADR-014? Partly.

ADR-014 (forward) maps a Kotlin `@JvmInline value class` to a C# `readonly record struct` and
**passes the underlying value unwrapped across the C boundary** — no `StableRef`, no handle. The
struct exists only as a surface type on the consuming side; the wire carries the component.

The mirror **holds for the principle** and **breaks on the arity**:

| | ADR-014 (forward) | This ADR (reverse) |
|---|---|---|
| Surface type on the consuming side | C# `readonly record struct` | Kotlin `data class` |
| Wire | the underlying value, unwrapped | the components, unwrapped |
| Handle / lifetime | none | none |
| Equality | value (record) | value (data class) |
| **Component count** | **exactly 1** (a value class wraps one property) | **N** |
| Return position | return the single value directly | **cannot return N values** → out-pointers |
| Methods on the type | reconstruct the value class per call | same idea, **deferred** (see Scope) |

So: the *unwrap-don't-box* principle transfers exactly, and it is the reason this ADR rejects the
boxed handle. The *arity* generalises from 1 to N, and that is what forces the one genuinely new
piece of machinery here: a struct **return** cannot be a return value, so it crosses as one
out-pointer per component.

### Constraint 1 (verified): `[UnmanagedCallersOnly]` blittability is enforced at **runtime**, is **transitive**, and excludes `bool` and `char`

The C# compiler accepts a struct containing `bool`/`char` in an `[UnmanagedCallersOnly]` signature.
The **runtime** then throws on first invocation. A struct containing a `string` is rejected earlier,
at compile time (CS8894).

```
$ cd $(mktemp -d) && dotnet new console   # TFM net8.0, AllowUnsafeBlocks
# struct WithBool { public bool Flag; public int N; }   -> compiles
# struct WithChar { public char C; public int N; }      -> compiles
# struct WithString { public string S; public int N; }  -> error CS8894: Cannot use 'WithString'
#                                       as a parameter type on a method attributed with 'UnmanagedCallersOnly'
```

Then, invoking the compiled `bool`/`char` thunks from a real C caller (`clang -shared`, plain C ABI):

```
sizeof(WithBool)=8 sizeof(WithChar)=8
struct with bool FAILED: InvalidProgramException: Non-blittable parameter types are invalid for UnmanagedCallersOnly methods.
struct with char FAILED: InvalidProgramException: Non-blittable parameter types are invalid for UnmanagedCallersOnly methods.
top-level bool  FAILED: InvalidProgramException: Non-blittable parameter types are invalid for UnmanagedCallersOnly methods.
```

**Verified.** This independently confirms *and explains* ADR-049's existing `bool → byte` /
`char → ushort` narrowing ("the two non-obvious blittability corrections"): `System.Boolean` and
`System.Char` are not blittable, the C# compiler does not catch it, and the CLR turns it into an
`InvalidProgramException` at the first call. Blittability is **transitive**, so the same rule
disqualifies any struct with a `bool` or `char` field.

The practical consequence is severe for the pass-by-value option: a struct may cross by value only
if **every** field is strictly blittable. No `string`, no `bool`, no `char`, no reference fields.
`readonly record struct Person(string Name, int Age)` — a completely ordinary C# type — can never
cross by value.

A blittable struct **does** cross correctly by value in both directions when the rules are obeyed
(same spike, `struct Point { int X; int Y; }`, and a 20-byte 5-int struct to force the arm64 `sret`
path):

```
take struct byval   -> 304              (expect 304)
return struct byval -> (7,8)            (expect (7,8))
return BIG struct   -> (10,11,12,13,14) (expect (10,11,12,13,14))
```

**Verified.** Compile-time results verified with TFM `net8.0`; the runtime results were produced on
the .NET 10.0.8 runtime (the only runtime installed on this machine, via `<RollForward>Major</RollForward>`).
The blittability rule has been stable since .NET 5, but the runtime-behaviour claims are strictly
verified on 10.0.8, not on 8.

### Constraint 2 (verified, and decisive): a by-value struct in a `CFunction` type requires a **cinterop-generated** `CStructVar`

The reverse bridge invokes C# through `CPointer<CFunction<...>>` (ADR-041/048). For a struct to
cross by value, the Kotlin `CFunction` type parameter must mention a `CStructVar` subtype
(`CValue<T>`). Hand-writing that subtype in plain `nativeMain` Kotlin **compiles as a declaration**
but **fails to lower at the call site**:

```
$ ~/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.0/bin/kotlinc-native -p program -o app main.kt
main.kt:24:56: error: type kotlinx.cinterop.CValue<Point> is not supported here: not a structure or too complex
```

(where `Point` is a hand-written `class Point(rawPtr: NativePtr) : CStructVar(rawPtr)` with a
`companion object : Type(size = 8, align = 4)`, and line 24 is `takeFn.invoke(arg)`.)

The identical program, with `Point` coming from **cinterop** instead of hand-written, works — through
a function pointer acquired at runtime via `dlsym`, which is exactly the bridge's shape:

```
$ cinterop -def point.def -o point && kotlinc-native -p program -l point.klib -o app main.kt && ./app.kexe
take_point(cValue{3,4}) = 304   (expect 304)
make_point(7) = (7,8)           (expect (7,8))
```

**Verified.** So by-value structs are not impossible in Kotlin/Native — they are impossible in *this
project's architecture*. ADR-048 generates **plain Kotlin source** into `nativeMain` and deliberately
"models the managed API once, not per Kotlin target". Adopting by-value structs would mean generating
a C header + `.def`, running `cinterop` per `konanTarget`, producing and wiring a klib — a
per-target, per-package build stage, to buy a wire format that (per Constraint 1) covers strictly
fewer structs than the alternative, and that the consumer would never see anyway (nobody wants
`CValue<Point>` in their Kotlin API; the public surface is a `data class` either way).

### Constraint 3 (verified): today the reader emits a struct as `{"kind": "class"}`

Running a **scratch copy** of the repository's `nuget-metadata-reader/Program.cs` (unmodified)
against a probe assembly containing five struct shapes:

```
TYPES EMITTED (kind, name, #methods, #props, #ctors):
  class    Person         methods=2 props=2 ctors=1     # readonly record struct Person(string Name, int Age)
  class    Point          methods=0 props=2 ctors=1     # readonly struct, ctor(int x, int y)
  class    Mutable        methods=0 props=2 ctors=0     # settable auto-props, no ctor
  class    PublicFields   methods=0 props=0 ctors=0     # public fields, no ctor
  class    Nested         methods=0 props=0 ctors=0     # struct field + int field

DIAGNOSTICS:
  [info_oblivious_nullability] Person.ToString: ...
  [skipped_overload_set] Person.Equals: overload set — 2 overloads of `Equals` cannot be uniquely exported to C
```

**Verified.** Three facts fall out, each of which shapes the decision:

1. **Every struct is currently emitted as an `RirClass`.** `ProcessType` special-cases enums and
   falls through to class processing for everything else. `MetadataHelpers.IsValueType` only excludes
   structs from `boundHandleTypeNames` — it does not stop them being emitted as types. So the
   generators would today render a struct as an ADR-051 **handle wrapper class**, with `close()`, a
   `Cleaner` and reference equality, while no method could actually reference it (any signature
   mentioning it gets `skipped_unbound_type_reference`). This state is incoherent and is fixed here.
2. **A `record struct` generates diagnostic noise.** Its compiler-synthesized `Equals(object)` /
   `Equals(Person)` pair trips the ADR-043 overload-set filter, and its synthesized `ToString` is
   emitted as a *bridgeable instance method* (plus a spurious oblivious-nullability diagnostic).
   Treating a struct as its own RIR node — and **not enumerating its members** in v1 — removes all of
   this.
3. **`init`-only setters read as settable.** `readonly record struct Person`'s properties come back
   with `isReadOnly: false`, because `init` is emitted as a `set_` accessor (with an
   `IsExternalInit` modreq). Any future work that binds struct properties must not trust
   `Setter.IsNil`. Not load-bearing for v1 (v1 does not bind struct members), but recorded because it
   is a live landmine.

### Constraint 4 (verified): what the metadata actually offers for component extraction

From the same probe decode (`System.Reflection.Metadata`, net8.0 assembly):

```
=== Sample.Structs.Person  base=System.ValueType  layout=Sequential  sealed=True
   FIELD  <Name>k__BackingField    attrs=Private, InitOnly
   FIELD  <Age>k__BackingField     attrs=Private, InitOnly
   PROP   Name   getter=True setter=True          # setter is `init`
   METHOD .ctor  public=True special=True compilerGen=False params=[Name#1,Age#2]
   METHOD Equals public=True special=False compilerGen=True params=[obj#1]      # + 8 more synthesized
=== Sample.Structs.Point   base=System.ValueType  layout=Sequential  sealed=True
   FIELD  <X>k__BackingField, <Y>k__BackingField  (Private, InitOnly)
   PROP   X getter=True setter=False ; PROP Y getter=True setter=False
   METHOD .ctor public=True params=[x#1,y#2]      # NOTE: ctor params are camelCase, properties PascalCase
=== Sample.Structs.PublicFields  ... FIELD A (Public), FIELD B (Public)   — and ZERO methods (no .ctor row at all)
```

**Verified**, and four consequences:

- All C# structs carry `SequentialLayout` in metadata (even the auto-property one). *Irrelevant to
  this design* — the chosen wire format never uses layout — but worth stating so a future reader does
  not reach for `Marshal.SizeOf`/`FieldOffset` thinking it is safe. (Metadata layout is not runtime
  layout for non-blittable types.)
- A struct with no explicit constructor has **no `.ctor` row at all** in metadata. "Exactly one public
  instance `.ctor`" is therefore a clean, checkable discriminator.
- **Ctor parameter names do not match property names in case** (`x` vs `X`). Component matching must
  be case-insensitive.
- Public **fields** are not extracted by the reader today at all (`PublicFields` came back with
  `props=0`, `methods=0` — an empty type). Field extraction does not exist and would have to be
  built.

### Prior art

**Kotlin/Native cinterop with C structs** — the closest analogue, and it confirms the surface choice
even while its wire format is unavailable to us. cinterop exposes a C struct as a `CStructVar` with
`CValue<T>`, `cValue { }` and `useContents { }`. That API is explicitly a *native-memory* API, not a
Kotlin value type: `useContents` exists precisely because the struct lives in native memory and must
be projected into Kotlin. No Kotlin developer wants `CValue<Point>` in a library API. Even if we
adopted the cinterop wire format, we would still hand-write a `data class` surface on top of it.
([Kotlin/Native C interop](https://kotlinlang.org/docs/native-c-interop.html))

**Kotlin consuming Java** — the gold standard. Java has no structs; the closest analogue is a small
immutable data carrier (a `record`), and Kotlin surfaces it as an ordinary class with value equality.
Kotlin developers expect a data carrier to have `==` by value, `copy()`, destructuring, and **no
lifecycle**. ([Java interop](https://kotlinlang.org/docs/java-interop.html))

**Swift Export / ObjC Export** — ObjC export cannot represent a Swift/Kotlin value type at all
(struct → `id`/opaque, losing value semantics — ADR-014 notes this as the thing we can do better
than). Swift structs are natively value types and Swift Export does not currently support Kotlin
value classes at all. Neither offers a wire format we can copy; both confirm that *losing value
semantics is the failure mode to avoid*.

**.NET's own P/Invoke marshalling** — the distinction between blittable (memcpy) and marshalled
(field-by-field copy through a marshaller) structs. `[UnmanagedCallersOnly]` deliberately opts out of
the marshaller entirely, which is exactly why Constraint 1 bites. ([Blittable and non-blittable
types](https://learn.microsoft.com/en-us/dotnet/framework/interop/blittable-and-non-blittable-types),
[UnmanagedCallersOnlyAttribute](https://learn.microsoft.com/en-us/dotnet/api/system.runtime.interopservices.unmanagedcallersonlyattribute))

**Rust `#[repr(C)]` / JNA / Panama `MemoryLayout`** — all three make the *host* language describe the
foreign layout explicitly, then memcpy. Every one of them pays for by-value structs with a
layout-description mechanism. That is the cost we avoid by decomposing.

## Alternatives Considered

### Decision 1 — the wire format

#### 1a. Component decomposition: the struct never appears on the wire (chosen)

A struct-typed **parameter** expands into one ABI argument per component. A struct-typed **return**
expands into `void` plus one **out-pointer per component**. Each component crosses using the wire
mapping its own type already has (ADR-048/049/051/053 tables), so a `string` component crosses as a
UTF-8 `IntPtr`, a `bool` as `byte`, a `char` as `ushort`, an enum as `int`.

Verified end-to-end in the **real topology** (Kotlin/Native dylib loaded by a .NET host, host
registers `[UnmanagedCallersOnly]` thunks through a `@CName` register export, Kotlin calls back
through the function pointers), with `readonly record struct Person(string Name, int Age)` — a struct
that is **non-blittable and could never cross by value**:

```
C#: thunks registered, calling into Kotlin...
KOTLIN: describe(Person(name=Oreo, age=3), 2)  -> "Oreo(3) Oreo(3)"
KOTLIN: promote(Person(name=Oreo, age=3))      -> Person(name=Oreo, age=4)
KOTLIN: value semantics    -> p == p.copy() is true, no close() needed
C#: round trip complete.
```

and again with the **entire v1 component vocabulary** in both directions
(`readonly record struct Profile(string Tag, int Score, bool Active, char Grade, Mood Mood)`):

```
KOTLIN in : Profile(tag=oreo, score=7, active=true, grade=A, mood=CALM)
KOTLIN out: Profile(tag=oreo!, score=8, active=false, grade=B, mood=PLAYFUL)
KOTLIN round-trip equal-by-value: true  (expect true)
```

**Pros:**

- **It is the ADR-014 principle, generalised.** The value crosses unwrapped; the struct is a surface
  type on each side, not a wire type. One mental model for both directions.
- **Blittability becomes irrelevant.** This is the decisive advantage. The `blittable vs
  non-blittable` split that motivates the ROADMAP question *dissolves*: no struct is ever put on the
  wire, so no struct needs to be blittable. `string`, `bool`, `char` and enum components all work
  (verified above), and those are exactly the components that make pass-by-value fail
  (Constraint 1).
- **Zero new ABI vocabulary.** Every ABI argument is a scalar the two generators already emit today.
  The only new shape is "pointer to a scalar", for returns.
- **Zero new build machinery.** No cinterop, no klib, no per-target stage. Stays inside ADR-048's
  "model once" property.
- **A v1 struct costs zero registration slots** (see Decision 3): it has no bridged members, so it
  gets no `nuget_{ns}_{type}_register` export and no `Bindings.kt`. The ADR-048/054 registration
  contract is untouched in *shape*; only the *signatures* of slots on other types change.
- Reading a struct's members costs **no bridge call at all** — the components were copied into the
  Kotlin `data class` at the boundary.

**Cons:**

- ABI arity grows with component count: `void Move(Point p, int dx)` becomes a 3-argument thunk. Fine
  for the small data carriers structs are for; deep/nested structs are deferred anyway.
- **The struct's component list becomes part of the ABI contract.** If the C# struct gains a field,
  every thunk taking it changes arity while the *method* signatures look unchanged. This is exactly
  the same-arity drift ADR-054's `contractHash` exists to catch, and it means the hash **must**
  expand struct components (see Decision 5). Missing this would corrupt every call through an
  affected slot.
- One `RirParameter` is no longer one ABI argument. Both generators must expand through **one shared
  function** or they will silently drift (ADR-049 Alternative 10's rule, now load-bearing in a second
  place).

#### 1b. Blittable struct by value, via a generated cinterop stage (rejected)

Emit a C header declaring each bound struct, a `.def` file, run `cinterop` per `konanTarget`, wire the
resulting klib into every native compilation, and use `CValue<T>` in the `CFunction` types.

**Pros:** the natural C signature; one argument per struct; would extend to struct-typed *methods*
without reconstruction.

**Cons (rejected):**

- **Covers strictly fewer structs than 1a.** Only strictly-blittable structs qualify (Constraint 1,
  verified): no `string`, no `bool`, no `char`, no reference components. `readonly record struct
  Person(string Name, int Age)` — the most ordinary struct imaginable — is excluded. A wire format
  that cannot carry a `bool` is not a serious basis for "map C# structs".
- **Requires a per-target cinterop build stage**, contradicting ADR-048's explicit "model the managed
  API once, not per Kotlin target" (and the synthesis document it cites).
- **Buys nothing at the surface.** The consumer-facing type is a `data class` either way (nobody
  wants `CValue<Point>` in a Kotlin API), so cinterop would purchase only the wire format — the one
  part the consumer never sees.
- The failure mode when a rule is broken is an `InvalidProgramException` at the first call, i.e. at
  runtime, in the host process (verified). Decomposition cannot produce that failure at all.

#### 1c. Boxed `GCHandle` handle — treat a struct exactly like ADR-051's opaque object (rejected)

`GCHandle.Alloc(someStruct)` boxes the struct onto the managed heap and hands Kotlin an `IntPtr`.
Correct for *any* struct, blittable or not, with **zero new wire machinery** — `RirObjectHandleType`
already does this. (Constraint 3 shows this is roughly what the pipeline would do *by accident*
today.)

**Cons (rejected):**

- **It destroys value semantics**, which is the entire point of a value type. The Kotlin consumer
  gets a class with `close()`, a `Cleaner`, reference `equals`, no `copy()`, no destructuring. `Point`
  would be a *disposable resource*. This is precisely the trap ADR-014 identified and rejected in the
  forward direction ("Forces `IDisposable` on a logically value-typed concept — `using var id = new
  CatId("x")` is unnatural"), and it violates GOALS ("Kotlin bindings should feel like Kotlin, never
  like a wrapper around the other language").
- It makes the C# **mutable-struct footgun** worse, not better: two Kotlin handles to two *different*
  boxes of an equal struct compare unequal, and a mutation through one is invisible to the other.
- Every `Point` crossing allocates a `GCHandle` and a box, and pins them until the Kotlin GC runs.
- Reading `point.x` becomes a bridge call.

Rejected on semantics, not on cost. It is the "correct but wrong" option.

#### 1d. Hybrid — by value when blittable, boxed handle otherwise (rejected)

The ROADMAP's implied compromise.

**Cons (rejected):** it is the **union of both options' costs**: the cinterop stage of 1b *plus* the
handle machinery of 1c, plus a rule that makes the generated Kotlin API's *semantics* (value type vs.
disposable resource) depend on an invisible property of the C# type's fields. Adding a `bool` field to
a C# struct would silently flip its Kotlin binding from a `data class` to a `Closeable` handle class —
a breaking API change in the consumer's source, triggered by a change that is not visibly related.
Two wire formats, two surfaces, one incoherent mental model.

#### 1e. Packed opaque byte buffer + generator-computed layout (rejected)

Kotlin allocates a byte buffer; the C# thunk `Unsafe.Write`s the struct into it; Kotlin reads fields
at offsets the Gradle plugin computed from metadata.

**Cons (rejected):** requires the plugin to reproduce the CLR's *runtime* layout algorithm exactly.
Metadata says `SequentialLayout` (Constraint 4) but that is not a promise about runtime layout for
non-blittable types, and `Marshal.SizeOf` reports the *marshalling* layout, which is a third thing
again. Getting this subtly wrong produces silent field corruption rather than a build error. Every
other FFI that does this (Rust `#[repr(C)]`, Panama `MemoryLayout`, JNA) makes the layout an explicit,
checked declaration; we would be *inferring* it. Decomposition gets the same result with no layout
question at all.

---

### Decision 2 — the Kotlin surface

#### 2a. An immutable `data class`, all components `val` (chosen)

```kotlin
data class Point(val x: Int, val y: Int)
```

Value equality, `copy()`, destructuring, `toString()`, no handle, no `close()`, no `Cleaner`.

**Pros:**

- It is what a Kotlin developer expects from a data carrier, and it is the exact mirror of ADR-014's
  choice of `readonly record struct` for the C# side (both languages' idiomatic "value with
  components").
- Structural equality **matches C# struct semantics**: `ValueType.Equals` and a `record struct`'s
  `==` are both by-value. `a == b` agrees across the bridge.
- **It structurally eliminates C#'s mutable-struct footgun.** In C#, mutating a struct through a copy
  silently discards the write. Here, the struct is *copied* across the boundary by construction, so a
  Kotlin-side mutation could never propagate back to C# — and an immutable `data class` makes that
  impossible to attempt. `copy()` replaces mutation-through-copy with an operation whose semantics are
  honest. The Kotlin binding is *safer than the C# original*.
- Free `hashCode`/`equals`/`toString` from the compiler; no bridge calls to implement them (contrast
  ADR-051's handle wrapper, which had to defer `Equals`/`GetHashCode`/`ToString` delegation entirely).

**Cons:**

- A C# struct with settable properties binds to an immutable Kotlin type, so the C# and Kotlin
  surfaces differ. Accepted and *intended*: see above. Documented in the generated KDoc.

#### 2b. `data class` with `var` for C#-settable properties (rejected)

Mirror the C# mutability.

**Rejected.** It would be a lie: mutating the Kotlin copy cannot affect the C# value, because a value
was copied across the boundary. A `var` that silently discards writes is worse than no `var` — it is
the C# footgun, faithfully re-exported into a language that does not have it.

#### 2c. `value class` when the struct has exactly one component (rejected)

A one-component C# struct (`readonly record struct CatId(string Id)`) could map to a Kotlin
`@JvmInline value class` — a perfect round-trip of ADR-014.

**Rejected for v1.** Tempting symmetry, but it makes the *kind* of the generated Kotlin type depend on
the component count, so adding a second field to a C# struct would change its Kotlin binding from a
`value class` to a `data class` (a source-breaking change in the consumer, and a different set of
supported operations: no `copy()`, no destructuring on a value class). A `data class` with one
component is already correct, idiomatic, and stable under that change. Revisit only if a real package
shows the boxing cost matters (Kotlin/Native `value class` inlining is not the JVM's, and the wire
format is identical either way — the component is unwrapped regardless).

#### 2d. Handle-backed class (rejected) — see 1c.

---

### Decision 3 — which struct shapes are bridgeable in v1

The generated C# thunk must be able to **reconstruct** the struct from its components (parameter
position) and **read** them back (return position). What is checkable in metadata (Constraint 4)
determines the rule.

#### 3a. Shape A — the constructor defines the components (chosen)

A struct is bridgeable iff **all** of:

1. It is a public, top-level, non-generic, non-`ref struct`, non-enum value type (`ref struct` already
   excluded by ADR-043).
2. It has **exactly one public instance constructor**, with at least one parameter.
3. Every constructor parameter matches, **case-insensitively by name**, a public readable instance
   property of the same type (verified necessary: ctor `x`/`y` vs. property `X`/`Y`).
4. Every component type is in the **v1 component vocabulary**: primitives (including `bool` and
   `char`), `string`, and bound enums.
5. **Its constructor covers all of its stored state**: the number of non-static instance fields equals
   the number of constructor parameters.

Components, and their order, are the **constructor's parameter list**. The Kotlin property name is the
parameter name camel-cased (`Name` → `name`, `x` → `x`). Reconstruction is `new T(c1, c2, …)`; reading
is `result.X`.

Rule 5 is the important safety rule and is not obvious. Without it, a struct with three stored fields
and a two-parameter constructor would bind to a two-component Kotlin `data class`, and the third
field's value would be **silently dropped** on every crossing — a data-loss bug that no test on the
Kotlin side could see. With it, such a struct is skipped and diagnosed. Computed (derived) properties
are *not* stored state and do not trip rule 5 — they are simply absent from the Kotlin type, which
loses no data, because the constructor still fully determines the value. (Rule 5 counts instance
fields only; a `public static readonly Point Empty` field is static and does not count.)

**Pros:** covers `readonly record struct` and the classic immutable struct, which is what structs
worth binding actually look like; gives a canonical, stable component **order** (metadata field order
is an implementation detail, constructor parameter order is public API); reuses the ADR-052
constructor-extraction machinery; every rule is checkable from metadata with no heuristics.

**Cons:** a struct with several public constructors is skipped, *including `System.Drawing.Point`*
(which has `Point(int,int)`, `Point(Size)` and `Point(int)`). This is an honest v1 limitation and it
is the same overload-set ceiling ADR-043 imposes everywhere; ROADMAP line 161 ("revisit the ADR-043
overload exclusion with a deterministic disambiguation scheme") lifts it for structs and classes at
once. It is called out here rather than papered over with a constructor-selection heuristic, because
a heuristic that picks "the constructor that looks like the fields" is exactly the kind of
silently-wrong rule this project has already paid for once.

#### 3b. Shape B — no constructor; public fields or settable auto-properties (deferred)

`public struct PublicFields { public int A; public int B; }` and
`public struct Mutable { public int A { get; set; } … }`. Reconstruction would be an object
initializer (`new T { A = a, B = b }`), component order the metadata declaration order.

**Deferred, not rejected.** It is cheap on the C# side, but: the reader **does not extract public
fields at all today** (verified: `PublicFields` came back as an empty type), so it needs new
extraction; metadata declaration order becomes an ABI-relevant contract; and it interacts with the
`init`-setter landmine (Constraint 3.3). Skipped with a named diagnostic in v1. This is the first
thing to add if a real package needs it.

#### 3c. Bind every struct, falling back to a boxed handle when it fails the rules (rejected)

See 1d. A struct's Kotlin *semantics* must not depend silently on its field types.

---

### Decision 4 — how a struct **return** crosses

#### 4a. One out-pointer per component; thunk returns `void` (chosen)

```csharp
[UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
private static unsafe void Promote_Thunk(IntPtr p_Name, int p_Age, IntPtr* outName, int* outAge)
{
    Person result = People.Promote(new Person(Marshal.PtrToStringUTF8(p_Name)!, p_Age));
    *outName = Marshal.StringToCoTaskMemUTF8(result.Name);
    *outAge  = result.Age;
}
```

Kotlin allocates the out-slots in `memScoped` and constructs the `data class` from them. **Verified**
end-to-end (spikes above), including `IntPtr*`, `int*`, `byte*`, `ushort*` out-pointers.

**Pros:** no layout knowledge anywhere; one crossing; uniform with every component type; pointers are
blittable so the `[UnmanagedCallersOnly]` constraint is trivially satisfied;
`<AllowUnsafeBlocks>true</AllowUnsafeBlocks>` is already required by ADR-041/049. It also generalises
directly to `Nullable<T>` (below), which is the deferred feature most coupled to this one.

**Cons:** the thunk body becomes `unsafe` (today only the `[ModuleInitializer]`'s address-of
expression is). Accepted: the alternative (`IntPtr` out-params + `Marshal.WriteInt32`) is not uniform —
`Marshal` has `WriteInt32`/`WriteInt64`/`WriteByte`/`WriteInt16`/`WriteIntPtr` but **no**
`WriteSingle`/`WriteDouble`, so `float`/`double` components would need `Unsafe.Write` (and therefore
`unsafe`) anyway. One rule beats two.

#### 4b. Blittable struct return by value (rejected) — Decision 1b; also excludes any struct with a `string`.

#### 4c. Box the return into a `GCHandle`, then read components with N further bridge calls (rejected)

N+1 crossings instead of 1, plus a handle to free, to produce a value type. Strictly worse than 4a.

## Decision

Take **1a + 2a + 3a + 4a**: a C# struct is a **first-class RIR type** whose **components are
decomposed onto the wire** (arguments in, out-pointers out) and which surfaces in Kotlin as an
**immutable `data class`**. No handle, no `close()`, no cinterop, no layout.

### The Kotlin surface

```kotlin
package sample.structs

/**
 * Kotlin value type for the C# struct `Sample.Structs.Point`.
 *
 * Copied by value across the bridge: equality is structural, and there is nothing to close.
 * Mutating this value never affects the C# side (a copy crossed the boundary); use [copy].
 */
data class Point(val x: Int, val y: Int)
```

Generated as `{StructName}.kt` in the derived Kotlin package. **No `{StructName}Bindings.kt`, and no
`nuget_{ns}_{type}_register` export**: a v1 struct has no bridged members, so it claims **zero
registration slots**.

### Wire format (extends the ADR-048/049 type-mapping tables)

| Position | `RirStructType` expands to |
|---|---|
| Parameter | one ABI argument per component, each using its own component type's existing wire mapping; ABI name `{param}_{Component}` |
| Return | thunk return becomes `void`; one **out-pointer** ABI argument per component, appended after the real parameters; ABI name `out{Component}` |
| Property getter | as a return (out-pointers) |
| Property setter | as a parameter (decomposed arguments) |

Per-component ABI types are **exactly the existing table** (ADR-048/049/051/053) — this ADR adds no
new scalar:

| Component type | Kotlin `CFunction` (in) | Kotlin `CFunction` (out-ptr) | C# thunk (in) | C# thunk (out-ptr) |
|---|---|---|---|---|
| `int` / enum | `Int` | `CPointer<IntVar>` | `int` | `int*` |
| `long` | `Long` | `CPointer<LongVar>` | `long` | `long*` |
| `bool` | `UByte` | `CPointer<UByteVar>` | `byte` | `byte*` |
| `char` | `UShort` | `CPointer<UShortVar>` | `ushort` | `ushort*` |
| `float` / `double` | `Float` / `Double` | `CPointer<FloatVar>` / `CPointer<DoubleVar>` | `float` / `double` | `float*` / `double*` |
| `string` | `COpaquePointer?` | `CPointer<COpaquePointerVar>` | `IntPtr` | `IntPtr*` |

String ownership is unchanged (ADR-048): C# allocates a returned string with
`Marshal.StringToCoTaskMemUTF8`, Kotlin reads it with `toKString()` and frees it with
`freeManagedString`; a string *argument* is `name.cstr.ptr` inside `memScoped`.

**Verified** (real topology, both directions, every component kind in this table except
`float`/`double`/`long`, which are the trivially-blittable cases the existing bridge already carries
at the top level).

### Generated code (the shapes verified in the spikes)

```kotlin
// Bindings: one ABI arg per component in; out-pointers appended for a struct return.
private var translateFn: CPointer<CFunction<(Int, Int, Int, CPointer<IntVar>, CPointer<IntVar>) -> Unit>>? = null

// Stub: struct parameter decomposed, struct return assembled from out-slots.
fun translate(p: Point, dx: Int): Point = memScoped {
    val fn = requireNotNull(translateFn) { "Geometry bindings are not registered. …" }
    val outX = alloc<IntVar>()
    val outY = alloc<IntVar>()
    fn.invoke(p.x, p.y, dx, outX.ptr, outY.ptr)
    Point(outX.value, outY.value)
}
```

```csharp
[UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
private static unsafe void Translate_Thunk(int p_X, int p_Y, int dx, int* outX, int* outY)
{
    Point result = Geometry.Translate(new Point(p_X, p_Y), dx);
    *outX = result.X;
    *outY = result.Y;
}
```

### RIR extension (`RirModel.kt` + the mirrored C# model in `Program.cs`)

A struct is a new `RirType` **and** a new `RirTypeRef`, exactly as an enum already is (`RirEnum` /
`RirEnumType` both use `@SerialName("enum")` in their own sealed hierarchy — the same `"struct"`
discriminator is reused in both here).

```kotlin
@Serializable
@SerialName("struct")
data class RirStruct(
  override val name: String,
  val components: List<RirStructComponent> = emptyList(),
) : RirType

@Serializable
data class RirStructComponent(
  val name: String,      // C# constructor parameter name — drives the Kotlin property name
  val readName: String,  // C# property name used to read it back (case may differ: `x` vs `X`)
  val type: RirTypeRef,
)

@Serializable
@SerialName("struct")
data class RirStructType(val namespace: String, val name: String) : RirTypeRef
```

`RirStructType` carries **no `nullable` flag**, consistent with ADR-053: a nullable value type is
`System.Nullable<T>`, a distinct closed generic struct, not an annotation.

New diagnostic kind:

```kotlin
@SerialName("skipped_unsupported_struct")
SKIPPED_UNSUPPORTED_STRUCT,
```

`reverse-ir.json` (additive; old JSON still parses):

```json
{ "kind": "struct", "name": "Point",
  "components": [
    { "name": "x", "readName": "X", "type": { "kind": "primitive", "name": "int" } },
    { "name": "y", "readName": "Y", "type": { "kind": "primitive", "name": "int" } }
  ] }
```

### Metadata reader (`nuget-metadata-reader/Program.cs`)

1. `ProcessType` gains a **struct branch before the class fall-through** (mirroring the existing enum
   branch). A value type that is not an enum is a struct candidate and **must not** be processed as a
   class. This is the fix for Constraint 3.
2. `CollectStructTypes` (mirror of `CollectEnumTypes`) applies the Decision 3a rules and yields either
   a `RirStruct` or an `Unsupported(reason)`.
3. **Struct members are not enumerated in v1.** No methods, no properties, no overload grouping. This
   removes the verified `record struct` diagnostic noise (`skipped_overload_set` on the synthesized
   `Equals` pair; `info_oblivious_nullability` on the synthesized `ToString`) and keeps compiler-
   generated members out of the RIR entirely.
4. `SignatureDecoder.GetTypeFromDefinition` gains a struct case (before the `_boundHandleTypeNames`
   check) returning `RirStructType`, or a `skipped_unsupported_struct` diagnostic for a struct that
   failed the rules — mirroring exactly what it already does for enums.
5. `MetadataHelpers.IsValueType` continues to keep structs out of `boundHandleTypeNames` (a struct is
   never a `GCHandle` handle).

Diagnostic reasons, each naming the rule it failed: no public instance constructor (Shape B —
deferred); N public constructors (overload set); constructor parameter `p` has no matching public
readable property; component type `T` is not bridgeable (nested struct / class handle / collection /
`Nullable<T>` / generic); struct has N stored fields but its constructor takes M parameters (state
would be silently dropped); zero components; generic struct.

### Shared ABI expansion (`RirBridging.kt`) — the anti-drift requirement

This is the single most dangerous part of the implementation. Today, one `RirParameter` is one ABI
argument and both generators independently assume it. With structs that is no longer true, and if the
two generators expand differently the registration slots still line up while the *arguments inside a
slot* silently misalign — corruption with no error.

Both generators **must** derive their argument lists from one shared function, extending the ADR-049
Alternative 10 rule:

```kotlin
// One ABI-level argument. `type` is always a scalar — never a RirStructType.
data class AbiArg(val name: String, val type: RirTypeRef, val isOutPointer: Boolean)

fun abiArgs(parameters: List<RirParameter>, structs: Map<RirTypeKey, RirStruct>): List<AbiArg>
fun abiOutArgs(returnType: RirTypeRef, structs: Map<RirTypeKey, RirStruct>): List<AbiArg>
fun abiReturnType(returnType: RirTypeRef, structs: Map<RirTypeKey, RirStruct>): RirTypeRef  // RirVoidType when out-pointers are used
```

The bound-type context (`boundHandleTypes`, and now the struct map) is already threaded through
`bridgeableStaticMethods(cls, boundHandleTypes)` and friends. Rather than growing every signature a
third time, collapse them into one context object (`BoundTypes(handles: Set<RirTypeKey>, structs:
Map<RirTypeKey, RirStruct>)`) derived once per `RirFile` by a shared helper, exactly as
`boundHandleTypes(file)` is today.

`isV1Type` gains: `is RirStructType -> RirTypeKey(namespace, name) in bound.structs`.

### `contractHash` must expand struct components (ADR-054)

**Load-bearing.** `contractHash` currently hashes each type ref via `describe()`, which for a struct
would render only `Sample.Structs.Point`. If the C# struct gains a component, every affected thunk's
**arity changes** while the hash does not — a stale C# shim would then call a freshly built Kotlin
export with the wrong number of arguments, which is precisely the class of corruption ADR-054 exists
to detect. `slotCount` cannot see it either (the number of methods is unchanged).

`signaturePart()` for a `RirStructType` must therefore expand to its components, e.g.
`Sample.Structs.Point{int,int}`. That requires the struct map at the hash site, so:

```kotlin
fun contractHash(cls: RirClass, registrables: List<RirRegistrable>, structs: Map<RirTypeKey, RirStruct>): Long
```

### What this unblocks: `Nullable<T>` (ROADMAP line 159 / ADR-053 Decision 3)

ADR-053 deferred `int?` because it "needs a wire-format decision this ADR has no reason to make",
anticipating "a single-call out-parameter (`byte X_Thunk(int* outValue)`, one crossing, one
evaluation), or a packed struct return". **This ADR settles that**: the out-pointer convention above
is that format, now verified. `Nullable<T>` becomes:

- **return**: the thunk returns `byte hasValue` and writes the value through out-pointer(s); Kotlin
  maps `0` → `null`. One crossing, one evaluation — no ADR-002 two-call re-evaluation problem.
- **parameter**: a leading `byte hasValue` argument followed by the value argument(s), ignored when
  `hasValue == 0`.
- **surface**: `Int?`, `Point?`.

`Nullable<T>` is **unblocked but not subsumed**: it stays its own ROADMAP slice (it also needs
`GetGenericInstantiation` to stop dropping `System.Nullable<T>`, which is reader work unrelated to
structs), and it needs **no further ADR** — it implements the format prescribed here. Keeping it out
of this ADR's v1 scope keeps this feature shippable on its own.

### Fixture surface (`sample-dependency/`)

New feature-scoped namespace `Sample.Structs` (the standing reverse-fixture convention), in a new
`sample-dependency/Geometry.cs`:

```csharp
namespace Sample.Structs;

// Shape A, all-primitive components: the one case pass-by-value could also have handled.
public readonly struct Point
{
    public Point(int x, int y) { X = x; Y = y; }   // ctor params camelCase, properties PascalCase:
    public int X { get; }                          // exercises the case-insensitive match rule
    public int Y { get; }
}

// Shape A with string + bool + enum components: NON-blittable. Could never cross by value.
// This is the type that proves the wire format's reach.
public readonly record struct Profile(string Tag, int Score, bool Active, CatMood Mood);

public static class Geometry                       // static methods: struct in, struct out, struct-to-string
{
    public static Point Translate(Point p, int dx, int dy) => new Point(p.X + dx, p.Y + dy);
    public static string Describe(Point p) => $"({p.X}, {p.Y})";
    public static int Manhattan(Point a, Point b) =>                       // TWO struct parameters
        Math.Abs(a.X - b.X) + Math.Abs(a.Y - b.Y);
}

public class Roster                                // instance members + a struct-typed property
{
    public Profile Owner { get; set; } = new Profile("unset", 0, false, CatMood.Calm);  // -> var owner: Profile
    public Profile Promote(Profile p) => p with { Score = p.Score + 1 };
}

// Adversarial: fails rule 2 (no public instance constructor). Must be skipped with
// skipped_unsupported_struct and must NOT generate a Kotlin type or a handle wrapper.
public struct Unsupported { public int A; public int B; }
```

`sample-library/build.gradle.kts` gains `include("Sample.Structs")` and
`alias("Sample.Structs", "sample.structs")` in the `SampleDependency` `bind {}` block.

Kotlin consumer in `sample-library`:

```kotlin
import sample.structs.Geometry
import sample.structs.Point
import sample.structs.Profile
import sample.structs.Roster

val p = Point(3, 4)
val moved: Point = Geometry.translate(p, 1, 1)
check(moved == Point(4, 5))          // value equality across the bridge; no close(), no handle
check(moved.copy(x = 0) == Point(0, 5))
check(Geometry.describe(moved) == "(4, 5)")
check(Geometry.manhattan(p, moved) == 2)

val roster = Roster()
roster.owner = Profile("Oreo", 3, true, CatMood.Playful)   // struct-typed property setter
check(roster.promote(roster.owner).score == 4)
```

Note `Roster` is an ADR-051 handle-backed class (it is a C# *class*), so it keeps its `close()`;
`Point` and `Profile` are values and have none. The fixture deliberately shows both in one file.

## Consequences

### New/changed artifacts

- `nuget-metadata-reader/Program.cs`: struct branch in `ProcessType` **before** the class
  fall-through; `CollectStructTypes` + Decision 3a rule checks; `RirStruct`/`RirStructComponent`/
  `RirStructType` in the mirrored C# model; struct case in `SignatureDecoder.GetTypeFromDefinition`;
  `skipped_unsupported_struct` diagnostics. Struct members are **not** enumerated.
- `RirModel.kt`: `RirStruct`, `RirStructComponent`, `RirStructType`,
  `RirDiagnosticKind.SKIPPED_UNSUPPORTED_STRUCT`.
- `RirBridging.kt`: `BoundTypes` context replacing the growing `boundHandleTypes` parameter; shared
  `abiArgs` / `abiOutArgs` / `abiReturnType` expansion (**both generators must consume these**);
  `isV1Type` struct case; `describe()`/`signaturePart()` struct expansion; `contractHash` gains the
  struct map.
- `NugetGenerateBindingsTask`: emit `{StructName}.kt` (`data class`, no Bindings file, no
  registration export); build `CFunction` types and stub bodies from `abiArgs`/`abiOutArgs`;
  `memScoped` + `alloc<…Var>()` out-slots for struct returns/getters.
- `NugetGenerateShimsTask`: thunk signatures from the same shared expansion; `unsafe` thunk bodies for
  out-pointer writes; `new T(...)` reconstruction for struct parameters.
- `sample-dependency/Geometry.cs` (new); `sample-library/build.gradle.kts` (`bind` include/alias);
  `sample-library` consumer code; `SampleApp.Tests` round-trip assertions.

### Behavioural changes

- **A struct is no longer emitted as a class.** Any struct in a bound namespace currently produces a
  bogus `RirClass` (verified); after this change it produces an `RirStruct` or a
  `skipped_unsupported_struct` diagnostic. No shipped fixture contains a struct, so nothing in the
  repository regresses; a user who had a struct in a bound namespace would previously have got a
  handle-wrapper class that no method could reference, and will now get a `data class` that methods
  *can* reference.
- **`record struct` diagnostic noise disappears** (verified present today: a spurious
  `skipped_overload_set` on the synthesized `Equals` pair and an `info_oblivious_nullability` on the
  synthesized `ToString`).
- The registration contract (`nuget_{ns}_{type}_register`, `slotCount`, pointer order) is **unchanged
  in shape**. Slot *signatures* change for any method/property that mentions a struct, and
  `contractHash` changes accordingly — both generators regenerate together, as always.
- Generated thunks that return a struct are `unsafe` (the shim project already sets
  `<AllowUnsafeBlocks>true</AllowUnsafeBlocks>`).

### Verified vs. inferred

**Verified** (commands and real output in Context/Alternatives above):

- `[UnmanagedCallersOnly]` blittability is enforced at runtime, is transitive, and rejects `bool`/
  `char` (`InvalidProgramException`) even nested inside a struct; a `string` field is rejected at
  compile time (CS8894). *(net8.0 compile; .NET 10.0.8 runtime.)*
- A strictly-blittable struct **does** cross by value in both directions across a real C ABI boundary.
- A **hand-written** `CStructVar` cannot be used in a `CFunction` type's `invoke`
  (`error: type kotlinx.cinterop.CValue<Point> is not supported here: not a structure or too complex`);
  a **cinterop-generated** one can. *(Kotlin/Native 2.4.0, macOS arm64.)*
- The chosen wire format (components in, out-pointers out) works end to end in the **real topology**
  (Kotlin/Native dylib + .NET host + `[UnmanagedCallersOnly]` thunks registered through a `@CName`
  export), for `string`, `int`, `bool`, `char` and enum components, in both directions, with value
  equality preserved.
- The repository's current metadata reader emits every struct shape as `{"kind": "class"}`, emits a
  `record struct`'s synthesized `Equals` as an overload-set diagnostic and its `ToString` as a
  bridgeable method, reports `init`-only setters as settable, extracts no public fields, and emits no
  `.ctor` row for a struct that declares none.
- C# structs carry `SequentialLayout` in metadata; ctor parameter names may differ in case from the
  property names they correspond to.

**Inferred** (not verified against a real artifact — the walking skeleton must falsify each):

- **`float` / `double` / `long` / `short` / `byte` components** are assumed to behave exactly like the
  `int` component that was verified (direct value in, `T*` out-pointer). They are already blittable
  and already cross at the top level today, so this is a small extrapolation — but it is an
  extrapolation.
- **Two struct parameters in one signature**, and a **struct-typed property setter**, are assumed to
  compose from the single-struct-parameter case that was verified. The expansion is mechanical, but no
  spike exercised more than one struct parameter at a time.
- **Rule 5's field-count check** (non-static instance fields == constructor parameters) is inferred to
  be a sound proxy for "the constructor covers all stored state". It is verified that `record struct`
  and the classic immutable struct both satisfy it, and that backing fields are the only instance
  fields those shapes have; it is *not* verified against a struct with a hand-written extra private
  field.
- The claim that a struct **never** needs a `GCHandle` is inferred for the v1 component vocabulary
  only. A struct with a class-typed component would need one (deferred below).

### Scope

**In v1 (this ADR):**

- `RirStruct` / `RirStructComponent` / `RirStructType` end to end (reader → RIR → both generators).
- Shape A structs (single public constructor; components = ctor parameters; all stored state covered).
- Component vocabulary: primitives (incl. `bool`, `char`), `string`, bound enums.
- Kotlin immutable `data class`; zero registration slots; no `close()`, no `Cleaner`.
- Struct as parameter, return, and property type on static **and** instance members of bound classes.
- Shared `abiArgs`/`abiOutArgs` expansion consumed by both generators; `contractHash` struct
  expansion.
- `skipped_unsupported_struct` diagnostics for every rule in Decision 3a.

**Deferred:**

- **Shape B** — structs with no public constructor (public fields / settable auto-properties). Needs
  field extraction, which does not exist. First candidate for follow-up.
- **Struct methods and computed properties** — the direct mirror of ADR-014's "reconstruct the value
  class on each invocation": the thunk takes the components as leading arguments and rebuilds the
  struct. Requires the struct to get its own registration export. The path is open; the machinery is
  not built.
- **Structs with multiple public constructors** (including `System.Drawing.Point`) — blocked on the
  ROADMAP line 161 overload-disambiguation item, which lifts this for structs and classes together.
- **Nested struct components** (a struct field inside a struct) — recursive flattening; deferred for
  scope, not for a technical obstacle.
- **Class-typed (handle) components** — deliberately deferred on *semantics*, not cost: a `GCHandle`
  inside a value type does not compose. `copy()`ing the Kotlin `data class` would duplicate a
  `Cleaner`-managed handle, so a value copy would carry shared, independently-freed ownership. Needs
  its own decision.
- **`Nullable<T>` (`int?`, `Point?`)** — unblocked by this ADR's out-pointer format (prescribed
  above), separate ROADMAP slice, **no new ADR needed**.
- **Generic structs** (`KeyValuePair<K,V>`) — open generics stay excluded (ADR-043); closed
  constructed generics are Phase 10.
- **Structs as collection elements** — Phase 10.
- **`ref struct` / `Span<T>`** — permanently excluded (ADR-043), unchanged.
- **Explicit `[StructLayout]` / `[FieldOffset]`** — irrelevant by construction: the wire format never
  uses layout. Recorded so that no future reader mistakes metadata's `SequentialLayout` for a
  guarantee about runtime layout.
