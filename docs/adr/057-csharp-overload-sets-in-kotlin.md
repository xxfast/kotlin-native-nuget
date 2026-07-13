# ADR-057: C# overload sets in Kotlin — preserve Kotlin overloads, hash managed signatures for bridge identity

## Status

Accepted

## Context

Phase 9 currently rejects every public C# method group with more than one member and every class or
struct with more than one public instance constructor. That was the deliberate ADR-043 v1 ceiling,
but it excludes otherwise bridgeable APIs such as `Newtonsoft.Json.JsonConvert.SerializeObject`
and prevents ADR-056 Shape A structs such as `System.Drawing.Point` from being represented.

The ceiling is not Kotlin. **Inferred from the Kotlin language specification:** Kotlin supports
same-name callable and constructor overloads and chooses an applicable most-specific candidate from
the argument types. The public projection should therefore look like ordinary Kotlin, just as Java
overloads do when consumed from Kotlin:

```kotlin
val byText = Template("Hello, {name}")
val byCapacity = Template(64)

Template.describe(42)
Template.describe(true)

byText.apply("Oreo")
byText.apply(3)
```

The actual ceiling is generated bridge identity. Current source proves all of these names are
singletons:

- `Program.cs` groups public methods by name and emits `skipped_overload_set` before RIR; it also
  admits exactly one public class constructor and exactly one struct constructor.
- `RirMethod` and `RirConstructor` carry no managed signature identity.
- `RirBridging.kt` stores one constructor slot followed by methods in reverse-IR declaration order.
- both generators name method pointer variables and register parameters from the unsuffixed method
  name; the C# generator emits `${MethodName}_Thunk`; constructors use the singleton `ctorFn` /
  `Ctor_Thunk` names.

The implementation therefore needs one stable identity per overload, shared ordering on both sides,
and an explicit rule for the rarer case where distinct CLR signatures become the same Kotlin
signature after type mapping.

### Verified metadata spike: MethodDef row/order is not a durable overload identity

The load-bearing question is whether the reader can use `MethodDefinitionHandle` row numbers or
enumeration order as the suffix/order. A scratch-only probe compiled the same API twice with member
declarations reversed, decoded each assembly with `System.Reflection.Metadata`, and printed the
MethodDef row plus a canonical decoded signature. It used the installed .NET SDK 10.0.300 / TFM
`net10.0`; this result is **verified on that toolchain**, not verified on a net8.0 compiler:

```text
# Assembly A
Point row=3 key=.ctor(System.Int32):System.Void
Point row=4 key=.ctor(System.Int32,System.Int32):System.Void
Api row=5 key=.ctor(System.String):System.Void
Api row=6 key=.ctor(System.Int32):System.Void
Api row=7 key=Render(System.Int32):System.String
Api row=8 key=Render(System.String):System.String
Api row=9 key=Mix(System.Boolean):System.String
Api row=10 key=Mix(System.Char):System.String

# Assembly B, declarations reversed
Api row=1 key=Mix(System.Char):System.String
Api row=2 key=Mix(System.Boolean):System.String
Api row=3 key=Render(System.String):System.String
Api row=4 key=Render(System.Int32):System.String
Api row=5 key=.ctor(System.Int32):System.Void
Api row=6 key=.ctor(System.String):System.Void
Point row=9 key=.ctor(System.Int32,System.Int32):System.Void
Point row=10 key=.ctor(System.Int32):System.Void
```

**Verified:** `TypeDefinition.GetMethods()` exposed MethodDef table order in these assemblies, and
Roslyn changed both the rows and enumeration order when declarations/types were reordered.
**Verified:** the decoded fully-qualified signature keys remained identical. Consequently a row,
metadata token, source/declaration ordinal, or `_1`/`_2` suffix is deterministic only for one
assembly image, not stable identity across a harmless reorder. The design must derive identity from
the signature itself.

### Prior art

- **Kotlin consuming Java. Inferred from official Kotlin documentation and the language
  specification:** Java overloads keep one source name and are selected by Kotlin overload
  resolution; constructors remain constructors. This is the consumer idiom chosen here.
- **Kotlin/Native Objective-C import. Inferred from official Kotlin documentation:** Objective-C
  selectors can import to clashing Kotlin signatures; `@ObjCSignatureOverride` exists for the
  special override case. That confirms mapped-signature clashes are a real interop concern, but the
  annotation is not an appropriate generated-API escape hatch for unrelated C# overloads.
- **.NET for Android bindings. Inferred from Microsoft binding documentation:** binding authors can
  use metadata transforms such as `managedName` to repair source-to-consumer name collisions. This
  project has no per-member rename DSL today, so its equivalent escape hatch remains a C# adapter.
- **Python.NET. Inferred from Python.NET documentation:** it normally dispatches same-name CLR
  overloads by arguments and exposes `__overloads__` for explicit selection. Kotlin already has
  static overload resolution, so an explicit overload-index API would be foreign and unnecessary.
- **ADR-034 (forward mirror):** multiple Kotlin constructors remain C# constructors, distinct
  native entry points are internal, and a collision after C# mapping fails fast. This ADR inverts
  that rule for C# declarations projected into Kotlin.

## Alternatives Considered

### 1. Preserve the Kotlin name; use a canonical managed-signature hash internally (chosen)

Every supported CLR overload becomes an ordinary Kotlin overload. Each RIR callable also carries a
canonical managed signature and a short deterministic digest used only in generated implementation
identifiers. Registration slots use canonical ordering rather than metadata order.

Pros: idiomatic Kotlin; adding or reordering another overload does not rename existing thunk/pointer
identifiers; no metadata-token dependency; constructors stay constructors; one shared identity works
for class and struct constructors and static/instance methods. Cons: generated internals are less
readable; a digest collision needs a fail-fast check; adding an overload can still move slot indexes
within the canonical sort, which ADR-054's contract check detects across stale builds.

### 2. Preserve the Kotlin name; use `_1`, `_2`, ... after sorting

Pros: simple and readable internals. Cons: inserting a signature earlier in the sort renames all
later thunks and pointer variables. Rejected because an ordinal is position, not identity.

### 3. Put parameter type names in generated identifiers

For example, `Render_System_Int32_Thunk`. Pros: readable and usually stable. Cons: arbitrarily long
identifiers for nested/generic types; punctuation/escaping and namespace flattening create a second
collision problem; mapped type names are not necessarily CLR identities. Rejected in favour of a
fixed-width digest of an inspectable canonical string.

### 4. Rename overloads in the public Kotlin API

For example, `describeInt` / `describeBoolean`, or `constructor1` factories. Pros: avoids Kotlin
overload conflicts. Cons: unlike Kotlin-consuming-Java, leaks bridge machinery into autocomplete;
parameter-type names age badly when mappings change; factories are not constructors. Rejected.

### 5. Keep skipping the whole set or choose one overload

Rejected. Skipping all is the current Phase 8 bootstrap ceiling, not a C ABI limitation. Selecting
one silently makes public managed API disappear and is incompatible with the repository's fail-fast
policy.

## Decision

### 1. Public API: ordinary Kotlin overloads

Render the same lower-camel Kotlin name for every method in a CLR name group. Preserve receiver kind:
static overloads remain in the Kotlin `object`/`companion object`, and instance overloads remain on
the wrapper. Render every bridgeable public class constructor as a Kotlin secondary constructor.

**Inferred from the Kotlin overload-resolution specification:** overload selection uses parameter
types, not return type or parameter names. The collision key for a generated callable is therefore:

```text
(Kotlin declaration scope, Kotlin callable name, ordered declared Kotlin parameter types)
```

Constructor keys use `constructor` as the name. Fully-qualified Kotlin type identity and nullability
participate; parameter names and return type do not. Static and instance members are in different
generated scopes (`companion object` versus wrapper), so they are compared separately.

Two mapped types with the same simple name but different Kotlin packages are **not** a collision.
The generator must render one or both with a qualified name or deterministic import alias. Current
repository source reveals a pre-existing limitation here: `kotlinType()` reduces handle, enum and
struct references to `type.name`, and the fixtures do not exercise two cross-package referenced
types sharing that name. Collision detection must not turn that renderer limitation into a false
API-erasure diagnostic.

### 2. Canonical managed identity

Add a required identity field to both `RirMethod` and `RirConstructor` (name suggested:
`managedSignature`). The metadata reader constructs it from decoded metadata, before Kotlin mapping:

```text
method|static|Sample.Text.Template|Describe|(System.Int32)|System.String
method|instance|Sample.Text.Template|Apply|(System.String)|System.String
ctor|instance|Sample.Text.Template|.ctor|(System.Int32)|System.Void
```

Use invariant UTF-8 text with:

- member kind and static/instance receiver kind;
- fully-qualified declaring CLR type and metadata member name;
- generic arity (zero in this ADR's scope, retained in the grammar for future closed generics);
- ordered, fully-qualified decoded CLR parameter types, including array rank, by-ref/pointer markers,
  generic construction and custom modifiers if those shapes become supported;
- decoded CLR return type; and
- no parameter names, metadata rows/tokens, nullable-reference attributes, or source order.

Nullability is omitted because it is not CLR overload identity. It remains in the RIR types and in
ADR-054's contract signature. **Inferred from ECMA-335 and verified for the probe's supported
primitive/reference shapes:** a decoded metadata signature supplies the stable type identity needed
here; the exact future encoding for generics/arrays/by-ref must be verified when those features are
admitted.

Both reader and Gradle side validate that `managedSignature` is unique within the declaring type.
Hash it as lower-case hexadecimal SHA-256 and use the first 16 bytes (32 hex characters) as
`bridgeId`. SHA-256 is chosen over the existing 64-bit FNV contract hash because this value is also
an identifier namespace, not merely a stale-build detector. If two different canonical strings
produce the same truncated value, fail generation and print both full signatures; never append an
order-dependent tie-breaker.

Examples of generated internals (names are illustrative; the digest is computed):

```kotlin
internal var describe__2f0a…Fn: CPointer<CFunction<(Int) -> COpaquePointer?>>? = null
internal var describe__918c…Fn: CPointer<CFunction<(Boolean) -> COpaquePointer?>>? = null
internal var ctor__7d31…Fn: CPointer<CFunction<(Int) -> COpaquePointer?>>? = null
```

```csharp
private static IntPtr Describe__2f0a…_Thunk(int value) { ... }
private static IntPtr Describe__918c…_Thunk(byte value) { ... }
private static IntPtr Ctor__7d31…_Thunk(int value) { ... }
```

The public Kotlin declarations contain none of these suffixes.

### 3. Deterministic registration order

Replace reverse-IR declaration order with this total order, shared by both generators in
`bridgeableRegistrables()`:

1. class constructors, ascending `managedSignature`;
2. static methods, ascending `managedSignature`;
3. instance methods, ascending `managedSignature`;
4. instance properties, ascending canonical property identity, getter immediately before setter;
5. static properties, the same order and adjacency.

Properties are included so the whole table has one ordering rule; this feature does not add
overloaded/indexed properties. Use ordinal Unicode/code-point string comparison, not locale-aware
comparison. All generated function-pointer variables, register parameters/body, C# function-pointer
arguments, thunks, `slotCount`, and `contractHash` consume this exact ordered list.

The `bridgeId` keeps an overload's internal name stable when another overload is inserted. Its slot
can move because the order is a sorted table, but **repo-verified ADR-054 behaviour** rejects a stale
shim/native pair when count or contract hash differs. `contractHash` continues to include member
kind/name/return/parameters/nullability and ADR-056 struct component expansion; it additionally
includes `managedSignature` so two overloads can never contribute indistinguishable columns.

### 4. Map every member independently, then detect Kotlin collisions

Do not let one unsupported overload poison its siblings. Decode and validate every public member:

- bridgeable overloads enter RIR;
- an unsupported overload gets its existing precise diagnostic (`ref struct`, open generic,
  dynamic, unbound type, and so on); and
- the supported remainder is checked for Kotlin-signature collisions.

If two supported CLR callables map to the same Kotlin collision key, fail `nugetGenerateBindings`
before writing sources. Name both managed signatures, show the common Kotlin signature, and suggest a
differently named C# adapter. This is the reverse of ADR-034's fail-fast C#-signature collision rule.
Do not silently select, public-rename, or warn-and-continue: the package's requested binding cannot
faithfully represent that API.

The existing `skipped_overload_set` enum value remains parse-compatible for old RIR JSON but is no
longer emitted for otherwise supported method/constructor sets. Add a structured
`error_kotlin_signature_collision` diagnostic (or an explicit diagnostic severity plus collision
kind) and make the centralized diagnostic reporter throw on `error_*` entries while continuing to
warn for `skipped_*`/`info_*`. Add a test that every severity reaches the correct Gradle behaviour,
closing the diagnostic-reporting gap already recorded in ROADMAP.

Phase 10 collection mappings must preserve their respective idiomatic Kotlin interfaces. For
example, `IReadOnlyList<T>` should map to `List<T>` while `IEnumerable<T>` should map to
`Iterable<T>`; overloads using those two CLR types therefore remain valid, distinct Kotlin
overloads. **Inferred:** no current or planned mapping has an ordinary C#-source example that must
collapse while retaining distinct fully-qualified CLR signatures. The collision rule is a
defensive invariant for a genuinely unavoidable future mapping collapse, not permission to erase
useful type distinctions.

### 5. Multiple class constructors

Remove ADR-052's singleton constructor rule. `RirClass.constructors` carries every independently
bridgeable public instance `.ctor`, each with its `managedSignature`. Each gets:

- one `RirRegistrable.Ctor` slot;
- a `ctor__{bridgeId}Fn` pointer and `Ctor__{bridgeId}_Thunk`;
- a bridge-ID-specific file-private Kotlin construct helper; and
- one public secondary `constructor(...)` on the wrapper.

Constructor return remains a non-null `GCHandle` pointer per ADR-052. Parameters reuse the normal
ABI expansion/conversion path, including ADR-056 struct decomposition. A collision with the
wrapper's internal `constructor(handle: COpaquePointer)` is impossible for current public mappings
because no public CLR type maps to raw `COpaquePointer`; assert it rather than relying on that
assumption silently.

### 6. Multiple constructors on ADR-056 Shape A structs

Multiple public constructors no longer make a struct unsupported. Separate two concepts which
ADR-056's one-constructor rule previously conflated:

1. **State constructor:** evaluate every public instance constructor with ADR-056 Decision 3a's
   case-insensitive parameter-to-readable-property and stored-field coverage rules. Exactly one must
   cover all stored state. Its ordered matched properties define `RirStruct.components` and the
   Kotlin `data class` primary constructor. It consumes no registration slot, as today.
2. **Alternate constructors:** every other independently bridgeable public constructor becomes a
   managed registration slot. Its thunk invokes the real C# constructor once, then writes every
   state component through ADR-056's existing out-pointer conversions. Kotlin renders a secondary
   constructor delegating to the primary with the returned components.

For a `Point`-like type, `Point(int x, int y)` is the unique state constructor and remains the data
class primary constructor. `Point(int value)` and `Point(Size size)` become bridge-backed secondary
constructors. A struct-typed alternate-constructor parameter expands with `abiArgs`; its result uses
one out-pointer per `Point` component. This does not enable nested struct *components*: `Size` is an
argument here, not stored recursively inside `Point`'s component list.

If no constructor or more than one constructor passes the state-coverage rules, keep the whole
struct unsupported with `skipped_unsupported_struct`: generating a value type without an unambiguous
complete component model would silently lose state. If an alternate constructor's Kotlin signature
collides with the data-class primary constructor or another alternate, apply Decision 4 and fail.

A struct with at least one alternate constructor now owns a `{Struct}Bindings.kt`, registration
export, and C# registration shim. It still has no object handle, `Cleaner`, or `close()`. A Shape A
struct with only its state constructor continues to cost zero slots, preserving ADR-056.

## Test Design

Implementation follows consumer-side TDD. The fixture should force every layer rather than test
only two primitive overloads.

### `sample-dependency`

Add `Sample.Overloads.OverloadLab`:

```csharp
public sealed class OverloadLab
{
    public OverloadLab(int seed) { ... }          // direct ABI scalar
    public OverloadLab(bool enabled) { ... }      // bool -> byte conversion

    public static string Describe(int value) => ...;
    public static string Describe(bool value) => ...;

    public string Apply(string value) => ...;     // UTF-8 conversion/free path
    public string Apply(int value) => ...;        // direct ABI scalar
}
```

Extend `Sample.Structs.Point` and add `Sample.Structs.Size`:

```csharp
public readonly struct Size
{
    public Size(int width, int height) { ... }    // unique state constructor
    public int Width { get; }
    public int Height { get; }
}

public readonly struct Point
{
    public Point(int x, int y) { ... }             // unique state constructor, zero slot
    public Point(int value) : this(value, value) { }
    public Point(bool unit) : this(unit ? 1 : 0, unit ? 1 : 0) { }
    public Point(Size size) : this(size.Width, size.Height) { }
    ...
}
```

This covers static and instance method overloads, class constructors, struct constructors, direct
and converted ABI values, and a struct-typed alternate-constructor parameter.

For the deliberate collision seam, keep the test synthetic and directly construct two distinct
canonical managed signatures with the same already-mapped Kotlin collision key. This tests the
defensive invariant without asserting that any planned CLR types should erase to one Kotlin type.
In particular, do not use `IReadOnlyList<T>` and `IEnumerable<T>` as the fixture: Phase 10 should
preserve them as `List<T>` and `Iterable<T>`, respectively. The expected result is one
`error_kotlin_signature_collision`, containing both canonical managed signatures, followed by a
failed generation task and no partial sources.

### `sample-library`

Add Kotlin functions which compile only if the natural overloads exist and dispatch correctly:

```kotlin
fun describeOverloads(i: Int, flag: Boolean): String =
  OverloadLab.describe(i) + "|" + OverloadLab.describe(flag)

fun applyOverloads(text: String, i: Int): String =
  OverloadLab(7).use { it.apply(text) + "|" + it.apply(i) }

fun classConstructorOverloads(): String =
  OverloadLab(7).use { a -> OverloadLab(true).use { b -> ... } }

fun structConstructorOverloads(): String {
  val primary = Point(2, 3)
  val scalar = Point(4)
  val converted = Point(true)
  val nestedArgument = Point(Size(5, 6))
  return ...
}
```

### `SampleApp.Tests`

Add end-to-end assertions for each function above. Assert distinct results for every overload so a
wrong pointer order cannot pass accidentally. Include at least one call to every class constructor,
every Point constructor, both static overloads and both instance overloads. Keep the normal fixture
package/versioning path; do not patch NuGet caches or generated shims.

### Generator and reader tests

- Reader integration: all supported overloads and constructors appear in RIR, each canonical
  signature is exact and unique, and no `skipped_overload_set` is emitted.
- Canonical identity: reverse source declaration order and assert unchanged canonical strings and
  bridge IDs.
- RIR parsing: old JSON without overloads remains readable only if the migration policy supplies a
  temporary derived identity; new generated JSON always contains the field. Prefer a required field
  and an explicit contract-version bump if no compatibility requirement exists.
- Shared ordering: shuffled RIR input produces the specified category/signature order in both
  generators.
- Kotlin generator: public names stay unsuffixed; internal fn vars/helpers/register params carry
  distinct bridge IDs; all overloaded methods/constructors render.
- C# generator: distinct thunk identifiers call the correct C# overload and use the correct ABI
  conversion (`bool`/string versus direct int); module initializer pointers match shared order.
- Contract: adding/reordering/removing an overload changes `slotCount` and/or `contractHash` as
  appropriate; merely reordering declarations before extraction does not change the generated
  contract.
- Struct: the unique state constructor defines components and costs no slot; every alternate
  constructor gets one slot and returns all components via out-pointers; `Point(Size)` decomposes
  `Size` on input.
- Collision: two distinct managed signatures with one mapped Kotlin key fail before files are
  written and print both signatures plus the adapter hint.
- Non-collision control: overloads taking `Left.Token` and `Right.Token` render qualified/aliased
  Kotlin parameter types and remain valid distinct overloads despite the shared simple name.
- Digest collision: force/inject equal bridge IDs for unequal canonical strings and assert a
  fail-fast diagnostic.

## Consequences

- Ordinary C# overloads become ordinary Kotlin overloads; no public suffix API is introduced.
- Reordering declarations in a managed library does not rename bridge internals or reorder slots.
- Adding/removing an overload changes the registration contract in a deterministic way; ADR-054
  reports stale shim/native mixtures rather than allowing silent pointer drift.
- A single unsupported overload no longer hides its bridgeable siblings.
- A true post-mapping Kotlin collision fails the binding with an actionable error, matching the
  forward ADR-034 policy.
- ADR-056 structs can have multiple public constructors while retaining data-class value semantics;
  only alternate managed constructors add registration slots.
- Existing generated source snapshots and slot-order tests will change because registration order
  becomes canonical rather than reverse-IR declaration order.

### Verified claims

- On the scratch .NET SDK 10.0.300 / net10.0 probe, MethodDef rows and `GetMethods()` order followed
  declaration order and changed when declarations/types were reordered.
- On that probe, fully-qualified decoded signatures stayed identical across the reorder.
- Repository source proves the current reader drops overload groups before RIR and the generators
  use singleton unsuffixed method/constructor identifiers and reverse-IR order.
- Repository source proves referenced handle/enum/struct Kotlin types are currently rendered from
  their simple `name`; cross-package same-simple-name references are an untested pre-existing seam.

### Inferred claims

- Kotlin's normal overload and constructor resolution is the correct consumer model (official
  Kotlin specification/documentation; not compiler-spiked here).
- The canonical grammar's future generic/array/by-ref/custom-modifier clauses are sufficient; each
  must be verified when that construct enters the bridgeable subset.
- Current admitted Phase 9 mappings and the planned distinct Phase 10 collection-interface
  mappings do not produce a known normal C#-source mapped-signature collision. The implementation
  nevertheless enforces the rule as a defensive invariant for a genuinely unavoidable future
  collapse.
- SHA-256 truncated to 128 bits is operationally collision-resistant. Correctness does not depend
  on that inference because unequal canonical strings sharing a digest are explicitly detected and
  rejected.

## Scope

**This ADR:** overloads of public class static/instance methods whose parameter/return types are
already bridgeable; multiple public constructors of bound classes; multiple constructors of
ADR-056 Shape A structs with exactly one state-covering constructor; scalar/string/enum/handle and
existing struct-decomposition conversions; deterministic canonical identity/order; fail-fast
post-Kotlin-mapping collision diagnostics.

**Deferred:** generic methods and open generic types (ADR-043); closed constructed generics and
collection mappings (Phase 10), which must preserve distinct idiomatic Kotlin interfaces rather
than manufacture collisions; `ref`/`out`/`in`, `params`, optional/default arguments, and function
pointers; indexer/overloaded property projection; overload choice by return type for non-C# IL;
per-member rename/selection DSL; Shape B
structs, nested struct components, handle-typed struct components, and struct methods/computed
properties except the alternate-constructor registration machinery decided here; exception
propagation from managed thunks (Phase 11).

## Sources

- [Kotlin overload resolution specification](https://kotlinlang.org/spec/overload-resolution.html)
- [Kotlin calling Java from Kotlin](https://kotlinlang.org/docs/java-interop.html)
- [Kotlin/Native Objective-C interop](https://kotlinlang.org/docs/native-objc-interop.html)
- [.NET for Android binding metadata transforms](https://learn.microsoft.com/en-us/dotnet/android/binding-libs/customizing-bindings/java-bindings-metadata)
- [Python.NET overload selection](https://pythonnet.github.io/pythonnet/python.html#overloaded-and-generic-methods)
