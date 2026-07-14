# ADR-058: C# Shape B structs in Kotlin: settable state is the component model, object-initializer reconstruction, FieldDef declaration order

## Status

Accepted

## Context

ROADMAP line 163, Phase 9:

> Shape B structs: no public constructor (public fields or settable auto-properties). Needs field
> extraction, which the metadata reader does not do today (ADR-056 Scope)

[ADR-056](056-csharp-structs-in-kotlin.md) settled everything about *how a struct crosses*: component
decomposition (one ABI argument per component in, one out-pointer per component out), an immutable
Kotlin `data class` surface, no handle, no `Cleaner`, no layout. It settled *which* structs are
bridgeable only for **Shape A**: a struct with a public constructor whose parameters cover all of its
stored state. It explicitly deferred **Shape B**, the struct with no such constructor:

> `public struct PublicFields { public int A; public int B; }` and `public struct Mutable { public
> int A { get; set; } … }`. Reconstruction would be an object initializer (`new T { A = a, B = b }`),
> component order the metadata declaration order.
>
> **Deferred, not rejected.** It is cheap on the C# side, but: the reader **does not extract public
> fields at all today**, so it needs new extraction; metadata declaration order becomes an
> ABI-relevant contract; and it interacts with the `init`-setter landmine (Constraint 3.3).

Those three concerns are the whole of this ADR. Two of them turn out to be non-problems once probed;
the third (order) is real, and it is why this is a decision rather than an implementation note.

### Why this is a new ADR and not another ADR-056 follow-up section

ADR-056's "Follow-up design: members" section shipped without a new ADR number, because ADR-056 had
already *prescribed* the mechanism ("reconstruct the value class per call") and the follow-up only
implemented it. Shape B is different on three counts:

1. **It amends an accepted decision.** ADR-056 Decision 3a rule 3 requires every constructor
   parameter to match a public readable *property*. This ADR widens that to *property or public
   instance field*. Without that amendment, an ordinary `struct { public int A; public int B; public
   S(int a, int b) {...} }` falls through to Shape B, its constructor becomes an
   [ADR-057](057-csharp-overload-sets-in-kotlin.md) alternate constructor, and its Kotlin signature
   collides with the generated `data class` primary constructor, which ADR-057 Decision 4 turns into
   a **hard build failure**. Amending an Accepted ADR in place hides that from the index.
2. **It amends ADR-054's contract hash** (see Decision 5): `signaturePart()` expands a struct
   reference to its component **types only**, so a reorder of two same-typed components is invisible
   to the stale-shim check. Shape B makes such a reorder a *source-compatible* upstream change,
   which promotes a latent hole into a plausible one.
3. **Component order becomes public Kotlin API derived from something C# does not treat as API.**
   That is a genuine decision with genuine alternatives, and it is the one place where the Kotlin
   binding can be *less* stable than the C# original.

### Constraint 1 (verified): the reader extracts no fields, and what the metadata really holds

A scratch probe (`mktemp -d`, `dotnet new classlib`, TFM `net8.0`, built with SDK 10.0.300) declared
thirteen Shape B candidate shapes and decoded the built `.dll` with `System.Reflection.Metadata`:

```
$ dotnet run -c Release -- .../ShapeB.dll
=== PublicFields  attrs=Public, SequentialLayout, Sealed, BeforeFieldInit
   FIELD row=4 A                        attrs=Public cattr=[]
   FIELD row=5 B                        attrs=Public cattr=[]
=== Mutable
   FIELD row=6 <A>k__BackingField       attrs=Private cattr=[System.Runtime.CompilerServices.CompilerGeneratedAttribute]
   FIELD row=7 <S>k__BackingField       attrs=Private cattr=[System.Runtime.CompilerServices.CompilerGeneratedAttribute]
   PROP  row=1 A     getter=True static=False setter=(Public sigHex=20010108)
   PROP  row=2 S     getter=True static=False setter=(Public sigHex=2001010E)
=== InitOnly
   FIELD row=8 <A>k__BackingField       attrs=Private, InitOnly cattr=[...CompilerGeneratedAttribute]
   PROP  row=3 A     getter=True static=False setter=(Public sigHex=20011F4D0108)
=== Mixed          # public int A; public bool Flag {get;set;} public char G {get;init;} public Mood M {get;init;}
   FIELD row=10 A                        attrs=Public cattr=[]
   FIELD row=11 <Flag>k__BackingField    attrs=Private cattr=[...CompilerGeneratedAttribute]
   FIELD row=12 <G>k__BackingField       attrs=Private, InitOnly cattr=[...CompilerGeneratedAttribute]
   FIELD row=13 <M>k__BackingField       attrs=Private, InitOnly cattr=[...CompilerGeneratedAttribute]
=== ReadonlyField  # public readonly int A; public int B;
   FIELD row=14 A                        attrs=Public, InitOnly cattr=[]
   FIELD row=15 B                        attrs=Public cattr=[]
=== HiddenState    # private int _h; public int A { get; set; }
   FIELD row=19 _h                       attrs=Private cattr=[]
   FIELD row=20 <A>k__BackingField       attrs=Private cattr=[...CompilerGeneratedAttribute]
=== WithStatics    # public static readonly WithStatics Empty; public const int Max = 9; public int A;
   FIELD row=21 Empty                    attrs=Public, Static, InitOnly cattr=[]
   FIELD row=22 Max                      attrs=Public, Static, Literal, HasDefault cattr=[]
   FIELD row=23 A                        attrs=Public cattr=[]
=== ManualProp     # private int _a; public int A { get => _a; set => _a = value; }
   FIELD row=24 _a                       attrs=Private cattr=[]
   PROP  row=9 A     getter=True static=False setter=(Public sigHex=20010108)
=== Empty          # (no rows at all)
```

and, with the attribute masks applied explicitly:

```
FieldAttributes.FieldAccessMask = 0x7
=== Mixed
   A                        public=True  static=False initOnly=False literal=False
   <Flag>k__BackingField    public=False static=False initOnly=False literal=False
   <G>k__BackingField       public=False static=False initOnly=True  literal=False
=== WithStatics
   Empty                    public=True  static=True  initOnly=True  literal=False
   Max                      public=True  static=True  initOnly=False literal=True
   A                        public=True  static=False initOnly=False literal=False
```

**Verified.** Six facts, each load-bearing:

1. **An auto-property's backing field is `Private` and carries `[CompilerGenerated]`**, named
   `<X>k__BackingField`. An accessibility filter alone (`(attrs & FieldAccessMask) == Public`)
   excludes every backing field from the "real public field" set. The name pattern is only needed to
   *attribute* a backing field to its property, not to exclude it.
2. **`TypeDefinition.GetFields()` yields FieldDef-table order, which is C# declaration order, and it
   interleaves real fields with backing fields correctly.** `Mixed` came back as `A`, `<Flag>`,
   `<G>`, `<M>`: exactly the source order of `public int A; public bool Flag {get;set;} public char G
   {get;init;} public Mood M {get;init;}`. This is the single fact that makes one unified component
   order across fields *and* auto-properties recoverable from one pass.
3. **`const` and `static readonly` fields are `Static`.** The existing non-static filter (already
   used by ADR-056 rule 5) excludes both for free. No separate `Literal` check is needed.
4. **A `readonly` public field is `InitOnly`**, distinguishable from a settable one.
5. **An `init`-only auto-property is distinguishable in metadata.** Its setter's signature blob
   carries a required custom modifier on the *return* type: `20 01 1F 4D 01 08` versus a plain
   setter's `20 01 01 08`. `0x1F` is `ELEMENT_TYPE_CMOD_REQD`; the coded index `0x4D` decodes to
   TypeRef row 19, which the probe resolved:
   `TypeRef[19] = System.Runtime.CompilerServices.IsExternalInit`. Shape B does not need this (see
   Constraint 2), but it settles ADR-056's Constraint 3.3 landmine as *detectable*.
6. **A manual (non-auto) property has no `<X>k__BackingField`.** Its store is an ordinary private
   field (`ManualProp._a`) with no discoverable relationship to the property. There is no way, from
   metadata alone, to prove that setting `A` covers `_a`.

### Constraint 2 (verified): object-initializer reconstruction works for every Shape B shape, cross-assembly, including `init`

A second scratch assembly referenced the first and reconstructed each shape. It compiled and ran:

```
$ dotnet run -c Release
pf=1,2 mu=3,s io=4,t mx=5,True,Z,Playful bc=6,7 pc=8 pcDefault=7
```

from

```csharp
var pf = new PublicFields { A = 1, B = 2 };                                    // public fields
var mu = new Mutable      { A = 3, S = "s" };                                  // settable auto-props
var io = new InitOnly     { A = 4, S = "t" };                                  // INIT-only, from another assembly
var mx = new Mixed        { A = 5, Flag = true, G = 'Z', M = Mood.Playful };   // field + set + init + char + enum
var bc = new BWithCtor    { A = 6, B = 7 };                                    // struct whose ONLY ctor takes 2 args
var pc = new ParamlessCtor{ A = 8 };                                           // explicit public parameterless ctor (C# 10)
var pcDefault = new ParamlessCtor();                                           // ...which runs, then the initializer wins
```

**Verified**, and three consequences:

- **The `init` landmine is defused by the choice of reconstruction syntax, not by detecting it.**
  An object initializer is *exactly* the context in which an `init` accessor is callable, and it is
  callable across assembly boundaries. So Shape B needs no `IsExternalInit` detection: `set` and
  `init` are both simply "settable".
- **A struct with a non-parameterless constructor is still object-initializable** (`bc=6,7`), because
  `new S()` is always available for a value type. And a struct with an *explicit* public parameterless
  constructor runs it first, then the initializer overwrites (`pc=8`, `pcDefault=7`). Since Shape B
  requires every stored field to be a component, the initializer overwrites all of it: the result is
  fully determined either way.
- The negative controls fail exactly where the rules need them to:

```
error CS0191: A readonly field cannot be assigned to ...        # new ReadonlyField { A = 1 }
error CS8852: Init-only property or indexer 'InitOnly.A' can only be assigned
              in an object initializer ...                       # io.A = 9
```

**Verified.** CS0191 is why a `public readonly` field can never be a Shape B component. CS8852 is an
*adjacent* finding, recorded below.

### Adjacent finding (verified, out of scope): an `init`-only property on a bound C# **class** generates a shim that does not compile

ADR-056 Constraint 3.3 already noted that the reader reports `init` setters as settable. For a bound
C# *class*, `RirProperty.isReadOnly = false` makes the generators emit a Kotlin `var` plus a setter
thunk whose body is `target.Prop = value;`. Constraint 2 shows that is **CS8852**. It is a shim
*compile* failure, not silent corruption, and no shipped fixture has an `init`-only class property, so
nothing is broken today. The fix is the modreq detection Constraint 1.5 verified. Filed as a ROADMAP
bug, **not fixed here** (Shape B does not need it).

### Prior art

- **Kotlin consuming Java.** Java has no structs; the analogue is a mutable JavaBean or a `record`.
  Kotlin surfaces a `record`'s components as `val`s and a bean's `getX`/`setX` pair as a `var`
  property. The `var` is safe there because the Java object is *the same object* (a reference).
  Here it is not: the value was copied across the boundary. This is precisely why ADR-056 Decision 2b
  rejected `var`, and Shape B is where the temptation is strongest, because the C# type really is
  mutable. ([Java interop](https://kotlinlang.org/docs/java-interop.html))
- **Kotlin/Native cinterop with C structs.** cinterop exposes every C struct field as a `var` on a
  `CStructVar` because the struct lives in native memory and the write goes back to that memory. The
  mutability is honest *because* there is shared memory. Decomposition has no shared memory, so the
  same surface would be a lie.
  ([Kotlin/Native C interop](https://kotlinlang.org/docs/native-c-interop.html))
- **.NET for Android / Xamarin binding libraries** (the mirror image: C# consuming Java) surface a
  Java field as a C# property on the *peer* object, which again is shared state, not a copy.
  ([Binding a Java library](https://learn.microsoft.com/en-us/dotnet/android/binding-libs/))
- **C# records and object initializers.** C#'s own answer to "immutable data carrier with named
  construction" is `init` + object initializers, and `with` for non-destructive mutation. Kotlin's
  answer is a `data class` with `copy()`. They are the same idea, and the mapping is exact.
  ([Object initializers](https://learn.microsoft.com/en-us/dotnet/csharp/programming-guide/classes-and-structs/object-and-collection-initializers),
  [init accessors](https://learn.microsoft.com/en-us/dotnet/csharp/language-reference/keywords/init))

## Alternatives Considered

### Decision 1: classification precedence, and what a Shape A component may match

#### 1a. Shape A first, widened to match public fields; Shape B only when no constructor covers state (chosen)

Amend ADR-056 Decision 3a **rule 3** from "matches a public readable instance property" to "matches a
public readable instance **property or public instance field**", case-insensitively. Rule 5 (stored
state coverage) is unchanged. Shape B applies **only** when no public instance constructor passes the
Shape A rules.

**Pros:**

- Kills a hard-fail on an ordinary struct. Without it,
  `struct S { public int A; public int B; public S(int a, int b) { A = a; B = b; } }` fails Shape A
  rule 3 (no *property* named `a`), falls to Shape B, and its constructor becomes an ADR-057
  **alternate** constructor rendering `constructor(a: Int, b: Int)` next to the generated `data class
  S(val a: Int, val b: Int)` primary. Same Kotlin collision key, and ADR-057 Decision 4 says: fail
  generation. A perfectly ordinary C# struct would break the build.
- Reading a field and reading a property are **the same C# expression** (`result.A`). The shim
  generator's read-back path (`csReturnConversion(c.type, "result.${c.readName}")`) needs no change at
  all: `readName` just sometimes names a field.
- The widening is purely additive against the shipped fixtures (`Point`, `Size`, `Profile`, `Metrics`,
  `Overstuffed` all use properties), so no existing binding changes.
- Keeps constructor-parameter order (which *is* C# public API) as the component order whenever a
  constructor exists, so Decision 3's order hazard applies only where it must.

**Cons:** ADR-056 Decision 3a's rule text changes. Accepted: the rule was written before the reader
could see fields at all.

#### 1b. Leave Shape A alone; let the fields-plus-constructor struct fall to Shape B (rejected)

**Rejected**: produces the `error_kotlin_signature_collision` hard failure described above for a
completely ordinary shape. Fixing it *inside* Shape B would mean silently dropping an alternate
constructor that collides with the primary, which is exactly the "silently select one overload"
behaviour ADR-057 Decision 5 forbids.

#### 1c. Shape B takes precedence whenever the struct has settable public state (rejected)

**Rejected**: it would change the component *order* of an already-shipped Shape A binding (from
constructor-parameter order to declaration order) for any struct that has both. A source-compatible
change to this project would silently reorder a consumer's positional constructor arguments.

---

### Decision 2: what counts as a Shape B component

#### 2a. Public settable fields and settable public **auto**-properties, unified by their FieldDef row (chosen)

Walk `typeDef.GetFields()` **once, in order**. For each **non-static** field, exactly one of three
things must be true:

1. it is **public** and **not `InitOnly`** → it is a component; `readName` is the field name;
2. it is a **`<X>k__BackingField`** carrying `[CompilerGenerated]`, and property `X` is a public,
   non-static property with a public getter **and** a public setter (`set` or `init`) → it is a
   component; `readName` is `X`;
3. otherwise → the struct has **stored state that no component covers**, and it is **skipped** with a
   `skipped_unsupported_struct` diagnostic naming the field.

This is ADR-056 rule 5 restated *structurally* instead of as a count, and it produces the component
list and its order in the same pass.

**Pros:**

- **Provable, not coincidental.** Every stored field is either written directly or written through
  the accessor of the auto-property it demonstrably backs. There is no shape where the object
  initializer leaves a field at its default and Kotlin never learns about it.
- **Fails closed.** Any field the rule cannot classify skips the struct with a named diagnostic. If
  Roslyn ever changed the `<X>k__BackingField` convention, the failure mode is "this struct is not
  bridgeable", not "this struct is bridged with a silently dropped component".
- Gives the canonical order for free (Decision 3), including the mixed field/auto-property case,
  which Constraint 1.2 verified.
- `const` and `static readonly` never participate (Constraint 1.3), so `public static readonly Point
  Empty` keeps working exactly as it does for Shape A.
- A `public readonly` field cannot be a component (Constraint 1.4 + the verified CS0191), so such a
  struct is skipped rather than bound with a field that reconstruction can never set.

**Cons:**

- A **manual settable property** backed by a hand-written private field (`ManualProp`) is *not*
  bridgeable, even though `new ManualProp { A = 1 }` would in fact work. Accepted: from metadata
  alone, nothing proves that `A`'s setter writes `_a` (Constraint 1.6), and the repository's stated
  policy is to fail closed rather than infer. Deferred, with a diagnostic that says why.

#### 2b. Count-based coverage: "number of components == number of non-static fields" (rejected)

The direct transliteration of ADR-056 rule 5. It accepts `ManualProp` (1 property, 1 field) and
rejects `HiddenState` (1 property, 2 fields), so it happens to be right on both.

**Rejected** on two grounds. It is right by *coincidence*, and it is fooled by
`struct S { private int _x; private int _y; public int A { get => _x; set => _x = value; } public int
B { get => _y; set { } } }`: two components, two fields, coverage "passes", and `B`'s value is
silently discarded on every crossing. That is exactly the data-loss class ADR-056 rule 5 exists to
prevent. It also cannot produce a component **order** for a manual property, because there is no
identifiable field row to order it by.

#### 2c. Public fields only (the literal ROADMAP `PublicFields` shape) (rejected)

**Rejected** as too narrow to be worth the extraction work. `struct { public int A { get; set; } }`
and the `init`-only variant are at least as common as public-field structs in modern C#, and both were
already probed by ADR-056 Constraint 3 (`Mutable`). Excluding them would leave the reader with field
extraction *and* an unfinished rule.

---

### Decision 3: component order

#### 3a. FieldDef (C# declaration) order, unified across fields and auto-properties (chosen)

Component order is the order in which each component's field row appears in `GetFields()`: a public
field is its own row, an auto-property is its backing field's row. Verified (Constraint 1.2) to equal
C# source declaration order, including when the two kinds interleave.

**Pros:**

- The Kotlin `data class` primary constructor reads in the same order as the C# declaration. A
  developer looking at both files sees one order.
- One rule covers fields and auto-properties without an arbitrary "fields first, then properties"
  tiebreak.
- It is what ADR-056 already sketched.

**Cons (and this is the real cost of Shape B):**

- **Reordering fields in the C# source is source-compatible for C# consumers, and it silently
  reorders the Kotlin `data class` primary constructor.** For a Shape A struct, order comes from the
  constructor parameter list, and reordering *that* already breaks every positional C# caller, so the
  Kotlin binding is no more fragile than the C# original. For a Shape B struct, the Kotlin binding is
  strictly **more** fragile than the C# original: C# callers use object initializers and never see the
  order. On regeneration, a consumer's `Extent(3, 4)` keeps compiling and starts meaning something
  else, whenever the two swapped components have the same type.
- Mitigations, all of which this ADR takes:
  1. Generated KDoc on every Shape B `data class` states that component order follows the C#
     declaration order and recommends **named arguments** (`Extent(width = 3, height = 4)`), which is
     the idiomatic Kotlin defence and costs the consumer nothing.
  2. `signaturePart()` starts including component **names**, so a reorder is at least an ABI contract
     change (Decision 5). This does not protect the consumer's *source*; it protects the wire.
  3. It is recorded as a named limitation in Consequences.

#### 3b. Ordinal-sorted by component name (rejected)

Stable under any source reorder, which removes the hazard entirely.

**Rejected.** It buys stability by making the Kotlin order *permanently* disagree with the C#
declaration for almost every struct: `struct Extent { public int Width; public int Height; }` would
bind as `data class Extent(val height: Int, val width: Int)`, and `Extent(3, 4)` in Kotlin would mean
`Width = 4`. That trades a rare hazard (an upstream reorder of two same-typed components) for a
permanent, always-armed one, and it makes the generated API actively confusing in IntelliSense. The
correct place to defend against positional mistakes in Kotlin is named arguments, not a reordering
that guarantees positional mistakes.

#### 3c. Refuse to bind a Shape B struct with two same-typed components (rejected)

The only shapes where a silent reorder can happen are those with two components of the same mapped
Kotlin type.

**Rejected**: `struct Extent { public int Width; public int Height; }` is the *canonical* Shape B
struct. A rule that excludes it excludes the feature.

---

### Decision 4: alternate constructors on a Shape B struct (deferred)

ADR-057 Decision 6 gives every *non-state* public constructor of a struct a registration slot and a
Kotlin secondary constructor. A Shape B struct has no state constructor by definition, so every one of
its public constructors would become an "alternate".

#### 4a. Do not bridge them in v1; diagnose each (chosen)

The reader emits **no `RirConstructor`** for a Shape B struct, and one `skipped_unsupported_struct`
diagnostic per skipped public constructor.

**Pros:**

- It removes a hard-fail landmine. `struct S { public int A; public int B; public S(int x, int y) { A
  = x; B = y; } }` (constructor parameter names deliberately *not* matching the fields, so Decision 1a
  cannot rescue it) would otherwise render a secondary `constructor(x: Int, y: Int)` next to the
  primary `S(val a: Int, val b: Int)`: same Kotlin collision key, and ADR-057 Decision 4 fails the
  whole build. Deferring costs the consumer nothing they cannot express (the primary constructor can
  already reach every state the struct has, because Decision 2a proved every field is a component).
- Nothing is lost that the existing surface cannot do; the only thing that could be lost is a
  constructor *body* with validation or derivation, which a struct constructor rarely has and which
  Decision 2a's coverage rule makes redundant.
- Zero change to `bridgeableStructConstructors` (which already filters on `isState`): the gate lives
  entirely in the reader.

**Cons:** a Shape B struct with a genuinely useful convenience constructor loses it. Diagnosed, and
listed as the first follow-up.

#### 4b. Bridge them, dropping any that collides with the primary (rejected)

Silently drops a public API member. That is "select one overload silently", which ADR-057 Decision 5
explicitly rejects.

#### 4c. Bridge them and let ADR-057's collision rule fail the build (rejected)

An ordinary C# struct would break the consumer's build with an error about a construct they did not
write. Unacceptable for a shape this common.

---

### Decision 5: the ADR-054 contract hash must see component **names**, not just component types

Not really an alternative, but a correctness fix Shape B forces, so it is recorded as a decision.

`RirBridging.kt` has **two** struct expansions and they disagree (**verified by reading the source**):

```kotlin
// structContractHash (line 483): the struct's OWN register export hashes name:type
val components: String = struct.components.joinToString(",") {
  "${it.name}:${it.type.signaturePart(structs)}"
}

// signaturePart (line 526): a REFERENCE to that struct from another type's method hashes types only
val componentParts: String =
  struct.components.joinToString(",") { it.type.signaturePart(structs) }
"$namespace.$name{$componentParts}"
```

So `Geometry.Translate(Point, int, int)`'s contract column renders `Sample.Structs.Point{int,int}`.
Swap `Point`'s `X` and `Y`: slot count unchanged, arity unchanged, hash unchanged, and a stale C#
shim paired with a fresh dylib writes each component into the other's ABI slot. That is precisely the
silent corruption ADR-054 exists to catch, and it is currently invisible. It is latent today (Shape A
component order is constructor parameter order, so a reorder is already a breaking C# change nobody
makes casually). Shape B makes it *plausible*, because a field reorder is source-compatible in C#.

**Decision:** `signaturePart()` for a `RirStructType` expands to `namespace.name{name:type,…}`,
matching `structContractHash`. One-line change, closes the hole for both shapes. A Shape B struct with
no members still owns no register export, but every method that *mentions* it now hashes its component
names.

## Decision

Take **1a + 2a + 3a + 4a + 5**.

### The Shape B bind rules (the mirror of ADR-056 Decision 3a's five rules)

A value type is classified in this order:

- **Shape A** (ADR-056 Decision 3a, as amended by ADR-057 Decision 6 and by Decision 1a here): exactly
  one public instance constructor covers all stored state; its parameters, matched case-insensitively
  to a public readable instance **property or public instance field**, are the components, in
  constructor-parameter order.
- **Shape B** (this ADR): otherwise, if the rules below hold.
- **Unsupported**: otherwise, with a `skipped_unsupported_struct` diagnostic naming the failed rule.

A struct is **Shape B** iff all of:

1. It is a public, top-level, non-generic, non-`ref struct`, non-enum value type. *(Unchanged from
   ADR-056 rule 1; already enforced by `ExtractStruct`.)*
2. **No** public instance constructor satisfies the Shape A rules. (A struct with a public
   parameterless constructor, C# 10, trivially satisfies this: a zero-parameter constructor has zero
   components and can never be a state constructor.)
3. **Every non-static field is covered**, where a non-static field is covered iff it is either:
   - **public, not `InitOnly`, not `Literal`** (a settable public field) → a component whose
     `readName` is the field name; or
   - a **`<X>k__BackingField` carrying `[CompilerGenerated]`** whose property `X` is public,
     non-static, has a public getter and a public setter (`set` **or** `init`) → a component whose
     `readName` is `X`.

   Anything else (a private hand-written field, a public `readonly` field, an auto-property with no
   public setter) leaves stored state that reconstruction cannot write, and **skips the struct**.
4. Every component type is in the v1 component vocabulary: primitives (including `bool` and `char`),
   `string`, and bound enums. *(Unchanged from ADR-056 rule 4. Nested struct components and
   class-typed components stay deferred; see Scope.)*
5. There is **at least one** component. A struct with no stored state is skipped (zero components),
   consistent with ADR-056's "public constructor has no parameters, zero components".

**Components, and their order, are the FieldDef-table order of each component's field** (its own row
for a public field, its backing field's row for an auto-property). Verified to equal C# declaration
order, including when the two kinds interleave.

**The reader sets `RirStructComponent.name` to the lower-camel form of `readName`** (`Girth` →
`girth`, `IsLoud` → `isLoud`), and `readName` to the C# member name. This mirrors Shape A, where
`name` is the C# constructor parameter name and is camelCase by C# convention. It matters that the
*reader* does it, not the generator: `NugetGenerateBindingsTask.kt` camel-cases `c.name` when it
renders the `data class` property (line 462, `c.name.toMethodCamelCase()`) but uses the **raw**
`it.name` when it renders the private carrier-constructor delegation (line 568,
`"components.${it.name}"`). Those two disagree for any non-camelCase `name`. **Verified by reading
the source**; it is latent today only because every fixture constructor parameter is already
camelCase. Emitting a camelCase `name` from the reader keeps both sites correct without touching the
generator.

**Reconstruction is an object initializer**; reading back is unchanged (`result.{readName}`, which is
the same expression for a field and a property).

#### `init`-only setters: **bind**

`set` and `init` are both simply "settable" for Shape B, because reconstruction is an object
initializer and that is exactly the context in which an `init` accessor is callable, including across
assembly boundaries (**verified**, Constraint 2). The reader needs **no** `IsExternalInit` detection
for this feature. ADR-056 Constraint 3.3's landmine is real for *class* properties, and is filed
separately (see Consequences).

#### Readonly / static / private / const fields

| Field shape | Metadata | Outcome |
|---|---|---|
| `public int A;` | `Public` | component |
| `public readonly int A;` | `Public, InitOnly` | **skips the struct** (CS0191: cannot be assigned in an object initializer, verified) |
| `private int _h;` | `Private` | **skips the struct** (uncovered stored state) |
| `public static readonly T Empty;` | `Public, Static, InitOnly` | ignored (not stored instance state) |
| `public const int Max = 9;` | `Public, Static, Literal` | ignored (static, verified) |
| `<X>k__BackingField` of `X { get; set; }` | `Private` + `[CompilerGenerated]` | component `X` |
| `<X>k__BackingField` of `X { get; init; }` | `Private, InitOnly` + `[CompilerGenerated]` | component `X` (the `InitOnly` is on the *field*; the write goes through the `init` accessor) |
| `<X>k__BackingField` of `X { get; }` (get-only auto-prop) | `Private, InitOnly` + `[CompilerGenerated]` | **skips the struct** (no public setter, and no constructor to set it) |

Note the last two rows: an `init` auto-property and a get-only auto-property have **identical field
attributes**. They are told apart by the *property's* accessors, not the field's. This is the one place
an implementation can get it wrong and must not.

#### A struct with both a constructor and public settable state

Decision 1a routes it to **Shape A** whenever some constructor covers all stored state (its parameters
may now match fields as well as properties). If no constructor does, it is Shape B and its
constructors are skipped with a diagnostic (Decision 4a). Either way the Kotlin surface is one
immutable `data class` and there is exactly one way to construct it.

#### The Kotlin surface stays an immutable `data class`

Unchanged from ADR-056 Decision 2a, and this is the point at which it must be defended, because the C#
type genuinely *is* mutable:

```kotlin
package sample.structs

/**
 * Kotlin value type for the C# struct `Sample.Structs.Collar`.
 *
 * Copied by value across the bridge: equality is structural, and there is nothing to close.
 * The C# struct's fields/properties are settable, but a Kotlin-side change can never be
 * observable in C# (a copy crossed the boundary), so every component is a `val`. Use [copy]
 * and pass the result back.
 *
 * Component order follows the C# declaration order. Prefer named arguments.
 */
data class Collar(
  val girth: Int,
  val colour: String,
  val belled: Boolean,
  val initial: Char,
  val mood: CatMood,
)
```

For the consumer this means: `collar.girth = 3` does not compile, and that is correct. The C# idiom
`c.Girth = 3` has no faithful Kotlin translation, because the Kotlin `Collar` is a *copy*. A `var`
would compile and do nothing observable on the C# side, which is ADR-056 Decision 2b's rejected
option, and it is worse here than for Shape A because a reader who knows the C# type is mutable would
reasonably expect the write to land. The read-modify-write idiom is:

```kotlin
val loosened = Collars.loosen(collar.copy(girth = collar.girth + 1))
```

### Bridge mechanism

**Nothing about the wire changes.** A Shape B struct decomposes exactly as a Shape A struct does:
one ABI argument per component in, `void` plus one out-pointer per component out, per-component wire
types straight from ADR-056's table. `abiArgs` / `abiOutArgs` / `abiReturnType` /
`structReceiverAbiArgs` are all driven by `RirStruct.components` and are **shape-agnostic**. The
**Kotlin generator needs no change**, provided the reader emits a camelCase `component.name` (above);
that is the whole reason for that instruction.

The only new C#-side machinery is the reconstruction expression. Today there are exactly two
reconstruction sites in `NugetGenerateShimsTask.kt`, and both hard-code `new T(...)`:

- `paramBinding` (line ~324): `expression = "new ${struct.name}($componentArgs)"` for a struct-typed
  parameter;
- `structReceiverReconstruction` (line ~981): `"new ${struct.name}($args)"` for a struct instance
  member's receiver.

Both must route through **one shared helper**, for the same anti-drift reason ADR-056 gave for
`abiArgs`:

```kotlin
// Shape A: new Collar(a, b)        Shape B: new Collar { Girth = a, Colour = b }
private fun structConstruction(struct: RirStruct, componentExprs: List<String>): String =
  when (struct.shape) {
    RirStructShape.CONSTRUCTOR -> "new ${struct.name}(${componentExprs.joinToString(", ")})"
    RirStructShape.INITIALIZER -> struct.components.zip(componentExprs)
      .joinToString(", ", prefix = "new ${struct.name} { ", postfix = " }") { (c, e) ->
        "${c.readName} = $e"
      }
  }
```

Generated thunk, Shape B struct in **both** positions (**inferred** shape: the object-initializer
reconstruction and the out-pointer writes are each verified in isolation, but no spike has combined
them inside an `[UnmanagedCallersOnly]` thunk; the walking skeleton must falsify this):

```csharp
[UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
private static unsafe void Loosen__a1b2_Thunk(
    int c_Girth, IntPtr c_Colour, byte c_Belled, ushort c_Initial, int c_Mood,
    int* outGirth, IntPtr* outColour, byte* outBelled, ushort* outInitial, int* outMood)
{
    Collar result = Collars.Loosen(new Collar
    {
        Girth   = c_Girth,
        Colour  = Marshal.PtrToStringUTF8(c_Colour)!,
        Belled  = c_Belled != 0,
        Initial = (char)c_Initial,
        Mood    = (CatMood)c_Mood,
    });
    *outGirth   = result.Girth;
    *outColour  = Marshal.StringToCoTaskMemUTF8(result.Colour);
    *outBelled  = (byte)(result.Belled ? 1 : 0);
    *outInitial = (ushort)result.Initial;
    *outMood    = (int)result.Mood;
}
```

Every per-component conversion here is the existing `paramConversion` / `csReturnConversion` output.
The only new text is the `{ Girth = …, … }` braces.

### Metadata reader (`nuget-metadata-reader/Program.cs`)

**Verified** API surface (Constraint 1's probe used exactly these calls; the repo's enum path already
uses `FieldDefinition.DecodeSignature` at line 337):

```csharp
foreach (var fh in typeDef.GetFields())                 // FieldDef-table order == declaration order
{
    var f = mr.GetFieldDefinition(fh);
    var attrs = f.Attributes;                            // System.Reflection.FieldAttributes
    if ((attrs & FieldAttributes.Static) != 0) continue; // excludes const + static readonly

    var fieldName = mr.GetString(f.Name);
    bool isPublic   = (attrs & FieldAttributes.FieldAccessMask) == FieldAttributes.Public;  // mask = 0x7
    bool isReadOnly = (attrs & FieldAttributes.InitOnly) != 0;
    bool isCompilerGenerated = f.GetCustomAttributes().Any(h =>
        MetadataHelpers.GetCustomAttributeTypeName(mr, mr.GetCustomAttribute(h))
            == "System.Runtime.CompilerServices.CompilerGeneratedAttribute");

    // component type: the SAME decoder the ctor-parameter path uses (empty bound-handle set,
    // no struct map), so a class-typed or nested-struct component is rejected identically.
    var decoded = f.DecodeSignature(componentDecoder, genericContext: null);   // -> TypeRefOrDiag
    ...
}
```

Backing-field-to-property attribution: a field is a backing field iff `isCompilerGenerated` **and**
its name matches `<X>k__BackingField`; `X` is the substring between `<` and `>`. **Verified** naming
convention (Constraint 1.1); **inferred** that it is stable across Roslyn versions (it is a
long-standing convention, not an ECMA-335 guarantee). The design fails **closed** if it ever changes:
an unattributable private field is uncovered stored state, so the struct is skipped, not mis-bound.

Component nullability: `NullabilityHelpers.Resolve(mr, memberHandle: EntityHandle, …)` takes an
`EntityHandle`, so a `FieldDefinitionHandle` or `PropertyDefinitionHandle` passes straight in
(**verified by reading `Program.cs` line 1463**). Shape B components decode nullability with the
existing ADR-053 chain (member → type → oblivious), so `public string? Colour { get; set; }` binds as
`val colour: String?`. This matters more for Shape B than for Shape A: **a Shape B struct is always
`default(T)`-constructible in C#, so a reference-typed component really can be null**, and a non-null
binding would send `null` into `Marshal.StringToCoTaskMemUTF8` inside a thunk. (Shape A's ctor
parameters are *not* nullability-decoded today. That is a pre-existing ADR-056 gap; this ADR does not
widen it, and closing it for Shape A is a small follow-up.)

Diagnostics (`skipped_unsupported_struct`), each naming its rule:

- `field \`_h\` is private and no public component covers it: state would be silently dropped`
- `public field \`A\` is readonly and cannot be set by an object initializer`
- `property \`A\` is settable but is not an auto-property (no compiler-generated backing field), so
  the struct's stored state cannot be proven covered`
- `auto-property \`A\` has no public setter and there is no constructor to set it`
- `struct has no settable public state: zero components`
- `component type \`T\` is not bridgeable` (reuses the existing component-vocabulary message)
- per skipped public constructor on a Shape B struct: `constructor on a struct with no state
  constructor; alternate constructors on Shape B structs are deferred`

### RIR model (`RirModel.kt` + the mirrored C# model in `Program.cs`)

One additive field. `RirStructComponent` is unchanged (`readName` already carries "the C# member used
to read this component back", and a field is read exactly like a property).

```kotlin
@Serializable
enum class RirStructShape {
  // Components are the state constructor's parameters; C# reconstructs with `new T(a, b)`.
  @SerialName("constructor") CONSTRUCTOR,
  // Components are the settable public state; C# reconstructs with `new T { A = a, B = b }`.
  @SerialName("initializer") INITIALIZER,
}

data class RirStruct(
  override val name: String,
  val components: List<RirStructComponent> = emptyList(),
  val shape: RirStructShape = RirStructShape.CONSTRUCTOR,   // default keeps old JSON parsing
  val constructors: List<RirConstructor> = emptyList(),     // always empty for INITIALIZER (Decision 4a)
  val methods: List<RirMethod> = emptyList(),
  val properties: List<RirProperty> = emptyList(),
) : RirType
```

The alternative (derive the shape from `constructors.none { it.isState }`) needs no schema change but
couples the C# generator's reconstruction syntax to the *absence* of a flag on another node. An
explicit discriminator is what an implementing agent should be able to read off the JSON.

### Struct members on a Shape B struct

The shipped ADR-056 "Follow-up design: members" composes **for free**, with one change: the
reconstruction expression (above). Concretely:

- The bind filter is unchanged: public non-void instance methods; get-only instance properties whose
  name is not a component `readName`; public static methods → `companion object`. A Shape B struct's
  component auto-properties are settable, and the existing filter already drops non-read-only
  properties (`RirBridging.kt`, `bridgeableStructRegistrables`: `.filter { it.isReadOnly && … }`), so
  they cannot leak in as computed getters. Belt and braces: they are also excluded by `readName`.
- Slot order is unchanged: alternate constructors → static methods → instance methods → computed
  getters. A Shape B struct contributes **no** constructor slots (Decision 4a), so its order starts at
  static methods.
- A Shape B struct with members gets a `{Struct}Bindings.kt`, a `nuget_{ns}_{type}_register` export,
  and a `{Struct}Registration.cs`, exactly as ADR-056's follow-up already prescribes for a struct with
  members. A Shape B struct with **no** members costs zero registration slots, exactly like Shape A.
- The receiver reconstruction in the thunk becomes `new T { … }` instead of `new T(…)`. That is the
  whole delta.

## Consequences

### New/changed artifacts

- `nuget-metadata-reader/Program.cs`: field extraction (`TypeDefinition.GetFields()` + `FieldAttributes`
  + backing-field attribution); `ExtractStruct` gains the Shape A → Shape B → unsupported precedence;
  ADR-056 rule 3's property lookup widens to include public instance fields; `RirStructShape` in the
  mirrored C# model; new `skipped_unsupported_struct` reasons; Shape B structs emit no
  `RirConstructor`.
- `RirModel.kt`: `RirStructShape` + `RirStruct.shape` (additive, defaulted).
- `RirBridging.kt`: `signaturePart()` for a `RirStructType` expands component **names** as well as
  types (Decision 5). No other change: `abiArgs` / `abiOutArgs` / `abiReturnType` /
  `structReceiverAbiArgs` / `bridgeableStructRegistrables` are shape-agnostic.
- `NugetGenerateShimsTask.kt`: one shared `structConstruction(struct, componentExprs)` helper,
  consumed by both `paramBinding` and `structReceiverReconstruction`.
- `NugetGenerateBindingsTask.kt`: **no change** beyond the KDoc line about component order and named
  arguments (the reader emits a camelCase `component.name`, so `toMethodCamelCase()` at line 462 and
  the raw `it.name` at line 568 agree).
- Fixtures: see below.

### Behavioural changes

- `Sample.Structs.Unsupported` (public fields, no constructor) **becomes supported**. It must move out
  of `UnsupportedStructs.cs`; the test asserting its `skipped_unsupported_struct` diagnostic must go.
- `Sample.Structs.Overstuffed` **stays unsupported**, but its diagnostic *reason text changes*: it
  fails Shape A rule 5 as it does today, then also fails Shape B (its only public member is a get-only
  property, so it has zero components and an uncovered private field). Any test asserting the exact
  reason string must be updated.
- A struct whose constructor parameters match public **fields** now binds as Shape A where it
  previously produced `skipped_unsupported_struct` (Decision 1a). Additive; no shipped fixture changes.
- Every method that mentions a struct gets a **new `contractHash`** (Decision 5 adds component names
  to the expansion). Both generators regenerate together, as always; a mixed stale/fresh pair now
  reports a contract mismatch where it previously ran silently.

### Verified vs. inferred

**Verified** (commands and real output above; scratch `mktemp -d`, `dotnet new classlib`, TFM `net8.0`,
SDK 10.0.300, decoded with `System.Reflection.Metadata`):

- An auto-property's backing field is `Private` + `[CompilerGenerated]`, named `<X>k__BackingField`.
- `TypeDefinition.GetFields()` yields declaration order, correctly interleaving public fields with
  auto-property backing fields (`Mixed`: `A`, `<Flag>`, `<G>`, `<M>`).
- `const` and `static readonly` fields are `Static`. `FieldAttributes.FieldAccessMask == 0x7`.
- A `readonly` public field is `InitOnly`; an `init` auto-property's backing field is *also* `InitOnly`
  (so the two are told apart by the property's accessors, not the field's).
- An `init` setter is detectable: its signature carries a required modifier on the return type
  (`1F 4D`), and coded index `0x4D` resolves to `TypeRef[19] = System.Runtime.CompilerServices.IsExternalInit`.
- Object-initializer reconstruction compiles **and runs**, cross-assembly, for: public fields, settable
  auto-properties, **`init`-only auto-properties**, a mixed field/`set`/`init` struct with
  `int`/`string`/`bool`/`char`/enum components, a struct whose only constructor takes two arguments,
  and a struct with an explicit public parameterless constructor (which runs first; the initializer
  wins).
- `new ReadonlyField { A = 1 }` is **CS0191**. `initOnlyValue.A = 9` outside an initializer is
  **CS8852**.
- Repository source: `signaturePart()` expands a struct *reference* to component types only, while
  `structContractHash` expands the struct's *own* components as `name:type` (`RirBridging.kt` lines
  483 and 526). `NullabilityHelpers.Resolve` takes an `EntityHandle` (line 1463).
- Repository source: the two `new T(...)` reconstruction sites are `paramBinding` and
  `structReceiverReconstruction` in `NugetGenerateShimsTask.kt`.

**Inferred** (not verified against a real artifact; the walking skeleton must falsify each):

- **The `<X>k__BackingField` naming convention is stable across Roslyn versions.** Verified on SDK
  10.0.300 / TFM net8.0 only. It is a long-standing Roslyn convention, **not** an ECMA-335 guarantee,
  and a different C# compiler (or a non-C# language) may name backing fields differently. The design
  fails **closed** if it does: an unattributable private field is uncovered stored state, so the struct
  is skipped with a diagnostic rather than bound with a dropped component. This is the single claim the
  feature most depends on.
- **The generated thunk shape combining an object-initializer reconstruction with out-pointer writes**
  compiles inside an `[UnmanagedCallersOnly]` method. Each half is verified separately (ADR-056
  verified the out-pointer half end to end; Constraint 2 verified the initializer half cross-assembly),
  but the combination has not been spiked.
- **Decoding a `FieldDefinition`'s component type with the existing `SignatureDecoder`** is assumed to
  behave exactly as the constructor-parameter path does. `FieldDefinition.DecodeSignature` is already
  used by the enum path (`Program.cs` line 337) with the same decoder, so this is a small
  extrapolation, but it is an extrapolation.
- **Component nullability decoding for a field** is assumed to work through the same
  `NullabilityHelpers.Resolve` chain a property uses. The signature accepts the handle
  (verified); that the `NullableAttribute` is actually *placed* on the FieldDef row for a public field
  is inferred.
- **A Shape B struct's `default(T)` reachability** (a C# caller can hand Kotlin a `Collar` whose
  `Colour` is `null` even though the property is annotated non-null) is inferred from the C# language
  rules, not spiked against the bridge. It is the reason component nullability is decoded here.

### Scope

**In v1 (this ADR):**

- Shape B classification, components, and order (Decisions 1–3).
- Field extraction in the metadata reader, with backing-field attribution.
- Object-initializer reconstruction in both C# reconstruction sites, through one shared helper.
- `init`-only auto-properties bind (no `IsExternalInit` detection needed).
- ADR-056 Decision 3a rule 3 widened to match public instance fields.
- Component nullability decoding for Shape B components.
- `signaturePart()` component-name expansion (ADR-054 contract fix).
- Struct members (methods, computed properties, statics) on Shape B, which compose from the shipped
  ADR-056 members follow-up.

**Deferred (unchanged from ADR-056 Scope, restated so scope stays tight):**

- **Nested struct components** (a struct field inside a struct): **still deferred.** Shape B changes
  nothing about it; the component decoder still passes no struct map, so a struct-typed field is
  rejected exactly as a struct-typed constructor parameter is.
- **Class-typed (handle) components**: **still deferred**, on semantics. A `GCHandle` inside a value
  type does not compose (a `copy()` would duplicate `Cleaner`-managed ownership). Shape B makes this
  *more* tempting, because settable class-typed auto-properties are common, and it must stay closed.
- `Nullable<T>` value types; generic structs; structs as collection elements; `ref struct` / `Span<T>`;
  explicit `[StructLayout]` / `[FieldOffset]`. All unchanged.

**Newly deferred by this ADR:**

- **Alternate constructors on a Shape B struct** (Decision 4a). Diagnosed, not silently dropped.
  First follow-up if a real package needs one; requires a rule for the primary-constructor collision.
- **Manual (non-auto) settable properties as components** (Decision 2a's accepted cost). Requires
  either IL analysis or a heuristic; fails closed today.
- **Shape A constructor-parameter nullability decoding** (a pre-existing ADR-056 gap, surfaced here
  because Shape B closes it for its own components).
- **`init`-only properties on bound C# *classes*** generate a `target.Prop = value;` setter thunk that
  does not compile (CS8852, verified). No shipped fixture hits it. Fix is the `IsExternalInit` modreq
  detection Constraint 1.5 verified; filed as a ROADMAP bug, not fixed here.

## Test Design

The fixture must cross every mechanism, not the fewest types: three component *sources* (public field,
`set` auto-property, `init` auto-property), both component *vocabularies* (a direct ABI scalar `int`,
and the converted `string` / `bool` / `char` / enum), in both parameter and return position, plus
members, plus every negative rule.

### `sample-dependency/Collar.cs` (new, namespace `Sample.Structs`)

```csharp
using Sample.Enums;

namespace Sample.Structs;

/// Shape B, pure public fields, all-direct (int) components. The canonical ROADMAP shape, and
/// the type that used to be `UnsupportedStructs.Unsupported`. Deliberately has NO properties, so
/// the public-field path is proven in isolation. Same components as the Shape A `Size`, which is
/// the control: two structs, identical component lists, different shapes.
public struct Extent
{
    public int Width;
    public int Height;

    public int Area => Width * Height;                                   // computed prop (non-component)
    public Extent Grow(int by) => new Extent { Width = Width + by, Height = Height + by };  // struct return
    public static Extent Unit() => new Extent { Width = 1, Height = 1 }; // static -> companion object
}

/// Shape B, MIXED component sources in one struct: a public field, a settable auto-property, and
/// two init-only auto-properties. Spans the full v1 vocabulary: int (direct), string/bool/char/enum
/// (converted). Component order is the C# declaration order, recovered from the FieldDef table.
public struct Collar
{
    public int Girth;                            // public field           -> int, direct
    public string Colour { get; set; }           // settable auto-prop     -> IntPtr / UTF-8
    public bool Belled { get; init; }            // INIT-only auto-prop    -> byte
    public char Initial { get; init; }           // INIT-only auto-prop    -> ushort
    public CatMood Mood { get; set; }            // settable auto-prop     -> int ordinal

    public string Label => $"{Colour}:{Girth}{(Belled ? "*" : "")}";     // computed prop (string)
    public bool IsLoud => Belled && Mood == CatMood.Playful;             // computed prop (bool)

    /// Instance method returning a Shape B struct: object-initializer reconstruct on the receiver,
    /// out-pointers on the way back, full vocabulary on both.
    public Collar Resize(int by) =>
        new Collar { Girth = Girth + by, Colour = Colour, Belled = Belled, Initial = Initial, Mood = Mood };

    /// Static factory on a Shape B struct -> Kotlin companion object.
    public static Collar Plain(string colour) =>
        new Collar { Girth = 1, Colour = colour, Belled = false, Initial = 'P', Mood = CatMood.Calm };
}

/// Static methods taking/returning Shape B structs (the mirror of Geometry/Cattery2 for Shape A):
/// struct param + non-struct return, struct param + struct return, TWO struct params.
public static class Collars
{
    public static string Describe(Collar c) => $"{c.Colour} {c.Girth} {c.Initial} {c.Mood} {c.Belled}";
    public static Collar Loosen(Collar c) => c.Resize(1);
    public static string Pair(Collar a, Extent b) => $"{a.Label}/{b.Width}x{b.Height}";  // Shape B + Shape B
}
```

`Collars.Pair` deliberately takes **two** Shape B structs of *different* shapes-of-Shape-B (one
field-only, one mixed) in one signature, which is where an `abiArgs` expansion bug would show up.

### `sample-dependency/UnsupportedStructs.cs` (five negatives, one per rule)

- **`Overstuffed`** (kept, unchanged source): fails Shape A rule 5 (2 stored fields, 1 constructor
  parameter), then fails Shape B (zero components, uncovered private `_hidden`). **Its diagnostic
  reason text changes; update the assertion.**
- **`PartlyHidden`**: `private int _hidden; public int Visible { get; set; }` → a settable
  auto-property, but the private field is uncovered stored state. Pins rule 3.
- **`Frozen`**: `public readonly int A; public int B;` → a public readonly field cannot be set by an
  object initializer (CS0191). Pins the `InitOnly` rule.
- **`Manual`**: `private int _a; public int A { get => _a; set => _a = value; }` → settable, but not an
  *auto*-property, so coverage is unprovable. **This is the negative a count-based implementation
  would get wrong** (1 component, 1 field, "coverage passes"), which is exactly why it is in the
  fixture.
- **`Nothing`**: `public struct Nothing { }` → zero components. Pins rule 5.

### `sample-library` consumer

```kotlin
// Shape B struct as a parameter (object-initializer reconstruction), non-struct return.
fun describeCollar(girth: Int, colour: String, belled: Boolean, initialCode: Int, mood: CatMood): String =
  Collars.describe(Collar(girth, colour, belled, initialCode.toChar(), mood))

// Shape B struct in AND out (out-pointer return of a mixed-vocabulary struct).
fun loosenCollar(girth: Int, colour: String, belled: Boolean, initialCode: Int, mood: CatMood): String {
  val loosened: Collar = Collars.loosen(Collar(girth, colour, belled, initialCode.toChar(), mood))
  return "${loosened.girth},${loosened.colour},${loosened.belled},${loosened.initial.code},${loosened.mood}"
}

// TWO Shape B structs in one signature.
fun pairCollar(girth: Int, colour: String, width: Int, height: Int): String =
  Collars.pair(Collar(girth, colour, true, 'X', CatMood.Calm), Extent(width, height))

// Members on a Shape B struct: computed properties, instance method returning a struct, static.
fun extentMembers(width: Int, height: Int): String {
  val grown: Extent = Extent(width, height).grow(2)
  return "${Extent(width, height).area}|${grown.width}x${grown.height}|${Extent.unit().area}"
}

fun collarMembers(girth: Int, colour: String, mood: CatMood): String {
  val c = Collar(girth, colour, true, 'B', mood)
  return "${c.label}|${c.isLoud}|${c.resize(1).girth}|${Collar.plain(colour).label}"
}

// ADR-056 Decision 2a, restated for a struct whose C# original is MUTABLE: value equality and
// copy() survive the crossing, and there is nothing to close().
fun collarValueEquality(): Boolean {
  val a = Collar(1, "red", true, 'A', CatMood.Playful)
  return a == a.copy() && a != a.copy(girth = 2)
}

// Named arguments: the documented defence against the declaration-order hazard. Compiles only if
// the generated component names are what the ADR says they are.
fun collarNamedArgs(): Collar =
  Collar(girth = 1, colour = "red", belled = true, initial = 'A', mood = CatMood.Calm)
```

`sample-library/build.gradle.kts` needs no change: `Sample.Structs` is already in the `bind {}` block.

### Reader / generator tests

- Field extraction: `Extent` yields components `[Width:int, Height:int]` in declaration order;
  `Collar` yields `[Girth, Colour, Belled, Initial, Mood]` in declaration order, spanning a field, two
  `set` auto-properties and two `init` auto-properties.
- Shape: `Extent`/`Collar` are `INITIALIZER`; `Point`/`Size`/`Profile`/`Metrics` stay `CONSTRUCTOR`.
- Precedence: a struct with public fields **and** a matching constructor binds as **Shape A** (the
  Decision 1a amendment), with components in constructor-parameter order.
- Order stability: reverse the field declarations in a scratch probe and assert the component order
  follows (this is the *documented* behaviour, not a bug, so the test pins it deliberately).
- Every negative fixture produces exactly one `skipped_unsupported_struct` naming its rule, and
  generates **no** Kotlin type and **no** handle wrapper.
- Shim generator: a `Collar` parameter reconstructs with `new Collar { Girth = …, Colour =
  Marshal.PtrToStringUTF8(…)!, Belled = … != 0, Initial = (char)…, Mood = (CatMood)… }`; a `Point`
  parameter still reconstructs with `new Point(…)`.
- `contractHash`: swapping two same-typed components of a struct changes the hash of every method that
  mentions it (Decision 5; this test **fails on `main` today**).
- End-to-end: `SampleApp.Tests` asserts each `sample-library` function above, with distinct values per
  component so a component-order bug cannot pass by coincidence.

## Sources

- [Object and collection initializers (C#)](https://learn.microsoft.com/en-us/dotnet/csharp/programming-guide/classes-and-structs/object-and-collection-initializers)
- [`init` accessor](https://learn.microsoft.com/en-us/dotnet/csharp/language-reference/keywords/init)
- [Struct types: parameterless constructors and field initializers (C# 10)](https://learn.microsoft.com/en-us/dotnet/csharp/language-reference/builtin-types/struct)
- [`System.Reflection.Metadata.FieldDefinition`](https://learn.microsoft.com/en-us/dotnet/api/system.reflection.metadata.fielddefinition)
- [`System.Reflection.FieldAttributes`](https://learn.microsoft.com/en-us/dotnet/api/system.reflection.fieldattributes)
- [`CompilerGeneratedAttribute`](https://learn.microsoft.com/en-us/dotnet/api/system.runtime.compilerservices.compilergeneratedattribute)
- [Kotlin: calling Java from Kotlin](https://kotlinlang.org/docs/java-interop.html)
- [Kotlin/Native C interop](https://kotlinlang.org/docs/native-c-interop.html)
- [.NET for Android binding libraries](https://learn.microsoft.com/en-us/dotnet/android/binding-libs/)
