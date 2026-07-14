# ADR-059: Nested struct components in Kotlin: recursive depth-first flattening on the wire, nested `data class` in Kotlin

## Status

Accepted

## Context

ROADMAP line 168, Phase 9:

> - [ ] Nested struct components: a struct field inside a struct, needs recursive flattening;
>   deferred for scope, not a technical obstacle (ADR-056 Scope)

[ADR-056](056-csharp-structs-in-kotlin.md) settled how a struct crosses: component decomposition (one
ABI argument per component in, one out-pointer per component out), an immutable Kotlin `data class`
surface, no handle, no `Cleaner`, no layout. [ADR-058](058-csharp-shape-b-structs-in-kotlin.md) added
Shape B (no state constructor; components are settable public state; reconstruction is an object
initializer). Both explicitly excluded a component that is *itself* a struct, and both said the same
thing about it: the component decoder is deliberately constructed with **no struct map**, so a
struct-typed component can never resolve, and the whole struct is skipped.

That exclusion is one line of the reader. Removing it is not, because it changes an invariant both
generators are built on: **one `RirStructType` is no longer one flat component group**. It is a tree.

The ROADMAP calls this "not a technical obstacle". That is *nearly* right, and the one place it is
wrong is the reason this is an ADR and not an implementation note: flattening multiplies ABI arity,
and Kotlin/Native's `CFunction` has a **hard 22-argument ceiling** (verified below) that nothing in
this repository has ever been close enough to notice. An ordinary two-struct method over the fixture
types proposed here crosses it. Without a fail-fast check, an implementing agent gets a generated
Kotlin file that does not compile and an error message ("none of the following candidates is
applicable") that says nothing about structs, nesting, or arity.

### Constraint 1 (verified): today the reader skips a nested struct, and **names the wrong rule**

A **scratch copy** of the repository's `nuget-metadata-reader/Program.cs` (unmodified, built in
`mktemp -d`) run against a probe assembly with nested structs:

```csharp
public readonly struct Tag {                                        // Shape A leaf
    public Tag(string text, int weight) { Text = text; Weight = weight; }
    public string Text { get; } public int Weight { get; } }
public struct Extent { public int Width; public int Height; }        // Shape B leaf
public readonly struct Card {                                        // Shape A outer, struct components
    public Card(Tag tag, Extent extent, Mood mood) { Tag = tag; Extent = extent; Mood = mood; }
    public Tag Tag { get; } public Extent Extent { get; } public Mood Mood { get; } }
public struct Frame {                                                // Shape B outer, struct components
    public Card Card; public Extent Bounds { get; set; } }
public static class Cards {
    public static Card Bump(Card c) => c;
    public static string Describe(Frame f) => f.Card.Tag.Text; }
```

```text
$ dotnet <scratch-reader>.dll --package Probe probe.dll --include Probe.Nested
=== TYPES EMITTED ===
  enum     Mood     shape=-            components=-
  struct   Tag      shape=constructor  components=['text:string', 'weight:primitive']
  struct   Extent   shape=initializer  components=['width:primitive', 'height:primitive']
  class    Cards    shape=-            methods=0
=== DIAGNOSTICS ===
  [skipped_unsupported_struct] Card.Card: auto-property `Tag` has no public setter and there is no
                                          constructor to set it
  [skipped_unsupported_struct] Frame.Frame: type `Probe.Nested.Card` is not a bridgeable bound class
                                            in this extraction run
  [skipped_unsupported_struct] Cards.Bump: struct `Probe.Nested.Card` is not bridgeable: auto-property
                                           `Tag` has no public setter ...
  [skipped_unsupported_struct] Cards.Describe: struct `Probe.Nested.Frame` is not bridgeable: type
                                               `Probe.Nested.Card` is not a bridgeable bound class ...
```

**Verified**, and three facts fall out:

1. **The leaves bind fine.** `Tag` (Shape A) and `Extent` (Shape B) are already correct today. Only
   the *nesting* is rejected.
2. **The diagnostic names the wrong rule, twice.** `Card` is reported as failing a **Shape B**
   auto-property rule. The truth is that Shape A failed first (its `Tag` component would not decode),
   and `ExtractStruct` discards the Shape A reason when it falls through to Shape B
   (`return StructExtraction.Unsupported(shapeBReason!)`, **verified by reading the source**). `Frame`
   is reported as "not a bridgeable bound **class**", which is worse: `Card` is not a class at all.
   Neither message contains the words "nested" or "struct component". A user with this shape cannot
   act on either message. **The diagnostic is part of this feature's deliverable, not an afterthought**
   (ADR-043's contract).
3. **The rejection is transitive and silent at the call site**: `Cards` is emitted as a class with
   **zero** methods. Both of its methods vanish.

### Constraint 2 (verified): the recursion terminates, and the C# compiler is what guarantees it

The design rests on "a struct cannot contain itself". Verified rather than asserted (TFM `net8.0`,
SDK 10.0.300):

```csharp
public struct SelfDirect { public SelfDirect Inner; public int N; }      // 1
public struct MutA { public MutB B; }  public struct MutB { public MutA A; }  // 2
public struct Gen<T> { public T Value; }  public struct ViaGeneric { public Gen<ViaGeneric> G; }  // 3
public class  Holder { public ViaClass V; }  public struct ViaClass { public Holder H; public int N; } // 4
public struct ViaArray { public ViaArray[] Items; public int N; }        // 5
public struct ViaNullable { public ViaNullable? Maybe; public int N; }   // 6
public struct ViaStatic { public static ViaStatic Empty; public int N; } // 7
```

```text
$ dotnet build -c Release
error CS0523: Struct member 'SelfDirect.Inner' of type 'SelfDirect' causes a cycle in the struct layout
error CS0523: Struct member 'MutA.B' of type 'MutB' causes a cycle in the struct layout
error CS0523: Struct member 'MutB.A' of type 'MutA' causes a cycle in the struct layout
error CS0523: Struct member 'ViaGeneric.G' of type 'Gen<ViaGeneric>' causes a cycle in the struct layout
error CS0523: Struct member 'ViaNullable.Maybe' of type 'ViaNullable?' causes a cycle in the struct layout
# (4) ViaClass, (5) ViaArray and (7) ViaStatic compile with NO error.
```

**Verified.** Four consequences, and the last one matters more than it looks:

- Direct, **mutual**, **generic-mediated** and **`Nullable<T>`-mediated** containment are all CS0523.
  The question "does it still hold through a generic struct?" is answered: **yes**, and the compiler
  catches it at the point of instantiation, not at type-load.
- A cycle **through a class** (`struct -> class -> struct`) is **legal C#**. It cannot make the
  flattener loop, for two independent reasons: class-typed components are deferred (ADR-056 Scope,
  unchanged here), and even when they land, a handle component is a **leaf** on the wire (an
  `IntPtr`), so the recursion does not descend through it. The recursion descends through
  struct-typed components only, and that graph is acyclic by CS0523.
- A cycle **through an array** or a **static field** is legal C# for the same reason (a reference, or
  not instance state). Neither is a component.
- **But the reader does not run the C# compiler.** It reads ECMA-335 metadata and never loads a type,
  so it does not get CS0523's protection for free: it gets it only because the assembly it is reading
  was produced by a compiler that enforced it. A hand-written or non-Roslyn IL assembly is not
  covered. The classification pass must therefore terminate **by construction**, not by trusting the
  input (Decision 6).

### Constraint 3 (verified, decisive, and new): `CFunction.invoke` tops out at **22 arguments**

The reverse bridge calls C# through `CPointer<CFunction<(...) -> R>>` (ADR-041/048). Kotlin function
types are `FunctionN`, and `kotlinx.cinterop` only provides `invoke` up to a fixed arity:

```kotlin
// 22 Int parameters: compiles and links.
private var fn22: CPointer<CFunction<(Int, /* ...20 more... */ Int, Int) -> Int>>? = null
// 23 Int parameters: the DECLARATION is accepted; the CALL is not.
private var fn23: CPointer<CFunction<(Int, /* ...21 more... */ Int, Int) -> Int>>? = null
```

```text
$ ~/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.0/bin/kotlinc-native -p program -o app main.kt
main.kt:15:32: error: none of the following candidates is applicable:      # the fn23.invoke(...) call

$ ... -o app22 main22.kt && ./app22.kexe
22-arg CFunction compiled
```

**Verified** (Kotlin/Native 2.4.0, macOS arm64). Note *where* it fails: the `CFunction` **type** with
23 parameters is accepted, and only the `invoke` **call site** fails, with a message that mentions
neither arity nor structs. This is a latent ceiling on the whole reverse bridge, not a new one, but
it has been unreachable in practice: you would need a 23-parameter C# method, or a 23-component
struct. **Flattening a nested struct is what makes it reachable with ordinary types.** Two `Litter`
parameters (8 leaves each) plus a `Litter` return (8 out-pointers) is 24 arguments, and `Litter` is a
four-component struct.

The budget is per bridged member, and everything counts: the receiver (a handle for a class instance
method, or the flattened receiver components for a struct instance member), the flattened parameters,
and the flattened out-pointers.

### Constraint 4 (verified): recursive flatten + recursive reconstruct round-trips, in all four shape combinations, at depth 3

A scratch console app declared the four combinations, flattened every struct depth-first, and called
each `[UnmanagedCallersOnly]` thunk through a raw `delegate* unmanaged[Cdecl]` function pointer,
which is exactly the C ABI call Kotlin makes:

```csharp
public readonly struct Tag { public Tag(string text, int weight) {...} ... }   // Shape A leaf
public struct Extent { public int Width; public int Height; }                  // Shape B leaf
public readonly struct Card {                                                  // A-in-A and B-in-A
    public Card(Tag tag, Extent extent, Mood mood) {...} ... }
public struct Frame {                                                          // A-in-B and B-in-B
    public Tag Label; public Extent Bounds { get; set; } public bool Active { get; init; } }
public readonly struct Deck { public Deck(Card top, int count) {...} ... }     // DEPTH 3

[UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
internal static void Bump_Thunk(
    IntPtr c_Tag_Text, int c_Tag_Weight, int c_Extent_Width, int c_Extent_Height, int c_Mood, int by,
    IntPtr* outTag_Text, int* outTag_Weight, int* outExtent_Width, int* outExtent_Height, int* outMood)
{
    Card result = Api.Bump(
        new Card(                                                    // outer Shape A: ctor call
            new Tag(Marshal.PtrToStringUTF8(c_Tag_Text)!, c_Tag_Weight),        // inner Shape A: ctor
            new Extent { Width = c_Extent_Width, Height = c_Extent_Height },    // inner Shape B: init
            (Mood)c_Mood),
        by);
    *outTag_Text      = Marshal.StringToCoTaskMemUTF8(result.Tag.Text);          // nested read-back
    *outTag_Weight    = result.Tag.Weight;
    *outExtent_Width  = result.Extent.Width;
    *outExtent_Height = result.Extent.Height;
    *outMood          = (int)result.Mood;
}
```

```text
$ dotnet run -c Release
A-in-A + B-in-A round trip: Tag(oreo!,4) Extent(11,21) Mood=Playful
A-in-B + B-in-B (Shape B outer, initializer): kit/5/7x8/True
DEPTH 3: Deck(Top=Card(Tag(deep!,2),Extent(3,4),Playful),Count=10)
```

**Verified** (TFM `net8.0`, .NET 10.0.8 runtime). A second scratch app verified the exact Shape B
outer shape this ADR's fixture proposes, with a struct arriving through **all three** Shape B
component sources at once:

```csharp
public struct Nest {
    public Collar Collar;                 // struct via a public FIELD          (B-in-B)
    public Point Centre { get; set; }     // struct via a settable auto-prop    (A-in-B)
    public Extent Bounds { get; init; }   // struct via an INIT-ONLY auto-prop  (B-in-B)
    public bool Lined { get; set; } }
```

```text
Shape B outer, 3 nested struct sources: 2redPlayful|5,6|7x8|True
```

**Verified.** The `init`-only accessor is callable inside a nested object initializer exactly as
ADR-058 Constraint 2 found for a flat one.

### Constraint 5 (verified by reading repository source): most of the machinery is already recursive or already generic

- `RirStructComponent.type` is already a `RirTypeRef`, and `RirTypeRef` already has a `RirStructType`
  variant. **The RIR JSON schema does not change at all** (`RirModel.kt` lines 111-116, 199-204).
- `signaturePart()` for a `RirStructType` **already recurses**: it expands each component as
  `"${it.name}:${it.type.signaturePart(structs)}"`, and that inner call re-enters the struct branch
  for a struct-typed component (`RirBridging.kt` lines 528-539). The ADR-054 contract hash therefore
  covers nesting **with no code change**, and its own comment already anticipates this: "a component
  that is itself a struct would expand again".
- `boundStructTypes(file)` already builds the resolution map both generators use.
- `CollectStructTypes` already runs **two passes** (components first, then constructors/methods,
  because "alternate constructors can reference another supported struct"). Nesting needs the same
  trick one level earlier: *components* can now reference another struct too.
- `isV1Type` already returns `true` for any `RirStructType` on the grounds that the reader only ever
  emits one for a struct it already validated. That contract still holds and needs no change.

And the places that are explicitly **not** ready, each of which is a silent-wrong-output risk:

- `abiArgs` / `abiOutArgs` / `structReceiverAbiArgs` (`RirBridging.kt` 60-83, 173-174) flatten
  exactly one level (`struct.components.map { ... }`).
- `typeContains` (`NugetGenerateBindingsTask.kt` 100-109) carries the comment "A struct can only be
  one level deep in v1 ... so this never needs to recurse more than once". It drives `needsInterop`,
  `memScoped`, `freeManagedString` and every import decision.
- `structEnumComponents` in **both** generators (bindings 118-125, shims 365-371) is one level deep.
- `structFileContent` renders each component as `kotlinType(c.type)` (bindings line 463), which for a
  struct returns a **bare simple name with no import and no package qualification** (line 1102). Every
  other reference site uses `declKotlinType(type, qualifiedTypeNames)`. A nested component in a
  different bound namespace would emit an unresolvable type name.
- `cVarType` and `csReturnConversion` both `error("nested struct components are not supported in v1
  (ADR-056)")`. Those two messages become false statements about the world and must be reworded to
  what they actually guard ("must be expanded via abiOutArgs first").

### Prior art: does anyone else flatten?

The interesting answer is that **nobody flattens, because nobody else has to**. Every other FFI in
this space has a real by-value struct ABI available to it, and ADR-056 Constraint 2 established
(verified) that this project does not.

- **Kotlin/Native cinterop with nested C structs.** A nested C struct is just a nested `CStructVar`
  at an offset inside the parent's native memory; `useContents { }` projects it. It is by-value with a
  cinterop-generated layout, which is precisely the mechanism ADR-056 Decision 1b rejected (it would
  require a per-target cinterop stage, and it cannot carry a `string`).
  ([Kotlin/Native C interop](https://kotlinlang.org/docs/native-c-interop.html))
- **P/Invoke's own nested `[StructLayout]` marshalling.** A nested struct field is marshalled
  field-by-field by the CLR marshaller into the parent's unmanaged layout. This is exactly the
  mechanism `[UnmanagedCallersOnly]` **opts out of** (it does no marshalling at all, and demands
  transitive blittability), which is why the bridge cannot use it.
  ([Blittable and non-blittable types](https://learn.microsoft.com/en-us/dotnet/framework/interop/blittable-and-non-blittable-types),
  [Marshalling classes, structures, unions](https://learn.microsoft.com/en-us/dotnet/framework/interop/marshalling-classes-structures-and-unions))
- **Rust `bindgen` / `cbindgen`.** `bindgen` reproduces the nested `#[repr(C)]` struct as a nested
  Rust `#[repr(C)]` struct and relies on the two compilers agreeing on layout; it never flattens.
  ([bindgen](https://rust-lang.github.io/rust-bindgen/))
- **JNA / JNI struct-by-value.** JNA's `Structure` describes the layout with `getFieldOrder()` and
  memcpy's it; a nested `Structure` field is embedded by value. JNI has no struct-by-value at all: the
  usual answer is to pass the fields, which is the same shape as flattening, but hand-written.
  ([JNA Structure](https://java-native-access.github.io/jna/5.13.0/javadoc/com/sun/jna/Structure.html))
- **Swift export / ObjC export.** Neither can represent a Kotlin value type with value semantics
  today (ADR-056), so neither offers a nested-value-type precedent worth copying.

So the precedent is unanimous in one direction only: **every one of them pays for by-value nesting
with an explicit layout description**, and this project has already decided (ADR-056 Decision 1e) not
to infer a layout it cannot verify. Flattening is the same trade ADR-056 already made, applied one
level deeper. The novelty here is not the wire format; it is that the *tree* has to be reassembled on
both sides, from one flat list, by two independent generators.

## Alternatives Considered

### Decision 1: what a struct-typed component becomes on the wire

#### 1a. Recursive depth-first, pre-order flattening; the struct tree never exists on the wire (chosen)

A struct-typed component expands **in place** into its own components, recursively, until every
element is a scalar. The flat argument list is **the leaves of the component tree in depth-first,
pre-order, left-to-right order**.

The rule a reader of a generated shim can apply by eye:

> Walk the member's parameters left to right. A non-struct parameter contributes one argument. A
> struct parameter contributes its component list in order, and wherever a component is itself a
> struct, splice that component's own list in at that point, recursively, before moving on to the next
> sibling. A struct return does the same, as out-pointers, appended after every in-argument. For an
> instance member, the receiver comes first: a handle for a class, the flattened components for a
> struct.

ABI names are the **path**, joined with `_`, using each level's `readName` (the C# member name), which
is exactly what the existing one-level rule already produces at depth 1:

| Position | depth 1 (today) | depth 2 (this ADR) |
|---|---|---|
| parameter | `p_Tag` | `p_Mother_Tag` |
| struct return | `outTag` | `outMother_Tag` |
| struct receiver | `Tag` | `Mother_Tag` |

**Pros:**

- **Zero new ABI vocabulary, and zero RIR schema change.** Every leaf is a scalar the two generators
  already emit. `RirStructComponent.type` is already a `RirTypeRef` that already admits
  `RirStructType` (verified).
- **The ADR-054 contract hash already covers it** (verified: `signaturePart()` already recurses). A
  change to the *inner* struct's component list changes the outer's flat arity, and the hash already
  moves with it. This was the single most dangerous hole in ADR-056 and it is closed for free.
- **Depth is not a special case.** Depth 1 is the depth-2 algorithm with the recursion never taken, so
  there is one code path, not two. Verified at depth 3.
- **The inner struct's shape is irrelevant to the outer.** Reconstruction composes (Decision 3), so
  all four A/B combinations fall out of one rule (verified).
- Value semantics compose: a nested `data class` is still structurally equal, still `copy()`-able,
  still has nothing to close.

**Cons:**

- **ABI arity multiplies**, and the 22-argument `CFunction` ceiling becomes reachable with ordinary
  types (Constraint 3). Addressed by Decision 5, and this is the reason this ADR exists.
- The generated thunk signature for a deeply nested struct is long and unlovely. Accepted: nobody
  reads it, and the naming is path-derived so it stays predictable.
- Two generators must agree on a **tree walk** now, not a flat list. This is the ADR-049 Alternative 10
  anti-drift rule, and it is now load-bearing in a third place. Both generators must consume the same
  recursive `abiArgs`/`abiOutArgs` (Decision 4).

#### 1b. Flatten only one level; a struct nested two deep stays unsupported (rejected)

**Rejected.** It costs *more* code than the general rule (a depth counter and a rule to explain), buys
nothing, and draws the supported/unsupported line somewhere a user cannot predict. Depth 3 is verified
working. The real ceiling is arity, and arity is what should be checked (Decision 5), because an arity
check is meaningful (it maps to a real failure) whereas a depth check is arbitrary.

#### 1c. The nested struct crosses as a boxed `GCHandle` (rejected)

`GCHandle.Alloc(inner)` for the nested component, an `IntPtr` on the wire, and a handle-backed Kotlin
class for the inner type.

**Rejected**, for exactly the reasons ADR-056 Decision 1c rejected it for the outer struct, all of
which get *worse* one level in: the inner value type acquires a `close()`, a `Cleaner` and reference
equality, so `outer == outer.copy()` becomes **false** (the copy carries a different handle to an
equal box). A value type whose equality depends on a boxing decision made by a code generator is not a
value type. It also makes the Kotlin *kind* of a type (`data class` vs `Closeable`) depend on whether
it appears at the top level or as a component, which is incoherent.

#### 1d. Pack the nested struct into a byte buffer at a generator-computed offset (rejected)

**Rejected** on the same grounds as ADR-056 Decision 1e, which apply verbatim: it requires reproducing
the CLR's runtime layout algorithm, and metadata's `SequentialLayout` is not a promise about runtime
layout for a non-blittable type. Getting it subtly wrong is silent field corruption. Nesting makes
that strictly harder (nested alignment/padding), for no gain.

---

### Decision 2: the Kotlin surface

#### 2a. The Kotlin surface stays nested: a component's type is the inner struct's `data class` (chosen)

```kotlin
data class Litter(
  val mother: Profile,   // <- the nested struct's own generated data class
  val basket: Extent,
  val count: Int,
  val mood: CatMood,
)
```

The wire is flat; the surface is not. The generated stub flattens on the way out and reassembles on
the way in.

**Pros:**

- It is what the C# type *is*. `litter.mother.tag` reads in Kotlin exactly as `litter.Mother.Tag`
  reads in C#, which is GOALS' whole test: the binding must feel native to the consumer, and it must
  not leak the wire format into the surface.
- **Structural equality composes for free.** `data class` `equals` delegates to each component's
  `equals`, and each component is itself a `data class`, so equality is deep and matches C#'s
  `ValueType.Equals` (which is also recursive by value). `a == a.copy()` stays true at any depth.
- The inner struct's `data class` already exists and is already generated. Nesting adds no new Kotlin
  type.
- `copy(mother = ...)` is the idiomatic non-destructive update, and it composes:
  `l.copy(mother = l.mother.copy(mood = CatMood.Playful))`. The C# equivalent is `with`, so the idioms
  line up exactly.

**Cons:**

- The Kotlin type's shape and the wire's shape now differ by a whole tree, not just by an unwrapping.
  A reader of the generated `Bindings.kt` sees 8 `Int`/`COpaquePointer?` slots where the stub's
  signature says `Litter`. Mitigated by the path-derived ABI names (`outMother_Tag`), which make the
  correspondence readable, and by a KDoc note.

#### 2b. Flatten the Kotlin surface too (`data class Litter(val motherTag: String, val motherActive: Boolean, ...)`) (rejected)

**Rejected.** It is the wire format leaking into the public API. It destroys the ability to pass a
`Profile` (which already exists as its own Kotlin type, with its own methods) into a `Litter`, forces
the consumer to hand-decompose a value the C# API hands them whole, and makes the Kotlin type
*diverge* from the C# one for a reason that is purely an artifact of the C ABI. GOALS forbids this in
one sentence: "never like a wrapper around the other language".

#### 2c. Surface the nested component as a Kotlin *nested class* declaration (rejected)

`data class Litter(val mother: Litter.Profile, ...)` with `Profile` declared inside `Litter`.

**Rejected.** C# nesting of *values* is not C# nesting of *types*. `Profile` is a top-level C# type
that happens to be used as a component; it is used as a top-level parameter elsewhere in the same
fixture. Re-declaring it inside `Litter` would give it two Kotlin identities.

---

### Decision 3: reconstruction on the C# side, and whether the inner shape matters

#### 3a. Recursive `structConstruction`; the inner shape is chosen independently of the outer (chosen)

ADR-058 already introduced the one shared helper both reconstruction sites route through:

```kotlin
// RirStructShape.CONSTRUCTOR -> "new T(a, b)"     RirStructShape.INITIALIZER -> "new T { A = a, B = b }"
private fun structConstruction(struct: RirStruct, componentExprs: List<String>): String
```

The only change is that a component expression may now be **another `structConstruction` call**. The
outer's shape decides how the component expression is *placed* (a positional argument, or an
`Origin = <expr>` assignment); the inner's shape decides how that expression is *built*. They do not
interact.

All four combinations, **verified** to compile and round-trip (Constraint 4):

```csharp
// A-in-A: outer ctor, inner ctor
new Card(new Tag(Marshal.PtrToStringUTF8(c_Tag_Text)!, c_Tag_Weight), ..., (Mood)c_Mood)

// B-in-A: outer ctor, inner object initializer
new Card(..., new Extent { Width = c_Extent_Width, Height = c_Extent_Height }, ...)

// A-in-B: outer object initializer, inner ctor
new Frame { Label = new Tag(Marshal.PtrToStringUTF8(f_Label_Text)!, f_Label_Weight), ... }

// B-in-B: outer object initializer, inner object initializer
new Frame { ..., Bounds = new Extent { Width = f_Bounds_Width, Height = f_Bounds_Height } }
```

Read-back is the mirror and is just as uniform: the out-pointer write for a leaf uses the existing
`csReturnConversion` on a **path expression**, `result.Mother.Tag` instead of `result.Tag`.

**Pros:** no new C# machinery whatsoever, only a recursive call; the shape matrix collapses to "each
struct constructs itself the way its own shape says", which is one sentence and four verified cases.

**Cons:** none found. The one thing to be careful of is that the recursion must consume the flat
component expressions in the **same DFS pre-order** the signature was built in, which is why both must
come from the shared expansion and not be re-derived (Decision 4).

#### 3b. Require the inner struct to be Shape A (a constructor) (rejected)

Simplifies nothing (the initializer path already exists and is already shared) and excludes
`B-in-A`/`B-in-B`, which are verified working and which include the canonical
`struct Extent { public int Width; public int Height; }`.

---

### Decision 4: where the flattening lives, and what the RIR carries

#### 4a. The RIR keeps the struct **reference**; the shared expansion in `RirBridging.kt` flattens (chosen)

`reverse-ir.json` gains **no new node kind and no new field**. A nested component is simply a
component whose `type` is `{"kind": "struct", ...}`:

```json
{ "kind": "struct", "name": "Litter", "shape": "constructor",
  "components": [
    { "name": "mother", "readName": "Mother",
      "type": { "kind": "struct", "namespace": "Sample.Structs", "name": "Profile" } },
    { "name": "basket", "readName": "Basket",
      "type": { "kind": "struct", "namespace": "Sample.Structs", "name": "Extent" } },
    { "name": "count",  "readName": "Count",  "type": { "kind": "primitive", "name": "int" } },
    { "name": "mood",   "readName": "Mood",
      "type": { "kind": "enum", "namespace": "Sample.Enums", "name": "CatMood" } }
  ] }
```

**Verified** by reading `RirModel.kt`: `RirStructComponent.type` is a `RirTypeRef` and `RirStructType`
is one of its variants, so **this JSON already parses on `main` today**. The reader simply never
emits it.

`abiArgs` / `abiOutArgs` / `structReceiverAbiArgs` become recursive, and both generators keep
consuming exactly those three functions.

**Pros:**

- **The Kotlin surface needs the nesting.** A pre-flattened component list would have thrown away the
  one thing Decision 2a depends on, and the generator would have to *rebuild* the tree by parsing
  underscore-separated name paths, which is strictly worse than never destroying it.
- The generators already resolve struct references (`boundStructTypes`, `resolveStruct`,
  `signaturePart`). The delta is a recursive call in three functions, not a new mechanism.
- The contract hash keeps working (it recurses over the same tree; verified).
- The RIR stays a faithful model of the managed API. "The reader models what C# says; the generators
  decide what the wire looks like" is the existing division of labour, and pre-flattening would move
  a *wire* decision into the reader.

**Cons:**

- The generators must resolve a type reference recursively, and a dangling reference is now possible
  two levels down. `resolveStruct` already `requireNotNull`s with a clear message; that check now
  matters more.
- The flattening lives in one place that both generators must call. It always did (ADR-056 said so);
  nesting raises the cost of getting it wrong from "misaligned arguments" to "misaligned arguments
  that also nest".

#### 4b. The reader pre-flattens and emits an already-flat component list (rejected)

`Litter` would emit `[motherTag: string, motherActive: bool, ..., count: int, mood: enum]`.

**Rejected**, on three counts:

1. **It destroys the Kotlin surface.** Decision 2a's `val mother: Profile` is unrecoverable from a
   flat list without re-deriving the nesting from name paths, which requires the reader to have
   encoded the path *and* the generator to parse it back. That is a serialization format pretending
   to be a model.
2. It would make `readName` a lie (`result.Mother.Tag` is not a member name), so the shim generator's
   read-back would need a *separate* path field anyway. The nesting would come back, just uglier.
3. It changes the contract hash's meaning (a nested struct's identity would disappear from the hash,
   leaving only its leaves), losing the ability to distinguish `Litter{mother:Profile{...}}` from a
   flat struct with the same leaf types.

The only thing it buys is that the generators would not need to recurse. They already need to recurse
for the surface, so it buys nothing.

---

### Decision 5: the 22-argument ceiling

#### 5a. Fail closed: skip the member with a named diagnostic, from the **shared** registrable filter (chosen)

Any bridged member whose flattened ABI argument count (receiver + parameters + out-pointers) exceeds
**22** is **skipped**, with a new `skipped_abi_arity_limit` diagnostic naming the member, the count,
and which struct blew the budget.

The check **must live in `RirBridging.kt`'s shared `bridgeableRegistrables` / `bridgeableStructRegistrables`**,
not in either generator, for the reason every other filter lives there: if only one generator dropped
the member, the two sides' registration slots would silently misalign, which is memory corruption with
no error (ADR-049 Alternative 10, ADR-054).

**Pros:**

- It is ADR-043's existing contract applied to a genuinely new subset boundary: a member outside the
  bridgeable subset is skipped and diagnosed, and the rest of the type still binds.
- The precedent already exists in this exact shape: `SKIPPED_MEMBER_NAME_COLLISION` is emitted
  **Gradle-plugin-side, not by the reader**, precisely because only the generators know the Kotlin/ABI
  projection (`RirBridging.kt` `collisionDiagnostics`). ABI arity is the same kind of fact: the reader
  cannot compute it, because it is a property of the flattening, not of the metadata.
- The failure it replaces is a Kotlin compiler error inside *generated* code, with a message
  ("none of the following candidates is applicable") that names neither the member nor the cause
  (verified). That is the worst possible failure mode: the user did not write the file.

**Cons:**

- A perfectly reasonable C# method silently loses its binding. Mitigated by a diagnostic that says
  exactly which struct to shrink or which parameter to drop, and by the fact that the alternative is a
  build failure the user cannot act on.
- 22 is a Kotlin/Native implementation limit, not a C ABI limit, so this ceiling could move.
  Recorded as such.

#### 5b. Let `kotlinc-native` fail (rejected)

**Rejected.** Generated code that does not compile, with an error that points at generated source and
mentions neither struct nor arity. This repository has already paid for one such debugging session
(CLAUDE.md, ADR-053).

#### 5c. Box the overflow: pass the struct as a `GCHandle` when the flattened arity exceeds 22 (rejected)

**Rejected.** It makes the wire format (and therefore, via Decision 1c's consequences, the *semantics*
of the Kotlin type) depend on how many components a sibling parameter happened to have. This is
ADR-056 Decision 1d's rejected hybrid, with an even more arbitrary trigger.

#### 5d. Raise the ceiling with a packing struct (deferred, not rejected)

A future option: when arity exceeds 22, pass a pointer to a scratch buffer the Kotlin side fills with
the leaves. This keeps decomposition and value semantics, and only changes the calling convention.
It needs its own decision (buffer ownership, layout, alignment) and is not needed to ship this. Listed
in Scope.

---

### Decision 6: termination in the reader, and the diagnostic path

#### 6a. Fixed-point classification, terminating by construction, with a path-naming diagnostic (chosen)

`CollectStructTypes` currently extracts every struct's components in one pass, with a component
decoder that has **no struct map** (verified). It must now resolve struct-typed components, which
means a struct cannot be classified until its components are.

Classify by **iterating to a fixed point**:

1. Pass 0: attempt every struct candidate with the currently-known supported-struct map (initially
   empty). A struct whose only failure is an *unresolved struct component* stays "pending".
2. Repeat, with the map grown by whatever was newly supported, until a pass adds nothing.
3. Anything still pending is **unsupported**, and its diagnostic names the component **path** that
   could not be resolved.

**Pros:**

- **It cannot loop or stack-overflow, whatever the input.** Each pass either grows the supported set
  or terminates, and the supported set is bounded by the number of structs. It does not rely on the
  input assembly being cycle-free, which matters because the reader never loads a type and therefore
  never gets CS0523's protection (Constraint 2's fourth point).
- A cyclic value type (which C# cannot produce, verified) fails **closed**: neither struct ever
  resolves, both are unsupported, both are diagnosed. No special cycle-detection code exists to get
  wrong.
- Convergence is O(max nesting depth) passes, which for real structs is 1 to 3.
- It reuses the exact pattern already in the file: `CollectStructTypes` already defers constructor
  mapping to a second pass "because alternate constructors can reference another supported struct".
  This is the same reason, one level earlier.

**Cons:**

- Re-runs extraction on unresolved candidates a few times. Irrelevant at these sizes.
- A cycle's diagnostic ("component `X` is not bridgeable") is less precise than a dedicated cycle
  message. Acceptable: C# cannot produce the input.

#### 6b. Memoized recursion with an in-progress set (rejected)

The obvious implementation: recurse into a component's struct, guarding with an `extracting` set to
detect the cycle.

**Rejected** as the primary. It is more code (a re-entrancy guard, a partially-built map threaded
through `SignatureDecoder`, which today takes a *finished* `IReadOnlyDictionary`), and its failure mode
if the guard is wrong is a `StackOverflowError` inside a Gradle task. The fixed point cannot have that
failure mode at all. (It remains a legitimate implementation if a profile ever shows the fixed point
matters, which it will not.)

#### 6c. The diagnostic must name the component path (part of the chosen decision)

Verified today: `Card` is reported as failing a Shape B auto-property rule, and `Frame` as referencing
"a bridgeable bound **class**". Both are wrong and neither is actionable. Required behaviour:

- When a struct is skipped because a **component** is not bridgeable, the reason must name the
  component and the reason recursively:
  `component `Manual` (`Sample.Structs.Manual`) is not bridgeable: property `A` is settable but is
  not an auto-property ...`
- When **Shape A fails and Shape B also fails**, the reason must carry **both** attempts, not just the
  last one. Discarding the Shape A reason is what produced the misleading `Card` message.

---

## Decision

Take **1a + 2a + 3a + 4a + 5a + 6a**: a struct-typed component is **flattened recursively, depth-first,
pre-order** onto the wire; the Kotlin surface **stays nested**; the C# side **reconstructs recursively**,
each struct using its own shape; the RIR keeps the **struct reference** and the shared expansion does
the flattening; a member whose flattened arity exceeds **22** is skipped and diagnosed; and the reader
classifies structs by **iterating to a fixed point**.

### The wire format (extends ADR-056's table; adds no scalar)

| Position | `RirStructType` expands to |
|---|---|
| Parameter | the leaves of its component tree, DFS pre-order; ABI name is the `_`-joined `readName` path, prefixed by the parameter name (`p_Mother_Tag`) |
| Return | thunk returns `void`; one out-pointer per **leaf**, DFS pre-order, appended after all in-arguments; ABI name `out` + the `_`-joined path (`outMother_Tag`) |
| Struct receiver (instance member on a struct) | the leaves, DFS pre-order, ABI name is the `_`-joined path with no prefix (`Mother_Tag`) |
| Property getter / setter | as a return / as a parameter, unchanged |

Every leaf uses the per-component wire type from ADR-056's table verbatim. **No new ABI scalar.**

### Kotlin generator

```kotlin
// Surface: nesting preserved. The nested component's type is its own generated data class.
internal data class Litter(
  val mother: Profile,
  val basket: Extent,
  val count: Int,
  val mood: CatMood,
)

// Bindings: the CFunction type is FLAT. 8 leaves in, 8 out-pointers out, `by` in the middle.
internal var growFn: CPointer<CFunction<(
  COpaquePointer?, UByte, UShort, Int,          // mother: Profile{Tag, Active, Grade, Mood}
  Int, Int,                                     // basket: Extent{Width, Height}
  Int, Int,                                     // count, mood
  Int,                                          // by
  CPointer<COpaquePointerVar>, CPointer<UByteVar>, CPointer<UShortVar>, CPointer<IntVar>,
  CPointer<IntVar>, CPointer<IntVar>, CPointer<IntVar>, CPointer<IntVar>,
) -> Unit>>? = null

// Stub: flatten on the way out (a path expression per leaf, each through the SAME argConversion the
// leaf's type already uses), reassemble on the way in (a nested constructor expression).
fun grow(l: Litter, by: Int): Litter = memScoped {
  val fn = requireNotNull(LitterBindings.growFn) { NugetRegistry.notRegistered(...) }
  val outMother_Tag = alloc<COpaquePointerVar>()
  val outMother_Active = alloc<UByteVar>()
  // ... one alloc per leaf, DFS pre-order ...
  fn.invoke(
    l.mother.tag.cstr.ptr, l.mother.active, l.mother.grade.code.toUShort(), l.mother.mood.ordinal,
    l.basket.width, l.basket.height,
    l.count, l.mood.ordinal,
    by,
    outMother_Tag.ptr, outMother_Active.ptr, /* ... */,
  )
  val outMother_TagPtr = requireNotNull(outMother_Tag.value) { "..." }
  val outMother_TagResult = outMother_TagPtr.reinterpret<ByteVar>().toKString()
  freeManagedString(outMother_TagPtr)
  // ... statements hoisted in DFS order, then ONE nested expression ...
  Litter(
    Profile(outMother_TagResult, outMother_Active.value.toInt() != 0, /* ... */),
    Extent(outBasket_Width.value, outBasket_Height.value),
    outCount.value,
    nugetEnumEntry(CatMood.entries, outMood.value, "CatMood"),
  )
}
```

Two properties of the Kotlin side that make this simpler than it looks, and that an implementer should
lean on:

1. **The Kotlin reassembly is shape-agnostic.** The generated `data class` always has a positional
   primary constructor in component order, whatever the C# shape was. Shape A vs Shape B is a
   **C#-side-only** distinction. `structConstruction`'s shape switch stays where it is.
2. **`componentRead` becomes recursive** and returns `(statements, expression)` as it already does. A
   struct component's `expression` is the nested constructor call over its children's expressions; its
   `statements` are its children's statements, concatenated in DFS order. Because children's statements
   are emitted before the parent's expression is evaluated, a `string` leaf's `toKString()`/
   `freeManagedString` locals are always in scope. (The existing `ComponentRead` data class already has
   exactly this shape.)

### C# generator

```csharp
[UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
private static unsafe void Grow__{bridgeId}_Thunk(
    IntPtr l_Mother_Tag, byte l_Mother_Active, ushort l_Mother_Grade, int l_Mother_Mood,
    int l_Basket_Width, int l_Basket_Height,
    int l_Count, int l_Mood,
    int by,
    IntPtr* outMother_Tag, byte* outMother_Active, ushort* outMother_Grade, int* outMother_Mood,
    int* outBasket_Width, int* outBasket_Height, int* outCount, int* outMood)
{
    Litter result = Litters.Grow(
        new Litter(                                                      // outer: Shape A -> ctor
            new Profile(                                                 // inner: Shape A -> ctor
                Marshal.PtrToStringUTF8(l_Mother_Tag)!, l_Mother_Active != 0,
                (char)l_Mother_Grade, (CatMood)l_Mother_Mood),
            new Extent { Width = l_Basket_Width, Height = l_Basket_Height },  // inner: Shape B -> init
            l_Count,
            (CatMood)l_Mood),
        by);
    *outMother_Tag    = Marshal.StringToCoTaskMemUTF8(result.Mother.Tag);     // path read-back
    *outMother_Active = result.Mother.Active ? (byte)1 : (byte)0;
    *outMother_Grade  = (ushort)result.Mother.Grade;
    *outMother_Mood   = (int)result.Mother.Mood;
    *outBasket_Width  = result.Basket.Width;
    *outBasket_Height = result.Basket.Height;
    *outCount         = result.Count;
    *outMood          = (int)result.Mood;
}
```

Every per-leaf conversion is the existing `paramConversion` / `csReturnConversion` output. The only new
text is the nesting.

### ABI-name collisions must fail generation

`_` is a legal C# identifier character, so the path join is not injective:

```csharp
public readonly struct Bad {
    public Bad(Tag tag, int tag_Text) { Tag = tag; Tag_Text = tag_Text; }
    public Tag Tag { get; }        // flattens to  p_Tag_Text, p_Tag_Weight
    public int Tag_Text { get; }   // flattens to  p_Tag_Text        <-- collision
}
```

Both are legal C#. The flattened names collide, and the two generators would each emit a duplicate
parameter name (a C# compile error at best, a silently swapped argument at worst if a future renaming
scheme papers over it). `abiArgs` / `abiOutArgs` / `structReceiverAbiArgs` must therefore **assert
that the ABI names they produce for one member are distinct**, and fail generation naming both
components. This is cheap, it is the repository's stated fail-fast policy, and it costs nothing when
the shape does not occur (which is essentially always).

### Contract hash: no change required (verified)

`signaturePart()` already recurses, so `Litter` hashes as:

```text
Sample.Structs.Litter{mother:Sample.Structs.Profile{tag:string,active:bool,grade:char,mood:Sample.Enums.CatMood},
                      basket:Sample.Structs.Extent{width:int,height:int},count:int,mood:Sample.Enums.CatMood}
```

Adding a field to `Profile` changes `Litter`'s hash, and therefore the hash of every member that
mentions `Litter`, which is exactly right: it changes their flat arity. **This is the one part of
ADR-056's most dangerous mechanism that needed no work**, and it is worth noticing *why*: ADR-056
wrote the recursion in before there was anything to recurse into, and ADR-058 fixed it to hash
component names as well as types. Both decisions pay off here.

One consequence: `signaturePart()` will recurse forever on a cyclic RIR. Decision 6a's fixed point is
what guarantees no cycle ever reaches the generators.

### Metadata reader

1. `CollectStructTypes` classifies by **fixed-point iteration** (Decision 6a). The component decoder is
   constructed **with** the currently-known struct map instead of `null`. That single argument change
   is what admits `RirStructType` as a component type (`GetTypeFromDefinition` already has the branch,
   verified: `Program.cs` lines 2107-2110).
2. ADR-056 rule 5 (stored-field count vs constructor-parameter count) is **unchanged and still
   correct**: a nested struct field is one field.
3. ADR-058's Shape B coverage rules are **unchanged**: a struct-typed public field is a settable public
   field; a struct-typed `set`/`init` auto-property is a settable auto-property (verified to reconstruct
   correctly through all three sources, Constraint 4).
4. Diagnostics name the component **path**, and a struct that fails both shapes reports **both**
   reasons (Decision 6c).

### Shared expansion (`RirBridging.kt`), the anti-drift requirement, restated and widened

ADR-056 said "both generators must expand through one shared function". Nesting raises the stakes: the
shared functions must now produce a **DFS pre-order tree walk**, and the *reconstruction* sites in both
generators must consume the flat list **in that same order**. Concretely, the following must all derive
from the same recursion, and none may re-derive it:

| Site | File | What it does today |
|---|---|---|
| `abiArgs`, `abiOutArgs`, `structReceiverAbiArgs` | `RirBridging.kt` | one level |
| `typeContains` | `NugetGenerateBindingsTask.kt` | one level, and its comment says so |
| `structEnumComponents` | **both** generators | one level |
| `componentRead` | `NugetGenerateBindingsTask.kt` | `error()`s on a struct |
| `argConversion` path building (4 sites) | `NugetGenerateBindingsTask.kt` | one level (`"${p.name}.${c.name}"`) |
| `structConstruction` | `NugetGenerateShimsTask.kt` | one level |
| `csReturnConversion` read-back path | `NugetGenerateShimsTask.kt` | one level (`result.${c.readName}`) |
| `cVarType`, `csAbiType`, `csReturnConversion`, `paramConversion`, `thunkParamName` | both | `error()` on a struct (correct; only the messages need rewording) |

The four `argConversion` path-building sites in the Kotlin generator (verified at lines 667-674,
872-874, 1965-1967, 2039-2041) are four copies of the same one-level flatten. They should collapse into
**one** recursive helper as part of this work; leaving four copies of a *recursive* walk is how the two
sides drift.

## Consequences

### New/changed artifacts

- `nuget-metadata-reader/Program.cs`: fixed-point struct classification; component decoders constructed
  with the struct map; recursive, path-naming `skipped_unsupported_struct` reasons; both shapes' reasons
  reported on a double failure.
- `RirModel.kt`: **no schema change**. One new diagnostic kind, `SKIPPED_ABI_ARITY_LIMIT`.
- `RirBridging.kt`: `abiArgs` / `abiOutArgs` / `structReceiverAbiArgs` become recursive (DFS pre-order,
  `_`-joined path names); an ABI-name distinctness assertion; the 22-argument ceiling check inside
  `bridgeableRegistrables` / `bridgeableStructRegistrables`, emitting `skipped_abi_arity_limit`.
  `signaturePart` / `contractHash`: **no change** (already recursive, verified).
- `NugetGenerateBindingsTask.kt`: recursive `typeContains`, `structEnumComponents`, `componentRead`, and
  one shared recursive path-expression builder replacing the four one-level copies; `structFileContent`
  must render a component's type with `declKotlinType(type, qualifiedTypeNames)` and import a nested
  struct declared in another Kotlin package (today it renders a bare `kotlinType(c.type)` with no import,
  verified).
- `NugetGenerateShimsTask.kt`: recursive `structConstruction` and recursive out-pointer write-back paths;
  recursive `structEnumComponents`; a `using` for a **struct** component's C# namespace (it emits one for
  a referenced enum today, and only for an enum), so a thunk can name a nested struct declared in another
  namespace; reworded `error()` messages on `cVarType` / `csReturnConversion`.
- `sample-library/build.gradle.kts`: `include`/`alias` for the new `Sample.Nested` namespace.
- Fixtures: see Test Design.

### Behavioural changes

- A struct with a struct-typed component **binds** where it was previously skipped. No shipped fixture
  has one, so nothing regresses.
- A member whose flattened ABI arity exceeds 22 is **now skipped** where it would previously have
  produced Kotlin that does not compile. No shipped fixture is near the limit (the largest today is
  `Collars.Pair(Collar, Extent)` at 7).
- `skipped_unsupported_struct` **reason strings change** for any struct that fails both shapes (they now
  carry both attempts and the component path). Tests asserting exact reason text must be updated. In
  particular `Overstuffed`'s text changes again.
- No `contractHash` changes for existing types (their component trees are unchanged).

### Verified vs. inferred

**Verified** (commands and real output in Context; scratch `mktemp -d` throughout; TFM `net8.0`,
SDK 10.0.300, .NET 10.0.8 runtime, Kotlin/Native 2.4.0 macOS arm64):

- CS0523 rejects direct, mutual, **generic-mediated** and **`Nullable<T>`-mediated** struct containment.
  A cycle through a **class**, an **array**, or a **static field** is legal C# and compiles.
- `CPointer<CFunction<...>>.invoke` accepts at most **22** arguments; a 23-argument `CFunction` type is
  *declarable* but its `invoke` does not resolve ("none of the following candidates is applicable").
- Recursive DFS pre-order flattening, recursive reconstruction, and recursive out-pointer write-back
  compile and round-trip through a real C-ABI function pointer inside `[UnmanagedCallersOnly]`, for
  **A-in-A, B-in-A, A-in-B, B-in-B**, at **depth 3**, with a `string` leaf inside the nested struct.
- A Shape B outer reconstructs correctly with struct components arriving through **all three** Shape B
  sources at once (public field, settable auto-property, `init`-only auto-property).
- The repository's **current, unmodified** metadata reader skips a struct with a struct-typed component,
  reports a **misleading reason** for it (a Shape B auto-property rule for a Shape A struct; "not a
  bridgeable bound class" for a struct), and transitively drops every method that mentions it, leaving
  the host class with zero methods.
- Repository source: `RirStructComponent.type` is a `RirTypeRef` that already admits `RirStructType`, so
  the RIR JSON schema does not change; `signaturePart()` already recurses through struct components;
  `CollectStructTypes` already runs a second pass for the same class of reason; the component decoder is
  deliberately constructed with a `null` struct map; `typeContains` and `structEnumComponents` are
  one-level-only by design; `structFileContent` renders a component's Kotlin type with a bare, unimported
  `kotlinType(c.type)`; `ExtractStruct` discards the Shape A failure reason when it falls through to
  Shape B.

**Inferred** (not verified against a real artifact; the walking skeleton must falsify each):

- **That the reader, once its component decoder is given the struct map, emits `RirStructType` for a
  struct-typed component.** `SignatureDecoder.GetTypeFromDefinition` has the branch and it is reached
  before the bound-handle check (read in source), but the spike ran the reader with the map absent, which
  is the current behaviour, not the proposed one. This is a small extrapolation, and it is the first
  thing the walking skeleton proves.
- **That the full topology works for a nested struct** (Kotlin/Native dylib loaded by a .NET host,
  registered through a `@CName` export). ADR-056 verified that topology for a one-level struct; the delta
  here is purely "more scalar arguments in the same slot", and Constraint 4 verified the C# half through
  a real C-ABI function pointer. But no spike has run a *nested* struct through the real dylib.
- **That the fixed-point classification converges in the passes claimed** for real-world packages. It
  terminates by construction; the pass count is inferred to be small (equal to maximum nesting depth).
- **That 22 is the ceiling on Kotlin/Native versions other than 2.4.0.** It is a `kotlinx.cinterop`
  implementation limit, not a C ABI or ECMA-335 limit, and it could move. The check should read as a
  named constant.
- **That a cyclic value type could reach the reader at all.** C# cannot emit one (verified); the CLR
  would reject it at type-load (inferred from the ECMA-335 layout rules, not spiked). The fixed point
  makes this moot, which is why it is the chosen design rather than a recursion with a guard.

### Scope

**In v1 (this ADR):**

- A struct-typed component on **both** shapes (A and B), in **all four** nesting combinations.
- **Arbitrary depth**, bounded by the ABI arity ceiling, not by a depth rule.
- Recursive `abiArgs` / `abiOutArgs` / `structReceiverAbiArgs`, consumed unchanged by both generators.
- Recursive reconstruction (C#) and recursive reassembly (Kotlin), each struct using its own shape.
- Nested struct components on struct **methods**, **computed properties**, **alternate constructors**,
  and on class **instance/static methods** and **struct-typed properties** (all of which already route
  through the shared expansion).
- The 22-argument ceiling check, with `skipped_abi_arity_limit`.
- ABI-name distinctness assertion.
- Path-naming diagnostics, and both-shape failure reasons.

**Deferred:**

- **Class-typed (handle) components**: unchanged, still deferred, still on semantics (ADR-056). Worth
  restating: a handle component would be a **leaf** in this flattening (an `IntPtr`), so it is
  mechanically trivial and semantically still wrong (`copy()` would duplicate `Cleaner`-managed
  ownership). Nesting does not change that argument in either direction.
- **Raising the 22-argument ceiling** (Decision 5d: a packed scratch buffer for the overflow). Needs its
  own decision.
- **`Nullable<T>` components**, including a nullable *nested struct* (`Profile?` inside a `Litter`). The
  out-pointer + `byte hasValue` format ADR-056 prescribed extends to it, but it stays its own ROADMAP
  slice.
- **Shape A constructor-parameter nullability decoding** (the pre-existing ADR-056 gap ADR-058 surfaced).
  Nesting does not create a new failure mode, but it **widens the surface**: a Shape A outer with a Shape
  B inner mixes nullability-decoded components (the inner's) with undecoded ones (the outer's) in one
  flat argument list, which is confusing to reason about even though it is not incorrect. A `string?`
  component of a Shape A struct still binds non-null and still fails fast at the bridge rather than
  corrupting. This should be closed soon, and it is a small change; it is not in this ADR's scope because
  it is orthogonal to nesting and has its own ROADMAP line.
- **Generic structs**, **structs as collection elements**, **`ref struct` / `Span<T>`**, **manual
  settable properties**, **alternate constructors on Shape B structs**: all unchanged.

## Test Design

The fixture must cross **every mechanism, not the fewest types**. The seam this feature is about is
*conversion inside a nested struct*, so the nested struct must contain a component that needs
marshalling conversion (a `string`, a `char`, a `bool`, a bound enum) **and** one that does not (an
`int`). A generator that open-codes a conversion at the wrong level (converting at the outer level, or
forgetting to convert a leaf reached through a path) must fail. It must also cross the **shape**
boundary in both directions, and go **deeper than one level**.

### `sample-dependency/Litter.cs` (new, namespace `Sample.Structs`)

Reuses the existing leaves deliberately: `Profile` is Shape A with the full converted vocabulary
(`string`/`bool`/`char`/enum) **and its own members and registration slots**, so nesting a struct that
already owns a register export is proven; `Extent` is Shape B with direct-only `int` components;
`Point` is Shape A with direct-only components; `Collar` is Shape B with the mixed vocabulary.

```csharp
using Sample.Enums;

namespace Sample.Structs;

/// Shape A OUTER with a Shape A nested component (Profile: the CONVERTED vocabulary, string/bool/
/// char/enum) and a Shape B nested component (Extent: DIRECT ints), plus a direct int and a
/// converted enum at the outer level. This is A-in-A and B-in-A in one type, and it is the type
/// that fails if conversion is open-coded at the wrong nesting level.
/// Flattens to 8 leaves: Mother{Tag,Active,Grade,Mood}, Basket{Width,Height}, Count, Mood.
public readonly struct Litter
{
    public Litter(Profile mother, Extent basket, int count, CatMood mood)
    {
        Mother = mother; Basket = basket; Count = count; Mood = mood;
    }

    public Profile Mother { get; }   // nested Shape A, converted components
    public Extent Basket { get; }    // nested Shape B, direct components
    public int Count { get; }
    public CatMood Mood { get; }

    /// Computed property on a NESTING struct: the receiver is reconstructed from 8 flattened
    /// leaves before the getter runs.
    public string Summary => $"{Mother.Tag}x{Count}@{Basket.Width}x{Basket.Height}/{Mood}";

    /// Instance method on a nesting struct: nested receiver in (8 leaves), nested struct out
    /// (8 out-pointers), plus an ordinary parameter. 8 + 1 + 8 = 17 args, under the ceiling.
    public Litter Grow(int by) =>
        new Litter(Mother, new Extent { Width = Basket.Width + by, Height = Basket.Height + by },
                   Count + by, Mood);

    /// Static factory on a nesting struct -> Kotlin companion object.
    public static Litter Single(string tag) =>
        new Litter(Profile.Resting(tag), Extent.Unit(), 1, CatMood.Sleepy);
}

/// Shape B OUTER with struct components arriving through ALL THREE Shape B component sources:
/// a public FIELD, a settable auto-property, and an INIT-only auto-property. A-in-B and B-in-B.
/// Flattens to 5 + 2 + 2 + 1 = 10 leaves.
public struct Nest
{
    public Collar Collar;                  // nested Shape B via a public FIELD          (B-in-B)
    public Point Centre { get; set; }      // nested Shape A via a settable auto-prop    (A-in-B)
    public Extent Bounds { get; init; }    // nested Shape B via an INIT-only auto-prop  (B-in-B)
    public bool Lined { get; set; }        // converted scalar at the outer level

    /// Computed property on a Shape B nesting struct (object-initializer receiver reconstruction).
    public string Tag => $"{Collar.Colour}/{Centre.X},{Centre.Y}/{Bounds.Area}/{Lined}";
}

public static class Litters
{
    /// Nested struct in, string out.
    public static string Describe(Litter l) => l.Summary;

    /// Nested struct in AND out (8 + 1 in, 8 out-pointers).
    public static Litter Grow(Litter l, int by) => l.Grow(by);

    /// TWO struct parameters with DIFFERENT nesting depths in one signature (8 + 2 = 10 leaves):
    /// where an abiArgs expansion bug shows up as a misaligned argument rather than a type error.
    public static string Compare(Litter a, Extent b) => $"{a.Basket.Area}vs{b.Area}";

    /// ADVERSARIAL, and the reason Decision 5 exists: 8 + 8 in, 8 out = 24 ABI arguments, over the
    /// verified 22-argument CFunction ceiling. Must be SKIPPED with `skipped_abi_arity_limit`,
    /// naming this method and the count. It must NOT appear in the generated Kotlin, and the rest
    /// of `Litters` must still bind.
    public static Litter Merge(Litter a, Litter b) =>
        new Litter(a.Mother, b.Basket, a.Count + b.Count, b.Mood);
}

/// A bound handle CLASS (ADR-051) with a nested struct on both member kinds: a settable
/// struct-typed PROPERTY whose components nest (getter = out-pointers, setter = in-args), and an
/// instance method taking and returning a nesting struct.
public class Shelter
{
    public Nest Current { get; set; } = new Nest
    {
        Collar = Collar.Plain("none"), Centre = new Point(0, 0),
        Bounds = Extent.Unit(), Lined = false,
    };

    public Litter Admit(Litter l) => l.Grow(1);
}
```

### `sample-dependency/Nursery.cs` (new, namespace `Sample.Nested`)

Deliberately a **different C# namespace**, therefore a **different Kotlin package**. This is not
decoration: it is the only way to exercise the two import sites nesting newly creates, and neither is
reachable from a same-package fixture.

- The generated Kotlin `Nursery.kt` (package `sample.nested`) declares `val litter: Litter`, whose type
  lives in `sample.structs`. `structFileContent` renders a component's type with a bare, unimported
  `kotlinType(c.type)` today (**verified by reading the source**), so a same-package fixture would pass
  while the cross-package case emits an unresolvable type name.
- The generated `NurseryRegistration.cs` reconstructs `new Litter(...)` inside a thunk that renders in
  `namespace Sample.Nested`, so the C# generator needs a `using` for a **struct**'s namespace.
  `referencedEnumTypes` collects `RirEnumType` and nothing else (**verified by reading the source**);
  there is no struct equivalent. Strictly this is a **pre-existing** gap (a class today could already
  reference a struct from another namespace), but no shipped fixture does, and nesting makes it routine.

```csharp
using Sample.Structs;

namespace Sample.Nested;

/// DEPTH 2, ACROSS A PACKAGE BOUNDARY: a struct whose component is itself a nesting struct
/// (Nursery -> Litter -> Profile -> string), declared in a different namespace from the struct it
/// nests. Proves (a) the recursion is a real tree walk, not a special-cased single level, and
/// (b) both generators import/`using` a nested struct declared elsewhere. Flattens to 9 leaves.
public readonly struct Nursery
{
    public Nursery(Litter litter, int room) { Litter = litter; Room = room; }

    public Litter Litter { get; }
    public int Room { get; }
}

public static class Nurseries
{
    /// Depth-2 struct in AND out (9 leaves each way, 18 args, under the ceiling).
    public static Nursery Rehome(Nursery n) => new Nursery(n.Litter.Grow(1), n.Room + 1);

    /// Depth-2 struct in, string out: proves the deep read path (`n.Litter.Mother.Tag`).
    public static string Trace(Nursery n) => $"{n.Litter.Mother.Tag}@{n.Room}";
}
```

`sample-library/build.gradle.kts` gains `include("Sample.Nested")` and
`alias("Sample.Nested", "sample.nested")` in the `SampleDependency` `bind {}` block. (This is the only
build-file change the feature needs.)

### `sample-dependency/UnsupportedStructs.cs` (one new negative)

```csharp
/// ADVERSARIAL (Decision 6c): an outer struct whose nested component is itself unsupported
/// (`Manual` is the existing manual-settable-property negative). The WHOLE outer struct must be
/// skipped, and the diagnostic must name the offending component PATH and the inner reason, not
/// one of Kennel's own shape rules. It must generate no Kotlin type and no handle wrapper.
public readonly struct Kennel
{
    public Kennel(Manual manual, int n) { Manual = manual; N = n; }
    public Manual Manual { get; }
    public int N { get; }
}
```

### `sample-library` consumer (new `structs/NestedSample.kt`)

```kotlin
// The Kotlin surface stays NESTED even though the wire is flat.
fun litterSummary(tag: String, count: Int): String =
  Litters.describe(
    Litter(
      mother = Profile(tag, true, 'A', CatMood.Playful),   // nested Shape A, converted vocabulary
      basket = Extent(3, 4),                                // nested Shape B, direct vocabulary
      count = count,
      mood = CatMood.Calm,
    )
  )

// Nested in AND out: reassembled from a flat out-pointer list back into a nested data class.
// Distinct values per leaf, so a DFS-order bug cannot pass by coincidence.
fun growLitter(tag: String, count: Int, by: Int): String {
  val grown: Litter = Litters.grow(
    Litter(Profile(tag, true, 'A', CatMood.Playful), Extent(3, 4), count, CatMood.Calm), by,
  )
  return "${grown.mother.tag}|${grown.mother.grade}|${grown.mother.mood}|" +
      "${grown.basket.width}x${grown.basket.height}|${grown.count}|${grown.mood}"
}

// DEPTH 2, both directions, and ACROSS a Kotlin package boundary: Nursery is `sample.nested`,
// its `litter` component is `sample.structs`. Compiles only if the generated Nursery.kt imports it.
fun rehome(tag: String, room: Int): String {
  val moved: Nursery = Nurseries.rehome(
    Nursery(Litter(Profile(tag, false, 'Z', CatMood.Sleepy), Extent(1, 2), 3, CatMood.Calm), room),
  )
  return "${moved.litter.mother.tag}/${moved.litter.basket.height}/${moved.litter.count}/${moved.room}"
}

// Two struct parameters of different nesting depth in one signature.
fun compareLitter(tag: String, w: Int, h: Int): String =
  Litters.compare(Litter(Profile(tag, true, 'A', CatMood.Calm), Extent(2, 3), 1, CatMood.Calm), Extent(w, h))

// Members on a nesting struct: computed property, instance method, companion static.
fun litterMembers(tag: String): String {
  val l = Litter(Profile(tag, true, 'A', CatMood.Playful), Extent(2, 3), 4, CatMood.Calm)
  return "${l.summary}|${l.grow(1).count}|${Litter.single(tag).count}"
}

// Shape B outer, all three nested component sources, through a HANDLE class's settable struct
// property (setter = flattened in-args; getter = flattened out-pointers).
fun shelterNest(colour: String, x: Int, y: Int): String = Shelter().use { shelter ->
  shelter.current = Nest(
    collar = Collar(2, colour, true, 'B', CatMood.Playful),
    centre = Point(x, y),
    bounds = Extent(5, 6),
    lined = true,
  )
  "${shelter.current.tag}|${shelter.current.collar.colour}|${shelter.current.centre.x}"
}

// Value semantics survive nesting: equality is DEEP, and there is still nothing to close().
fun nestedValueEquality(): Boolean {
  val a = Litter(Profile("o", true, 'A', CatMood.Calm), Extent(1, 2), 3, CatMood.Calm)
  return a == a.copy() &&
      a.mother == a.copy().mother &&
      a != a.copy(count = 4) &&
      a != a.copy(mother = a.mother.copy(tag = "p"))     // a change two levels down is observable
}
```

`sample-library/build.gradle.kts` gains the `Sample.Nested` include/alias (above); `Sample.Structs` is
already bound.

### `SampleApp.Tests`

One assertion per `sample-library` function above (the ADR-050 round trip). **Every leaf must get a
distinct value**, so that a DFS-order bug, an off-by-one in the out-pointer list, or a conversion
applied at the wrong level cannot pass by coincidence. Concretely: no two `int` leaves in the same call
may share a value, and the two `CatMood` leaves in a `Litter` (`Mother.Mood` and `Mood`) must differ.

### Reader / generator tests

- Reader: `Litter` yields components `[mother: struct Profile, basket: struct Extent, count: int,
  mood: enum]`, with the struct references intact (not pre-flattened).
- Reader: `Nursery` resolves at depth 2, which requires the fixed point to run at least two passes.
- Reader: `Kennel` yields exactly one `skipped_unsupported_struct` whose reason names the component
  **path** and the inner reason, and generates no Kotlin type.
- Reader: a struct that fails Shape A *and* Shape B reports **both** reasons (the `Card` regression from
  Constraint 1).
- `abiArgs`: `Litter` expands to exactly 8 leaves, in DFS pre-order, named
  `l_Mother_Tag, l_Mother_Active, l_Mother_Grade, l_Mother_Mood, l_Basket_Width, l_Basket_Height,
  l_Count, l_Mood`.
- `abiOutArgs`: the same 8, prefixed `out`, in the same order.
- `structReceiverAbiArgs`: the same 8, unprefixed.
- ABI-name distinctness: a **synthetic** RIR struct with a component named `Tag_Text` alongside a nested
  `Tag` component fails generation, naming both. (Synthetic, not a fixture: this is an `error_*`, and an
  `error_*` in a shipped fixture would fail the build. Same precedent as ADR-057's collision test.)
- Arity ceiling: `Litters.Merge` produces exactly one `skipped_abi_arity_limit` naming the method and
  the count (24); `Litters.Grow` (17) does not. Both generators must drop it, so assert the **slot count
  and contract hash agree** between the two generated sides with `Merge` absent.
- `contractHash`: adding a component to `Profile` changes the hash of `Litters.Describe` (which never
  mentions `Profile` directly). This is the nesting version of ADR-056's load-bearing property and it
  should pass without a code change; pin it.
- Shim generator: a `Litter` parameter reconstructs as `new Litter(new Profile(...), new Extent { ... },
  ..., ...)`, that is, an inner ctor call and an inner object initializer inside one outer ctor call; a
  `Nest` parameter reconstructs as `new Nest { Collar = new Collar { ... }, Centre = new Point(...),
  Bounds = new Extent { ... }, Lined = ... }`.
- Kotlin generator: the `Litter` data class declares `val mother: Profile` (not eight flattened
  components), and the stub's `fn.invoke(...)` passes `l.mother.tag.cstr.ptr` (the leaf's own
  conversion, applied through a path), never a raw `l.mother`.
- Cross-package imports: the generated `Nursery.kt` (package `sample.nested`) contains
  `import sample.structs.Litter`, and the generated `NurseryRegistration.cs` contains
  `using Sample.Structs;`. Both fail on `main` today, and neither is reachable from a same-package
  fixture, which is why `Nursery` lives in its own namespace.

## Sources

- [CS0523: struct member causes a cycle in the struct layout](https://learn.microsoft.com/en-us/dotnet/csharp/misc/cs0523)
- [C# struct types](https://learn.microsoft.com/en-us/dotnet/csharp/language-reference/builtin-types/struct)
- [Blittable and non-blittable types](https://learn.microsoft.com/en-us/dotnet/framework/interop/blittable-and-non-blittable-types)
- [Marshalling classes, structures, and unions](https://learn.microsoft.com/en-us/dotnet/framework/interop/marshalling-classes-structures-and-unions)
- [`UnmanagedCallersOnlyAttribute`](https://learn.microsoft.com/en-us/dotnet/api/system.runtime.interopservices.unmanagedcallersonlyattribute)
- [Object and collection initializers (C#)](https://learn.microsoft.com/en-us/dotnet/csharp/programming-guide/classes-and-structs/object-and-collection-initializers)
- [Kotlin/Native C interop](https://kotlinlang.org/docs/native-c-interop.html)
- [Kotlin: calling Java from Kotlin](https://kotlinlang.org/docs/java-interop.html)
- [rust-bindgen](https://rust-lang.github.io/rust-bindgen/)
- [JNA `Structure`](https://java-native-access.github.io/jna/5.13.0/javadoc/com/sun/jna/Structure.html)
