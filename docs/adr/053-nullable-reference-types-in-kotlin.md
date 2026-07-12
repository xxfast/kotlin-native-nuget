# ADR-053: C# nullable reference types in Kotlin: `NullableAttribute` decoding, oblivious-as-non-null, and per-type-ref nullability in the RIR

## Status

Accepted

## Context

Phase 9, ROADMAP line 156: "Map nullable reference type annotations → Kotlin `T?` (`NullableAttribute`
in metadata; needs ADR: what un-annotated legacy assemblies default to)", and its sub-item on line
157 (the handle-typed property setter, which this ADR unblocks).

The reverse IR carries **no nullability at all** today. `RirTypeRef` is `RirVoidType`,
`RirStringType`, `RirPrimitiveType`, `RirObjectHandleType`, `RirEnumType`, and none of them can say
"this may be null". Both generators therefore hardcode a nullability policy, and the policy is
inconsistent:

| Position | Today's generated Kotlin | Where it is decided |
|---|---|---|
| `string` return | non-null `String`, `?: error(...)` if the pointer is null | `buildStubMethod`, ADR-048 |
| `string` parameter | non-null `String` | `argConversion`, ADR-048 |
| handle return | **always** `Foo?` (`IntPtr.Zero` = null) | `buildStubMethod`, ADR-051 |
| handle parameter | **always** non-null `Foo` | `argConversion`, ADR-051 |
| handle property with a public setter | read-only `val foo: Foo?` (setter slot dropped) | `bridgeableRegistrables` "rule 4" |

ADR-051 flagged this explicitly ("Nullability (v1 judgment call, flagged)") and deferred it here.
The line-157 sub-item is a direct casualty: a C# `Foo Bar { get; set; }` cannot render as a Kotlin
`var`, because a `var`'s getter and setter must share one type while ADR-051 gives object returns
`Foo?` and object parameters `Foo`. With real nullability in the RIR, a property carries exactly
**one** annotation (C# puts `NullableAttribute` on the *property*, not on its accessors), so getter
and setter trivially agree and `var bar: Foo` / `var bar: Foo?` both become expressible.

Three things must be decided:

1. **What an un-annotated (nullable-oblivious) reference type maps to.** This is the ADR's central
   question.
2. **How nullability is encoded in the RIR / `reverse-ir.json`.**
3. **Whether `Nullable<T>` value types (`int?`) are in scope.**

### 1. How C# nullable reference types appear in ECMA-335

Nullable reference types are a **compile-time-only** feature: "The annotation doesn't change the
runtime type. `string` and `string?` are both `System.String`"
([Nullable reference types](https://learn.microsoft.com/en-us/dotnet/csharp/nullable-references)).
The intent is preserved in metadata as two compiler-synthesized custom attributes, specified in
[roslyn/docs/features/nullable-metadata.md](https://github.com/dotnet/roslyn/blob/main/docs/features/nullable-metadata.md):

**`System.Runtime.CompilerServices.NullableAttribute`** carries a `byte` or `byte[]` payload where
the values are "0 for oblivious, 1 for not annotated, and 2 for annotated". So:

- `0` = **oblivious**: the type was compiled outside a nullable context (pre-C# 8, or
  `#nullable disable`). "I don't know."
- `1` = **not annotated**: `string`, i.e. non-null.
- `2` = **annotated**: `string?`, i.e. nullable.

Its `AttributeUsage` is `Class | Event | Field | GenericParameter | Parameter | Property |
ReturnValue`. A method's *return* nullability therefore lands on the **return-value pseudo-parameter**
(the ECMA-335 `Param` row with `Sequence == 0`), not on the `MethodDef` row.

The payload is a **flattened pre-order walk of the type tree**, not a single bit:

> Reference types: "the nullability (0, 1, or 2), followed by the representation of the type arguments in order"
> Nullable value types: "the representation of the type argument only"
> Non-generic value types: "skipped" (so `int` contributes nothing)
> Arrays: "the nullability, followed by the representation of the element type"

The single-`byte` constructor is used "when all values in the `byte[]` are the same". For every type
the v1 subset can bridge (`string`, a bound class), the tree is depth-1 and the payload is a single
byte, but the decoder must still tolerate the `byte[]` form and read index 0.

**`System.Runtime.CompilerServices.NullableContextAttribute`** carries one `byte` and supplies the
default for every type reference in its scope that has no `NullableAttribute` of its own. Its
`AttributeUsage` is `Class | Delegate | Interface | Method | Struct`, and the doc is explicit that it
"is valid in metadata on type and method declarations" (there is **no module-level**
`NullableContextAttribute`; the module-level attribute Roslyn emits is `NullablePublicOnly`, which is
about whether *internal* members are annotated). Resolution rule:

> "The nearest `NullableContextAttribute` in the metadata hierarchy applies. If there are no
> `NullableContextAttribute` attributes in the hierarchy, missing `NullableAttribute` attributes are
> treated as `NullableAttribute(0)`."

Roslyn emits the context attribute as a size optimisation: it "finds the most common single `byte`
value across all the `NullableAttribute` attributes at that level" and drops the redundant per-member
ones. So a `<Nullable>enable</Nullable>` assembly typically has `NullableContext(1)` on each type or
method and an explicit `NullableAttribute(2)` only on the members that are actually nullable.

The **fallback chain this ADR implements** (v1 binds top-level types only, so no nested-type walk):

```
parameter / return pseudo-parameter:  Nullable on the Param row
                                   -> NullableContext on the MethodDef
                                   -> NullableContext on the TypeDef
                                   -> 0 (oblivious)

property:                             Nullable on the Property row
                                   -> NullableContext on the TypeDef      (a Property cannot carry a context)
                                   -> 0 (oblivious)
```

**`System.Reflection.Metadata` can read all of this.** Both attributes are ordinary custom attributes
with a raw value blob. Whether their constructor handle is a `MethodDefinitionHandle` or a
`MemberReferenceHandle` depends on the target framework: on a pre-.NET-5-ish TFM (or `netstandard2.0`),
where the BCL does not yet ship these types, Roslyn compiler-synthesizes them *into the assembly being
compiled*, so the constructor is a `MethodDefinitionHandle` in that same assembly. On a modern TFM
(this project's fixture targets net8.0), the BCL already defines both attribute types, so Roslyn emits
an ordinary `MemberReferenceHandle` to the existing type instead. **Both shapes must be handled**: the
existing `MetadataHelpers.GetCustomAttributeTypeName` in `Program.cs` already resolves the type name
for either kind, but the ctor-signature decode that distinguishes the single-`byte` `NullableAttribute`
overload from the `byte[]` overload must also branch on `EntityHandle.Kind` and decode whichever kind
it finds. (An earlier implementation of this ADR assumed the `MethodDefinitionHandle` case
unconditionally, which silently decoded every annotation on a net8.0 fixture as oblivious; the fix
branches on `EntityHandle.Kind` in `NullabilityHelpers.CustomAttributeCtorTakesByteArray`.) The blob
itself is decoded with `CustomAttribute.DecodeValue(ICustomAttributeTypeProvider<T>)` (a small provider
handling `byte` and SZArray-of-`byte` is enough), or, as implemented, by reading
`mr.GetBlobReader(attr.Value)` directly: `0x0001` prolog, then either one byte or a 4-byte count plus
bytes. No `System.Reflection` runtime type is needed and the assembly is never loaded (ADR-042 holds).

Two facts that make this safe in practice:

- **The annotations survive into shipped assemblies.** They are part of the public API contract;
  Roslyn's `nullablePublicOnly` feature exists precisely to strip annotations on *private* members
  from reference assemblies while preserving public and protected ones. `deriveDllPaths()` resolves
  the **runtime** (`lib/`) assets from `project.assets.json` anyway, which carry the full set.
- **`reverse-ir.json` stays forward/backward compatible.** `RirParsing.kt` parses with
  `ignoreUnknownKeys = true` and every field added below is defaulted.

#### The return pseudo-parameter landmine (must be fixed with this feature)

`Program.cs` currently maps parameter names positionally:

```csharp
var paramHandles = methodDef.GetParameters().ToList();
for (int i = 0; i < sig.ParameterTypes.Length; i++) { ... mr.GetParameter(paramHandles[i]) ... }
```

`MethodDefinition.GetParameters()` returns **all** `Param` rows for the method, including the
return-value row (`Sequence == 0`) whenever the return carries any metadata. Today no fixture method
has a return attribute, so no such row exists and the indexing happens to work. The moment a method
returns `string?`, Roslyn emits `[return: Nullable(2)]`, the `Param` table gains a `Sequence == 0`
row, and every parameter name shifts by one (the first parameter silently degrades to `arg0`).
Parameter lookup must be keyed on `Parameter.SequenceNumber` (1-based for real parameters, 0 for the
return value), not on list position. This is a prerequisite fix, not an optional cleanup.

#### `Nullable<T>` is a different feature

`int?` is `System.Nullable`1<System.Int32>`, a closed generic **struct**. It carries no
`NullableAttribute` of its own (the spec says a nullable value type contributes only "the
representation of the type argument"). In the current decoder it reaches
`SignatureDecoder.GetGenericInstantiation` and is silently dropped. Mapping it is therefore not a
`NullableAttribute` problem at all but a *wire-format* problem (see Decision 3).

### 2. Prior art: what every interop layer does with "unknown"

| Ecosystem | Annotated | Un-annotated / unaudited |
|---|---|---|
| **Kotlin consuming Java** (gold standard) | `@Nullable`/`@NotNull`, JSpecify, JSR-305 produce real `T?` / `T` | **Platform type `T!`**: "Platform types can't be mentioned explicitly in the program, so there's no syntax for them in the language." Assignment to a non-null Kotlin type is allowed and "may fail at runtime (assertion emitted)". ([Java interop](https://kotlinlang.org/docs/java-interop.html)) |
| **Kotlin/Native consuming ObjC** (cinterop) | `_Nullable` / `_Nonnull`, and `NS_ASSUME_NONNULL_BEGIN` audited regions, produce `T?` / `T` | **Platform type**, synthesized by the compiler from the header |
| **Swift consuming ObjC** | `nullable` → `T?`, `nonnull` → `T` | **Implicitly unwrapped optional `T!`**: unaudited references import as IUO, permissive in both directions, trapping on a null deref ([nullability declarations](https://tanaschita.com/20230130-nullability-declarations-objective-c/)) |
| **C# consuming an oblivious dependency** | warnings per the annotation | **Oblivious (byte 0)**: no warning when dereferenced, no warning when assigned `null`. The compiler simply stops diagnosing. |

The convergent answer is a **permissive middle**: platform type, IUO, oblivious. All three are
*compiler-synthesized* representations of "unknown". Kotlin has one too, and it is exactly the right
semantic. It is also **unavailable to us**: a platform type has no source syntax (quoted above), and
this bridge emits Kotlin **source** (ADR-048), not compiler-internal descriptors. Only cinterop and
the JVM class-file reader can mint `T!`; a generator writing `.kt` files cannot. So `T!` is ruled out
on a hard constraint, not on taste, and the question collapses to: which *expressible* side of the
platform type do we snap "unknown" to?

## Alternatives Considered

### Decision 1: what an un-annotated (oblivious) reference type maps to

#### 1a. Non-null `T`, uniformly in every position, with a fail-fast bridge guard (chosen)

An oblivious type (`NullableAttribute(0)`, or no annotation anywhere in the chain) binds exactly like
an explicit non-null `1`: `String`, `Foo`, `var name: String`. If a null nevertheless arrives from
C#, the generated stub throws `IllegalStateException` naming the member.

**Pros:**

- **It is the closest expressible approximation of a platform type on the read side.** Kotlin's own
  platform type, assigned to a non-null variable, is "allowed, may fail at runtime (assertion
  emitted)". A generated non-null `T` plus a bridge-side null check is that behaviour precisely, with
  the assertion moved from the assignment to the boundary and given a much better message. Consumers
  of a legacy package get `String`, not `String?`, and write ordinary Kotlin.
- **It matches C#'s own treatment of oblivious types on the dereference side**: no warning, use it as
  if it were non-null. A Kotlin binding that forced `?.` on every legacy call would be *stricter* than
  the C# compiler is about the same assembly.
- **It is what this codebase already does.** v1 string returns are non-null with `?: error(...)`
  (ADR-048) and the assemblies behind them (e.g. `MimeMapping`) are un-annotated. Choosing non-null
  for oblivious is the status quo for strings, so no existing binding regresses.
- **It keeps null out of C#.** A non-null Kotlin parameter means the type system prevents a Kotlin
  `null` from ever reaching a thunk. That matters today: reverse exception propagation is Phase 11, so
  an `NullReferenceException` inside an `[UnmanagedCallersOnly]` thunk fast-fails the whole host
  process (ADR-049 "let it crash").
- Fail-fast with a message naming the member is the house style (CLAUDE.md: `require`/`check` with
  explicit messages; ADR-021's disposed-object guard forward).

**Cons:**

- A legacy API that *does* return null gives an `IllegalStateException` at the bridge instead of a
  `null` the consumer could handle. This is the accepted cost, and it is the same cost a Kotlin
  developer already accepts on every un-annotated Java call. The escape hatch (1e) exists if a real
  package proves painful.
- Kotlin cannot pass `null` to an oblivious parameter that would have accepted it. In practice a
  legacy API that accepts null almost always has a non-null alternative, and today the caller cannot
  pass null at all (ADR-051), so this is not a regression.

#### 1b. Nullable `T?` for oblivious (pessimistic, "sound")

Bind every un-annotated reference type as `T?`.

**Pros:** never lies; a null from C# is a value, not an exception. Formally sound.

**Cons (rejected):** it poisons every pre-C#-8 package with `?.`/`!!` on every single call, including
`MimeMapping.MimeUtility.GetMimeMapping(path)!!`, which is *never* null. That directly violates GOALS
("Kotlin bindings should feel like Kotlin, never like a wrapper around the other language"). It is
also more pessimistic than the C# compiler itself, which issues no warning at all for the same
assembly. And it would regress today's non-null string bindings into `String?` for zero real safety
gain, since the null those `!!`s guard against essentially never materialises.

#### 1c. A platform type `T!` (rejected: not expressible)

The ideal answer, and the one Kotlin/Java, Kotlin/ObjC and Swift/ObjC all use. Ruled out on a hard
constraint: "Platform types can't be mentioned explicitly in the program, so there's no syntax for
them in the language" ([Java interop](https://kotlinlang.org/docs/java-interop.html)). Platform types
can only be *synthesized by the compiler* while loading a foreign descriptor (a `.class` file, an ObjC
header via cinterop). This project generates Kotlin source that is then compiled by the ordinary
Kotlin compiler (ADR-048), so it can only emit types the language can spell. Achieving `T!` would mean
becoming a compiler plugin or a cinterop-style klib producer, which is a different project.

#### 1d. Asymmetric permissiveness: non-null returns, nullable parameters (rejected)

The literal emulation of a platform type's permissiveness: an oblivious *return* binds `T` (free to
consume), an oblivious *parameter* binds `T?` (free to pass null). Both directions warning-free, which
is exactly what C# oblivious means.

**Cons (rejected):**

- **It fractures on properties, the one member this ADR must fix.** A C# property carries a *single*
  `NullableAttribute`; a Kotlin `var` has a single type. There is no coherent way to be "permissive in
  both directions" for `Foo Bar { get; set; }`, which is precisely the ROADMAP line-157 blocker. The
  rule would have to special-case properties, and then it is no longer one rule.
- It invites Kotlin callers to pass `null` into a legacy API that never claimed to accept it. With
  Phase 11 outstanding, the resulting `NullReferenceException` inside the thunk kills the host process
  (ADR-049). The non-null parameter is the only thing preventing that today.
- It reads as a bug: `Foo Bar(Foo x)` binding to `fun bar(x: Foo?): Foo` looks like the annotation was
  inverted.

#### 1e. A per-dependency DSL switch on `bind {}` (deferred, not rejected)

`bind { obliviousNullability = NON_NULL | NULLABLE }` (ADR-044's `bind` block is the natural home).

Deferred rather than rejected. It is a genuine escape hatch for a package where the optimistic default
misfires, and it composes cleanly with 1a (which becomes the default value). But shipping a knob
before a single real package has demonstrated the need is speculative configuration, and the knob's
value is only discoverable once diagnostics (below) tell the user their package is oblivious at all.
Revisit when the ROADMAP's "reverse-bridge integration tests against real published NuGet packages"
item surfaces a concrete offender.

#### Whole-assembly oblivious vs. an oblivious island (both chosen, same policy, different diagnostics)

The *policy* is one rule (non-null), but the two situations mean different things and deserve different
signal. Both are emitted at **warning** severity, not info: "we assumed non-null because the package
told us nothing" is exactly the kind of silent assumption ADR-043's fail-fast premise exists to surface,
and an info-level line in a Gradle build is a line nobody reads. The two differ only in granularity:

- **The whole assembly is oblivious** (pre-C# 8, or `<Nullable>disable</Nullable>`): not a single
  `NullableAttribute`/`NullableContextAttribute` anywhere. This is a legacy package. Emit **one**
  `info_oblivious_nullability` diagnostic for the assembly, warned **once per assembly**. A per-member
  warning here would print hundreds of lines and train users to ignore the log.
- **One member is oblivious inside an otherwise-annotated assembly** (a `#nullable disable` region):
  a deliberate island of "unknown" in a codebase that otherwise knows. Emit one
  `info_oblivious_nullability` **per member**: it is rare, precise, and actionable ("this member's
  nullability is unknown; the binding assumes non-null").

Both are surfaced through the existing `RirDiagnostic` model (ADR-043) and the warning path
`NugetGenerateBindingsTask` already uses for `SKIPPED_MEMBER_NAME_COLLISION`. The plumbing folds into
ROADMAP line 142 ("surface `RirDiagnostic` to the build"), which already calls out that the
collision warning is a one-off to be generalized: this ADR adds a *diagnostic*, not a second one-off
warn path. A consequence to accept up front: if `MimeMapping` 4.0.0 turns out to be un-annotated, every
build of the sample prints one warning naming it. That is the correct signal, not noise.

---

### Decision 2: how nullability is encoded in the RIR

#### 2a. A `nullable` flag on the reference-typed `RirTypeRef` variants (chosen)

```kotlin
@Serializable @SerialName("string")
data class RirStringType(val nullable: Boolean = false) : RirTypeRef        // was: data object

@Serializable @SerialName("handle")
data class RirObjectHandleType(
  val namespace: String,
  val name: String,
  val nullable: Boolean = false,
) : RirTypeRef

// RirVoidType, RirPrimitiveType, RirEnumType gain nothing: a nullable value type is
// System.Nullable<T>, a distinct closed generic struct (Decision 3), not an annotation.
val RirTypeRef.isNullable: Boolean get() = when (this) {
  is RirStringType -> nullable
  is RirObjectHandleType -> nullable
  else -> false
}
```

**Pros:**

- **Nullability is part of the type in Kotlin.** `String?` *is* a type, and the generators' three
  mapping tables (`kotlinType`, `csAbiType`, `csNativeType`) take a `RirTypeRef`. Putting the flag on
  the type ref lets each table render `?` locally, with no extra boolean threaded through every call
  site.
- **It is the faithful model of the C# encoding.** The `byte[]` payload is a *tree over type nodes*
  (outer type first, then its type arguments, recursively); `NullableContextAttribute` is only a
  compression scheme for the defaults, and the attribute's placement on the `Param`/`Property` row is
  just where the tree's root is anchored. A per-type-node flag reproduces that; a per-slot flag
  reproduces only the anchor.
- **It survives Phase 10.** When `List<string?>` becomes bridgeable, a `RirGenericType(name, args)`
  node's arguments each carry their own `nullable`. With flags on the carrier instead, nullability
  would have to live in two places at once.
- Illegal states stay unrepresentable: there is no `RirVoidType(nullable = true)`.
- Additive on the wire: `{"kind":"string"}` still parses (default `false`), and `RirParsing` sets
  `ignoreUnknownKeys = true`.

**Cons:**

- `RirStringType` stops being a `data object`, so roughly 40 existing call sites (`type = RirStringType`
  in the generator tests, `RirStringType.Instance` in `Program.cs`) become `RirStringType()` /
  `new RirStringType(nullable)`. Mechanical, and the tests for this feature touch them anyway.

#### 2b. Flags on the carrier nodes (`RirParameter.nullable`, `RirProperty.nullable`, `RirMethod.returnNullable`) (rejected)

**Pros:** zero churn. `paramConversion(p: RirParameter)` and `thunkParamName(p)` already receive the
carrier, so they could read `p.nullable` for free, and every existing test compiles untouched. It also
mirrors *where the attribute physically sits* in metadata (on the `Param` / `Property` row).

**Cons (rejected):** it models the anchor rather than the payload. It cannot express the nullability of
a nested type argument, so Phase 10 would either re-do this decision or end up with nullability in two
places. It also needs a `returnNullable` field sitting awkwardly beside `returnType` on `RirMethod`,
which is the same information split across two fields. The churn saved is a one-time mechanical cost;
the modelling error would be permanent.

#### 2c. A `RirNullableType(inner: RirTypeRef)` wrapper (rejected)

Every `is RirStringType` / `is RirObjectHandleType` check in both generators (about 20 sites) would
have to unwrap first, and any missed site silently misbehaves. It also admits nonsense
(`RirNullableType(RirVoidType)`). Strictly worse than 2a for the same expressive power.

---

### Decision 3: `Nullable<T>` value types (`int?`) (deferred)

**Deferred to the Phase 9 structs item (ROADMAP line 158), or its own ADR.** Reasons:

- **It is not this feature.** `int?` is `System.Nullable`1<int>`, a closed generic struct in the
  signature. It carries no `NullableAttribute`; the annotation machinery this ADR builds is irrelevant
  to it. It is discovered at `GetGenericInstantiation`, where every generic instantiation is currently
  dropped.
- **It needs a wire-format decision this ADR has no reason to make.** A nullable `int` cannot cross as
  an `int`: there is no spare bit pattern. The forward direction's answer is ADR-002's two-call pattern
  (`_has_value` + `_value`), which that ADR itself documents as unsound for anything with side effects,
  and which in reverse would mean calling a C# property getter twice per read. The likely reverse answer
  is a single-call out-parameter (`byte X_Thunk(int* outValue)`, one crossing, one evaluation), or a
  packed struct return. Picking between them is a real decision with its own tradeoffs, and it belongs
  with structs (the general "value types on the wire" problem) rather than with reference-type
  annotations.
- **The cost of waiting is low.** A member with an `int?` in its signature is silently skipped today and
  continues to be. Nothing regresses.

Nested and generic annotations (`List<string?>`, `string?[]`) are likewise out of scope: those types are
not bridgeable at all until Phase 10. The decoder still reads the `byte[]` form and takes index 0, so it
is robust against them appearing on a member it *does* bind.

C#'s nullable-analysis attributes (`[MaybeNull]`, `[NotNull]`, `[AllowNull]`, `[DisallowNull]`,
`[NotNullWhen]`) refine a contract beyond what a single annotation says (for example `[AllowNull] string
Name { get; set; }`: the getter never returns null, the setter accepts null). Kotlin's `var` cannot
express getter/setter-asymmetric nullability, so v1 **ignores** them and binds from the property's own
`NullableAttribute`. Documented, deferred.

## Decision

Take **1a + 2a**, defer **3**.

### The mapping rules

| C# (annotated) | `NullableAttribute` byte | Kotlin |
|---|---|---|
| `string` | 1 (or via `NullableContext(1)`) | `String` |
| `string?` | 2 | `String?` |
| `Foo` | 1 | `Foo` (was `Foo?` for returns under ADR-051) |
| `Foo?` | 2 | `Foo?` |
| `string` / `Foo` in a `#nullable disable` region, or in a pre-C#-8 assembly | 0 (or absent) | `String` / `Foo`, plus an `info_oblivious_nullability` diagnostic |
| `int`, `CatMood` | not applicable (value types are skipped in the payload) | `Int`, `CatMood` |
| `int?`, `CatMood?` | not applicable (`System.Nullable<T>`) | not bridgeable in v1 (unchanged) |

The rule is uniform across returns, parameters, constructor parameters and properties. A property has
one annotation, so its getter and setter share one Kotlin type, which is what unblocks ROADMAP line 157.

### RIR model (`RirModel.kt` + the mirrored C# model in `Program.cs`)

As shown in Alternative 2a: `nullable: Boolean = false` on `RirStringType` and `RirObjectHandleType`,
plus a `RirTypeRef.isNullable` accessor in `RirBridging.kt`. `RirVoidType`, `RirPrimitiveType` and
`RirEnumType` are unchanged. One new diagnostic kind:

```kotlin
@SerialName("info_oblivious_nullability")
INFO_OBLIVIOUS_NULLABILITY,
```

`reverse-ir.json` shape (additive; absent `nullable` parses as `false`):

```json
{
  "name": "Lookup",
  "returnType": { "kind": "handle", "namespace": "Sample.Nullability", "name": "Nickname", "nullable": true },
  "parameters": [
    { "name": "name", "type": { "kind": "string", "nullable": false } }
  ],
  "isStatic": false
}
```

### Metadata reader (`nuget-metadata-reader/Program.cs`)

1. **Fix parameter lookup to key on `Parameter.SequenceNumber`** (1-based; 0 is the return value), not
   on list position. Prerequisite: the first nullable-annotated return in any bound assembly creates a
   `Sequence == 0` `Param` row and shifts every positional lookup.
2. Read `NullableAttribute` (`byte` or `byte[]`, take index 0) and `NullableContextAttribute` (`byte`)
   off the `Param` (including the return pseudo-parameter), `Property`, `MethodDef` and `TypeDef` rows,
   via `GetCustomAttributeTypeName` (already handles the synthesized-in-assembly `MethodDefinition`
   constructor form) plus `CustomAttribute.DecodeValue` or a direct `BlobReader`.
3. Resolve each reference-typed slot through the fallback chain (member → method → type → 0) and set
   `nullable = (byte == 2)` on the emitted `RirStringType` / `RirObjectHandleType`. Byte 0 and byte 1
   both produce `nullable = false`; only their *diagnostics* differ.
4. Track whether any nullable attribute was seen anywhere in the assembly. If none was, emit one
   assembly-level `info_oblivious_nullability`. Otherwise emit one per oblivious member.

### Generated Kotlin (`NugetGenerateBindingsTask`)

`kotlinType(type)` appends `?` when `type.isNullable`. The three shapes that change:

```kotlin
// nullable string return: no more `?: error(...)`
fun find(name: String): String? {
  val fn = requireNotNull(findFn) { ... }
  val resultPtr = memScoped { fn.invoke(handle.require("NicknameBook"), name.cstr.ptr) }
    ?: return null
  val result = resultPtr.reinterpret<ByteVar>().toKString()
  freeManagedString(resultPtr)
  return result
}

// nullable string parameter: null becomes a null pointer
fun greet(name: String?): String {
  val fn = requireNotNull(greetFn) { ... }
  val resultPtr = memScoped {
    fn.invoke(handle.require("NicknameBook"), if (name == null) null else name.cstr.ptr)
  } ?: error("NicknameBook.Greet returned null, expected a non-null string pointer")
  ...
}

// non-null handle return (NEW: was always `Foo?` under ADR-051)
fun defaultNickname(): Nickname {
  val fn = requireNotNull(defaultNicknameFn) { ... }
  val ptr: COpaquePointer? = fn.invoke(handle.require("NicknameBook"))
  return Nickname(requireNotNull(ptr) {
    "NicknameBook.DefaultNickname returned null, but the C# API annotates it non-null."
  })
}

// nullable handle return (unchanged shape, now driven by metadata)
fun lookup(name: String): Nickname? { ...; return ptr?.let { Nickname(it) } }

// nullable handle parameter (NEW: null crosses as IntPtr.Zero)
fun describe(nickname: Nickname?): String {
  val fn = requireNotNull(describeFn) { ... }
  val resultPtr = fn.invoke(
    handle.require("NicknameBook"),
    nickname?.handle?.require("Nickname"),
  ) ?: error(...)
  ...
}

// settable handle property (NEW: ROADMAP line 157 unblocked; "rule 4" in RirBridging deleted)
var favourite: Nickname?
  get() { ...; return ptr?.let { Nickname(it) } }
  set(value) {
    val fn = requireNotNull(favouriteSetterFn) { ... }
    fn.invoke(handle.require("NicknameBook"), value?.handle?.require("Nickname"))
  }

var primary: Nickname       // non-null annotated: getter requireNotNulls, setter passes the handle
```

`bridgeableRegistrables()` drops its handle-typed-property special case: a settable property now always
gets a `PropertySetter` slot, whatever its type. The registration-slot ordering (constructor, static
methods, instance methods, property getter/setter pairs) is otherwise unchanged, but the slot **count**
changes for any type with a settable handle property, so both generators regenerate together as usual.

### Generated C# thunks (`NugetGenerateShimsTask`)

The ABI is unchanged. `IntPtr.Zero` is already the null sentinel on both wires, and the string helpers
are already null-capable in both directions:

- [`Marshal.StringToCoTaskMemUTF8(string? s)`](https://learn.microsoft.com/en-us/dotnet/api/system.runtime.interopservices.marshal.stringtocotaskmemutf8)
  returns "an integer representing a pointer to the block of memory allocated for the string, **or 0 if
  `s` is `null`**". An empty string is *not* null: it allocates a one-byte buffer, so `""` and `null`
  stay distinguishable on the wire.
- [`Marshal.PtrToStringUTF8(IntPtr ptr)`](https://learn.microsoft.com/en-us/dotnet/api/system.runtime.interopservices.marshal.ptrtostringutf8)
  returns `string?`: "a managed string that holds a copy of the unmanaged string if the value of the
  `ptr` parameter is not `null`; otherwise, this method returns `null`". The current shim writes
  `Marshal.PtrToStringUTF8(p)!`, and that `!` is exactly what must be dropped for a nullable parameter.

So the only shim changes are local:

```csharp
// nullable string return: declare the local as string? (the `#nullable enable` shim would
// otherwise warn CS8600, which is an error in a warnings-as-errors consumer project)
string? result = receiver.Find(Marshal.PtrToStringUTF8(namePtr)!);
return Marshal.StringToCoTaskMemUTF8(result);          // null -> IntPtr.Zero, unchanged call

// nullable string parameter: drop the `!`
string result = receiver.Greet(Marshal.PtrToStringUTF8(namePtr));

// nullable handle parameter: IntPtr.Zero must be checked, because
// GCHandle.FromIntPtr(IntPtr.Zero) throws (ADR-051 reserved the sentinel for exactly this)
Nickname? nickname = nicknameHandle == IntPtr.Zero
    ? null
    : (Nickname)GCHandle.FromIntPtr(nicknameHandle).Target!;
string result = receiver.Describe(nickname);

// handle return: body unchanged for BOTH nullable and non-null annotations
Nickname? result = receiver.DefaultNickname();
return result is null ? IntPtr.Zero : GCHandle.ToIntPtr(GCHandle.Alloc(result));
```

The last one is deliberate: the null check stays even on a non-null-annotated return. `GCHandle.Alloc(null)`
is legal and produces a *non-zero* handle whose `Target` is null, which would sail across the bridge and
NRE inside some later thunk (killing the process). Returning `IntPtr.Zero` instead turns a lying library
into a clear Kotlin-side `IllegalStateException` naming the member. Same generated body, all the nullability
lives on the Kotlin side.

### Fixture surface (`sample-dependency/`)

New feature-scoped namespace `Sample.Nullability` (the standing convention for reverse fixtures), in a
new `sample-dependency/Nickname.cs`. The project is already `<Nullable>enable</Nullable>`, so the
oblivious case needs an explicit `#nullable disable` region.

```csharp
namespace Sample.Nullability;

/// Handle-typed partner for NicknameBook. Single public ctor, one read-only property.
public class Nickname
{
    public Nickname(string value) => Value = value;
    public string Value { get; }
}

public class NicknameBook
{
    public Nickname? Favourite { get; set; }                      // -> var favourite: Nickname?  (line 157)
    public Nickname Primary { get; set; } = new Nickname("Oreo"); // -> var primary: Nickname     (line 157)
    public string? Note { get; set; }                             // -> var note: String?

    public string? Find(string name) => name == "Oreo" ? "O" : null;   // -> fun find(name: String): String?
    public string Greet(string? name) => $"Hello, {name ?? "stranger"}"; // -> fun greet(name: String?): String
    public Nickname? Lookup(string name) => ...;                        // -> fun lookup(name: String): Nickname?
    public Nickname DefaultNickname() => Primary;                       // -> fun defaultNickname(): Nickname
    public string Describe(Nickname? nickname) => nickname?.Value ?? "none"; // -> fun describe(nickname: Nickname?): String
}

#nullable disable
/// Oblivious island inside an annotated assembly: every reference type here is byte 0.
/// Binds non-null, and raises one info_oblivious_nullability diagnostic per member.
public class LegacyNicknameBook
{
    public string Find(string name) => name;    // -> fun find(name: String): String
}
#nullable restore
```

`sample-library/build.gradle.kts` gains `include("Sample.Nullability")` and
`alias("Sample.Nullability", "sample.nullability")` in the `SampleDependency` `bind {}` block.

The **whole-assembly**-oblivious path is not reachable from `sample-dependency` (one assembly cannot be
both annotated and un-annotated). It is covered by a `generateKotlinStubs` unit test over a hand-built
`RirFile`, and, if `MimeMapping` 4.0.0 turns out to be un-annotated, by the existing end-to-end fixture
emitting exactly one warning for that package.

## Consequences

### New/changed artifacts

- `nuget-metadata-reader/Program.cs`: `SequenceNumber`-keyed parameter lookup (prerequisite fix);
  `NullableAttribute`/`NullableContextAttribute` decoding and the member → method → type → oblivious
  fallback chain; `nullable` on emitted `RirStringType`/`RirObjectHandleType`; assembly-level and
  member-level `info_oblivious_nullability` diagnostics; `RirStringType.Instance` singleton removed.
- `RirModel.kt`: `RirStringType` becomes a data class with `nullable`; `RirObjectHandleType` gains
  `nullable`; `RirDiagnosticKind.INFO_OBLIVIOUS_NULLABILITY`.
- `RirBridging.kt`: `RirTypeRef.isNullable`; **delete "rule 4"** (the handle-typed settable property no
  longer collapses to a `val`, so it now emits a `PropertySetter` slot).
- `NugetGenerateBindingsTask`: `?` in `kotlinType`; null-guarded string/handle argument conversion;
  `requireNotNull` on non-null handle returns; `?: return null` on nullable string returns; `var` for
  settable handle properties; warn on `INFO_OBLIVIOUS_NULLABILITY` via the existing diagnostic path.
- `NugetGenerateShimsTask`: `string?` locals for nullable string returns; drop `!` on nullable string
  parameters; `IntPtr.Zero` guard on nullable handle parameters.
- `sample-dependency/Nickname.cs` (new), `sample-library/build.gradle.kts` (`bind` include/alias).

### Behavioural changes (breaking, at the generated-API level)

- **Handle returns lose their unconditional `?`.** `Template.parse(...)` and `template.clone()` are
  declared non-null in the fixture (the project is `<Nullable>enable</Nullable>`), so they become
  `Template`, not `Template?`. Existing `sample-library` call sites that write
  `requireNotNull(Template.parse(...))` must drop the `requireNotNull`. This is the intended correction
  of ADR-051's flagged judgment call; the reverse direction ships as a labelled preview (MVP.md), so no
  compatibility promise is broken.
- **A settable handle property becomes a `var`.** Registration slot counts grow by one per such property;
  both generators regenerate together, so nothing hand-written observes it.
- An un-annotated (legacy) package binds exactly as it does today: non-null everywhere. The only visible
  change is one new build warning naming the package.
- A null arriving where the metadata says non-null is a loud `IllegalStateException` at the bridge, not a
  silent `null`.

### Deferred

- `Nullable<T>` value types (`int?`, `CatMood?`): needs a wire format (out-parameter vs. two-call vs.
  packed struct). Fold into ROADMAP line 158 (structs) or give it its own ADR.
- Nullability of generic type arguments and arrays (`List<string?>`): Phase 10.
- Nullable-analysis attributes (`[AllowNull]`, `[MaybeNull]`, `[NotNullWhen]`, ...): C# can express
  getter/setter-asymmetric nullability, Kotlin's `var` cannot. v1 ignores them.
- The `bind { obliviousNullability = ... }` DSL escape hatch (Alternative 1e): revisit when a real
  package demonstrates the need.
- Nullable *generic type parameters* (`T?` on an unconstrained `T`, where byte 0 has a third meaning):
  arrives with generics.

Nothing in this ADR is left open. The four questions raised during research are settled above:
oblivious binds non-null (Alternative 1a); the oblivious diagnostic is a **warning**, once per
oblivious assembly and once per oblivious member in an otherwise-annotated assembly (Decision 1,
"Whole-assembly oblivious vs. an oblivious island"); `Nullable<T>` value types are deferred
(Decision 3); the `bind { obliviousNullability = ... }` knob is deferred (Alternative 1e).
