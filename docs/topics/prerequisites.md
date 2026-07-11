# Prerequisites

## Kotlin side (library author)

- JDK 17+
- Gradle, via the included wrapper (`./gradlew`)
- [.NET SDK](https://dotnet.microsoft.com/download) 8.0+, **only if you bind a NuGet package into
  Kotlin** (`nuget { dependencies { dependency(...) { bind { ... } } } }`).

  Publishing Kotlin to NuGet needs no .NET SDK. `packNuget` writes the `.nupkg` itself with
  `java.util.zip` and never shells out to `dotnet`. Only `nugetRestore` and `nugetExtractApi`
  invoke `dotnet` (both require it via `requireDotnet()` in
  `nuget-plugin/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/NugetTooling.kt`), and both
  only run when a dependency is declared. See [Gradle tasks](gradle-tasks.md).

## C# side (consumer)

- [.NET SDK](https://dotnet.microsoft.com/download) 8.0+

```bash
brew install dotnet
```

That's it. Bindings are pre-generated at Kotlin compile time via KSP, so the consumer needs no
additional tooling.

## Compatibility

| kotlin-native-nuget | Kotlin  | KSP     | Gradle | JDK | .NET   |
|----------------------|---------|---------|--------|-----|--------|
| `0.1.0`              | `2.4.0` | `2.3.9` | `9.1`  | 17+ | `8.0`+ |

KSP is pinned to its Kotlin version, so bumping Kotlin without bumping the plugin is not
supported.

## Supported native targets

The plugin maps Kotlin/Native targets to NuGet runtime identifiers (RIDs), used for the
`runtimes/{rid}/native/` package layout. The map lives as `KONAN_TO_RID` in
`nuget-plugin/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/NugetPlugin.kt`, keyed on
`KonanTarget.name`; the table below uses the Gradle DSL target names you actually write inside
`kotlin { }`:

| Kotlin target | RID           | Exercised in CI |
|----------------|---------------|:----------------:|
| `mingwX64`     | `win-x64`     | Yes              |
| `macosArm64`   | `osx-arm64`   | Yes              |
| `macosX64`     | `osx-x64`     | No               |
| `linuxX64`     | `linux-x64`   | No               |
| `linuxArm64`   | `linux-arm64` | No               |

A native target outside this table is skipped with a warning, and if no configured target is
supported, the plugin skips the whole project for that build. See
[Getting started](getting-started.md) for target configuration and
[Gradle tasks](gradle-tasks.md) for what runs where.
