# ADR-105: Sealed types at property positions read through the generated discriminator

## Status
Accepted

## Context

[ROADMAP.md](../../ROADMAP.md) Phase 3 (line 33) bundles three slots under one item: a sealed
type as a property (`val shape: Shape?`, [#54](https://github.com/xxfast/kotlin-native-nuget/issues/54)),
a sealed collection component in a property (`List<Shape>`, the follow-on to
[#52](https://github.com/xxfast/kotlin-native-nuget/issues/52), which now skips instead of
crashing), and a sealed collection component in a return or parameter (`fun x(): List<Shape>`).
The line claims "same handle in a different slot, so one feature closes all three". This ADR
treats that as a design claim to verify, and it turns out to be half right: the **handle** is
the same at every slot, but the **C# reconstruction** is not, and the third slot is not on the
same route as the first two at all.

Today, `ForwardBridgeTypeClassifier.kt:123` classifies every sealed class as
`BridgeType.SpecializedProtocol("sealed helper <fqn>")`. `ForwardPropertyPlanner.isPlannable`
(~`:437`) has no arm for a specialized protocol, so the property is dropped and
`NugetProcessor.kt:178` emits the `SKIPPED_UNSUPPORTED_PROPERTY` the issue quotes. The sealed
type itself exports fine under [ADR-009](009-sealed-class-mapping.md): `public abstract class
Shape` with an `internal Shape(IntPtr)` constructor, nested `sealed` subclasses, and an
`internal static Shape FromHandle(IntPtr)` discriminator that calls the Kotlin
`<name>_get_type` export. Issue #40 additionally registered the sealed base in
`NugetMarshal.Factories` via `CirFactoryEntry.viaFromHandle` (`CirTranslator.kt:575`), so
`NugetMarshal.FromHandle<Shape>(h)` already dispatches to `Shape.FromHandle(h)`.

The forward planner already does an inline `sealed helper` → `ObjectHandle` rewrite for a
value class whose underlying is sealed (`ForwardCallablePlanner.kt:~450-457`), with the comment
that sealed crosses as a `StableRef` identically to any object handle. The open question was
purely the C# read side of a *property*.

## Spike (verified)

A throwaway fixture was added to `test-library` (reverted afterwards; not in the tree):

```kotlin
sealed class Shape {
  data object Empty : Shape()
  data class Circle(val radius: Double) : Shape()
}
data class Drawing(val shape: Shape, val maybe: Shape?, val shapes: List<Shape>)
```

with one experimental change: immediately after `classifier.classify(prop.type.resolve())` at
`ForwardPropertyPlanner.kt:222`, `SpecializedProtocol("sealed helper X")` was rewritten to
`BridgeType.ObjectHandle(X, "global::<ns>.<Simple>")`, recursing through `Nullable` and the
`Collection` components. Nothing else changed. `./gradlew :test-library:packNuget` exited 0
(KSP + konanc mingwX64), and the generated `Interop.cs` contained, verbatim:

```csharp
public global::TestLibrary.Issue54.Shape Shape
{
    get
    {            IntPtr nativeResult = Native_Get_shape(_handle, out IntPtr error);
    if (error != IntPtr.Zero) { throw NugetErrorNative.BuildException(error); }
    return new global::TestLibrary.Issue54.Shape(nativeResult);          // <-- CS0144
    }
}

public global::TestLibrary.Issue54.Shape? Maybe
{
    get
    {            IntPtr nativeResult = Native_Get_maybe(_handle, out IntPtr error);
    if (error != IntPtr.Zero) { throw NugetErrorNative.BuildException(error); }
    return nativeResult == IntPtr.Zero ? null : new global::TestLibrary.Issue54.Shape(nativeResult); // <-- CS0144
    }
}

public IReadOnlyList<global::TestLibrary.Issue54.Shape> Shapes
{
    get
    {            IntPtr nativeResult = Native_Get_shapes(_handle, out IntPtr error);
    if (error != IntPtr.Zero) { throw NugetErrorNative.BuildException(error); }
    int count = NugetListNative.Count(nativeResult);
    var result = new List<global::TestLibrary.Issue54.Shape>(count);
    for (int i = 0; i < count; i++)
    {
        result.Add(NugetMarshal.FromHandle<global::TestLibrary.Issue54.Shape>(NugetListNative.Get(nativeResult, i)));
    }
    NugetListNative.Dispose(nativeResult);
    return result.AsReadOnly();
    }
}
```

and the sealed base itself, generated as before by the ADR-009 renderer:

```csharp
public abstract class Shape : IDisposable, INugetHandle
{
    internal IntPtr _handle;
    internal Shape(IntPtr handle) { _handle = handle; }
    ...
    internal static Shape FromHandle(IntPtr handle)
    {
        return Native_GetType(handle) switch
        {
            0 => new Circle(handle),
            1 => new Empty(handle),
            _ => throw new InvalidOperationException("Unknown sealed class type")
        };
    }
}
```

The Kotlin side (generated `CNameExports.kt`, verbatim):

```kotlin
@CName("drawing_get_shape")
public fun export_drawing_get_shape(handle: COpaquePointer, errorOut: COpaquePointer?): COpaquePointer? = try {
  StableRef.create(handle.asStableRef<...Drawing>().get().shape).asCPointer()
} catch (e: Throwable) { ... null }

@CName("drawing_get_maybe")
public fun export_drawing_get_maybe(handle: COpaquePointer, errorOut: COpaquePointer?): COpaquePointer? = try {
  val result = handle.asStableRef<...Drawing>().get().maybe
  if (result == null) null else StableRef.create(result).asCPointer()
} catch (e: Throwable) { ... null }

@CName("shape_get_type")
public fun export_shape_get_type(handle: COpaquePointer): Int {
  val obj: ...Shape = handle.asStableRef<...Shape>().get()
  return when (obj) {
      is ...Shape.Circle -> 0
      is ...Shape.Empty -> 1
  }
}
```

A scratch net8.0 project reproducing the generated shape (`abstract class Shape` with
`internal Shape(IntPtr)` and `new Shape(h)` at a call site) fails with
`error CS0144: Cannot create an instance of the abstract type or interface 'Shape'`
(**verified**, `dotnet build` output). The full `GeneratedBindingsCheck` build against the
spiked package (caches purged exactly as `scripts/verify.sh` does) then failed with **exactly
two** errors and nothing else (**verified**, verbatim):

```
Interop.cs(14648,20): error CS0144: Cannot create an instance of the abstract type or interface 'Shape'
Interop.cs(14663,57): error CS0144: Cannot create an instance of the abstract type or interface 'Shape'
```

Line 14648 is the bare `Shape` getter, 14663 the `Shape?` getter. The `List<Shape>` getter
compiled, which is the direct proof that the collection-component slot needs no C# change.

What the spike establishes:

1. **Verified**: the Kotlin side needs no change. The bare getter is one `StableRef.create`;
   the nullable getter ships a null pointer for `null` and otherwise one `StableRef`, i.e. null
   crosses in-band on the pointer, and the getter reads its export **once**. The ROADMAP line 29
   double-call leak lives in `CirClassTranslator.kt:~1118` (the sealed-*subclass* member
   renderer), a different renderer from the property plan path; it is not on this route.
2. **Verified**: `shape_get_type` reads the handle as `asStableRef<Shape>()`, so a `StableRef`
   created from a base-typed `val shape: Shape` is exactly what the discriminator expects.
3. **Verified**: the *only* thing wrong is the C# reconstruction at
   `ForwardCirPropertyProjection.kt:227` (nullable) and `:258` (bare), which spell
   `new <csharpType>(nativeResult)` for every `ObjectHandle`. On an abstract base that is
   CS0144.
4. **Verified**: the collection-component slot (`List<Shape>` property) needs **no projection
   change**. `ForwardCirCollectionComponents.kt:126` already reads every component through
   `NugetMarshal.FromHandle<T>`, and `Materialize<Shape>` resolves through the issue-#40
   `viaFromHandle` factory entry. The only reason it skips today is the `isReadableComponent`
   gate (`ForwardPropertyPlanner.kt:~490`), which returns `false` for `SpecializedProtocol`.
5. **Verified**: with the mapping in place the `packNuget` log carried no `issue54` /
   `Drawing` diagnostic at all: `SKIPPED_UNSUPPORTED_PROPERTY` simply stops firing for the
   property, which is correct, not a silenced warning.

## Alternatives Considered

### 1. Property planner maps `sealed helper` to an `ObjectHandle` flagged as a sealed base; the projection renders that flag through `<Base>.FromHandle(...)` (chosen)

The classifier keeps minting `SpecializedProtocol("sealed helper <fqn>")` for every position
(so no other route moves), but the protocol now carries the handle it *would* be:
`BridgeType.SpecializedProtocol(name, sealedHandle: ObjectHandle? = null)`, minted at
`ForwardBridgeTypeClassifier.kt:123` as
`ObjectHandle(qualifiedName, csharpTypeNameFor(cls), viaDiscriminator = true)`. The property
planner unwraps it (`protocol.sealedHandle ?: type`, recursing through `Nullable` and the
`Collection` components) right after `classify` at `:222`. The projection's two `ObjectHandle`
getter arms test the flag:

```csharp
return global::TestLibrary.Issue54.Shape.FromHandle(nativeResult);                                    // bare
return nativeResult == IntPtr.Zero ? null : global::TestLibrary.Issue54.Shape.FromHandle(nativeResult); // nullable
```

This is the spelling the shipped top-level sealed return already uses
(`return Issue39State.FromHandle(nativeResult);` in the generated `Interop.cs`, **verified**),
so a consumer sees one idiom for a sealed value whether it came from a function or a property.
`FromHandle` is `internal static` in the same generated assembly, which is exactly how
`NugetMarshal.Factories` already calls it.

- Pros: rides the existing `ObjectHandle` arms in every exhaustive `when` (wire type `IntPtr`,
  `HANDLE_TO_STABLE_REF` / `STABLE_REF_TO_HANDLE` conversions, setter spelling
  `value._handle`); zero Kotlin change; the flag is also what the callable planner's value-class
  site at `:~450` should eventually consume instead of its ad-hoc string rewrite.
- Cons: one more field on two `BridgeType` data classes, so `SpecializedProtocol`'s data-class
  `toString()` (which `diagnosticTypeName()` may surface in messages) changes shape; the one unit
  test that asserts on the `sealed helper ...` spelling (`Tier1SealedCollectionPropertyTest.kt:42`,
  a string assertion on the diagnostic, not a value comparison) flips anyway under (c), see
  Consequences.

### 2. Render every `ObjectHandle` getter through `NugetMarshal.FromHandle<T>(h)` (rejected)

No flag needed: `FromHandle<T>` already dispatches sealed bases to the discriminator. But it
turns every plain-class property getter into a `Dictionary<Type, Func<IntPtr, object>>` lookup
plus a boxing round trip, and it moves the failure mode for an abstract *non-sealed* class from
a compile-time CS0144 to a runtime `NotSupportedException` from `Materialize<T>`. The collection
path pays that cost only because it is generic over `T`; a property getter knows its type
statically.

### 3. A new `BridgeType.SealedHandle` variant (rejected)

Cleaner in name, but every exhaustive `when` across the forward planners, emitters and
projections (`isPlannable`, `isReadableComponent`, `isBridgeableComponent`,
`isWrappableComponent`, `wireType`, `csharpType`, `conversion`, the Kotlin emitter's dozen
arms) would need a new arm that duplicates the `ObjectHandle` one verbatim. The flag on
`ObjectHandle` gives the same information with the same arms.

### 4. Change the classifier so sealed classifies as `ObjectHandle` at every position (rejected)

Scope option (a) below. This flips `skipReason` for the existing top-level sealed returns
(the #38/#39/#50 fixtures) from `SEALED_PROTOCOL` (a `droppedFromCSharp = false` legacy-route
deferral) to a *planned* `ObjectHandle` return, which `ForwardCirPlanProjection.kt:812` renders
as `new State(nativeResult)` (CS0144 again) **and** which `exports/FunctionExports.kt:26` still
emits on its legacy route (double emission of the same `@CName`). It also silently pulls ROADMAP
line 32 (class methods returning sealed) into the change with no diagnostic design. **Inferred
from reading**, not built: the spike deliberately stayed off the classifier.

## Decision

Adopt alternative 1 at scope **(c)**: bare sealed property, nullable sealed property, and sealed
collection components in a property. Concretely:

| File | Change |
|---|---|
| `forward/ForwardMarshallingModel.kt` | `ObjectHandle` gains `val viaDiscriminator: Boolean = false`; `SpecializedProtocol` gains `val sealedHandle: ObjectHandle? = null`. |
| `forward/ForwardBridgeTypeClassifier.kt:123` | Mint the sealed protocol with `sealedHandle = ObjectHandle(qualifiedName, csharpTypeNameFor(classDeclaration), viaDiscriminator = true)`. Position-agnostic, as the classifier must stay. |
| `forward/ForwardPropertyPlanner.kt:222` | `val type = classifier.classify(...).sealedAsHandle()` where `sealedAsHandle` unwraps `sealedHandle` and recurses through `Nullable` and `Collection.element/key/value`. `isReadableComponent` then admits the mapped `ObjectHandle` with **no predicate change** (verified rendering above). |
| `forward/ForwardCirPropertyProjection.kt:227,258` | When `viaDiscriminator`, spell `${csharpType}.FromHandle(nativeResult)` instead of `new ${csharpType}(nativeResult)`. |
| `forward/ForwardCallablePlanner.kt:2542` (`isWrappableComponent`) | `is BridgeType.ObjectHandle -> !viaDiscriminator`. See "Collection write side" below. |

Five production files. The Kotlin emitter (`ForwardPropertyKotlinEmitter.kt`) and the collection
component reader are untouched (**verified**: their output above is already correct).

**Collection write side (decided: gated, not admitted).** The rewrite at `:222` runs before
*both* property gates. The read gate is what the spike verified. But the property planner's
collection *setter* gate (`ForwardPropertyPlanner.kt:322-330`) delegates to the shared
`isWrappableComponent`, which today admits every `ObjectHandle`; without the extra line above, a
`var shapes: MutableList<Shape>` (or `MutableMap`/`MutableSet`) property would plan **with a
setter** and C# would box sealed-base handles into a Kotlin collection through the ADR-073 write
path, which no fixture has ever rendered or run for an abstract C# base (the spike fixture was a
read-only `val shapes: List<Shape>`). Gating on `viaDiscriminator` makes such a property plan
get-only, and the *existing* ADR-075 `SKIPPED_UNSUPPORTED_INPUT` diagnostic ("the C# property
Shapes is read-only", `NugetProcessor.kt:~141`) names it: zero new diagnostic design, one
predicate line, and the write side stays with scope (d) where it belongs. **Inferred**: that the
diagnostic fires with that wording for this case follows from reading the setter gate, not from a
build. Placing the gate in the shared predicate rather than in the property planner's copy also
protects (d)'s parameter-position boxing from being enabled by accident later.

A *scalar* `var shape: Shape` setter is **not** gated: it rides the ordinary `ObjectHandle` setter
(`value._handle` in C#, `value.asStableRef<Shape>().get()` in Kotlin), the same wire as any
handle-typed setter. Inferred, see the claims list below; the implementing fixture must carry a
`var` to promote it.

Load-bearing claims and their status:

- **Verified**: the generated Kotlin getters and the discriminator export are compatible
  (`StableRef.create(shape)` read by `asStableRef<Shape>()`).
- **Verified**: `Shape.FromHandle(IntPtr)` exists, is `internal static`, and is reachable from a
  property getter in the same namespace/assembly (the `Issue39State.FromHandle(nativeResult)`
  precedent is already in the generated file).
- **Verified**: `List<Shape>` property renders through `NugetMarshal.FromHandle<Shape>` and the
  `Factories` table has a `viaFromHandle` entry for every sealed base.
- **Inferred** (not spiked): a `var shape: Shape` *setter*. The projection's `ObjectHandle`
  setter spelling is `value._handle` (`ForwardCirPropertyProjection.kt:520`) and the Kotlin side
  is `value.asStableRef<Shape>().get()` (`ForwardPropertyKotlinEmitter.kt:367`); both exist on
  the base class and a subclass instance behind a `StableRef<Shape>` is a valid `Shape`. The
  implementing fixture must include a `var` to promote this.
- **Inferred**: `csharpTypeNameFor` uses `simpleName` only, so a *nested* sealed base
  (`class Outer { sealed class Inner }`) would spell `global::NS.Inner`. Pre-existing for every
  `ObjectHandle`; not chased here.
- **Inferred**: a sealed-base *subclass* typed property (`val c: Shape.Circle`) is already an
  ordinary `ObjectHandle` (a `data class` is not sealed) and is unaffected, but its
  `csharpTypeNameFor` spelling has the same nested-name caveat.

**Correction (2026-09-05):** the first bullet above is unreachable as stated. A sealed *base*
nested inside another class (`Outer.Inner`) never reaches `csharpTypeNameFor` at an `ObjectHandle`
position at all: an ordinary nested class, object, or interface is never declared and the
classifier's exported-handle gate skips it named before any spelling code runs. The real,
reachable case was the second bullet's shape: a sealed **subclass** nested inside its own sealed
base (`Shape.Circle`), which *is* declared under ADR-009 and *is* referenced at a member position.
That was the actual gap, fixed by a shared `nestedCsName()` helper in `CirTypeMapping.kt` that both
`ForwardBridgeTypeClassifier.csharpTypeNameFor` and `CirTypeMapping.qualifiedElementCsType` now call,
walking `parentDeclaration` and joining enclosing simple names outermost-first. See
[Interfaces, abstract classes, and sealed classes](../topics/interfaces-abstract-sealed.md#a-nested-sealed-subclass-at-a-member-position).

### Consumer API

```csharp
using var drawing = Drawing();                 // fun drawing(): Drawing
Shape shape = drawing.Shape;                   // val shape: Shape
Shape? maybe = drawing.Maybe;                  // val maybe: Shape?
IReadOnlyList<Shape> shapes = drawing.Shapes;  // val shapes: List<Shape>

string describe = shape switch
{
    Shape.Circle c => $"circle r={c.Radius}",
    Shape.Empty => "empty",
    _ => throw new InvalidOperationException(),
};
```

Sample xunit shape for the fixture:

```csharp
[Fact]
public void SealedPropertyDiscriminatesToTheRightSubclass()
{
    using var drawing = Issue54.Drawing();
    Assert.IsType<Shape.Circle>(drawing.Shape);
    Assert.Equal(2.0, ((Shape.Circle)drawing.Shape).Radius);
    Assert.Null(drawing.Maybe);
    Assert.Collection(drawing.Shapes,
        s => Assert.IsType<Shape.Empty>(s),
        s => Assert.Equal(1.0, Assert.IsType<Shape.Circle>(s).Radius));
}
```

### Diagnostics

- `SKIPPED_UNSUPPORTED_PROPERTY` (`NugetProcessor.kt:178`) stops firing for a sealed-typed
  property and for a collection property whose component is sealed, because `recordDropped` is
  never reached. **Verified**: the spiked `packNuget` log has no `issue54` diagnostic. Nothing
  becomes a false alarm; nothing goes silent that used to bind.
- `ForwardPlanSkipReason.SEALED_PROTOCOL` (`droppedFromCSharp = false`) is produced only by the
  callable planner's `skipReason` (`ForwardCallablePlanner.kt:2288`), i.e. return/parameter
  positions. Untouched by this ADR. It is the reason behind ROADMAP line 32 (class method
  returning sealed: legacy-deferred, nothing re-emits it, no diagnostic), which stays adjacent.
- `scripts/verify-forward-diagnostics.sh` has no sealed expectation (grep is empty), so nothing
  in the delivery check changes.
- `Tier1SealedCollectionPropertyTest` (issue #52) currently pins that `List<Shape>` /
  `Map<String, Filter>` properties *skip named*. Under (c) they bind, so that test flips from an
  absence assertion into a presence assertion (property planned, `FromHandle<Shape>` read).

### Scope options priced

| Option | Files | What binds | Notes |
|---|---|---|---|
| (a) classifier-level, every position | 1 classifier line + at least `ForwardCirPlanProjection.kt:812/896` + `FunctionExports.kt` de-dup + a diagnostic design for line 32 | everything | Rejected: double-emits top-level sealed returns and drags line 32 in (inferred from reading). |
| (b) property planner only, bare + nullable | 4 (model, classifier mint, planner rewrite, projection arms) | `Shape`, `Shape?` | Narrowest for #54. The only difference from (c) is *not* recursing into `Collection` in `sealedAsHandle`. |
| **(c) (b) + collection components in a property** | **5: (b) + the one-line `isWrappableComponent` gate** | + read-only `List<Shape>`, `Map<K, Shape>`, `Set<Shape>`, nullable components; a *mutable* collection plans get-only with the ADR-075 read-only diagnostic | The **read** side is genuinely free on top of (b): the recursion is three lines, `isReadableComponent` admits the mapped handle with no text change, and the reader is verified. The fifth file exists only to keep the unverified **write** side closed. |
| (d) (c) + collection components at return/parameter | + `ForwardCallablePlanner.kt` (`isBridgeableComponent` `:2492`, `isWrappableComponent` `:2542`, the `skipReason` element recursion `:2262/2391`, and the rewrite at every callable `classify` site) | + `fun x(): List<Shape>`, `fun f(shapes: List<Shape>)` | Deferred. Different route, its own gates, and the parameter side must *write* a sealed handle into a Kotlin list (the ADR-073 box path), which has never been exercised for an abstract C# base. |

Recommendation: ship **(c)**. It closes #54 and the #52 follow-on for the same four files (b)
needs; (d) is a separate feature.

## Consequences

- A sealed-typed property (`Shape`, `Shape?`) and a collection property with a sealed component
  bind in C#, discriminated by the ADR-009 `FromHandle`. The Kotlin ABI is unchanged; no new
  exports, no `contractHash` input change.
- `docs/topics/bridgeable-subset.md` must state what still skips at property positions after
  this ships (issue #54 asks for this explicitly): sealed collection components at **return and
  parameter** positions, and a value class whose underlying is sealed at a property position
  (`isPlannable`'s `ValueClass` arm admits only String/Primitive/Enum/ObjectHandle underlyings;
  the callable planner's inline rewrite at `:~450` does not run for properties). Both remain
  skipped and both are named by the existing diagnostics (the value-class case through
  `SKIPPED_UNSUPPORTED_PROPERTY`). A `var` mutable-collection property with a sealed component
  (`var shapes: MutableList<Shape>`) binds get-only and is named by the ADR-075
  `SKIPPED_UNSUPPORTED_INPUT` read-only diagnostic (inferred, see Decision).
- Deferred, with a correction to the ROADMAP line: `fun x(): List<Shape>` does **not** "skip
  named" today. Its `skipReason` recurses to the element (`ForwardCallablePlanner.kt:2262`) and
  lands on `SEALED_PROTOCOL`, a `droppedFromCSharp = false` deferral that never reaches
  `droppedCallables`, while `FunctionExports.kt:26` only re-emits a *bare* sealed return
  (`returnDecl.modifiers.contains(SEALED)` is false for `kotlin.collections.List`). **Inferred
  from reading**, not built: that slot is a silent drop, the same class of defect as line 32.
- Adjacent, untouched: ROADMAP line 29 (sealed-subclass nullable getter double call in
  `CirClassTranslator.kt:~1118`) and line 32 (class method returning sealed dropped silently).
- The `ForwardCallablePlanner.kt:~450` value-class rewrite should converge on
  `SpecializedProtocol.sealedHandle` in a follow-up; not required for this ADR.

## Post-implementation note: the classifier mint is gated, and two inferred claims are now verified

The Decision table says the classifier mints `sealedHandle` "position-agnostic" whenever it sees a
sealed class. As shipped, `ForwardBridgeTypeClassifier.kt` gates the mint on
`classKind == CLASS && qualifiedName in context.exportedObjectHandles`: a sealed **interface** has
no ADR-009 `FromHandle` to reconstruct through (only a sealed `class` gets one, `NugetProcessor.kt`'s
`rootSealedClasses` filters on `classKind == CLASS`), and a sealed class outside the export scope has
no C# spelling to build `ObjectHandle(csharpTypeNameFor(...))` from. Neither case can safely mint a
handle, so the gate is load-bearing, not an implementation detail. Pinned by
`Tier1SealedCollectionPropertyTest`'s `a sealed interface component still skips named` case: a
`List<Filter>` property with a sealed-interface element still routes through the named
`SKIPPED_UNSUPPORTED_PROPERTY` diagnostic exactly as before this ADR.

The Consequences section above still names `docs/topics/bridgeable-subset.md` as the page that must
state the residual restriction. That page documents the *reverse* (C# → Kotlin) subset; it is the
wrong pointer. The forward sealed-property page is
[Interfaces, abstract classes, and sealed classes](../topics/interfaces-abstract-sealed.md), and that
is the page the shipped docs amended.

Two claims marked **Inferred** above are now **Verified**, both by
`Tier1SealedMutableCollectionPropertyTest`:

- A scalar `var current: Shape` setter rides the ordinary `ObjectHandle` wire. The generated C#
  setter is `Native_Set_current(_handle, value._handle, out IntPtr error)` and the getter reads
  back through `global::Interop.Shape.FromHandle(nativeResult)`, exercised by
  `a scalar sealed property keeps its setter on the ordinary handle wire`.
- A `var shapes: MutableList<Shape>` property plans get-only, named by the ADR-075 read-only
  diagnostic. `the gated setter is named by the ADR-075 read-only diagnostic` asserts the exact
  wording: `SKIPPED_UNSUPPORTED_INPUT` naming `Board.shapes` and containing
  `"the C# property Shapes is read-only"`.

The scalar-setter claim is additionally promoted by the shipped `test-library` fixture
(`test-library/src/nativeMain/kotlin/.../issue54/Issue54Sample.kt`), which carries a scalar
`var current: Issue54Shape` and asserts the round trip in `IntegrationTests/Issue54Tests.cs`. The
mutable-collection claim rests on `Tier1SealedMutableCollectionPropertyTest` alone: the fixture
deliberately carries no `var` mutable collection of sealed (see its KDoc), since that shape belongs
to a processor unit test, not an integration fixture.

## Prior art (to the depth that changes the decision)

- **ObjC / Swift Export**: Kotlin/Native maps a sealed class to an ordinary class hierarchy
  (ADR-009 already records this), so a sealed-typed property is simply a class-typed property
  with the declared type's static spelling; nothing position-specific exists to mirror.
  Inferred from the Kotlin docs
  ([ObjC interop](https://kotlinlang.org/docs/native-objc-interop.html#classes-and-objects)),
  no spike, and it does not change the decision: the C# mapping's only novelty is that the base
  is `abstract`, which is ADR-009's own choice and the sole reason `new` fails.
- **Kotlin → JVM**: no analogue; a sealed class is a plain JVM class with a private constructor
  and the JVM has no construction-from-handle step.
- Skipped: JS/Wasm export (no handle-reconstruction seam at all).
