# ADR-015: Generic Type Constraint Mapping — C# `where` clauses from Kotlin upper bounds

## Status
Proposed

## Context
Kotlin generics support upper bounds: `class Container<T : Pet>`, `fun <T : Comparable<T>> sort(...)`. These are compile-time constraints on what types can be used as type arguments. The C bridge (Kotlin/Native) has no representation for these — all type parameters are erased to `void*`. However, the C# side has full generic constraints (`where T : IPet`), and omitting them makes the generated API less type-safe and less idiomatic for C# consumers.

Currently, `CirGenericClass.typeParameters` is `List<String>` — just parameter names like `["T"]`. There is no way to carry bound information through the CIR to the renderer.

### How other platforms handle this

- **Java interop**: Kotlin upper bounds become Java bounded type parameters: `<T extends Comparable<T>>`. Preserved at compile time, erased at runtime.
- **ObjC Export**: Lightweight generics with protocol conformance: `id<IPet>`. Only the first bound is used; multiple bounds are not representable.
- **Swift Export**: Maps to `where T: SomeProtocol`, but not fully supported — complex constraints are skipped.
- **JS/Wasm Export**: Constraints are entirely dropped — no type system to represent them.

No existing Kotlin interop target propagates bounds faithfully across a native (C) boundary.

## Alternatives Considered

### 1. Parallel bounds list on CirGenericClass

Add `typeParameterBounds: List<List<String>>` alongside existing `typeParameters: List<String>`.

```kotlin
data class CirGenericClass(
  val typeParameters: List<String>,            // ["T"]
  val typeParameterBounds: List<List<String>>, // [["IPet"]]
  ...
)
```

**Pros:** Minimal change — existing code using `typeParameters` by index still works.
**Cons:** Two lists must be kept in sync. Fragile and non-obvious.

### 2. Structured CirTypeParameter (chosen)

Replace `List<String>` with `List<CirTypeParameter>` where `CirTypeParameter(name, bounds)`.

```kotlin
data class CirTypeParameter(
  val name: String,
  val bounds: List<String> = emptyList(),
)
```

```kotlin
data class CirGenericClass(
  val typeParameters: List<CirTypeParameter>,
  ...
)
```

**Pros:** Self-contained — bounds travel with their parameter name. Extensible (variance can be added later). Aligns with how KSP models `KSTypeParameter`.
**Cons:** Changes the shape of `CirGenericClass` — requires updating all construction and rendering sites.

### 3. Emit no constraints (defer entirely)

Leave `CirGenericClass.typeParameters` as `List<String>`, generate unconstrained `Box<T>` for all generic classes.

**Pros:** Zero implementation effort.
**Cons:** C# consumers can instantiate `PetBox<int>` which will crash at runtime. Loses type safety that Kotlin guarantees at compile time. This is the current state.

## Decision
Use **Option 2: structured `CirTypeParameter`** for the CIR model.

The bridge layer (Kotlin CName exports) requires **no changes** — constraints are compile-time only on the C# side. `NugetMarshal` requires no changes. The C# `where T : IPet` clause is a renderer-only concern.

### Bound mapping rules

| Kotlin bound                                      | C# constraint                                   |
|---------------------------------------------------|-------------------------------------------------|
| `kotlin.Any`                                      | `class`                                         |
| Exported interface `Pet`                          | `IPet` (I-prefix per existing interface naming) |
| Exported class `Animal`                           | `Animal`                                        |
| `kotlin.Comparable<T>` and other stdlib bounds     | dropped (see 2026-10-03 amendment)              |
| Unrecognized / external                           | not shipped; see the 2026-10-03 amendment       |
| Unconstrained (no bounds or only implicit `Any?`) | no `where` clause                               |

### CIR changes
- New `CirTypeParameter(name: String, bounds: List<String>)` in `CirModel.kt`
- `CirGenericClass.typeParameters: List<CirTypeParameter>` (was `List<String>`)
- Renderer emits `where T : IBound1, IBound2` after the class declaration line

### Translator changes (KSP)
- `translateGenericClass` reads `cls.typeParameters`, maps each param's `bounds` sequence to C# constraint strings via the bound-mapping table above
- Generic function translation does the same for function type parameters via `CirMethod`

### Renderer changes
- `renderGenericClass` emits `where` clause when any type parameter has non-empty bounds
- `renderMethod` emits `where` clause on generic method signatures

### Example

Kotlin source:
```kotlin
class PetBox<T : Pet>(val value: T)
```

Generated C#:
```csharp
public class PetBox<T> : IDisposable where T : IPet
{
    internal IntPtr _handle;

    public T Value => NugetMarshal.FromHandle<T>(PetBoxNative.Get_value(_handle));

    public void Dispose() { ... }
}
```

Bridge (unchanged from unconstrained generic):
```kotlin
@CName("petbox_get_value")
fun export_petbox_get_value(handle: COpaquePointer): COpaquePointer { ... }
```

## Consequences

- `CirGenericClass` shape changes — all construction sites need updating
- `CirRenderer.renderGenericClass` emits `where` clause when type parameters have bounds
- `CirRenderer.renderMethod` emits `where` clause for generic functions with bounds
- C# consumers get compile-time enforcement of Kotlin's type constraints
- The bridge layer is unchanged — no new CName functions generated
- `NugetMarshal` unchanged — runtime dispatch logic is type-parameter-agnostic
- Extensible: variance (`in`/`out`) can be added to `CirTypeParameter` later

## Amendment (2026-10-03): stdlib bounds are dropped

The table's `Comparable<T>` to `IComparable<T>` row and its "simple name with warning" row never
shipped and are superseded. A bound declared in a Kotlin builtin package (`Comparable<T>`,
`Number`, `CharSequence`, `Enum<T>`) used to produce C# and Kotlin that did not compile: the
`where` clause named `global::...Kotlin.IComparable` or a bare `Number` (CS0234, CS0246, CS0701),
and the generic function and class routes spelled `kotlin.Comparable` with no type arguments.

Rule: a builtin bound is dropped from the C# `where` clause, keeping `notnull` when the bound is
non-null (`class Ranked<T : Comparable<T>>` renders `where T : notnull`), and each dropped bound
is reported as `INFO_DROPPED_BOUND`; the declaration still binds. Mapping to `IComparable<T>` was
rejected because no generated wrapper implements it, so every wrapper type argument would fail
with CS0311 / CS0315 (spike). One helper, `cirBoundConstraint` (`cir/CirTypeMapping.kt`), serves the
generic class and generic function spellers, and checks the builtin package before the
interface/class split so `Comparable` and `Number` take one arm. The Kotlin half spells a bound's
type arguments as `Any?` (`kotlin.Comparable<Any?>`, valid because `Comparable` is contravariant)
in `forwardKotlinBoundSpelling`. The function route's two halves read one gate,
`legacyGenericHasNonTrivialBound`, for whether primitive variants exist. The same helper writes the
interface check as `declaration != null && declaration.classKind == ClassKind.INTERFACE`, which
removes the compiler "Condition is always 'true'" warning in `cirTypeParameters` and its twin in
`CirFunctionTranslator.kt`. `legacyBoundClassCsName`'s builtin fallback served only builtin classes
and is deleted.

Consequence for callers: with the bound gone, C# can pass a type argument Kotlin would reject.
When this paragraph was written the Kotlin read did not check it (the 2026-10-04 builtin
amendment below makes it fail at the call).

Evidence. Verified: `Tier1BuiltinGenericBoundTest` six cells red before and green after;
`:nuget-processor:test` 1568 passed, 0 failed; no "Condition is always" warning in the build log;
full `scripts/verify.sh` green (Contract 3, Integration 3010, Leak 158, MultiPackage 9,
SharedException 2, all six NativeAOT shapes); `Ranked<int>`, `Ranked<string>`, `Tally<int>`,
`Tally<double>` and `Favourite<Treat>` run. Not covered on purpose: the star-projected and nested
generic bound-argument arms of `forwardKotlinBoundSpelling`. No LeakTests row.

Known limits, verified and tracked in ROADMAP: `T : Enum<T>` has no valid Kotlin type argument, so
its generated Kotlin does not compile on both generic routes. The multi-bound limit this amendment
first listed is fixed by the 2026-10-04 multi-bound amendment. The builtin-bounded generic function
limit it also listed (`Treats.Weigh<int>(4)` threw `NotSupportedException`) is fixed by the
2026-10-04 builtin amendment.

## Amendment (2026-10-04): a multi-bound type parameter

A type parameter with several upper bounds (`where T : Comparable<T>, T : Pet`) generated Kotlin
that did not compile on both generic routes: the owner and every `T` value were spelled by the
first bound only (`Kennel<kotlin.Comparable<Any?>>`), which a `Pet` instance does not satisfy. The
C# half was already correct and is unchanged: every exportable bound is listed in the `where`
clause and builtin bounds are dropped as above. Kotlin has no type for the intersection of the
bounds, so the generated Kotlin never names one.

Rule, on the generic class route and the generic function route. A `T` value read from a handle is
read as the first bound (as `Any`, then cast to each bound, per the 2026-10-04 builtin amendment),
then cast to each remaining bound (`.let { bounded -> bounded as Trainable; bounded }`), which
Kotlin smart-casts to the whole intersection; a nullable bound keeps its `?` through the cast. A read of an existing instance uses
`Owner<*>`. A member that takes a `T` gets its receiver from a local generic function that
restates the owner's bounds (`fun <T> nugetTypedOwner(owner: Any, witness0: T?): Owner<T> where T :
Pet, T : Trainable`), with the argument value as the witness, so Kotlin infers `T` as the
intersection. A constructor is spelled `Owner<_>`, or `Owner<Nothing>` when no constructor
parameter mentions `T`. A bound that names `T` itself (`T : Rival<T>` with `Rival<in T>`) is
spelled through its erased form (`Rival<Any?>`), which is valid because the argument is
contravariant. Single-bound parameters kept their spelling here, and the builtin amendment below
changes it to a checked cast.

Consequence for callers: as with a dropped `Comparable` bound, C# can pass a type argument that
satisfies the listed bounds but not a dropped one, which fails at the call (verified for the
erased class by the 2026-10-04 builtin amendment, which also casts every bound, not only the
remaining ones, so the first bound is checked too).

Separate fix in the same change. A generic data class's exported `equals`, `hashCode` and
`toString` wrote `asStableRef<pkg.Box>()` with no type argument, which is not a type for a generic
class, so the generated Kotlin did not compile for any generic data class. They now read the
owner through `forwardOwnerTypeName` like every other member (`Box<Any?>`, `Pen<Pet>`). No
fixture has a generic data class; this is covered at Tier 1 only (`Tier1GenericDataClassTest`).

Evidence. Verified: `Tier1MultiBoundGenericTest` covers two interface bounds, `Comparable<T>` plus
an interface, nullable bounds, contravariant self-referencing bounds and a second type parameter,
each compiling the generated Kotlin on both routes; `Tier1BuiltinGenericBoundTest` still green;
`:nuget-processor:test` 1642 passed, 0 failed; native pipeline Integration 3042, Leak 164, seven
NativeAOT shapes. `MultiBoundGenericTests` runs `Arena`, `Podium` (`Comparable<T>` plus `Pet`) and
`Hamper` (nullable bounds) and the `Rehearsals.Rehearse` and `Headline` functions against the
native library, and pins the C# `where` clauses by reflection. No LeakTests row: the handle
crossings are the ones `GenericClassMethod_ExportedClassTypeParameter_ReturnsToBaseline` and
`GenericCtorNullableArg_NullArgument_ReturnsToBaseline` already cover. Inferred when written, since
verified for the erased class: that a `T` argument missing a dropped `Comparable` bound fails at
the call (see the 2026-10-04 builtin amendment).

Known limits, verified and fixed by a later item together with `T : Enum<T>`: (1) an invariant
self-referencing bound such as `T : Node<T>` still fails, because `Node<Any?>` is not a
`Node<Node<Any?>>`; it is the same problem as `T : Enum<T>` and is pinned by a known-limit cell in
`Tier1MultiBoundGenericTest`. (2) A generic interface bound renders in the C# `where` clause
without its type arguments (`where T : IRival` for the declared `IRival<in T>`, CS0305), single
bound or several; the spelling is in `cirBoundConstraint` and `legacyBoundInterfaceCsName`
(`cir/CirTypeMapping.kt`), verified on the multi-bound shape (the Tier 1 cell asserts the
Kotlin half only and pins the C# spelling, so a fix shows up as that cell going red).

## Amendment (2026-10-04): builtins as `T` on a generic function, and checked bound reads

A builtin (`int`, `string`, `short`, ...) works as `T` on a generic function, as it already did on a
generic class. `Treats.Weigh<int>(4)` (`fun <T : Number> weigh(value: T): T`) compiled and threw
`NotSupportedException`: the function route's object variant wrote the argument through
`NugetMarshal.Wrap<T>`, which boxes a builtin, but read the result through `Materialize<T>`, which
knows only the generated factories. The same defect hit an unconstrained function for a builtin
with no C# width variant (`Identity<short>`; only `string`, `int`, `long`, `float`, `double` and
`bool` have one).

Rule 1, the read. The object variant returns `NugetMarshal.FromHandle<T>(result)`, the class
route's reader: it unwraps and disposes a builtin box, then falls to the factory registry, and
answers the null pointer with `default`. A wrapper as `T` still works, and the clause stays
`where T : notnull` with `INFO_DROPPED_BOUND` unchanged. Which builtins work then follows the
Kotlin bound: `T : Number` takes `sbyte`, `short`, `int`, `long`, `float`, `double`;
`T : CharSequence` takes `string`; an unconstrained `T` takes every builtin, nullable included
(`Identity<short?>`). Re-enabling the width variants for a builtin bound was rejected: the
`_string` variant would emit `weigh(value: String)`, which does not satisfy `T : Number`.

Rule 2, the bound check. Every bounded-`T` read used `asStableRef<Bound>().get()`, which is an
unchecked generic cast in Kotlin/Native, so a `T` outside a bound C# cannot see (`Weigh<uint>`,
`Weigh<string>`) reached the body: an identity function handed it back, and a body calling
`toDouble()` dispatched on an object that is not a `Number`. Reads now go
`asStableRef<Any>().get() as Bound` (`as Bound?` for a nullable `T`), a checked cast, inside the
export's `try`. A wrong `T` fails there, before the body runs, as `ClassCastException`, which C#
sees as `Kotlin.Native.Interop.KotlinInvalidCastException`; the boxed argument is still released
by the call's `finally`. The rule applies on both routes: the function route
(`exports/GenericFunctionExports.kt`), and the class and plan route through `forwardBoundedRead`
(`forward/ForwardKotlinPlanEmitter.kt`) for constructor and member arguments, `copy` arguments and
the typed-receiver witness; the first bound of a multi-bound `T`; and the value-class unbox export
(ADR-171). A generic bound (`Comparable<Any?>`) checks its erased class (`Comparable`) and prints
an unchecked-cast compiler warning in the generated `CNameExports.kt`; it is a warning only.
Unconstrained `T` keeps the plain `Any` read. This is a behaviour change on the class route: a
wrong `T` was an unchecked pass-through there (`new Tally<uint>(3u)` now throws at construction).

This makes true two sentences that were not. The 2026-10-03 amendment's "a C# caller can pass a
type argument Kotlin would reject, which fails at the call" described an unchecked read when it was
written; it holds now on both routes. The 2026-10-04 multi-bound amendment's "a C# argument missing
a dropped `Comparable` bound fails at the call", labelled inferred there, is verified for the
erased class (`Comparable`).

Evidence. Verified: `Tier1BuiltinGenericBoundTest` (the function body reads through
`FromHandle<T>`, the reads are checked casts), `Tier1MultiBoundGenericTest`,
`Tier1GenericNullableTypeArgumentTest`, `Tier1ReflectionFreeDispatchTest` and
`Tier1WrapValueClassTest` updated to the checked spelling; `:nuget-processor:test` 1651 passed,
0 failed; native pipeline Integration 3057, Leak 167, seven NativeAOT shapes.
`BuiltinGenericBoundTests` runs `Weigh<int>`, `Weigh<double>`, `Favourite<string>`,
`Nickname<string>` and `Favourite<Treat>`, and pins the rejections
`Weigh_UInt_IsRejectedByKotlinAtTheRead`, `Weigh_String_IsRejectedByKotlinAtTheRead`,
`Portion_UInt_IsRejectedBeforeTheBodyDispatches` and `Tally_UInt_IsRejectedAtConstruction`;
`GenericFunctionTests` runs `Identity<short>` and `Identity<short?>`. LeakTests rows
`BuiltinGenericFunction_BoxedPrimitiveAndString_ReturnsToBaseline` and
`BuiltinGenericFunction_BoundCastFails_ReturnsToBaseline` cover the two boxes per call and the
failure path. Inferred, not run: that the other unsigned builtins (`byte`, `ushort`, `ulong`) are
rejected by `T : Number` as `uint` is, since Kotlin's unsigned types are not `Number`; only `uint`
and `string` are pinned.
