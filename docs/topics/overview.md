# Overview

![kotlin-native-nuget](icon.svg){width="160"}

**kotlin-native-nuget** is a Gradle plugin that bridges Kotlin/Native and C# across a NuGet
package boundary, in two directions:

- **[Kotlin → C#](forward-overview.md)** (forward): publish a Kotlin/Native library as a NuGet
  package. KSP generates a C# `Interop.cs` at compile time, so consumers add the package and call
  your Kotlin API as ordinary C# classes. No consumer-side tooling required.
- **[C# → Kotlin](reverse-overview.md)** (reverse): bind a C# NuGet package's public API into
  Kotlin. The Gradle plugin resolves the dependency, extracts its API surface, and generates
  Kotlin-idiomatic stubs backed by opaque handles.

## Stability

`0.x` and experimental. Anything can change between plugin versions.

The generated bindings are the public API of **your** NuGet package. Your consumers see your
version, never the plugin's. A plugin upgrade that changes how Kotlin renders into C# breaks them
at your version, not the plugin's.

Pin the plugin version. Diff the generated `Interop.cs` when you bump it.

## Where to go next

- [Setup](prerequisites.md): prerequisites, getting started end to end, the Gradle tasks the
  plugin registers, and the `nuget {}` DSL reference.
- [Publishing Kotlin to C#](forward-overview.md): the forward direction, what Kotlin constructs
  map to what C#.
- [Consuming C# in Kotlin](reverse-overview.md): the reverse direction, binding a NuGet package
  into Kotlin.
