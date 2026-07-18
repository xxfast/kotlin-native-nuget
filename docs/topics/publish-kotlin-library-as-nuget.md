# Publish a Kotlin/Native library as NuGet

Configure a Kotlin Multiplatform module, package its native binaries and generated C# API, then add
the resulting `MyCatLib` package to a .NET project.

## 1. Apply the plugin and build shared libraries

Apply Kotlin Multiplatform and the NuGet plugin, then give every supported native target the same
shared-library `baseName`:

```kotlin
plugins {
  kotlin("multiplatform")
  id("io.github.xxfast.kotlin.native.nuget") version "<version>"
}

kotlin {
  mingwX64 {
    binaries {
      sharedLib {
        baseName = "mycatlib"
      }
    }
  }

  macosArm64 {
    binaries {
      sharedLib {
        baseName = "mycatlib"
      }
    }
  }
}
```

Replace `<version>` with the version shown in [Getting started](getting-started.md), and keep it
pinned. Only configured targets in the [supported target table](prerequisites.md) are added to the
package.

## 2. Configure the NuGet package

Publish every public declaration below `rootPackage` into the corresponding C# namespace:

```kotlin
nuget {
  publish {
    packageId = "MyCatLib"
    version = "1.0.0"
    authors = "yourname"
    description = "A Kotlin/Native library for cat lovers"
    rootPackage = "com.example.cats"
  }
}
```

For example, add this API under `src/nativeMain/kotlin/com/example/cats/Cat.kt`:

```kotlin
package com.example.cats

interface Pet {
  val name: String
  fun speak(): String
}

enum class Mood { HAPPY, SLEEPY, GRUMPY }

abstract class Animal(override val name: String) : Pet

class Cat(
  name: String,
  val lives: Int = 9,
) : Animal(name) {
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

The root package maps to the `MyCatLib` C# namespace. After adding the NuGet package to a .NET
project, use the generated API like this:

```C#
using MyCatLib;

using var oreo = new Cat("Oreo", 9);
Console.WriteLine(oreo.Name);    // Oreo
Console.WriteLine(oreo.Speak()); // Meow!

using var mylo = new Cat("Mylo", 9);
oreo.Brother = mylo;
oreo.Mood = Mood.Happy;
```

See [Publishing Kotlin to C#](forward-overview.md) for the supported Kotlin constructs and their
generated C# shapes. The complete package metadata options are in the
[`nuget {}` DSL reference](nuget-dsl.md).

## 3. Build and consume the package

Build the package from the project root:

```bash
./gradlew packNuget
```

The package is written to `build/nuget/MyCatLib.1.0.0.nupkg`. See
[Gradle tasks](gradle-tasks.md) for the complete output layout.

Add that directory as a NuGet package source, then add a `PackageReference` from the C# consumer.
For example, create a `NuGet.Config` beside the .NET solution:

```xml
<?xml version="1.0" encoding="utf-8"?>
<configuration>
  <packageSources>
    <clear />
    <add key="nuget.org" value="https://api.nuget.org/v3/index.json" />
    <add key="mycatlib-local" value="../my-cat-lib/build/nuget" />
  </packageSources>
</configuration>
```

```xml
<ItemGroup>
  <PackageReference Include="MyCatLib" Version="1.0.0" />
</ItemGroup>
```

Adjust the relative feed path for your directory layout. Keep the package reference version in sync
with `publish {}`. NuGet brings in the generated C# source and selects the native binary for the
consumer's runtime identifier.

A framework-dependent C# build also needs a runtime identifier so MSBuild copies the matching
native asset beside the host. For a cross-platform project, use the SDK host RID:

```xml
<RuntimeIdentifier>$(NETCoreSdkRuntimeIdentifier)</RuntimeIdentifier>
<SelfContained>false</SelfContained>
```

Do not hardcode one host RID into a cross-platform test project. For deployment, select the RID of
the target environment.

## 4. Publish the package to a feed

The local package source above is useful while developing and testing the library. To distribute
`MyCatLib`, publish the generated package to NuGet.org or a private NuGet feed instead.

`packNuget` creates the `.nupkg`, but does not upload it. With the .NET SDK installed and
credentials for the destination feed, push the package with
[`dotnet nuget push`](https://learn.microsoft.com/dotnet/core/tools/dotnet-nuget-push):

```bash
dotnet nuget push build/nuget/MyCatLib.1.0.0.nupkg \
  --source https://api.nuget.org/v3/index.json \
  --api-key <NUGET_API_KEY>
```

For a private feed, replace `--source` with that feed's endpoint and use credentials accepted by
the feed. Consumers then reference `MyCatLib` with the same normal `PackageReference` shown above
and restore it from the published feed. They do not need the `mycatlib-local` entry in their
`NuGet.Config`.

<seealso>
    <category ref="related">
        <a href="how-to-guide.md">How-to guides</a>
        <a href="getting-started.md">Getting started</a>
        <a href="gradle-tasks.md">Gradle tasks</a>
        <a href="nuget-dsl.md">The nuget {} DSL</a>
        <a href="forward-overview.md">Publishing Kotlin to C#</a>
    </category>
</seealso>
