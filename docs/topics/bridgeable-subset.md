# The bridgeable subset

Not every C# construct visible in assembly metadata can cross the C ABI. This page describes exactly
what binds and what doesn't, read directly from `NugetMetadataReader/Program.cs`, the tool that
does the actual filtering. Where the ADR prose and the reader's real behaviour could be read two
ways, this page follows the code.

## Types

| Construct | Binds? |
|---|---|
| Public top-level class | Yes, if it has at least one bridgeable member |
| Public top-level static class | Yes, as a Kotlin `object` (see [Static classes and methods](static-classes-and-methods.md)) |
| Interface | No members are bound to Kotlin; a default (non-abstract, non-static) interface method is explicitly skipped and diagnosed |
| Enum | Yes, as a standalone Kotlin `enum class`, when it is public, top-level, default-`int` backed, non-`[Flags]`, and has unique contiguous values from `0` through `N-1` |
| Struct / value type | Yes, but never as a **handle**. A struct is its own RIR node (never emitted as a class), and `CollectBoundHandleTypeNames` explicitly excludes any type whose base type is `System.ValueType` or `System.Enum`, so it can never become an object-handle parameter or return. A bridgeable struct decomposes into an immutable Kotlin `data class` instead, whether it has a state constructor ("Shape A") or only public settable fields/auto-properties ("Shape B"); see [C# structs](structs.md) |
| `ref struct` (`Span<T>`, `ReadOnlySpan<T>`, custom) | No. Detected via the `IsByRefLikeAttribute` custom attribute; any member referencing one is skipped and diagnosed (`skipped_ref_struct`) |
| Nested type (public or not) | No. The reader filters on `TypeAttributes.VisibilityMask == Public`, which excludes `NestedPublic` as well as every non-public visibility. Only top-level public types are candidates at all |
| Generic type | Not explicitly filtered at the type level. A generic class is still enumerated as a candidate type, but virtually every member whose signature actually uses the open type parameter is skipped per-member (`skipped_open_generic`) when the parameter or return type is decoded, so a generic type in practice binds nothing unless it happens to expose non-generic members |
| Record | Not recognized as a distinct construct at all: a C# `record class` compiles to an ordinary class in IL with no marker, and each constructor/member is evaluated by the same rules as any other class |

Classes (static or not) and supported enums produce Kotlin output. Interfaces are extracted into the
RIR as `RirInterface` but no stub is generated from them today.

## Enums

A supported C# enum becomes a standalone Kotlin `enum class`. Its member names are converted to
Kotlin `SCREAMING_SNAKE_CASE`. Enum values cross the C ABI as their ordinal `Int`, so enum arguments,
returns, constructors, and properties use the generated Kotlin enum type without allocating an
object handle or registration table for the enum itself.

```c#
// TestDependency/CatMood.cs
public enum CatMood
{
    Playful,
    Sleepy,
    Hungry,
}
```

```kotlin
// build/nuget-interop/kotlin/nativeMain/sample/enums/CatMood.kt
enum class CatMood {
  PLAYFUL,
  SLEEPY,
  HUNGRY
}
```

An enum can appear in the supported members of a bound class. This Kotlin sample uses an enum
constructor argument, an instance property, a static property, and an enum return value:

```kotlin
// test-library/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/sample/enums/CatMoodSample.kt
fun catMoodRoundTrip(): CatMood {
  CatMoodService.defaultMood = CatMood.SLEEPY

  val service = CatMoodService(CatMood.PLAYFUL)
  service.currentMood = CatMood.HUNGRY

  check(service.readDefault() == CatMood.SLEEPY) {
    "CatMoodService.defaultMood did not round trip through its instance method"
  }

  return service.advance(service.currentMood)
}
```

### Enum limitations

- `[Flags]` enums do not bind.
- The underlying type must be the default C# `int`.
- Values must be unique and contiguous from `0` through `N-1`. Explicit, sparse, negative, and
  aliased values do not bind.
- Nullable enums, nested enums, and enums in collections or generic type arguments are not yet
  supported.

Unsupported enums are excluded with a `skipped_unsupported_enum` diagnostic in `reverse-ir.json`.

## Constructors

- Every independently bridgeable public instance constructor on a class binds as a Kotlin secondary
  constructor (see [Objects and handles](objects-and-handles.md)).
- A Shape A struct has exactly one state-covering constructor that defines its data-class
  components. Other bridgeable public constructors bind as secondary constructors backed by
  registration slots (see [C# structs](structs.md)).
- A Shape B struct (no state-covering constructor; public settable fields/auto-properties instead)
  binds **no** constructors at all. Its `data class` primary constructor already reaches every
  component, so every public constructor on it, alternate or otherwise, is skipped with a diagnostic
  rather than bound (see [C# structs](structs.md)).
- A non-public constructor is excluded before the constructor-admission check even runs: the reader
  tests `MethodAttributes.MemberAccessMask == Public` first, so `private`/`internal`/`protected`
  constructors are silently invisible, never diagnosed.
- Static classes and interfaces never carry a constructor at all (a static class is `abstract
  sealed` in metadata and has no instance `.ctor`; interfaces have none by definition).

## Methods and properties

- **Static methods** and **instance methods** both bind on classes. Each overload is checked
  independently against the parameter/return type rules.
- **Instance properties** and **static properties** bind: read-only → `val`, settable → `var` (see
  [Instance members](instance-members.md) and [Static classes and methods](static-classes-and-methods.md)).
- **Struct members** also bind: public non-void instance methods, get-only computed properties that
  are not component `readName`s, and static methods (Kotlin `companion object`). Skipped on structs:
  `Equals`/`GetHashCode`/`ToString`/`Deconstruct`, operators, setters, void instance methods, and
  component auto-properties. Wire form reconstructs the receiver from leading component args (see
  [C# structs](structs.md)).
- **`async`/`Task`-returning methods do not bind.** The reader recognizes `Task`, `Task<T>`,
  `ValueTask`, `ValueTask<T>`, and `IAsyncEnumerable<T>` by name and emits an *informational*
  diagnostic (`info_async_not_yet_mapped`), not a skip-with-reason like the others; the method is
  still excluded from the generated output either way.

### The visibility mask bug this project already hit

Methods and property accessors are filtered with `(attrs & MemberAccessMask) == Public`, an exact
equality check against the masked value, not a non-zero `AND` test. This distinction matters: `AND`
against `Public` (`0x6`) also matches `Assembly`/`internal` (`0x3`) and `Family`/`protected` (`0x4`)
because both share bits with `0x6`. An earlier version of the reader used the non-zero-AND form and
leaked internal members through as if they were public; it's called out here because it's exactly
the kind of subtle metadata-reading detail that's easy to reintroduce.

## Overload sets

Bridgeable C# method and constructor overloads keep their name and parameter types as ordinary
Kotlin overloads. An unsupported member is diagnosed independently and does not hide supported
siblings. Generated thunk and function-pointer names use a stable identifier derived from the full
managed signature, so they remain unique without changing the public Kotlin API.

If two different managed signatures map to the same Kotlin scope, name, and ordered parameter
types, `nugetGenerateBindings` fails with `error_kotlin_signature_collision` and names both managed
signatures. This is a defensive invariant, not a reason to collapse useful type distinctions.
Phase 10 mappings must retain their respective interfaces, for example
`IReadOnlyList<T>` as `List<T>` and `IEnumerable<T>` as `Iterable<T>`, so overloads using them remain
distinct.

## The bridgeable type vocabulary

| Kind | Bridges as |
|---|---|
| `void` | return position only |
| `string` | UTF-8 marshalled `IntPtr` |
| `bool`, `byte`, `short`, `int`, `long`, `float`, `double`, `char` | primitives, direct or narrowed for ABI blittability |
| A supported enum | ordinal `Int`, converted to and from a Kotlin `enum class` |
| A bound, non-static, non-value-type, non-`ref struct` class from the current extraction | an opaque `GCHandle`-backed pointer (a "handle" type) |
| A bridgeable struct (Shape A or Shape B) | decomposed onto the wire, one ABI argument per component (parameter) or one out-pointer per component (return); surfaces as an immutable Kotlin `data class`, no handle. Methods and get-only computed properties on the struct bind via reconstruct-on-call (leading component args). See [C# structs](structs.md) |

`string` and a bound handle type both carry real nullability now: a `NullableAttribute`/
`NullableContextAttribute`-derived `nullable` flag on the `RirTypeRef` decides `String` vs. `String?`
and `Foo` vs. `Foo?` for every return, parameter, and property position (see
[Objects and handles](objects-and-handles.md) and [Instance members](instance-members.md); ADR-053).
`Nullable<T>` value types (`int?`, `CatMood?`) are the one nullable shape that still does not bridge:
they carry no `NullableAttribute` at all (a nullable value type is `System.Nullable<T>`, a distinct
closed generic struct). The wire format for it is no longer an open question: [ADR-056](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/056-csharp-structs-in-kotlin.md)'s
struct out-pointer convention is exactly the format `Nullable<T>` needs, so this is unblocked and
needs no further ADR, only the reader work to stop dropping `System.Nullable<T>` at
`GetGenericInstantiation` (tracked in [ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md) Phase 9).

Everything else, arrays, collections, delegates, `dynamic`, `object`, open generics, does not bridge.
`System.String` is the only external (out-of-assembly) reference type recognized; every other
external type reference is unbound and diagnosed (`skipped_unbound_type_reference`).

### Why a type reference might be "unbound" rather than a hard type mismatch

A parameter or return whose type is a class from a namespace you didn't `include()`, or from an
assembly outside this extraction run entirely, isn't a type-vocabulary failure, it's an *unbound*
reference: the type exists, the reader can name it, but it wasn't part of the bound set, so it can't
become a handle. This produces the same `skipped_unbound_type_reference` diagnostic whether the type
is external (e.g. a type from a NuGet dependency you didn't declare) or merely excluded by your own
`include()`/`exclude()` filters.

## Exceptions are not propagated

A C# exception thrown inside a thunk is never caught. `[UnmanagedCallersOnly]` thunks contain no
`try`/`catch` at all, by design: a managed exception cannot cross the managed/native boundary
gracefully, and the .NET runtime tears the whole host process down (`FailFast`) if one tries. This is
the accepted v1 behaviour, not a bug, chosen specifically over catch-and-return-a-sentinel, because a
sentinel value (`0`, `false`, `null`) is indistinguishable from a legitimate result for a
primitive-or-`void`-returning method. A crash is loud; a wrong answer is not.

```C#
// v1: no try/catch - a thrown C# exception escapes and fast-fails the host process
[UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
private static IntPtr SerializeObject_Thunk(int value)
{
    string result = JsonConvert.SerializeObject(value);
    return Marshal.StringToCoTaskMemUTF8(result);
}
```

Graceful propagation into a catchable Kotlin exception is tracked as
[ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md) Phase 11.

## Diagnostics: recorded in the RIR and surfaced to the build

Every skip the metadata reader makes is recorded in `reverse-ir.json`, under each assembly's
`diagnostics` array, as a `RirDiagnostic`:

```kotlin
// nuget-plugin/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/rir/RirModel.kt
data class RirDiagnostic(
  val kind: RirDiagnosticKind,
  val typeName: String,
  val memberName: String,
  val memberSignature: String,
  val reason: String,
  val hint: String,
)

enum class RirDiagnosticKind {
  SKIPPED_OVERLOAD_SET,
  SKIPPED_REF_STRUCT,
  SKIPPED_OPEN_GENERIC,
  SKIPPED_DYNAMIC,
  SKIPPED_DEFAULT_INTERFACE_METHOD,
  SKIPPED_UNBOUND_TYPE_REFERENCE,
  SKIPPED_MEMBER_NAME_COLLISION,   // Gradle-plugin-side only, see below
  SKIPPED_UNSUPPORTED_ENUM,
  SKIPPED_UNSUPPORTED_STRUCT,
  ERROR_KOTLIN_SIGNATURE_COLLISION,
  INFO_ASYNC_NOT_YET_MAPPED,
  INFO_OBLIVIOUS_NULLABILITY,      // ADR-053: an un-annotated (oblivious) reference type bound non-null
}
```

`INFO_OBLIVIOUS_NULLABILITY` is emitted once per assembly when the *whole* assembly carries no
`NullableAttribute`/`NullableContextAttribute` anywhere (a legacy, pre-C#-8, or
`<Nullable>disable</Nullable>` package), and once per member for an oblivious island (a
`#nullable disable` region) inside an otherwise-annotated assembly. Either way the member still binds,
non-null, per [ADR-053](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/053-nullable-reference-types-in-kotlin.md);
the diagnostic just records that the binding is an assumption, not a read fact.

`skipped_overload_set` remains readable for older `reverse-ir.json` files, but the metadata reader
does not emit it for otherwise supported overloads. `error_kotlin_signature_collision` is fatal;
`skipped_*` diagnostics remain warnings and `info_*` diagnostics remain informational warnings.

`NugetGenerateBindingsTask` calls a single diagnostic path that first rejects every `ERROR_*` entry,
then formats the remaining diagnostics as Gradle build warnings. It combines every reader-emitted diagnostic (from each
assembly's `diagnostics` array: `ref struct` parameters, open generics, `dynamic`,
default interface methods, unbound type references, async-not-yet-mapped, and ADR-053's
oblivious-nullability notes) with the Gradle-plugin-derived `SKIPPED_MEMBER_NAME_COLLISION`
diagnostics (the instance-member-name-vs-wrapper collision described in
[Instance members](instance-members.md), which depends on the Kotlin wrapper's own member names and
so can only be computed plugin-side) through one shared formatter, and logs each one with
`logger.warn(...)`. An `ERROR_*` kind fails generation before sources are written. A `SKIPPED_*`
kind is logged as "Skipping ..." (the member is absent from the
generated output); an `INFO_*` kind is logged as "Note ..." (the member still binds, under an assumed
policy).

If a method you expected to see in Kotlin is missing, the build log now names it and says why; the
underlying `reverse-ir.json` `diagnostics` array is still there for programmatic inspection, but
reading it by hand is no longer the only way to find out. What is not yet built: a structured,
queryable diagnostics report (only a Gradle log line exists today), tracked in
[ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md) Phase 8.

<seealso>
    <category ref="related">
        <a href="reverse-overview.md">Consuming C# in Kotlin</a>
        <a href="static-classes-and-methods.md">Static classes and methods</a>
        <a href="objects-and-handles.md">Objects and handles</a>
        <a href="instance-members.md">Instance members</a>
        <a href="structs.md">C# structs</a>
        <a href="registration-diagnostics.md">Registration diagnostics</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/043-bridgeable-subset-boundary.md">ADR-043: Bridgeable subset boundary</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/046-reverse-ir-model-and-json-contract.md">ADR-046: Reverse IR model and JSON contract</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/049-csharp-registration-shim-generation.md">ADR-049: C# registration shim generation</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/053-nullable-reference-types-in-kotlin.md">ADR-053: Nullable reference types in Kotlin</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/056-csharp-structs-in-kotlin.md">ADR-056: C# structs (value types) in Kotlin</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/058-csharp-shape-b-structs-in-kotlin.md">ADR-058: C# Shape B structs in Kotlin</a>
    </category>
</seealso>
