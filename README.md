# kotlin-native-nuget

[![Stability](https://kotl.in/badges/experimental.svg)](https://kotlinlang.org/docs/components-stability.html#stability-of-subcomponents)
[![CI](https://github.com/xxfast/kotlin-native-nuget/actions/workflows/ci.yml/badge.svg)](https://github.com/xxfast/kotlin-native-nuget/actions/workflows/ci.yml)

A plugin that allows you to publish your Kotlin/Native libraries as NuGet packages to be consumed by .NET projects.

## Background

[![Talk](http://img.youtube.com/vi/DywUS-qYn6o/0.jpg)](http://www.youtube.com/watch?v=DywUS-qYn6o "Isuru Rajapakse - Kotlin → C#; Experimenting with Cross-Runtime Interop")

https://www.youtube.com/watch?v=DywUS-qYn6o

## Usage

Write Kotlin — get C# bindings automatically:

```kotlin
// Kotlin
fun meow(): String = "meow!"
fun lives(name: String): Int = 9
fun owner(name: String): String? = if (name == "Oreo") "Isuru" else null
```

```csharp
// C# (auto-generated)
string sound = CatLibNative.meow();
int lives = CatLibNative.lives("Mylo");
string? owner = CatLibNative.owner("Oreo"); // nullable support
```

## Prerequisites

### Kotlin side (library author)

- JDK 17+
- Gradle (included via wrapper)

### C# side (consumer)

- [.NET SDK](https://dotnet.microsoft.com/download) 8.0+

```bash
brew install dotnet
```

That's it. Bindings are pre-generated at Kotlin compile time via KSP — no additional tooling needed on the consumer side.

## Design Goals

1. Plug and play; just add the plugin, configure the target and publish your library as a NuGet package
2. Native; Generated bridge should be C# idiomatic
3. Comprehensive; Support as many Kotlin features as possible, including OOP constructs, Collections, Generics and Coroutines
4. Bidirectional; Support generating bindings from Kotlin → C# and C# → Kotlin

## Architecture

```
Gradle Plugin (Kotlin side)          NuGet Package            C# Consumer
┌─────────────────────────┐     ┌─────────────────────┐     ┌──────────────┐
│ Compile Kotlin/Native   │     │ native libs (.dll)  │     │ Add package  │
│ KSP → Interop.cs *      │────>│ Interop.cs *        │────>│ Build        │
│ Link shared libraries   │     │ header (.h)         │     │ Run          │
│ Package as .nupkg *     │     │ .targets            │     └──────────────┘
└─────────────────────────┘     └─────────────────────┘

* = not yet implemented (currently uses ClangSharp at consumer build time)
```

- **Gradle plugin** compiles, links, and packages the NuGet. In Phase 2, KSP will generate `Interop.cs` at compile time with full Kotlin type info.
- **NuGet package** ships native libs + generated C# bindings. Currently uses `.targets` + ClangSharp at consumer build time; Phase 2 will ship pre-generated `.cs` instead.
- **Consumer** just imports the package — no tooling required beyond the .NET SDK (once Phase 2 is complete).

## Roadmap

### Phase 1: Basic bridging
- [x] Gradle plugin structure with `includeBuild`
- [x] Link shared libraries for multiple targets (mingwX64, macosArm64)
- [x] Package native libs into NuGet layout (`runtimes/{rid}/native/`)
- [x] Generate P/Invoke bindings via ClangSharpPInvokeGenerator
- [x] Move ClangSharp invocation to a `.targets` file shipped inside the NuGet package (see [ADR-001](docs/adr/001-csharp-codegen-in-consumer.md))
- [x] Ship ClangSharp native libs as a package dependency (eliminate Gradle-side ProcessBuilder)

### Phase 2: KSP-driven generation
- [x] KSP processor that discovers all public declarations with full type info
- [x] Auto-generate `@CName` wrappers (no manual annotations needed)
- [x] Emit `Interop.cs` directly from KSP (pre-generated, no consumer-side tooling)
- [x] Remove ClangSharp dependency and `.targets` generation step
- [x] Map primitive types (Byte, Short, Int, Long, Float, Double + unsigned variants)
- [x] Map nullable primitives (see [ADR-002](docs/adr/002-nullable-two-call-pattern.md))
- [x] Map nullable strings

### Phase 3: Rich type support
- [x] Map Kotlin packages to C# namespaces (user-configurable root, sub-packages mapped relative to it)
- [x] Research memory management on the bridge (see [ADR-003](docs/adr/003-memory-management-across-bridge.md))
- [x] Map String parameters (C# `string` → Kotlin `String` via P/Invoke marshalling)
- [x] Map String returns as proper `string` (hidden `IntPtr` + `Marshal.PtrToStringUTF8`)
- [x] Map classes → C# classes with `IDisposable`, StableRef + opaque pointer
- [x] Refactor to CIR (C# Intermediate Representation) model (see [ADR-004](docs/adr/004-cir-intermediate-representation.md))
- [x] Map object-typed properties/returns (see [ADR-005](docs/adr/005-object-return-semantics.md))
- [x] Test cyclic reference disposal (verified wrappers are independent, dispose doesn't cascade)
- [ ] Map enums
- [ ] Map data classes
- [ ] Map interfaces
- [ ] Map abstract classes
- [ ] Map sealed classes
- [ ] Map Collections types
- [ ] Map Generics

### Phase 4: Async support
- [ ] Map Suspend functions (coroutines → Task/async)
- [ ] Map Flow APIs (cold streams → IAsyncEnumerable or RxObservables
- [ ] Handle cancellation and exceptions across the bridge

### Phase 5: Bidirectional support (C# → Kotlin)
- [ ] Research calling C# from Kotlin/Native (reverse P/Invoke, function pointers)
- [ ] Generate Kotlin wrappers for C# interfaces (callbacks, event handlers)
- [ ] Support implementing C# interfaces in Kotlin and passing them back to C# consumers
- [ ] Support implementing Kotlin interfaces in C# and passing them to Kotlin producers

## Future Improvements

- Verify Kotlin GC actually frees objects after all StableRefs are disposed (requires Kotlin-side weak references + GC trigger — not feasible in standard unit tests)
- Memory leak detection tooling for bridged objects in CI
- Object identity preservation (caching wrappers) if profiling shows allocation overhead is significant
