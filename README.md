# kotlin-native-nuget
<img src="docs/icon.svg" height="240" align="right"/> 

[![Stability](https://kotl.in/badges/experimental.svg)](https://kotlinlang.org/docs/components-stability.html#stability-of-subcomponents)
[![CI](https://github.com/xxfast/kotlin-native-nuget/actions/workflows/ci.yml/badge.svg)](https://github.com/xxfast/kotlin-native-nuget/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/xxfast/kotlin-native-nuget/graph/badge.svg?token=PEDQUEBEV8)](https://codecov.io/gh/xxfast/kotlin-native-nuget)

A plugin that allows you to publish your Kotlin/Native libraries as NuGet packages to be consumed by .NET projects.

## Background

[![Talk](http://img.youtube.com/vi/DywUS-qYn6o/0.jpg)](http://www.youtube.com/watch?v=DywUS-qYn6o "Isuru Rajapakse - Kotlin → C#; Experimenting with Cross-Runtime Interop")

https://www.youtube.com/watch?v=DywUS-qYn6o

Read the announcement: [Bring your KMP library to NuGet](https://medium.com/proandroiddev/bring-your-kmp-library-to-nuget-02e1131a4707)

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

## Kotlin → C#

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

## C# -> Kotlin

```c#
public class Template
{
    public Template(string template) { ... }  // constructor
    public string Name { get; set; }          // instance property
    public string Apply(string name) { ... }  // instance method
    public static Template Parse(string template) { ... } // static method
    public void Use(Action<Template> action) { ... }      // IDisposable pattern
    public static string Render(Template template, string name) { ... } // static method
}   
```

```kotlin
// build.gradle.kts
nuget {
  dependencies {
    dependency("TestDependency", version = "1.0.0") {
      bind {
        include("Test.Text")               // C# namespaces to bind
        alias("Test.Text", "sample.text")  // C# namespace to Kotlin package
      }
    }
  }
}
```

```kotlin
// Kotlin (auto-generated)
val template = Template("Hello, {name}")       // constructor
template.name = "Oreo"                         // instance property
template.apply("Oreo")                         // instance method
Template.parse("Hello, {name}")                // static -> companion object
template.use { Template.render(it, "Oreo") }   // handles are AutoCloseable
```

## Documentation

Full docs: **[xxfast.github.io/kotlin-native-nuget](https://xxfast.github.io/kotlin-native-nuget/)**

- [Prerequisites](https://xxfast.github.io/kotlin-native-nuget/prerequisites.html) and the version compatibility table
- [Getting started](https://xxfast.github.io/kotlin-native-nuget/getting-started.html)
- [Publishing Kotlin to C#](https://xxfast.github.io/kotlin-native-nuget/forward-overview.html): every construct that crosses, page by page
- [Consuming C# in Kotlin](https://xxfast.github.io/kotlin-native-nuget/reverse-overview.html): the `bind {}` DSL and what binds today
- [Gradle tasks](https://xxfast.github.io/kotlin-native-nuget/gradle-tasks.html) and the [`nuget {}` DSL reference](https://xxfast.github.io/kotlin-native-nuget/nuget-dsl.html)

> [!TIP]
> See [ARCHITECTURE.md](ARCHITECTURE.md) for how the bridge is built, [ROADMAP.md](ROADMAP.md) for what's next, and [docs/adr/](docs/adr/) for the architecture decision records.
