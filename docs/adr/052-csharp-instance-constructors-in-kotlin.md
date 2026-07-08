# ADR-052: C# instance constructors in Kotlin — `new Foo(...)` as a Kotlin secondary `constructor`, non-null handle-returning ctor thunk, and single-constructor v1 ceiling

## Status

Accepted

## Context

Phase 9 line 150: "Map instance constructors (`new Foo(...)` → Kotlin constructor or factory
function)." ADR-051 made a C# reference type usable from Kotlin as a handle-backed wrapper, but
deliberately **deferred instance construction**: the only construction paths it generates are
*static factories* (a named C# static method returning the type, e.g. `Template.Parse` →
`Template.parse(): Template?`). A C# type whose only way to instantiate is `new Foo(a, b)` cannot
be created from Kotlin at all today — the metadata reader skips every `.ctor` via the `SpecialName`
filter in `nuget-metadata-reader/Program.cs:272`. This ADR lifts that skip for public instance
constructors and decides the Kotlin surface they take.

Almost every boundary mechanic this needs is already settled by ADR-051 and reused unchanged:

- **Handle representation** — the C# constructor thunk allocates `GCHandle.Alloc(new Foo(...))` and
  returns `GCHandle.ToIntPtr(h)`, exactly like ADR-051's `Parse_Thunk`. A constructor thunk *is a
  static thunk that takes no receiver and returns a handle* — the ADR-051 insight ("an instance
  thunk is a static thunk whose first parameter is the receiver handle") with the receiver dropped.
- **Wrapper** — the generated `class Foo internal constructor(handle: COpaquePointer) : AutoCloseable`,
  `NugetObjectHandle`, `Cleaner`, `close()`, and the shared `nuget_runtime_register`/
  `FreeGcHandle_Thunk` free export are all as ADR-051 generates them today.
- **String marshalling** — constructor parameters marshal identically to method parameters
  (`memScoped { fn.invoke(name.cstr.ptr, …) }` on the Kotlin side, `Marshal.PtrToStringUTF8` in the
  thunk), per ADR-048/049 and the current `NugetGenerateBindingsTask.buildStubMethod`.
- **Registration contract** — the constructor is one more registered function pointer on the type's
  existing `nuget_{ns}_{type}_register` export (ADR-048 contract, extended by one slot).

So the mirror to ADR-051 holds cleanly for the plumbing. Three things are genuinely new and are what
this ADR decides:

1. **The Kotlin surface for `new`.** ADR-051's wrapper already spends its single primary-constructor
   slot on `internal constructor(handle: COpaquePointer)`. A public `new Foo(a, b)` must call a C#
   ctor thunk, get a handle back, and *then* construct the wrapper — but Kotlin forbids running
   statements before a `this(...)` delegation. Factory function, secondary constructor, or
   companion `invoke`?
2. **Metadata-reader `.ctor` detection.** `.ctor` is `SpecialName` (as are `.cctor` and every
   property accessor). Lifting the skip must admit *only* public instance `.ctor`, and must group
   multiple `.ctor`s as an overload set.
3. **RIR modelling + non-null return.** A constructor never returns null (`new` either succeeds or
   throws), unlike ADR-051's `Foo?` factory returns — the RIR node and the generated signatures must
   express that.

### The forward-direction mirror (ADR-031, ADR-034)

The forward direction maps a Kotlin constructor to a C# `new Foo(...)` — the exact inverse. It kept
constructors as **real C# constructors** (not factories): the generated C# wrapper runs
`IntPtr handle = Native_Create(args, out error); if (error) throw; _handle = handle;` inside the
constructor body (ADR-031). C# permits arbitrary statements in a constructor body before the field
assignment, so the "call the bridge, then adopt the handle" shape fits a real constructor directly.

Kotlin is the mirror-image constraint: its *primary* constructor slot is taken, and its *secondary*
constructor can only delegate via `: this(expr)` — no statement block before delegation. The
question is whether that restriction forces a factory, or whether a real secondary constructor still
works. It works: the delegation *argument* may be an arbitrary expression, including a call to a
top-level helper that runs the bridge call and returns the handle. `: this(construct(a, b))` is
legal, so Kotlin can keep the idiomatic "constructor → constructor" mapping the forward direction
uses, just expressed through the delegation expression instead of a statement block.

ADR-034 (forward secondary constructors) is the mirror for the *overload* question: C has no
overloading, so it exports `_create`, `_create_2`, `_create_N` and **fails fast on a C#-erased
signature collision**. In the reverse direction the collision constraint is the same (each thunk
needs a unique C export symbol / a unique registration slot), but Kotlin *does* have constructor
overloading, so the ceiling is only export-symbol uniqueness — exactly what ROADMAP line 156
(overload-set disambiguation) is about. This ADR keeps the two concerns separate: **v1 supports a
single public instance constructor**; multiple public `.ctor`s are an overload set, skipped and
diagnosed per ADR-043, with the disambiguation scheme deferred to line 156.

### Why not map C# primary constructors to Kotlin primary constructors

C# *does* have a primary-constructor concept — records since C# 9 (`record Foo(int A)`), plain
classes/structs since C# 12 (`class Foo(int a)`) — so an obvious question is whether a C# primary
constructor should become the Kotlin **primary** constructor and additional `.ctor`s the secondary
ones. It cannot, for two independent reasons:

1. **The distinction does not survive compilation.** This bridge extracts the API from *compiled*
   assembly metadata (ECMA-335 via `MetadataReader`, ADR-042/046), not source. The C# compiler
   lowers a primary constructor into an ordinary instance `.ctor` plus synthesized capture fields;
   there is no `[PrimaryConstructor]` attribute or metadata flag. At the IL level a primary and a
   secondary constructor are indistinguishable — both are `.ctor` methods with parameter lists — so
   the reader has no signal to map on.
2. **The Kotlin primary slot is structurally reserved.** ADR-051 spends the wrapper's primary
   constructor on `internal constructor(handle: COpaquePointer)`, and that is not incidental: *every*
   path that produces a wrapper (factory returns today, instance-method returns later) funnels
   through "I already hold a handle, wrap it." A domain constructor cannot take the primary slot
   because the object cannot exist until C# has been called to *produce* a handle, and a Kotlin
   primary constructor cannot run that bridge call before initialization. So a domain constructor is
   necessarily a **secondary** constructor delegating through `this(construct(...))`, whatever it was
   in C#.

For v1 the point is doubly moot: the single-public-constructor ceiling below means there is only one
`.ctor` per type in scope anyway.

### How other ecosystems surface a foreign constructor

| Ecosystem (consuming a foreign lib) | Foreign constructor surfaces as | Real ctor or factory? |
|---|---|---|
| **Kotlin consuming Java** (gold standard) | Java `new Foo(a)` → Kotlin `Foo(a)` | **Real constructor.** A Java constructor is a Kotlin constructor; the call site drops `new`. |
| **Kotlin/Native ObjC import** | ObjC `[[Foo alloc] initWithA:a]` → Kotlin `Foo(a = a)` | **Real constructor.** cinterop maps `init` family methods to Kotlin constructors ([native-objc-interop: Initializers](https://kotlinlang.org/docs/native-objc-interop.html#initializers)). |
| **Xamarin / .NET consuming Java** (mirror of this project) | Java `new Foo(a)` → C# `new Foo(a)`; the MCW ctor calls JNI `NewObject`, stores the resulting global ref, and passes it to the peer base ctor | **Real constructor.** ([Xamarin.Android architecture — MCWs](https://learn.microsoft.com/en-us/previous-versions/xamarin/android/internals/architecture)). |
| **Python.NET consuming CLR** | CLR `new Foo(a)` → Python `Foo(a)` (`__init__` over the ctor) | **Real "constructor"** (Python `__call__` on the class object — the language's normal construction syntax). |
| **kotlinx "fake constructors"** | e.g. `MutableStateFlow(x)`, `List(n) {}` | Top-level **function named like the type** — used when the type is an interface or needs pre-processing, *not* for a concrete class with a plain ctor. |

The consistent precedent for "consuming a foreign class with a plain instance constructor" is a
**real constructor at the call site** (`Foo(a, b)`), with only interface/abstract/needs-preprocessing
cases falling back to a factory. ObjC import is the closest analogue inside Kotlin/Native itself, and
it maps `init` to a constructor. This aligns with GOALS ("bindings should feel like Kotlin"): a
Kotlin developer instantiates with `Foo(a, b)`, not `Foo.create(a, b)`.

### What the metadata reader must change

`ExtractType` currently drops every `SpecialName` method (`Program.cs:272`), which covers property
accessors (`get_`/`set_`), the static constructor (`.cctor`), *and* instance constructors (`.ctor`).
This ADR narrows that: a method that is `SpecialName` **and** named `.ctor` **and** public **and**
non-static becomes a candidate constructor; everything else `SpecialName` stays skipped. Candidate
`.ctor`s are grouped: exactly one → emit a constructor; more than one → `skipped_overload_set`
diagnostic (the same rule already applied to overloaded methods, `Program.cs:286`). A `private`/
`internal`/`protected` `.ctor` is skipped silently (already excluded by the `MemberAccessMask`
public check, which must run *before* the narrowed `.ctor` admission).

## Alternatives Considered

### 1. Secondary public `constructor(...)` delegating through a bridge helper (chosen)

The wrapper keeps its ADR-051 `internal constructor(handle: COpaquePointer)` primary and gains a
secondary constructor per public instance `.ctor`, delegating through a file-private `construct(...)`
helper that runs the bridge call:

```kotlin
internal class Template internal constructor(handle: COpaquePointer) : AutoCloseable {
    internal val handle: NugetObjectHandle = NugetObjectHandle(handle)
    @Suppress("unused")
    private val cleaner = createCleaner(this.handle) { it.free() }
    override fun close(): Unit = handle.free()

    // Generated for the public C# `Template(string source)`:
    constructor(source: String) : this(construct(source))

    companion object { /* static parse/render as ADR-051 */ }
}

// file-private helper in Template.kt — runs the ctor thunk, returns the raw handle
private fun construct(source: String): COpaquePointer {
    val fn = requireNotNull(ctorFn) { "Template bindings are not registered. …" }
    val ptr: COpaquePointer? = memScoped { fn.invoke(source.cstr.ptr) }
    return requireNotNull(ptr) {
        "Template constructor returned a null handle — a C# constructor never returns null."
    }
}
```

Call site — identical to `new Template(...)` minus `new`:

```kotlin
val template = Template("Hello, {name}")
val greeting = Template.render(template, "world")   // static consumer (ADR-051 handle param)
// GCHandle freed on GC; template.close() / use { } for deterministic release
```

**Pros:**
- **Most idiomatic** — a real Kotlin constructor. Matches the Kotlin-consuming-Java and
  Kotlin/Native-ObjC-import precedents (constructor → constructor), and the forward direction's own
  "keep it a real constructor" choice (ADR-031). Satisfies GOALS directly.
- Supports `::Template` constructor references and shows as a constructor in IntelliJ autocompletion,
  which a factory function does not.
- The `this(...)` delegation restriction is fully sidestepped: `construct(...)` is an ordinary
  function whose body can `memScoped`/marshal/`requireNotNull` freely. The "can't run code before
  `this()`" concern is real but not a blocker — it only forces the bridge call into a helper.
- **Non-null return falls out naturally.** A constructor's Kotlin type is `Template`, never
  `Template?` — distinct from ADR-051's `Foo?` factory returns, and correct: `new` never yields
  null. `construct` `requireNotNull`s the handle (a null there is a bridge invariant violation, not a
  legitimate value — unlike a factory where `IntPtr.Zero` means a real C# `null`).
- **Future-proof for Phase 11.** When reverse exception propagation lands (mirror of ADR-031), the
  error-out inspection and mapped-exception throw live inside `construct(...)`; it throws before
  returning, so the delegation simply never completes. No re-shaping of the constructor is needed.

**Cons:**
- The bridge call is one indirection away from the constructor (in `construct`), slightly less
  obvious than an inline body. Acceptable — it is the direct consequence of Kotlin's delegation rule,
  and the helper is generated, not hand-written.

### 2. Factory function on the companion — `Template.create(...)` (rejected)

Reuse ADR-051's static-factory machinery verbatim: emit `fun create(source: String): Template` on
the companion, exactly like the generated `parse`.

**Pros:** zero new wrapper machinery — a constructor becomes "just another handle-returning static
stub"; the RIR could even reuse `RirMethod`.

**Cons (rejected):**
- Not idiomatic. `Foo.create(a, b)` reads like a factory (implying caching, polymorphic return, or
  validation-with-`null`), which a plain `.ctor` is not. It breaks the constructor → constructor
  precedent every analogue in the table follows.
- Conflates two *different* C# constructs. ADR-051's `parse` is a **named** static method
  (`Parse`) that genuinely maps to a companion function; a constructor is **anonymous** (`new`) and
  its idiomatic Kotlin spelling is a constructor call. Making both companion functions erases that
  distinction and would leave a Kotlin dev unsure whether `Guid.parse(s)` and a hypothetical
  `Guid.create(s)` are the same thing.
- Would need a *non-null* factory variant anyway (`create` returns `Template`, not `Template?`),
  so it is not even a free reuse of the `Foo?`-returning factory path.

### 3. `operator fun invoke(...)` on the companion (rejected)

Make `Template(a, b)` resolve to a companion `operator fun invoke(a, b): Template` that runs the
bridge call in an ordinary function body and returns `Template(handle)`.

**Pros:** call site reads `Template(a, b)` (same as Alternative 1); the body has no delegation
restriction, so pre-construction logic is unconstrained.

**Cons (rejected):**
- It is a **fake constructor**: not an actual constructor member, so it does not support `::Template`
  as a constructor reference, and IntelliJ presents it as a function rather than a constructor. The
  kotlinx "fake constructor" idiom exists specifically for *interfaces* and types needing
  preprocessing, not for a concrete class that has a real ctor to model.
- Alternative 1 already achieves the same call site *and* is a genuine constructor, at the cost of
  one generated helper — so `invoke` buys nothing that Alternative 1 lacks while giving up
  constructor-ness. The only thing `invoke` does more easily (arbitrary pre-`this` code) is already
  handled by the `construct` helper.

### 4. Multiple public constructors in v1 (deferred, not chosen)

Support overloaded `.ctor`s by generating overloaded Kotlin secondary constructors, each backed by a
distinctly-named thunk (`Ctor_2_Thunk`, …) with a fixed registration-slot order — the direct mirror
of ADR-034's `_create_N` scheme.

**Deferred, not rejected.** Kotlin *can* overload constructors, so this is achievable, but it
requires a deterministic thunk-naming + registration-ordering scheme and C#-erased-collision
fail-fast rules that are exactly the subject of ROADMAP line 156 (overload-set disambiguation). Folding
it in here would drag the whole overload-set decision into this ADR. v1 ceiling: a type with more than
one public instance `.ctor` has its constructors treated as an overload set — skipped and diagnosed per
ADR-043 — and remains constructible only via any surviving static factory. Revisit under line 156.

## Decision

Use **Alternative 1**: a C# public instance constructor maps to a Kotlin **secondary
`constructor`** on the ADR-051 wrapper class, delegating via `: this(construct(...))` to a
file-private `construct(...)` helper that calls a non-null handle-returning constructor thunk. v1
supports exactly one public instance constructor per type; multiple are an overload set (skip +
diagnose, ADR-043). All other boundary mechanics are reused from ADR-051 unchanged.

### RIR extension

A constructor maps to a distinct Kotlin construct (`constructor`, not `fun`) and has no return type
in metadata, so it gets its own node rather than overloading `RirMethod` (whose `returnType` is
mandatory and would be redundant). This mirrors the forward CIR, where `CirClass` carries
`constructors: List<CirConstructor>` separately from methods (ADR-034).

```kotlin
// RirModel.kt
@Serializable
data class RirConstructor(
    val parameters: List<RirParameter> = emptyList(),
)

// RirClass gains:
val constructors: List<RirConstructor> = emptyList()   // additive; old JSON parses (default empty)
```

The return is implicit: the enclosing `RirClass`'s own `RirObjectHandleType(namespace, name)`. The
metadata reader emits at most one `RirConstructor` per class in v1 (the single surviving public
instance `.ctor`); a class with >1 public instance `.ctor` emits **no** `RirConstructor` and one
`skipped_overload_set` diagnostic naming `.ctor`.

Parameter types are constrained to the same bridgeable subset as any other member (ADR-043): a
constructor with a `ref struct`/open-generic/`dynamic` parameter, or a `RirObjectHandleType`
parameter that does not resolve to a bound type, is skipped with the existing per-parameter
diagnostic — the constructor is not partially bound.

### Metadata-reader change (`nuget-metadata-reader/Program.cs`)

The `SpecialName` skip (line 272) is narrowed. Pseudocode of the new admission, run *after* the
existing `MemberAccessMask == Public` check (so non-public `.ctor`s stay excluded):

```
if (SpecialName):
    name = GetString(method.Name)
    if (name == ".ctor" && not static):        // public instance constructor candidate
        add to ctorGroup
    continue                                    // .cctor, get_/set_ still skipped
...
// after the method loop:
if (ctorGroup.Count == 1 && all params map to the bridgeable subset):
    emit RirConstructor
else if (ctorGroup.Count > 1):
    emit skipped_overload_set diagnostic for ".ctor"
```

Static classes (`isStatic`, ADR-051) have no instance `.ctor` and are unaffected. Interfaces have no
constructors. `RirClass.isStatic == false` non-abstract classes are the only ones that can carry a
`RirConstructor`; an abstract class's `.ctor` is not publicly `new`-able and is treated the same as
"no public ctor" (its `.ctor` is `protected`/skipped).

### Shared bridgeable ordering (anti-drift, extends ADR-049 Alt 10)

The registration export gains one pointer for the constructor. Both generators must agree on where
it sits. **The constructor pointer comes first, before the static-method pointers**, then methods in
their existing `reverse-ir.json` order. The shared filter (`RirBridging.kt`) exposes the ordered
"registrable" list — constructor(s) then methods — and both `NugetGenerateBindingsTask` and
`NugetGenerateShimsTask` consume it, closing the same parameter-order-drift hole ADR-049 Alt 10
closed for methods.

### Generated Kotlin — `{TypeName}Bindings.kt` (ctor pointer added)

```kotlin
// registration machinery — one new pointer var + one new register parameter
internal var ctorFn: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
internal var renderFn: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> COpaquePointer?>>? = null

@OptIn(ExperimentalNativeApi::class)
@CName("nuget_sample_text_template_register")
fun nuget_sample_text_template_register(
    ctorPtr: COpaquePointer,          // ctor first (shared ordering)
    renderPtr: COpaquePointer,
) {
    ctorFn = ctorPtr.reinterpret()
    renderFn = renderPtr.reinterpret()
}
```

### Generated Kotlin — `{TypeName}.kt` (secondary constructor + `construct` helper)

As shown in Alternative 1: the secondary `constructor(source: String) : this(construct(source))`
inside the class, and the file-private `private fun construct(source: String): COpaquePointer` below
it. The `construct` helper reuses the same string marshalling
(`memScoped { fn.invoke(source.cstr.ptr) }`) already emitted by `buildStubMethod`, and
`requireNotNull`s the returned handle (non-null constructor contract).

### Generated C# — `{TypeName}Registration.cs` (ctor thunk)

The thunk mirrors ADR-051's `Parse_Thunk` but calls `new` and is unconditionally non-null:

```csharp
[UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
private static IntPtr Ctor_Thunk(IntPtr sourcePtr)
{
    var obj = new Template(Marshal.PtrToStringUTF8(sourcePtr)!);
    return GCHandle.ToIntPtr(GCHandle.Alloc(obj));   // never IntPtr.Zero — new never returns null
}
```

registered first in `Initialize()`:

```csharp
[ModuleInitializer]
internal static unsafe void Initialize() =>
    nuget_sample_text_template_register(
        (IntPtr)(delegate* unmanaged[Cdecl]<IntPtr, IntPtr>)(&Ctor_Thunk),
        (IntPtr)(delegate* unmanaged[Cdecl]<IntPtr, IntPtr, IntPtr>)(&Render_Thunk));
```

v1 exception behaviour is unchanged from ADR-049: a throwing C# constructor (e.g. `ArgumentException`
on bad input) escapes the thunk and fast-fails the host process — no `try/catch`. Graceful
propagation of a constructor exception into a Kotlin `catch` is Phase 11 (mirror of ADR-031), and
hooks into the `construct(...)` helper as noted in Alternative 1.

### Thunk/type table (extends the ADR-051 table)

| Position | Kotlin | Kotlin `CFunction` slot | C# thunk | Conversion |
|---|---|---|---|---|
| Constructor (the type itself, return) | `Foo` (non-null) | `COpaquePointer?` | `IntPtr` | `GCHandle.ToIntPtr(GCHandle.Alloc(new Foo(...)))` — never zero |
| Constructor parameter | per ADR-048/051 param rules | per ADR-048/051 | per ADR-049 | per ADR-049 |

## Consequences

### New/changed artifacts

- `nuget-metadata-reader/Program.cs` — narrow the `SpecialName` skip to admit public instance
  `.ctor`; group `.ctor`s (single → `RirConstructor`, multiple → `skipped_overload_set`); apply the
  ADR-043 subset filter to constructor parameters.
- `RirModel.kt` — `RirConstructor`; `RirClass.constructors` (additive, defaulted).
- `RirBridging.kt` — shared "registrable" ordering that places constructor pointer(s) before method
  pointers; consumed by both generators.
- `NugetGenerateBindingsTask` — emit the secondary `constructor` + file-private `construct(...)`
  helper in `{TypeName}.kt`; add `ctorFn` + the `ctorPtr` register parameter in `{TypeName}Bindings.kt`.
- `NugetGenerateShimsTask` — emit `Ctor_Thunk` and register it first in `Initialize()`.

### Behavioural notes

- `Foo(a, b)` constructs a fresh C# object and a fresh wrapper/`GCHandle` (new-wrapper-per-crossing,
  ADR-051 unchanged). Equality stays wrapper identity.
- Constructor return is non-null `Foo`; a null handle from the thunk is a bridge-invariant violation
  (`requireNotNull` in `construct`), not a mappable value — distinct from ADR-051's `Foo?` factories.
- A throwing C# constructor fast-fails the process in v1 (ADR-049 policy); Phase 11 will route it
  through `construct(...)`.

### Breaking changes

None at the consumer level. `reverse-ir.json` gains an additive `constructors` array (old JSON still
parses). A type that previously generated only static stubs and now also has a public `.ctor` gains a
constructor — additive. The type's `nuget_{ns}_{type}_register` export gains a leading `ctorPtr`
parameter; both generators regenerate together, so no hand-written code observes the change.

### Test-fixture surface (`sample-dependency/`)

ADR-051's fixture (`Sample.Text.Template`) has a **private** constructor (`private Template(string)`)
and is constructed only via `Parse`. Make that constructor **public** —
`public Template(string source)` — the minimal one-line change that gives the fixture a
single-overload public instance constructor within the v1 subset (one `string` parameter). The
existing static `Render(Template, string)` (ADR-051 handle parameter) and `Parse` (ADR-051 handle
return) stay, so the same fixture now exercises the full arc end-to-end:

```kotlin
val template = Template("Hello, {name}")          // this ADR: secondary constructor
val greeting = Template.render(template, "world")  // ADR-051: static consumer of the handle
template.close()                                    // ADR-051: deterministic release
```

Having both `new Template(...)` and `Template.Parse(...)` on one type is realistic (`Guid`,
`Version`, etc. expose both) and keeps the fixture minimal. A second public `.ctor` (to exercise the
overload-set skip diagnostic) is deferred with the multi-constructor scope (line 156).

### Scope

**In v1 (this ADR):**
- `RirConstructor` + `RirClass.constructors`; metadata-reader `.ctor` admission and overload
  grouping.
- Exactly one public instance constructor per non-static, non-abstract bound class → one Kotlin
  secondary `constructor(...)` delegating through a file-private `construct(...)` helper.
- Non-null handle-returning `Ctor_Thunk`, registered first in the type's registration export.
- Constructor parameters drawn from the ADR-043 bridgeable subset (primitives, `string`, bound
  `RirObjectHandleType`), marshalled exactly as method parameters.
- Fixture: make `Sample.Text.Template`'s constructor public.

**Deferred:**
- **Multiple public constructors** (overload set) — mirror of ADR-034's `_create_N` + collision
  fail-fast; folded into ROADMAP line 156 (overload-set disambiguation). v1 skips + diagnoses them.
- Instance methods / instance properties (line 151) — reuse this wrapper unchanged (an instance thunk
  is a static thunk whose first parameter is the receiver handle, ADR-051).
- Constructor exception propagation into a Kotlin `catch` (Phase 11, mirror of ADR-031) — hooks into
  `construct(...)`.
- Nullable constructor parameters (`Foo?` arguments) — falls out of the `NullableAttribute` mapping
  item (Phase 9), same as ADR-051's parameter-nullability deferral.
