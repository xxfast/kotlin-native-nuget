# ADR-007: Top-level function class naming — file name as class name

## Status

Accepted

## Context

Kotlin top-level functions don't belong to a class. When bridging to C#, they must be placed in a static class. We need to decide how to name that class.

### How Kotlin handles this for Java

- Functions in `Arithmetic.kt` → compiled into `ArithmeticKt` class
- `@file:JvmName("Arithmetic")` overrides to `Arithmetic`
- Each file gets its own class; package maps 1:1

### How C# conventions work

- C# has top-level statements (syntactic sugar for `Main`) but no top-level *functions* callable from other code — reusable methods must live in a class
- Static utility classes are named after the domain: `Math.Abs()`, `String.Join()`, `Path.Combine()`
- No `Kt` suffix convention exists

### Current behaviour (incorrect)

All top-level functions from all files in a package get merged into a single `SampleLibraryNative` static class, regardless of source file. This loses the grouping information.

## Alternatives Considered

### 1. File name as class name, PascalCase, no suffix (chosen)

`Arithmetic.kt` → `Arithmetic`, `Mappings.kt` → `Mappings`

**Pros:** Idiomatic C#. Matches the `@file:JvmName` equivalent in Kotlin/Java interop. Natural grouping.
**Cons:** If two files have the same name in different packages, they'd be disambiguated by namespace (which is correct).

### 2. FileNameKt (Java-style)

`Arithmetic.kt` → `ArithmeticKt`

**Pros:** Exact parity with Kotlin's Java interop.
**Cons:** `Kt` suffix is a Kotlin-ism that means nothing to C# developers.

### 3. Single class per namespace

All functions in a package go into one class (current behaviour).

**Pros:** Simple.
**Cons:** Loses file-level grouping. Large classes with unrelated functions mixed together.

## Decision

Use **file name as class name** (option 1), with `Kt` suffix when there's a naming conflict:

- `Arithmetic.kt` in package `io.github.xxfast.kotlin.native.nuget.sample.math` → `SampleLibrary.Math.Arithmetic`
- `Mappings.kt` in package `io.github.xxfast.kotlin.native.nuget.sample` → `SampleLibrary.Mappings`
- `Cat.kt` containing both `class Cat` and top-level functions → `SampleLibrary.Cat.CatKt` (suffix added to avoid conflict with the `Cat` class)

The KSP processor already has access to `func.containingFile?.fileName` — use this to group functions by file and derive the class name by dropping the `.kt` extension. If a class with the same name exists in the same namespace, append `Kt` to the top-level function class.

## Conflict resolution

When a file contains both a class and top-level functions, the file name matches the class name (Kotlin convention). To avoid a C# naming conflict:

- If no class with the same name exists → use file name as-is (`Arithmetic`)
- If a class with the same name exists → append `Kt` suffix (`CatKt`)

This mirrors Kotlin's Java interop behaviour where the `Kt` suffix exists precisely for this disambiguation.

### How other exports handle this

- **ObjC Export**: avoids the problem entirely — all top-level functions go into a single `<FrameworkName>Kt` class
- **Swift Export**: avoids the problem entirely — top-level functions become module-scoped free functions
- **Java interop**: always uses `Kt` suffix (`CatKt`), avoidable with `@file:JvmName`

Our approach is a hybrid: default to clean names, fall back to `Kt` suffix only when needed.

## Consequences

- Top-level functions are grouped by their source file, matching developer intent
- Class names are meaningful and predictable from the Kotlin source structure
- Aligns with both C# naming conventions and Kotlin's `@file:JvmName` pattern
- Breaking change from current single-class approach — tests will need updating
- Conflict resolution is deterministic and matches existing Kotlin/Java behaviour

### Amendment (2026-09-07): the `Kt` suffix also fires on a name collision, not just a type collision, [ADR-110](110-top-level-function-pascal-case.md)

The decision above only ever compared the file class name against another **type** declared in the
same namespace (a class, sealed class, or interface backing class). [ADR-110](110-top-level-function-pascal-case.md)
PascalCases every top-level function's public name, which opens a new way for the file class name to
be claimed: a function whose own PascalCased name equals its file class (`fun greeting()` in
`Greeting.kt`, which wants the C# name `Greeting.Greeting()`) is a member named like its enclosing
type, forbidden by C# (CS0542) the same way a same-named sibling type is.

The existing `Kt`-suffix remedy is reused as-is for this case: the whole file class renames to
`GreetingKt`, every member of that file moves with it, and an `INFO_FILE_CLASS_RENAMED` diagnostic
notes the rename and the call site (`GreetingKt.Greeting(...)`). The native `@CName` export is
unaffected either way. See ADR-110's Decision for the full mechanism.

## Amendment (2026-09-15): a file-derived class name is sanitised

The decision above took the file stem verbatim. A Kotlin file stem is not a C# identifier.
`Hub.mingw.kt` emitted `public static partial class Hub.mingw`, which C# reads as a qualified
name: one dot, 19 parse errors, and every declaration after it parsed in the wrong context, so the
last error is a `CS1022` at the final line of `Interop.cs` (issue #233).

Platform-suffixed file names (`Foo.ios.kt`, `Foo.mingw.kt`, `Foo.android.kt`) are the standard KMP
layout for `actual` declarations, so this is the normal case for a multiplatform consumer, not an
exotic one. It stayed hidden because no fixture anywhere in the repository used a dotted stem.

### The rule

A file-derived name now goes through `String.csharpIdentifier()` (`Reserved.kt`), applied once at
the grouping key in `CirTranslator.groupByNamespaceAndFile` and
`groupPropertiesByNamespaceAndFile`, so a file's functions and its properties land on one holder
and `resolveStaticClassName` only ever compares legal identifiers.

The rule is structural, never a list of known platform suffixes. Split the stem on every run of
characters outside `[A-Za-z0-9_]`, keep the first segment verbatim, uppercase each following
segment's first character, join. Prefix `_` if the result starts with a digit. Pass the result
through `toCSharpName` so a stem spelling a C# keyword becomes a verbatim identifier.

| stem | holder |
|---|---|
| `Hub` | `Hub` |
| `Hub.mingw` | `HubMingw` |
| `Net.io.core` | `NetIoCore` |
| `foo-bar` | `fooBar` |
| `9lives` | `_9lives` |

An already-legal stem is a fixed point, so no existing generated name moves.

### Why PascalCase segments and not `Hub_mingw`

`Hub_mingw` is Kotlin's own rule: the JVM facade for `Hub.mingw.kt` is `Hub_mingwKt`. It was
considered, and it is defensible. PascalCase wins because [ADR-110](110-top-level-function-pascal-case.md)
already made PascalCase the forward spelling of every C# name the generator emits, so the
file-derived one reads like its neighbours rather than like a JVM artefact.

### Why not strip the suffix

Mapping `Hub.mingw` to `Hub` was rejected. `Hub.kt` commonly sits beside `Hub.mingw.kt` holding the
`expect` and its own helpers, and merging two files into one class silently is worse than the parse
error, which at least fails loudly. `@file:JvmName` was also rejected as a workaround: it is a JVM
annotation with no meaning for a native target.

### Interaction with ADR-074 Decision 3

Unchanged, and it still wins. A genuine top-level `actual` takes its holder name from the `expect`'s
file, so `Hub.mingw.kt`'s `actual fun` lands on `Hub`, and the sanitiser never sees the per-target
stem. The sanitiser is what makes the fix hold when that lookup does not apply: a top-level function
with no `expect` at all, or an `actual` whose `expect` did not match by signature.

### Known edge: two files can now share a holder

`HubMingw.kt` and `Hub.mingw.kt` in one package both sanitise to `HubMingw`. This is harmless while
their members differ, and Kotlin already forbids two identical top-level signatures in one package.
Where a member name does repeat across the two, ADR-110's existing CS0102 guard
(`ERROR_CSHARP_NAME_COLLISION`) fires and names `HubMingw.Status`, because that check is keyed on
the file-class key, which is the sanitised one. Verified by a Tier 1 cell. No new check was built
for the merge, and the collision message now reads "on the same file class" rather than "in the same
file", which is no longer true of a merged holder.

### This is new machinery

Issue #233 states that `csharpIdentifier()` already existed from ADR-110 and was merely not called
on this path. That is wrong: there was no `csharpIdentifier()` anywhere in `nuget-processor/src/main`
before this amendment. The function is new, and `Reserved.kt` is where it lives, next to
`toCSharpName`, as the single place a file-derived name becomes an identifier.

The file stem never feeds a `@CName` export name (exports derive from the Kotlin declaration name
via `toCName`), so the native side of the ABI is untouched by this change.
