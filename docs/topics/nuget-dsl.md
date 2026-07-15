# The nuget {} DSL

Reference for the `nuget {}` extension, read from `NugetExtension.kt`, `NugetPublishConfig.kt`,
`NugetDependency.kt`, `NugetDependencyScope.kt`, and `NugetBindConfig.kt` in
`nuget-plugin/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/`.

## `nuget { }`

| Member | Configures | Required |
|---|---|---|
| `publish { }` | `NugetPublishConfig` | optional, omit for a consume-only project |
| `dependencies { }` | `NugetDependencyScope` | optional, omit for a publish-only project |

A project can declare either block, both, or neither meaningfully. `publish {}` alone publishes a
package with no reverse bindings; `dependencies {}` alone binds C# packages into Kotlin without
publishing anything. See [Gradle tasks](gradle-tasks.md) for exactly which tasks each combination
registers.

## `publish { }`

Configures `NugetPublishConfig`. Every field is a nullable `String` with no default. Nothing in
the DSL itself enforces they're set, but `packNuget` fails once it reads an unset one.

| Property | Type | Required | Maps to |
|---|---|---|---|
| `packageId` | `String?` | yes | `.nuspec` `<id>`, and the `.nupkg` file name |
| `version` | `String?` | yes | `.nuspec` `<version>` |
| `authors` | `String?` | yes | `.nuspec` `<authors>` |
| `description` | `String?` | yes | `.nuspec` `<description>` |
| `rootPackage` | `String?` | yes | the Kotlin package the generated C# namespaces are rooted at; sub-packages map relative to it |

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

## `dependencies { }`

Configures a `NugetDependencyScope`, whose only member is `dependency(...)`.

### `dependency(id, version = null) { }`

| Parameter | Type | Required |
|---|---|---|
| `id` | `String` | yes, the NuGet package id |
| `version` | `String?` | no, omit for an unpinned `<PackageReference Include="$id" />` |

Inside the trailing block, `NugetDependency` exposes:

| Property / function | Type | Required | Notes |
|---|---|---|---|
| `version` | `String?` | no | same as the `version` parameter; settable inside the block instead of passing it positionally |
| `source` | `String?` | no | an extra NuGet feed URL, added to `<RestoreSources>` alongside `api.nuget.org`, for a private/internal feed |
| `bind { }` | function, configures `NugetBindConfig` | no | omit to resolve the dependency without generating any Kotlin bindings for it |

```kotlin
nuget {
  dependencies {
    dependency("TestDependency", version = "1.0.0") {
      source = "https://my.private.feed/v3/index.json"
      bind { /* ... */ }
    }
  }
}
```

### `bind { }`

Configures `NugetBindConfig`. Declaring `bind {}` at all is what triggers `nugetExtractApi`,
`nugetGenerateBindings`, and `nugetGenerateShims` for that dependency. See
[Gradle tasks](gradle-tasks.md).

| Property / function | Type | Required | Default |
|---|---|---|---|
| `packageName` | `String?` | no | the dependency id, lowercased with `-` replaced by `_` (e.g. `TestDependency` becomes `sampledependency`) |
| `include(vararg namespace: String)` | function | no | empty, with no `include` at all, every namespace in the package is considered, subject to `exclude` |
| `exclude(vararg namespace: String)` | function | no | empty |
| `alias(csharpNamespace, kotlinPackage)` | function | no | none |

`include`/`exclude` match a C# namespace exactly, or any of its sub-namespaces (`ns == filter` or
`ns.startsWith("$filter.")`); when both match the same namespace, `exclude` wins. `alias` maps one
specific C# namespace to a Kotlin package, overriding both `packageName` and the id-derived
default for that namespace only.

```kotlin
dependency("TestDependency", version = "1.0.0") {
  bind {
    include("Test.Text")
    exclude("Test.Text.Internal")
    alias("Test.Text", "sample.text")
  }
}
```

Only one `bind { }` block is supported per dependency (a second call overwrites the first).

See
[ADR-044](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/044-nuget-dependency-dsl.md)
for the DSL's design rationale, and
[ADR-047](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/047-per-package-namespace-filters-at-reader-cli.md)
for the include/exclude filter semantics. For what actually gets bound once a namespace is
included, see [Consuming C# in Kotlin](reverse-overview.md).
