# The bridgeable subset

Not every C# construct visible in assembly metadata can cross the C ABI. This page describes exactly
what binds and what doesn't, read directly from `nuget-metadata-reader/Program.cs`, the tool that
does the actual filtering. Where the ADR prose and the reader's real behaviour could be read two
ways, this page follows the code.

## Types

| Construct | Binds? |
|---|---|
| Public top-level class | Yes, if it has at least one bridgeable member |
| Public top-level static class | Yes, as a Kotlin `object` (see [Static classes and methods](static-classes-and-methods.md)) |
| Interface | No members are bound to Kotlin; a default (non-abstract, non-static) interface method is explicitly skipped and diagnosed |
| Enum | Yes, as a standalone Kotlin `enum class`, when it is public, top-level, default-`int` backed, non-`[Flags]`, and has unique contiguous values from `0` through `N-1` |
| Struct / value type | No, as a **handle** type. `CollectBoundHandleTypeNames` explicitly excludes any type whose base type is `System.ValueType` or `System.Enum`, so a struct can never be used as an object-handle parameter or return elsewhere |
| `ref struct` (`Span<T>`, `ReadOnlySpan<T>`, custom) | No. Detected via the `IsByRefLikeAttribute` custom attribute; any member referencing one is skipped and diagnosed (`skipped_ref_struct`) |
| Nested type (public or not) | No. The reader filters on `TypeAttributes.VisibilityMask == Public`, which excludes `NestedPublic` as well as every non-public visibility. Only top-level public types are candidates at all |
| Generic type | Not explicitly filtered at the type level. A generic class is still enumerated as a candidate type, but virtually every member whose signature actually uses the open type parameter is skipped per-member (`skipped_open_generic`) when the parameter or return type is decoded, so a generic type in practice binds nothing unless it happens to expose non-generic members |
| Record | Not recognized as a distinct construct at all: a C# `record class` compiles to an ordinary class in IL with no marker, and its fate is decided entirely by the same rules as any other class (its synthesized copy-constructor typically turns its constructors into a multi-member overload set, which is skipped wholesale) |

Classes (static or not) and supported enums produce Kotlin output. Interfaces are extracted into the
RIR as `RirInterface` but no stub is generated from them today.

## Enums

A supported C# enum becomes a standalone Kotlin `enum class`. Its member names are converted to
Kotlin `SCREAMING_SNAKE_CASE`. Enum values cross the C ABI as their ordinal `Int`, so enum arguments,
returns, constructors, and properties use the generated Kotlin enum type without allocating an
object handle or registration table for the enum itself.

```csharp
// sample-dependency/CatMood.cs
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
// sample-library/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/sample/enums/CatMoodSample.kt
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

- Exactly **one** public instance constructor per class binds, mapping to a Kotlin secondary
  constructor (see [Objects and handles](objects-and-handles.md)).
- Two or more public instance constructors on the same type are grouped and skipped wholesale as an
  overload set, with a `skipped_overload_set` diagnostic naming `.ctor`.
- A non-public constructor is excluded before the constructor-admission check even runs: the reader
  tests `MethodAttributes.MemberAccessMask == Public` first, so `private`/`internal`/`protected`
  constructors are silently invisible, never diagnosed.
- Static classes and interfaces never carry a constructor at all (a static class is `abstract
  sealed` in metadata and has no instance `.ctor`; interfaces have none by definition).

## Methods and properties

- **Static methods** and **instance methods** both bind, subject to the same parameter/return type
  and overload-set rules.
- **Instance properties** and **static properties** bind: read-only → `val`, settable → `var` (see
  [Instance members](instance-members.md) and [Static classes and methods](static-classes-and-methods.md)).
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

## Overload sets: skipped wholesale, not partially

Methods (and constructors) are grouped by name before anything else happens. Any group with more
than one member is dropped in its entirety, with one diagnostic naming the group:

```
"reason": "overload set - 4 overloads of `SerializeObject` cannot be uniquely exported to C",
"hint": "Add a C# adapter shim to expose each overload under a distinct name."
```

There is no partial binding of "the overload with the simplest signature." This is why
`Newtonsoft.Json` cannot be bound at all under v1: every static method on `JsonConvert` is an
overload set.

## The bridgeable type vocabulary

| Kind | Bridges as |
|---|---|
| `void` | return position only |
| `string` | UTF-8 marshalled `IntPtr` |
| `bool`, `byte`, `short`, `int`, `long`, `float`, `double`, `char` | primitives, direct or narrowed for ABI blittability |
| A supported enum | ordinal `Int`, converted to and from a Kotlin `enum class` |
| A bound, non-static, non-value-type, non-`ref struct` class from the current extraction | an opaque `GCHandle`-backed pointer (a "handle" type) |

Everything else, nullable primitives and strings, arrays, collections, delegates, `dynamic`, `object`,
open generics, does not bridge. `System.String` is the only external (out-of-assembly) reference type
recognized; every other external type reference is unbound and diagnosed
(`skipped_unbound_type_reference`).

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

## Diagnostics: recorded in the RIR, mostly not surfaced to the build

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
  INFO_ASYNC_NOT_YET_MAPPED,
}
```

A representative entry, taken from a real diagnostic the reader emits for an overload set:

```json
{
  "kind": "skipped_overload_set",
  "typeName": "JsonConvert",
  "memberName": "SerializeObject",
  "memberSignature": "SerializeObject(object) [+3 overloads]",
  "reason": "overload set - 4 overloads of `SerializeObject` cannot be uniquely exported to C",
  "hint": "Add a C# adapter shim to expose each overload under a distinct name."
}
```

**Only one of these kinds is currently surfaced as a Gradle build warning:**
`SKIPPED_MEMBER_NAME_COLLISION`, the instance-member-name-vs-wrapper collision described in
[Instance members](instance-members.md). It's produced on the Gradle-plugin side (not by the
metadata reader) because it depends on the Kotlin wrapper's own member names, which only the plugin
knows about, and `NugetGenerateBindingsTask` logs it via `logger.warn(...)` during generation.

Every other diagnostic kind, overload sets, `ref struct` parameters, open generics, `dynamic`,
default interface methods, unbound type references, is written into `reverse-ir.json` and never
printed anywhere during a normal build. If a method you expected to see in Kotlin is simply missing,
the only way to find out why today is to open `build/nuget-interop/reverse-ir.json` and read its
`diagnostics` array by hand. Closing that gap, generalizing the member-name-collision warning into a
warning for every diagnostic kind, is an open item in
[ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md) Phase 8.

<seealso>
    <category ref="related">
        <a href="reverse-overview.md">Consuming C# in Kotlin</a>
        <a href="static-classes-and-methods.md">Static classes and methods</a>
        <a href="objects-and-handles.md">Objects and handles</a>
        <a href="instance-members.md">Instance members</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/043-bridgeable-subset-boundary.md">ADR-043: Bridgeable subset boundary</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/046-reverse-ir-model-and-json-contract.md">ADR-046: Reverse IR model and JSON contract</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/049-csharp-registration-shim-generation.md">ADR-049: C# registration shim generation</a>
    </category>
</seealso>
