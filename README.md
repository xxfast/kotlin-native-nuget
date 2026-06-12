# kotlin-native-nuget

[![Stability](https://kotl.in/badges/experimental.svg)](https://kotlinlang.org/docs/components-stability.html#stability-of-subcomponents)
[![CI](https://github.com/xxfast/kotlin-native-nuget/actions/workflows/ci.yml/badge.svg)](https://github.com/xxfast/kotlin-native-nuget/actions/workflows/ci.yml)

A plugin that allows you to publish your Kotlin/Native libraries as NuGet packages to be consumed by .NET projects.

## Background

[![Talk](http://img.youtube.com/vi/DywUS-qYn6o/0.jpg)](http://www.youtube.com/watch?v=DywUS-qYn6o "Isuru Rajapakse - Kotlin → C#; Experimenting with Cross-Runtime Interop")

https://www.youtube.com/watch?v=DywUS-qYn6o

## Prerequisites

> **Note:** These are required on the machine that builds the C# consumer project. They will be eliminated in Phase 2 when ClangSharp is replaced with a custom Source Generator — at that point only the .NET SDK will be needed.

### .NET SDK

```bash
brew install dotnet
```

### ClangSharpPInvokeGenerator

```bash
dotnet tool install --global ClangSharpPInvokeGenerator
```

### ClangSharp native libraries

ClangSharp requires matching `libclang` and `libClangSharp` native libraries:

```bash
# Create a temporary project to pull the native packages
mkdir -p /tmp/clangsharp-deps && cd /tmp/clangsharp-deps
dotnet new console --force
dotnet add package libclang.runtime.osx-arm64
dotnet add package libClangSharp.runtime.osx-arm64
```

The libraries are cached in `~/.nuget/packages/` and resolved automatically by the `.targets` file shipped inside the NuGet package.

### PATH setup

Add to `~/.zprofile`:

```bash
export PATH="/opt/homebrew/opt/dotnet/bin:$HOME/.dotnet/tools:$PATH"
```

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
- [x] Move ClangSharp invocation to a `.targets` file shipped inside the NuGet package
- [x] Ship ClangSharp native libs as a package dependency (eliminate Gradle-side ProcessBuilder)

### Phase 2: KSP-driven generation
- [ ] KSP processor that discovers `@CName`-annotated declarations with full type info
- [ ] Emit `Interop.cs` directly from KSP (pre-generated, no consumer-side tooling)
- [ ] Remove ClangSharp dependency and `.targets` generation step
- [ ] Map primitive types with nullability
- [ ] Research memory management on the bridge

### Phase 3: Rich type support
- [ ] Map non-primitive types (strings, opaque pointers)
- [ ] Map OOP constructs (classes, interfaces → C# classes with IDisposable)
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
