# kotlin-native-nuget

[![Stability](https://kotl.in/badges/experimental.svg)](https://kotlinlang.org/docs/components-stability.html#stability-of-subcomponents)
[![CI](https://github.com/xxfast/kotlin-native-nuget/actions/workflows/ci.yml/badge.svg)](https://github.com/xxfast/kotlin-native-nuget/actions/workflows/ci.yml)

A plugin that allows you to publish your Kotlin/Native libraries as NuGet packages to be consumed by .NET projects.

## Background

[![Talk](http://img.youtube.com/vi/DywUS-qYn6o/0.jpg)](http://www.youtube.com/watch?v=DywUS-qYn6o "Isuru Rajapakse - Kotlin → C#; Experimenting with Cross-Runtime Interop")

https://www.youtube.com/watch?v=DywUS-qYn6o

## Setup

```kotlin
// build.gradle.kts
plugins {
  kotlin("multiplatform")
  id("io.github.xxfast.nuget")
}

nuget {
  packageId.set("MyCatLib")
  version.set("1.0.0")
  authors.set("yourname")
  description.set("My Kotlin/Native library")
  rootPackage.set("com.example.cats")
}
```

## Usage

Write Kotlin — get C# bindings automatically:

```kotlin
// Kotlin
interface Pet {
  val name: String
  fun speak(): String
}

enum class Mood { HAPPY, SLEEPY, GRUMPY }

abstract class Animal(override val name: String) : Pet

class Cat(name: String, val lives: Int = 9) : Animal(name) {
  var brother: Cat? = null
  var mood: Mood = Mood.SLEEPY
  override fun speak(): String = "Meow!"
}

data class Toy(val name: String, val color: String)

fun owner(name: String): String? = if (name == "Oreo") "Isuru" else null
```

```csharp
// C# (auto-generated)
using var oreo = new Cat("Oreo", 9);
using var mylo = new Cat("Mylo", 9);

oreo.Name;                         // "Oreo"
oreo.Speak();                      // "Meow!"
oreo.Brother = mylo;               // object property setter
oreo.Mood = Mood.Happy;            // enum support
Mood.Happy.Description();          // enum extension methods

using var toy = new Toy("Mouse", "Gray");
toy.ToString();                    // "Toy(name=Mouse, color=Gray)"
using var copy = toy.Copy("Ball", "Red");
toy.Equals(copy);                  // false (data class equality)

string? owner = CatKt.owner("Oreo");  // "Isuru" (nullable string)

IPet pet = oreo;                   // interface polymorphism
Animal animal = oreo;              // abstract class hierarchy
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
Gradle Plugin (Kotlin side)          NuGet Package       C# Consumer
┌─────────────────────────┐     ┌────────────────┐     ┌──────────────┐
│ Compile Kotlin/Native   │     │ native libs    │     │ Add package  │
│ KSP → CIR → Interop.cs  │────>│ Interop.cs     │────>│ Build        │
│ KotlinPoet → Bridges.kt │     └────────────────┘     │ Run          │
│ Link shared libraries   │                            └──────────────┘
│ Package as .nupkg *     │
└─────────────────────────┘     * = not yet implemented
```

- **Gradle plugin** compiles Kotlin/Native, runs KSP to generate C# bindings (via CIR model) and Kotlin bridge wrappers (via KotlinPoet), links shared libraries, and packages everything.
- **NuGet package** ships native libs + pre-generated `Interop.cs`. No consumer-side tooling required.
- **Consumer** just includes the package — bindings are ready at build time.

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
- [x] Map member setters
- [x] Map enums (see [ADR-006](docs/adr/006-enum-mapping.md))
- [x] Map per-file top-level function class naming (see [ADR-007](docs/adr/007-top-level-function-class-naming.md))
- [x] Map data classes (see [ADR-008](docs/adr/008-data-class-mapping.md))
- [x] Map interfaces (C# `interface` with `I` prefix, default methods delegate to Kotlin)
- [x] Map abstract classes (C# `abstract class`, `_handle` inherited by subclasses)
- [x] Map sealed classes (see [ADR-009](docs/adr/009-sealed-class-mapping.md))
- [x] Map object (→ static class) / data object in sealed classes (→ sealed subclass with ToString)
- [ ] Map Generics
- [ ] Map Collections types
  - [ ] `List<T>` → `IReadOnlyList<T>` (opaque handle + count, get)
  - [ ] `MutableList<T>` → `IList<T>` (add, removeAt, set)
  - [ ] `Map<K,V>` → `IReadOnlyDictionary<K,V>` (count, get, containsKey, keys)

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

- Support flat/unnested sealed class hierarchies (subclasses as top-level in same namespace)
- KSP incremental processing if build times become a concern on large libraries
- Map data classes to C# `record class` if a safe `with`-expression pattern can be found (see [ADR-008](docs/adr/008-data-class-mapping.md))
- Verify Kotlin GC actually frees objects after all StableRefs are disposed (requires Kotlin-side weak references + GC trigger — not feasible in standard unit tests)
- Memory leak detection tooling for bridged objects in CI
- Object identity preservation (caching wrappers) if profiling shows allocation overhead is significant
