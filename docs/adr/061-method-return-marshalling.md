# ADR-061: Non-primitive and nullable return marshalling for class methods and extension functions

## Status

Accepted. Implemented and verified: both return positions (class instance method, extension
function) route the full matrix (object, nullable object, `List<T>`/collection, nullable `String`,
nullable primitive) through the property-getter cascade, with the single-call out-parameter for
nullable primitives (Alternative 1). `scripts/verify.sh` is green.

## Context

The forward direction (Kotlin → C#) marshals non-primitive and nullable **property getters**
correctly (ADR-005 objects, ADR-011 collections, ADR-002 nullables), but the two **method-return**
positions — a class instance method and an extension function — do not. This ADR closes ROADMAP
line 61 (generic type args at the two return positions), and subsumes the open method-return items:
ADR-060 cells 4, 5, 6, and the nullable-method-return item (ROADMAP line 65). Scope, chosen by the
user, is the full return matrix at both positions: **object**, **`List<T>`/collection**,
**nullable object**, **nullable primitive**, and **nullable `String`**.

### What is broken today (all verified-in-repo)

- **Class-method loop** — `ClassExports.kt:509-577`. Non-`Unit` returns fall to a single `else`
  branch (`:560-574`) that declares the export return via
  `methodReturnType?.toBridgeTypeName(nullable = false) ?: ClassName.bestGuess(methodReturn)` and,
  in the catch path, emits `defaultValueFor(methodReturn)` (`:571`). For a `List<String>` this
  declares a `List<String>` return that cannot cross the C ABI by value, and `defaultValueFor`
  (`Helpers.kt:57-68`) emits the literal `0` for any `kotlin.*` name, so the try/catch expression
  infers `Any` — **invalid Kotlin**. For an object or nullable return it likewise declares a
  by-value or non-null type the body cannot satisfy.
- **Extension-function return** — `ExtensionFunctionExports.kt:66-80`. Identical shape:
  `returnType?.toBridgeTypeName(nullable = false) ?: ClassName.bestGuess(qualifiedReturn)` +
  `defaultValueFor(qualifiedReturn)` catch. Same failure.
- **C# side** — `CirClassTranslator.kt:624-678` builds each `CirMethod` with
  `body = "Native_$csMethodName($args)"` and `returnType = mapReturnType(methodReturn)`
  (`:660,665,670`). `mapReturnType` yields `"IntPtr"` for any reference/collection/nullable type
  and the body returns it **raw** — no `new Type(handle)`, no list materialization, no null check.
  There is no `isMarkedNullable` read anywhere in this method path (the ADR-060 / BUG-008
  position-not-type finding).

### The already-decided mechanisms this ADR reuses (do not redesign)

- **Object return via handle carrier** — the companion-method loop already does exactly this:
  `ClassExports.kt:747-760` (`isObjectReturn` → export returns `COpaquePointer?`, body
  `StableRef.create(obj).asCPointer()`, catch `null`). C# wraps as `new Type(handle)`
  (property path `CirClassTranslator.kt:431-438`). *Verified-in-repo.*
- **Collection return** — the property getter path materializes `List<T>` at
  `CirClassTranslator.kt:304-320` (`Native_Get` → `NugetListNative.Count` → loop →
  `NugetMarshal.FromHandle<T>` → `Dispose` → `AsReadOnly()`), public type `IReadOnlyList<T>`
  (`:251`). The **Kotlin side of a `List` property is just the object carrier**: a `List` is
  non-primitive, so it flows through the property `else` branch (`ClassExports.kt:396-415`) that
  boxes it as `StableRef.create(list).asCPointer()`. The shared `nuget_list_count` / `nuget_list_get`
  exports operate on any `StableRef<List<*>>` (`GenericClassExports.kt:135-156`). **So a `List`
  method return needs no new Kotlin export shape — it is an object return whose handle the shared
  list helpers already materialize.** *Verified-in-repo.*
- **Nullable object return** — property path `CirClassTranslator.kt:421-429`
  (`nativeResult == IntPtr.Zero ? null : new $propType(nativeResult)`); Kotlin side boxes
  non-null or returns a null pointer (`ClassExports.kt:352-372`). *Verified-in-repo.*
- **Nullable `String` return** — property path `CirClassTranslator.kt:375-383` is **already a
  single call**: `Native_Get` returns `IntPtr`, `Marshal.PtrToStringUTF8(nativeResult)` yields
  `null` for `IntPtr.Zero`. No two-call. *Verified-in-repo.*

### The one place the property mechanism cannot be copied

The nullable **primitive** property getter is the *only* return that uses ADR-002's **two-call**
pattern: `Native_Get_$prop` returns `bool hasValue`, then `Native_Get_${prop}_value` returns the
value (`CirClassTranslator.kt:385-399`; Kotlin exports at `ClassExports.kt:198-242`). Two-call is
safe for a property getter because it is idempotent by Kotlin convention. **It is not safe for a
method**: a method may have side effects or return a different value on the second invocation, and
the two-call pattern invokes it twice. This is the genuine design decision, and the reason this is
an ADR rather than a light mirror.

### Native-export gating needs widening (verified-in-repo)

`needsListSupport` (`NugetProcessor.kt:471-484`) is computed from `classesHaveLists`
(scans `getAllProperties()` **only**) and `functionsReturnLists` (scans top-level
`functions`/`genericFunctions` returns **only**). Neither scans class-method returns nor extension
functions. So a `List`-returning method/extension would box its handle but the shared
`NugetListNative` helper and its Kotlin exports would **not be emitted**, and the C# materialization
would fail to compile. Same gap for Map/Set. The helpers themselves are shared and reusable; only
the detection predicate must be extended (add class-method-return and extension-return scans).

## Alternatives Considered (nullable-primitive method return)

How Kotlin's own C-ABI interop targets represent a nullable primitive return, for precedent:

- **Kotlin/Native ObjC export** — `Int?` is exported as `KotlinInt *` (a boxed `NSNumber`
  subclass); `nil` = null, otherwise `.int32Value`. Nullability forces a boxed **object pointer**
  because ObjC primitives cannot be nil. *Inferred from docs (not spiked):* kotlinlang
  `native-objc-interop`.
- **Swift export** — same, surfaced as `KotlinInt?`. *Inferred from docs.*
- **JNI** — Java has no nullable primitive; a nullable primitive is `java.lang.Integer`, returned
  as a `jobject` that may be `NULL`. Again **boxing**. *Inferred (well-known JNI behaviour).*

Every precedent boxes. Boxing is one single-call option; an out-parameter is another.

### 1. Out-parameter + `Boolean` has-value return (chosen)

Single call. The export returns `Boolean` (has-value) and writes the unwrapped value through a
new out-parameter pointer, exactly mirroring ADR-024's already-shipped `errorOut` out-write
(`errorOut.reinterpret<COpaquePointerVar>().pointed.value = …`):

```kotlin
@CName("patient_age_in_months")
fun export_patient_age_in_months(
  handle: COpaquePointer,
  valueOut: COpaquePointer?,
  errorOut: COpaquePointer?,
): Boolean = try {
  val result: Int? = handle.asStableRef<Patient>().get().ageInMonths()
  if (result != null && valueOut != null) valueOut.reinterpret<IntVar>().pointed.value = result
  result != null
} catch (e: Throwable) {
  if (errorOut != null) errorOut.reinterpret<COpaquePointerVar>().pointed.value =
    StableRef.create(buildError(e)).asCPointer()
  false
}
```

```csharp
public int? AgeInMonths()
{
    bool hasValue = Native_AgeInMonths(_handle, out int value, out IntPtr error);
    if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);
    return hasValue ? value : null;
}
```

**Pros:** the method is invoked exactly once; no boxing, no `StableRef`, no extra crossing to
unbox, no disposal. Reuses the proven `reinterpret().pointed.value` out-write and the proven
`Boolean` return (the existing `has_value` export already returns `Boolean`). One crossing total.

**Cons:** introduces a value out-parameter shape not previously used forward (only `errorOut` was).
`Boolean?` and `Char?` values are the marshalling-sensitive cases (Kotlin `Boolean`/`Char` width vs
default C# `bool`/`char` out-param marshalling — the same fragility ADR-056 found for these two
primitives); v1 scopes nullable-primitive to the blittable numerics and defers `Boolean?`/`Char?`.

### 2. Box the primitive as a handle (the ObjC/Swift/JNI precedent)

Export returns `COpaquePointer?` boxing the `Int` via `StableRef`; null pointer = null. C# unboxes
via a new per-primitive read export (`nuget_box_int_value(handle)`) then disposes.

**Pros:** exactly the reference precedent; single invocation of the method; uniform with the object
carrier.
**Cons:** a `StableRef` allocation per non-null return, a **second** crossing to read the value out,
and a disposal — for a scalar. Needs new per-primitive `nuget_box_*_value` export wiring. Heavier
than Alternative 1 for no additional expressiveness in this project (we control both sides, so we
are not constrained to ObjC's object-only nil channel).

### 3. Keep the two-call pattern

Rejected: invokes the method twice. Incorrect for any method with side effects or a
non-deterministic result — the whole reason this ADR exists.

### 4. Cache the last result on the Kotlin side

Two-call, but the second call reads a cached value. Rejected: stateful, not thread-safe, and
leaks per-method mutable state into the export layer for a scalar.

## Decision

Route both method-return positions through the **same marshalling cascade the property getter
already uses**, position-by-position, and resolve the nullable-primitive case with the **single-call
out-parameter** (Alternative 1). Concretely:

1. **Object return** (`fun findOwner(): Cat`) — Kotlin export returns `COpaquePointer?`, body
   `StableRef.create(result).asCPointer()`, catch `null` (mirror `ClassExports.kt:747-760`). C#
   `new Cat(handle)` (mirror `CirClassTranslator.kt:431-438`). Public C# type `Cat`.

2. **Nullable object return** (`fun maybeOwner(): Cat?`) — Kotlin export returns `COpaquePointer?`,
   body `result?.let { StableRef.create(it).asCPointer() }`, catch `null` (mirror
   `ClassExports.kt:352-372`). C# `nativeResult == IntPtr.Zero ? null : new Cat(nativeResult)`
   (mirror `:421-429`). Public C# type `Cat?`.

3. **Collection return** (`fun tags(): List<String>`, `fun scores(): List<Int>`) — Kotlin export is
   the **object carrier** (box the list as `StableRef.create(list).asCPointer()`, return
   `COpaquePointer?`). C# materializes with the existing list helper cascade
   (`CirClassTranslator.kt:304-320`): `Count` → loop → `NugetMarshal.FromHandle<T>` → `Dispose` →
   `AsReadOnly()`. Public C# type `IReadOnlyList<T>` (`MutableList<T>` → `IList<T>` per ADR-011).
   **Also widen** `needsListSupport`/`needsMapSupport`/`needsSetSupport`
   (`NugetProcessor.kt:471-484`) to scan class-method and extension-function returns, or the shared
   helper exports are not emitted.

4. **Nullable `String` return** (`fun alias(): String?`) — single call. Kotlin export returns
   `COpaquePointer?` (the string pointer, or null). C# `Marshal.PtrToStringUTF8(nativeResult)`
   (mirror `:375-383`), which yields `null` for `IntPtr.Zero`. Public C# type `string?`. **No
   two-call.**

5. **Nullable primitive return** (`fun ageInMonths(): Int?`) — single call via Alternative 1:
   export returns `Boolean` has-value and writes the value through a `valueOut: COpaquePointer?`
   out-parameter; C# `Native_X(_handle, out int value, out IntPtr error)` → `hasValue ? value :
   null`. Public C# type `int?`. **The method is invoked exactly once.**

Both positions get the same cascade. The existing `errorOut` out-parameter (ADR-024) is present on
every branch, before the new `valueOut` where applicable.

### Claim labelling

- *Verified-in-repo:* every "what is broken" and "reuse" statement above (each cites the read file
  and line range), including that the `List` property Kotlin side is the object carrier, that the
  shared list helpers key off `StableRef<List<*>>`, that nullable-`String` and nullable-object
  property paths are already single-call, that the nullable-primitive property path is the only
  two-call, and that `needsListSupport` does not scan the two method positions.
- *Inferred from docs (not spiked):* ObjC/Swift box `Int?` as `KotlinInt`; JNI boxes to `Integer`.
- *Inferred, load-bearing, NOT spiked:* that `valueOut.reinterpret<IntVar>().pointed.value =
  result` compiles and round-trips against a `[DllImport]` `out int` on the real Kotlin/Native
  mingwX64 toolchain. Strong adjacent evidence: `errorOut.reinterpret<COpaquePointerVar>()
  .pointed.value = …` is the identical mechanism already shipped on every sync export, differing
  only in the `CVariable` type argument (`IntVar`/`LongVar`/`FloatVar`/`DoubleVar`/`ByteVar`/
  `ShortVar` are all standard `kotlinx.cinterop` types with a matching-width `.value`). A full
  Kotlin/Native spike was **not run** (konanc toolchain, platform-specific, high cost). **If this
  claim is wrong, nullable-primitive method returns produce garbage or fail to compile**; the
  fallback is Alternative 2 (box + per-primitive read export). The implementing agent MUST confirm
  it via the walking-skeleton integration test that ADR-055/060 already established as the real ABI
  validator, before relying on it. `Boolean?`/`Char?` are additionally flagged: confirm out-param
  width for `bool`/`char` or keep them deferred (see Scope).

## Consequences

- The two method-return positions gain full parity with property getters for object, collection,
  nullable-object, and nullable-`String` returns — reusing shipped C# materialization verbatim.
- Nullable-primitive method returns are correct under side effects (single invocation), diverging
  deliberately from the property getter's two-call pattern. The two positions now differ in ABI
  shape for nullable primitives (property: two exports; method: one export + value out-param); this
  is intentional and documented.
- `ForwardAbiContract` (ADR-055) sees the new `valueOut` parameter and the `Boolean` return on both
  sides, so the contract check keeps them in lockstep — no change to the check itself.
- **Deferred:** `Boolean?` / `Char?` method returns (out-param width fragility, ADR-056 precedent) —
  defer until width is confirmed. Nullable-primitive **parameters** (ROADMAP line 21) and
  nullable-object/`String` parameters ride the *same* single-call philosophy but are a distinct
  position with their own C# signature concerns (the forward mirror of ADR-053) and their own
  fixtures; keep them a **separate** item, not folded into this ADR — but the `valueOut`/hasValue
  mechanism decided here is the recommended template for the nullable-primitive-parameter case when
  it lands. `Map`/`Set` method returns are covered by the same object-carrier + gating widening as
  `List` and may land together or immediately after.
- **Not changed:** property getters keep ADR-002 two-call (idempotent, shipped); a future
  unification onto the single-call out-param is possible but out of scope.
