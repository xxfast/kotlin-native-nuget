# Getting started

This walks through publishing a Kotlin/Native library as a NuGet package and consuming it from a
C# project, end to end. It mirrors `test-library/build.gradle.kts` and the `IntegrationTests`
project in this repo, read those alongside this page for a working reference.

## 1. Apply the plugin

```kotlin
// build.gradle.kts
plugins {
  kotlin("multiplatform")
  id("io.github.xxfast.kotlin.native.nuget") version "<version>"
}
```

The plugin id on the
[Gradle Plugin Portal](https://plugins.gradle.org/plugin/io.github.xxfast.kotlin.native.nuget) is
`io.github.xxfast.kotlin.native.nuget`.

## 2. Declare native targets

Every target that should ship a native binary needs a `sharedLib` binary with a `baseName`. This
becomes the native library name (`mycatlib.dll` on Windows, `libmycatlib.dylib` on macOS, ...)
that the generated C# `[DllImport]`s.

```kotlin
kotlin {
  mingwX64 { binaries { sharedLib { baseName = "mycatlib" } } }
  macosArm64 { binaries { sharedLib { baseName = "mycatlib" } } }
}
```

Targets outside the supported set are skipped with a warning; see
[Prerequisites](prerequisites.md) for the full target → RID table.

## 3. Configure the package

```kotlin
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

`rootPackage` is the Kotlin package the generated C# namespaces are rooted at; sub-packages map
relative to it. See the [nuget {} DSL reference](nuget-dsl.md) for every field.

## 4. Pack it

```bash
./gradlew packNuget
```

`packNuget` runs KSP to generate the C# bindings, links the shared libraries for every configured
target, and writes a real `.nupkg` at:

```
build/nuget/MyCatLib.1.0.0.nupkg
```

(the staged, unzipped contents sit alongside it at `build/nuget/MyCatLib.1.0.0/`). No .NET SDK is
required for this step.

## 5. Consume it from C#

Point a C# project at the folder containing the `.nupkg` as a local NuGet feed (or push it to a
real feed) and add a `PackageReference`. The package ships the native libs under
`runtimes/{rid}/native/` and the pre-generated `Interop.cs` under `contentFiles/cs/any/`, both
wire up automatically, no consumer-side codegen.

```xml
<!-- your.csproj -->
<ItemGroup>
  <PackageReference Include="MyCatLib" Version="1.0.0" />
</ItemGroup>
```

```C#
using var oreo = new Cat("Oreo", 9);
Console.WriteLine(oreo.Speak());
```

For the mapping between individual Kotlin constructs and their generated C# shape, see
[Publishing Kotlin to C#](forward-overview.md).
