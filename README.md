# kotlin-native-nuget
<img src="docs/icon.svg" height="240" align="right"/> 

[![Stability](https://kotl.in/badges/experimental.svg)](https://kotlinlang.org/docs/components-stability.html#stability-of-subcomponents)
[![CI](https://github.com/xxfast/kotlin-native-nuget/actions/workflows/ci.yml/badge.svg)](https://github.com/xxfast/kotlin-native-nuget/actions/workflows/ci.yml)

A plugin that allows you to publish your Kotlin/Native libraries as NuGet packages to be consumed by .NET projects.

## Background

[![Talk](http://img.youtube.com/vi/DywUS-qYn6o/0.jpg)](http://www.youtube.com/watch?v=DywUS-qYn6o "Isuru Rajapakse - Kotlin → C#; Experimenting with Cross-Runtime Interop")

https://www.youtube.com/watch?v=DywUS-qYn6o

## Stability

`0.x` and experimental. Anything can change between versions.

The generated bindings are the public API of **your** NuGet package. Your consumers see your version, never the plugin's. A plugin upgrade that changes how Kotlin renders into C# breaks them at your version, not ours.

Pin the plugin version. Diff the generated `Interop.cs` when you bump it.

## Setup

[![Gradle Plugin Portal](https://img.shields.io/gradle-plugin-portal/v/io.github.xxfast.kotlin.native.nuget)](https://plugins.gradle.org/plugin/io.github.xxfast.kotlin.native.nuget)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.xxfast/nuget-plugin?label=maven%20central)](https://central.sonatype.com/artifact/io.github.xxfast/nuget-plugin)

```kotlin
// build.gradle.kts
plugins {
  kotlin("multiplatform")
  id("io.github.xxfast.kotlin.native.nuget") version "<version>"
}

kotlin {
  mingwX64 { binaries { sharedLib { baseName = "mycatlib" } } }
  macosArm64 { binaries { sharedLib { baseName = "mycatlib" } } }
}

nuget {
  publish {
    packageId = "MyCatLib"
    version = "1.0.0"
    authors = "yourname"
    description = "My Kotlin/Native library"
    rootPackage = "com.example.cats"
  }
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
  - [.NET SDK](https://dotnet.microsoft.com/download) 8.0+ 
  — **only when consuming NuGet packages from Kotlin** 
  - Not needed for the forward direction (exporting Kotlin to C#).

### C# side (consumer)

- [.NET SDK](https://dotnet.microsoft.com/download) 8.0+

```bash
brew install dotnet
```

That's it. Bindings are pre-generated at Kotlin compile time via KSP — no additional tooling needed on the consumer side.

## Architecture

The Gradle plugin compiles Kotlin/Native, runs KSP to generate the C# bindings and Kotlin bridge
wrappers, links the shared libraries, and packages everything into a `.nupkg`. The consumer just
adds the package — bindings are ready at build time, no consumer-side tooling required.

Bindings are generated through a mirrored intermediate representation in each direction — **CIR**
(Kotlin → C#) and **RIR** (C# → Kotlin) — and at runtime calls cross the C ABI both ways. See
[ARCHITECTURE.md](ARCHITECTURE.md) for the full picture.

## Supported Features

The bridge maps OOP constructs, generics, collections, lambdas (both directions), exceptions, and coroutines/`Flow`. See [FEATURES.md](FEATURES.md) for the full mapping catalogue.

> [!TIP]
> See [ROADMAP.md](ROADMAP.md) for the development roadmap and [docs/adr/](docs/adr/) for architecture decision records.
