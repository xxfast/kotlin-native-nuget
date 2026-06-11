# kotlin-native-nuget

A plugin that allows you to publish your Kotlin/Native libraries as NuGet packages to be consumed by .NET projects.

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
│ Link shared libraries   │────>│ header (.h)         │────>│ Build        │
│ Emit metadata.json      │     │ metadata.json       │     │ Run          │
│ Package as .nupkg       │     │ .targets / analyzer │     └──────────────┘
└─────────────────────────┘     └─────────────────────┘
```

- **Gradle plugin** compiles, links, and packages the NuGet (including `.targets` and header)
- **NuGet package** ships native libs + header + `.targets` file that auto-runs ClangSharp at consumer build time
- **Consumer** just imports the package — binding generation happens automatically on `dotnet build`

## Roadmap

### Phase 1: Basic bridging
- [x] Gradle plugin structure with `includeBuild`
- [x] Link shared libraries for multiple targets (mingwX64, macosArm64)
- [x] Package native libs into NuGet layout (`runtimes/{rid}/native/`)
- [x] Generate P/Invoke bindings via ClangSharpPInvokeGenerator
- [x] Move ClangSharp invocation to a `.targets` file shipped inside the NuGet package
- [x] Ship ClangSharp native libs as a package dependency (eliminate Gradle-side ProcessBuilder)

### Phase 2: Metadata-driven generation
- [ ] Emit `metadata.json` from Gradle plugin (function signatures, types, nullability)
- [ ] Replace ClangSharp with a custom C# Source Generator that reads metadata
- [ ] Map primitive types
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
