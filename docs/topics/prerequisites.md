# Prerequisites

## Kotlin side (library author)

- JDK 17+
- Gradle, via the included wrapper (`./gradlew`)
- [.NET SDK](https://dotnet.microsoft.com/download) 10.0+, only if you bind a NuGet package into
  Kotlin (`nuget { dependencies { dependency(...) { bind { ... } } } }`). Publishing needs no
  .NET SDK: `packNuget` writes the `.nupkg` itself.

  If Gradle can't see `dotnet` on its `PATH` (an IDE-launched daemon, say), set `nuget.dotnet` to the
  executable's absolute path in the root project's `local.properties`:

  ```
  nuget.dotnet=/usr/local/share/dotnet/dotnet
  ```

  A Gradle property of the same name (`-Pnuget.dotnet=...` or `gradle.properties`) also works;
  `local.properties` wins. A path that is relative, missing or not executable fails the build
  rather than falling back to `PATH`.

  <note>
    <p>
      If a .NET SDK is on <code>PATH</code>, <code>packNuget</code> also compiles the generated C#
      bindings before packing and fails the build on a compiler error. Without the SDK (or one
      that can't run), this check is skipped with a warning and publishing proceeds as before, unless
      you set <code>publish { strictCompileCheck = true }</code> (or <code>-Pnuget.strictCompileCheck=true</code>), which fails the build instead. See
      <a href="gradle-tasks.md#nugetcompileinterop">Gradle tasks</a>.
    </p>
  </note>

## C# side (consumer)

- [.NET SDK](https://dotnet.microsoft.com/download) 10.0+

```bash
brew install dotnet
```

Bindings are pre-generated at Kotlin compile time via KSP, so the consumer needs no additional
tooling.

## Compatibility

| kotlin-native-nuget | Kotlin              | KSP (bundled) | Gradle | JDK | .NET   |
|----------------------|---------------------|---------------|--------|-----|--------|
| `0.9.0`              | `2.4.0` to `2.4.20` | `2.3.10`      | `9.1`  | 17+ | `10.0`+ |
| `0.2.0` – `0.8.0`    | `2.4.10`            | `2.3.10`      | `9.1`  | 17+ | `8.0`+ |
| `0.1.0`              | `2.4.0`             | `2.3.9`       | `9.1`  | 17+ | `8.0`+ |

Releases up to `0.8.0` shipped one Kotlin Gradle plugin and silently upgraded yours to it. From
`0.9.0` the plugin uses the Kotlin version you declare, from a floor up to the last tested release.

- **Below `2.4.0`** the build fails at configuration time. The published `nuget-runtime` and
  `nuget-annotations` libraries are built at klib ABI 2.4, which an older Kotlin/Native compiler
  can't read.

  ```
  [nuget] Kotlin 2.3.21 is not supported. kotlin-native-nuget 0.9.0 needs Kotlin 2.4.0 or newer: its nuget-runtime and nuget-annotations klibs are built at klib ABI 2.4, which an older Kotlin/Native compiler cannot read. Update the Kotlin Gradle plugin to 2.4.0 or newer.
  ```

- **Above `2.4.20`** the build warns and continues:
  `Kotlin 2.5.0 is newer than the last version kotlin-native-nuget 0.9.0 was tested with (2.4.20). It is expected to work. If it does not, update the plugin.`
  Patch releases above `2.4.20` warn too. Only the leading `major.minor.patch` is compared, so
  `2.4.0-RC2` counts as `2.4.0`.
- **KSP** is applied by the plugin, so you don't choose a KSP version and it doesn't have to match
  your Kotlin version.

<note>
  <p>
    Declare this plugin and the Kotlin Multiplatform plugin in the same project's
    <code>plugins {}</code> block. If the root project declares this plugin with
    <code>apply false</code> and only a child project declares Kotlin Multiplatform, the build
    fails with <code>Could not generate a decorated class for type NugetPlugin</code> before the
    version check runs. Declare both plugins with <code>apply false</code> in the root project to
    work around it.
  </p>
</note>

## Supported native targets

The plugin maps Kotlin/Native targets to NuGet runtime identifiers (RIDs), used for the
`runtimes/{rid}/native/` package layout:

| Kotlin target | RID           | Exercised in CI |
|----------------|---------------|:----------------:|
| `mingwX64`     | `win-x64`     | Yes              |
| `macosArm64`   | `osx-arm64`   | Yes              |
| `macosX64`     | `osx-x64`     | No               |
| `linuxX64`     | `linux-x64`   | Yes              |
| `linuxArm64`   | `linux-arm64` | Compiled only    |

The runtime library that generated bindings depend on is published for every target in this table,
including `linuxArm64`.

A target outside this table is skipped with a warning, and if no configured target is supported,
the plugin skips the whole project for that build. See [Getting started](getting-started.md) for
target configuration.
