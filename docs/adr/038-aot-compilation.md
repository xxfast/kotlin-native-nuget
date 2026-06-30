# ADR-038: NativeAOT compatibility

## Status

Deferred

## Context

Design goal #2 is **Native** — the generated bridge should be C#-idiomatic and fit how modern
.NET ships native-interop code. The direction modern .NET is heading is
[NativeAOT](https://learn.microsoft.com/en-us/dotnet/core/deploying/native-aot/): ahead-of-time
compilation to a self-contained native binary with no JIT and an aggressive trimmer. AOT is the
natural fit for a library whose whole purpose is calling into a Kotlin/Native shared library, and
several consumers (games, CLI tools, mobile) will expect it.

AOT is also not always opt-in for consumers: C# on iOS (.NET for iOS / MAUI) forbids JIT and is
AOT-only, so the reflection in the generics path breaks unconditionally there, not just under
desktop NativeAOT.

The generated bindings are not AOT-safe today, for two reasons.

### The generics path uses reflection the trimmer breaks

The type-erased generics bridge (ADR-010) dispatches type arguments at runtime using private-member
reflection. `CirFunctionRenderer.kt` and `CirFunctionTranslator.kt` emit, for the object case:

```csharp
var field = typeof(T).GetField("_handle",
    System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Public);
// ...
return (T)Activator.CreateInstance(typeof(T),
    System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.NonPublic, ...);
```

`CirClassRenderer.kt` (constrained generic constructor) does the same `GetField("_handle", ...)`
reflection.

Both patterns — reflecting over a `NonPublic` instance field, and `Activator.CreateInstance`
against a non-public constructor — are exactly what NativeAOT's trimmer cannot statically prove
reachable. Under AOT the `_handle` field can be trimmed, and the reflective constructor call can
fail at runtime with a missing-member exception. The `typeof(T) == typeof(int)` primitive checks
are fine (they are statically analyzable); the reflection fallback for object type arguments is not.

So AOT support is gated first on removing reflection from the generics path.

### The dialect is hardcoded across the renderers

`CirRenderer` writes C# as text via `appendLine`, with the exact interop syntax hardcoded across
`CirClassRenderer`, `CirFunctionRenderer`, `CirMarshalRenderer`, and the other per-feature
renderers. Every `[DllImport]` is a literal string in dozens of `appendLine` calls. Moving to the
AOT-friendly interop attribute is therefore a cross-file rewrite rather than a localized change.

### What changes for AOT

| Concern                    | Today (JIT)                                                                      | AOT-friendly                                                            |
|----------------------------|----------------------------------------------------------------------------------|-------------------------------------------------------------------------|
| Interop attribute          | `[DllImport]` (runtime marshalling stub)                                         | `[LibraryImport]` (source-generated, trim-safe), partial methods        |
| Generic type dispatch      | `GetField`/`Activator` reflection                                                | static, generated per-type-argument paths (no reflection)               |
| Delegate function pointers | `Marshal.GetFunctionPointerForDelegate` on `[UnmanagedFunctionPointer]` delegate | works under AOT if the delegate is blittable; verify per callback shape |
| Native int                 | `IntPtr`                                                                         | `IntPtr` is fine under AOT                                              |

`[DllImport]` still *works* under AOT, so it is not strictly blocking, but `[LibraryImport]` is the
recommended path: marshalling is generated at compile time, which is what makes it trim/AOT-safe and
removes the per-call marshalling stub. It requires C# 11+ and the methods to be `partial` inside a
`partial` class.

### How other Kotlin/Native interop targets relate

Kotlin/Native's own C export produces a plain C header + static/shared library — it has no opinion
on the consumer's AOT story; that is entirely the C# side's concern. Swift export compiles to a
native framework with no managed runtime at all, so the "AOT vs JIT" question does not arise there.
This is a .NET-specific consideration with no direct precedent to borrow from the other targets; the
authority is the .NET AOT/trimming documentation.

## Alternatives Considered

### 1. Do nothing — keep `[DllImport]` + reflection, document "JIT only"

**Pros:** zero work; current output compiles and runs on the standard CoreCLR/JIT runtime.

**Cons:** abandons a stated design goal; the generics feature silently misbehaves under AOT
(trimmer removes `_handle`, reflective `Activator.CreateInstance` throws). Consumers who publish
AOT get runtime failures rather than a clear "unsupported." Not acceptable long-term.

### 2. Switch wholesale to `[LibraryImport]` and AOT-only output

Emit only AOT-friendly C# and require C# 11+ / .NET 7+ from every consumer.

**Pros:** one code path; smallest renderer surface.

**Cons:** drops support for consumers on older language versions or build setups that cannot use
the source generator; a hard, unconditional break with no migration window. Over-commits before AOT
is actually validated end to end with a Kotlin/Native `.dll`/`.dylib`.

### 3. Introduce a `CSharpProfile` and make the dialect a generation-time choice (preferred)

Centralize the AOT-sensitive choices into a single `CSharpProfile` value that the renderers consult
instead of hardcoding them:

```kotlin
data class CSharpProfile(
  val interop: InteropStyle = InteropStyle.DllImport,        // or LibraryImport
  val genericDispatch: GenericDispatch = GenericDispatch.Reflection, // or StaticGenerated
)
```

Renderers (`renderDllImport`, the generics dispatch in `CirFunctionRenderer`/`CirFunctionTranslator`,
the constrained-generic constructor in `CirClassRenderer`) read from the profile. Switching a
consumer to AOT becomes selecting a profile, not rewriting six files. The reflection-free generic
dispatch is implemented as an alternative `genericDispatch` strategy, kept beside the existing one
until proven.

**Pros:** the CIR model is already the right abstraction (ADR-004); this only fixes that the
*backend* hardcodes one dialect. Incremental — the default profile reproduces today's output
byte-for-byte, so no existing test changes until we opt a path into the AOT profile. Lets the
AOT-unsafe reflection path and the AOT-safe static path coexist during migration.

**Cons:** more machinery in the renderers (every AOT-sensitive emit goes through the profile); the
reflection-free generic dispatch is a non-trivial design effort in its own right (likely its own
ADR when picked up). Two output dialects means two paths to test.

## Decision

**Deferred.** AOT support is not a pre-launch requirement and depends on a reflection-free generics
design that is sizeable on its own. When picked up, pursue **Alternative 3**: introduce a
`CSharpProfile` so the dialect (interop attribute, generic dispatch strategy) is a generation-time
choice, with the default profile reproducing current output exactly.

Sequencing when the work is scheduled:

1. Add a `CSharpProfile`, route the AOT-sensitive emits through it, default = today's output.
2. Implement a reflection-free `genericDispatch` strategy (statically generated per-type-argument
   paths) behind the profile — removes the `GetField`/`Activator` reflection that AOT trims.
   Likely warrants its own ADR.
3. Switch the interop attribute to `[LibraryImport]` (partial methods, `partial` classes) under the
   AOT profile.
4. Add an AOT publish smoke test to CI (see Consequences) that links against a real Kotlin/Native
   library and runs the generated bindings.

## Consequences

- New `CSharpProfile` model + threading it through every renderer.
- A second, reflection-free generic-dispatch code path (and the tests to cover it).
- `[LibraryImport]` requires `partial` classes/methods — affects the shape emitted by
  `CirClassRenderer` and the shared marshal helpers, not just the attribute line.
- An AOT publish + run smoke test in CI (`PublishAot=true`), which is the only reliable way to
  catch trimming/reflection failures — they do not show up under the JIT.

### Breaking changes

None until the AOT profile is opted into. The default profile is byte-for-byte today's output.
