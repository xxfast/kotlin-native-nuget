# kotlin-native-nuget
<img src="docs/icon.svg" height="240" align="right"/> 

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
  id("io.github.xxfast.kotlin.native.nuget")
}

kotlin {
  mingwX64 { binaries { sharedLib { baseName = "mycatlib" } } }
  macosArm64 { binaries { sharedLib { baseName = "mycatlib" } } }
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
interface Pet { val name: String; fun speak(): String }
enum class Mood { HAPPY, SLEEPY, GRUMPY }
abstract class Animal(override val name: String) : Pet

class Cat(name: String, val lives: Int = 9) : Animal(name) {
  var brother: Cat? = null
  var mood: Mood = Mood.SLEEPY
  val toys: List<Toy> = listOf(Toy("Mouse", "Gray"))
  val onMeow: () -> String = { "Meow! My name is $name" }
  override fun speak(): String = "Meow!"
}

data class Toy(val name: String, val color: String)
class Box<T>(val item: T)
fun owner(name: String): String? = if (name == "Oreo") "Isuru" else null
```

```csharp
// C# (auto-generated)
using var oreo = new Cat("Oreo", 9);

oreo.Name;                                  // "Oreo"
oreo.Speak();                               // "Meow!"
oreo.Brother = new Cat("Mylo", 9);          // nullable object setter
oreo.Mood = Mood.Happy;                     // enums

using var toy = new Toy("Mouse", "Gray");
toy.ToString();                             // "Toy(name=Mouse, color=Gray)"
toy.Equals(toy.Copy("Ball", "Red"));        // data class equality + copy

IReadOnlyList<Toy> toys = oreo.Toys;        // collections
using var box = new Box<string>("hello");    // generics
string? owner = CatKt.Owner("Oreo");        // nullable returns

using var onMeow = oreo.OnMeow;
onMeow.Invoke();                            // lambdas

IPet pet = oreo;                            // interface polymorphism
Animal animal = oreo;                       // abstract class hierarchy
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

## Supported Types

### Primitives

Primitive types follow the standard [Kotlin/Native C interop mappings](https://kotlinlang.org/docs/mapping-primitive-data-types-from-c.html#inspect-generated-kotlin-apis-for-a-c-library).

### OOP Constructs

| Kotlin                    | C#                    | Notes                                            |
|---------------------------|-----------------------|--------------------------------------------------|
| `class`                   | `class : IDisposable` | StableRef + opaque pointer                       |
| `data class`              | `class`               | `ToString`, `Equals`, `Copy`                     |
| `interface`               | `interface`           | `I`-prefixed, default methods delegate to Kotlin |
| `abstract class`          | `abstract class`      | `_handle` inherited by subclasses                |
| `sealed class`            | `abstract class`      | subclasses nested                                |
| `object` (in sealed)      | `static class`        |                                                  |
| `data object` (in sealed) | sealed subclass       | with `ToString`                                  |
| `enum class`              | `enum`                | with extension methods                           |
| top-level functions       | `static class`        | one per source file                              |

### Generics

| Kotlin        | C#             | Notes                                   |
|---------------|----------------|-----------------------------------------|
| `class<T>`    | `class<T>`     | type-erased bridge + generic C# wrapper |
| `fun <T> f()` | typed variants | runtime dispatch via `NugetMarshal`     |

### Collections

| Kotlin            | C#                         | Notes                        |
|-------------------|----------------------------|------------------------------|
| `List<T>`         | `IReadOnlyList<T>`         | eager copy via opaque handle |
| `MutableList<T>`  | `IList<T>`                 | eager copy                   |
| `Map<K,V>`        | `IReadOnlyDictionary<K,V>` | eager copy                   |
| `MutableMap<K,V>` | `IDictionary<K,V>`         | eager copy                   |
| `Set<T>`          | `IReadOnlySet<T>`          | eager copy                   |
| `MutableSet<T>`   | `ISet<T>`                  | eager copy                   |

> [!TIP]
> See [ROADMAP.md](ROADMAP.md) for the development roadmap and [docs/adr/](docs/adr/) for architecture decision records.
