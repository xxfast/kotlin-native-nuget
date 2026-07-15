# Declaring dependencies

`nuget { dependencies { ... } }` declares which NuGet packages your Kotlin/Native library resolves,
and, for the ones you actually want to call from Kotlin, which of their namespaces get bound into
Kotlin stubs.

## Resolve versus bind

Declaring a dependency always resolves it, transitively, through `dotnet restore`, the same way any
`<PackageReference>` would. Resolving does **not** generate any Kotlin bindings. A NuGet package
commonly pulls in ten or more transitive packages; generating stubs for all of them by default would
produce an overwhelming API surface for a library you only wanted one method from.

Binding is opt-in: only a `dependency()` block that contains a nested `bind { }` block gets Kotlin
stubs, and only the namespaces you `include()` inside that block get bound
([ADR-044](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/044-nuget-dependency-dsl.md)).
Everything else in the transitive closure is resolved (so it links and restores correctly) but never
turns into Kotlin code.

## The DSL

The model classes live in `nuget-plugin/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/`:
`NugetDependency.kt`, `NugetDependencyScope.kt`, and `NugetBindConfig.kt`.

```kotlin
class NugetDependency(val id: String) {
  var version: String? = null
  var source: String? = null
  var bind: NugetBindConfig? = null
    private set

  fun bind(configure: NugetBindConfig.() -> Unit) { /* ... */ }
}

class NugetBindConfig {
  var packageName: String? = null

  fun include(vararg namespace: String) { /* ... */ }
  fun exclude(vararg namespace: String) { /* ... */ }
  fun alias(csharpNamespace: String, kotlinPackage: String) { /* ... */ }
}
```

| Property / function | Where | Meaning |
|---|---|---|
| `version` | `dependency()` | The package version to restore. |
| `source` | `dependency()` | A custom NuGet feed URL for this package. `null` uses `nuget.org`. |
| `bind { }` | `dependency()` | Presence opts the package into Kotlin binding generation; absence means resolve-only. |
| `packageName` | `bind { }` | The Kotlin package the bound namespaces land in. `null` derives it from the NuGet package ID (lower-cased, `-` → `_`). |
| `include(vararg)` | `bind { }` | Namespace whitelist. Empty means every public namespace in the package is a candidate. |
| `exclude(vararg)` | `bind { }` | Namespace blacklist, applied after `include()`. |
| `alias(csharpNamespace, kotlinPackage)` | `bind { }` | Overrides `packageName` for one specific C# namespace. |

## Real examples

Both of these are taken verbatim from `test-library/build.gradle.kts`.

A static-methods-only package, bound under an explicit `packageName`:

```kotlin
nuget {
  dependencies {
    dependency("MimeMapping", version = "4.0.0") {
      bind {
        packageName = "mimemapping"
        include("MimeMapping")
      }
    }
  }
}
```

Every public type in the `MimeMapping` namespace becomes Kotlin under the `mimemapping` package,
which is how `MimeUtility.getMimeMapping(...)` in
[Static classes and methods](static-classes-and-methods.md) ends up importable as
`mimemapping.MimeUtility`.

A package bound with a per-namespace `alias()` instead of a package-wide name:

```kotlin
nuget {
  dependencies {
    dependency("TestDependency", version = "1.0.0") {
      bind {
        include("Test.Text")
        alias("Test.Text", "sample.text")
      }
    }
  }
}
```

Here no `packageName` is set at all; `alias()` maps the single included namespace `Test.Text`
directly onto the Kotlin package `sample.text`, which is where the `Template` wrapper used throughout
[Objects and handles](objects-and-handles.md) and [Instance members](instance-members.md) is
generated.

## Namespace filtering semantics

- If `include()` is never called, every public namespace in the package is a candidate.
- If `include()` is called one or more times, only those namespaces (and their sub-namespaces) are
  candidates.
- `exclude()` is applied after `include()` and can only remove candidates, never add them back.
- `alias()` overrides `packageName` for one namespace; namespaces without a matching `alias()` fall
  back to `packageName`, or to the derived default if `packageName` is also unset.

These filters are forwarded to the `NugetMetadataReader` subprocess as `--include`/`--exclude` CLI
arguments, so a namespace you never included never even reaches `reverse-ir.json`, let alone the
generators.

## Limitations

- Only one `bind { }` block per `dependency()` is supported; binding the same package under two
  different `packageName` values needs a workaround today.
- `source` is per-dependency only; there's no extension-level shared feed list yet.
- There's no local `.nupkg` or path-based dependency source yet, only registry resolution.

See [ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md) Phase 8 for the
current state of these.

<seealso>
    <category ref="related">
        <a href="reverse-overview.md">Consuming C# in Kotlin</a>
        <a href="bridgeable-subset.md">The bridgeable subset</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/044-nuget-dependency-dsl.md">ADR-044: NuGet dependency DSL</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/045-nuget-resolution-pipeline.md">ADR-045: NuGet resolution pipeline</a>
    </category>
</seealso>
