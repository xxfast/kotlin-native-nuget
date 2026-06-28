# ADR-035: Value class primary-constructor init validation — hand-written record struct

## Status

Accepted

## Context

[ADR-033](033-value-class-constructor-exception-propagation.md) added exception
propagation for value-class **secondary** constructors and explicitly deferred
the **primary** constructor:

> Deferred: Enforcing primary-constructor `init` validation (no create export
> exists for it today — would require always routing construction through Kotlin).

A Kotlin value (inline) class can validate in its primary constructor's `init`
block:

```kotlin
value class CatId(val id: String) {
  init { require(id.length <= 20) { "Cat ID too long: $id" } }
  constructor(name: String, number: Int) : this("$name-$number")
}
```

Today `renderValueClass` (`CirObjectRenderer.kt`) emits a **positional** record
struct:

```csharp
public readonly record struct CatId(string Id)
{
    // secondary ctors only (ADR-033) ...
    public CatId(string name, int number) : this(CreateChecked(name, number)) { }
}
```

The record's compiler-generated **positional primary constructor** `CatId(string Id)`
takes the underlying value directly and is constructed entirely C#-side. So
`new CatId("an-id-string-that-is-far-too-long")` never calls Kotlin, and the
`require` in `init` is silently bypassed. Validation that lives in a value class's
primary constructor is not enforced across the bridge — a correctness gap.

### Why the positional record blocks a fix

A positional `record struct CatId(string Id)` auto-generates the primary ctor,
the `Id` property, value equality, `Deconstruct`, and `ToString`. The primary
ctor cannot be given a body, and a second `public CatId(string)` would collide
with the positional one's signature — so there is no place to insert a native
validation call for the primary construction path.

### How other Kotlin interop targets handle it

- **Swift export / ObjC**: inline classes are boxed at the boundary and their
  initializers run the Kotlin constructor (including `init`); a `@Throws` primary
  init maps to `init() throws` / `NSError**`, so validation is always enforced
  and a failed init never yields a value (same mechanism cited in ADR-031/033).
- **Kotlin/JS (`@JsExport`)**: the exported constructor runs the Kotlin
  constructor; `init` throws a JS `Error`. No bypass.

Source: [Kotlin/Native ObjC interop — Errors and exceptions](https://kotlinlang.org/docs/native-objc-interop.html#errors-and-exceptions),
[Swift interoperability](https://kotlinlang.org/docs/native-swift-interop.html#exceptions).

Across every other target, constructing an inline class runs its `init`. Our
bridge is the outlier; this ADR closes the gap. The C# Framework Design
Guidelines likewise mandate "do throw exceptions from constructors", so C#
developers already expect `new CatId(bad)` to throw.

## Alternatives Considered

### 1. Hand-written record struct; every constructor has a validating body (chosen)

Replace the positional record with a hand-written `readonly record struct` that
declares the underlying as an explicit get-only property and gives **every**
constructor (primary and secondary) a body that assigns the property from a
`CreateChecked*` helper (the ADR-033 pattern: native call + `out IntPtr error`
check + return underlying):

```csharp
public readonly record struct CatId
{
    public string Id { get; }

    [DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "catid_create")]
    private static extern IntPtr Native_Create(string id, out IntPtr error);

    private static string CreateChecked(string id)
    {
        IntPtr underlying = Native_Create(id, out IntPtr error);
        if (error != IntPtr.Zero)
        {
            throw NugetErrorNative.BuildException(error);
        }
        return Marshal.PtrToStringUTF8(underlying)!;
    }

    public CatId(string id)
    {
        Id = CreateChecked(id);
    }

    // secondary: same shape, distinct entry point + helper
    [DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "catid_create_2")]
    private static extern IntPtr Native_Create_2(string name, int number, out IntPtr error);

    private static string CreateChecked_2(string name, int number) { /* … */ }

    public CatId(string name, int number)
    {
        Id = CreateChecked_2(name, number);
    }
}
```

The Kotlin primary export runs the full constructor and returns the (validated)
underlying:

```kotlin
@CName("catid_create")
fun export_catid_create(id: String, errorOut: COpaquePointer?): String =
  try {
    CatId(id).id
  } catch (e: Throwable) {
    if (errorOut != null) {
      errorOut.reinterpret<COpaquePointerVar>().pointed.value =
        StableRef.create(buildError(e)).asCPointer()
    }
    ""
  }
```

Pros:
- Enforces primary `init`; `new CatId(bad)` throws the mapped exception (ADR-029).
- **Eliminates the `: this(...)` problem from ADR-033** — with bodies, each
  constructor assigns `Id` directly, so secondaries no longer delegate and never
  double-validate.
- Keeps the idiomatic `new CatId(x)` surface and `record struct` value equality.

Cons:
- All value-class construction now does a native round-trip (see Consequences).
- `Id` is get-only (no `init` accessor) to prevent object-initializer / `with`
  bypass — drops `with`-expression support for value classes (see Consequences).

### 2. Keep positional record + a static factory `CatId.Create(string)`

Add a validating factory and leave the positional ctor unvalidated. Rejected:
two construction idioms coexist, the unvalidated `new CatId(x)` remains a trap,
and it violates the C#-native goal in GOALS.md.

### 3. Keep positional record + make the positional ctor private

A positional record's primary ctor cannot be made private without losing the
positional syntax, and a separate `public CatId(string)` collides with it.
Not expressible.

## Decision

Adopt **Alternative 1**. Render value classes as a hand-written
`readonly record struct` with an explicit get-only underlying property; route
**every** constructor through a `CreateChecked*` helper with a body that assigns
the property. Add a primary-constructor export.

### Entry-point naming (aligns value classes with ADR-034)

Currently value-class secondary exports are numbered `${prefix}_create` (first),
`${prefix}_create_1`, … (ADR-033). This ADR realigns value classes with the
regular-class scheme from ADR-034:

- **primary** → `${prefix}_create` / `Native_Create` / `CreateChecked`
- **secondaries** → `${prefix}_create_2`, `_create_3`, … / `Native_Create_2` /
  `CreateChecked_2`

This supersedes ADR-033's secondary numbering. It is safe churn: ADR-033 is
generated and unreleased, so nothing external depends on the old names, and the
result is one consistent constructor-naming rule across regular and value classes.

### Affected files

- `ValueClassExports.kt` — emit a primary-constructor export (`${prefix}_create`)
  with `errorOut` + try/catch + dummy return; renumber secondary exports to
  `_create_2+`.
- `CirModel.kt` — `CirValueClass` carries a primary `CirValueClassConstructor`
  (or a flag) in addition to the secondary list.
- `CirClassTranslator.kt` (`translateValueClass`) — build the primary constructor
  descriptor; renumber secondaries; set `hasErrorCheck = true`.
- `CirObjectRenderer.kt` (`renderValueClass`) — emit a hand-written record struct:
  explicit get-only underlying property; per-constructor body assigning it from a
  `CreateChecked*` helper; drop the positional parameter and the `: this(...)`
  delegation.

## Consequences

- **Primary `init` is enforced.** `new CatId(tooLong)` throws
  `KotlinArgumentException` (per ADR-029) instead of silently constructing an
  invalid value. Behavior change, strictly a correctness improvement.
- **Every construction does a native round-trip.** Previously the primary path
  was pure C#. For value types this is a real per-construction cost; acceptable
  because correctness (running `init`) requires calling Kotlin, matching all
  other interop targets.
- **`with`-expressions and object initializers are dropped for value classes.**
  The underlying property is get-only so neither `catId with { Id = … }` nor
  `new CatId { Id = … }` can bypass validation. Value equality, `Deconstruct`
  (via the record), and `ToString` are retained. (If `with` is later required, it
  would need a validated `init` accessor that re-routes through `CreateChecked`.)
- **`default(CatId)` still bypasses validation.** A zero-initialized struct
  (`default`, array allocation, uninitialized field) cannot run a constructor;
  this is an inherent C# struct limitation and is unavoidable. Document it; do not
  attempt to guard it.
- Secondary-constructor entry points are renumbered (`_create` → `_create_2+`);
  generated only, no external impact.

### Scope

**This ADR:** primary-constructor `init` validation for value classes with a
**primitive or `String` underlying** type (e.g. `CatId`), plus realignment of
secondary entry-point numbering.

**Deferred:**
- **Reference-underlying** value classes (`CatResult(val cat: Cat)`,
  `ObservationResult`): the underlying crosses as a `_handle` and these have no
  secondary constructors today; enforcing their primary `init` needs separate
  handling of the reference round-trip and is tracked as a follow-up.
- `with`-expression support on validated value classes.
