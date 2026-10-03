# Declaring dependencies

`nuget { dependencies { } }` declares which NuGet packages your Kotlin/Native module resolves.
Declaring a dependency always restores it and its transitive packages through `dotnet restore`,
the same way a plain `<PackageReference>` would. Add a `bind { }` block to also generate Kotlin
stubs for it, scoped to the namespaces you name.

```kotlin
nuget {
  dependencies {
    dependency("MimeMapping", version = "4.0.0") {
      bind {
        packageName = "mimemapping"
        includeNamespaces("MimeMapping")
      }
    }
  }
}
```

The bridgeable types in the `MimeMapping` namespace bind under the `mimemapping` package, so
`MimeUtility.GetMimeMapping(...)` is callable as `mimemapping.MimeUtility.getMimeMapping(...)`; see
[Static classes and methods](static-classes-and-methods.md) and
[The bridgeable subset](bridgeable-subset.md).

## Choosing namespaces

`includeNamespaces()`, `excludeNamespaces()`, and `alias()` scope and rename what a `bind { }` block generates:

```kotlin
nuget {
  dependencies {
    dependency("TestDependency", version = "1.0.0") {
      bind {
        includeNamespaces("Test.Text")
        alias("Test.Text", "test.text")
      }
    }
  }
}
```

- No `includeNamespaces()` call means every public namespace in the package is a candidate.
- `includeNamespaces()` narrows candidates to the named namespaces and their sub-namespaces; `excludeNamespaces()` then
  removes from that set and always wins on a conflict.
- A namespace outside the final set gets no Kotlin at all: no stub, no import, nothing to call.
- `alias(csharpNamespace, kotlinPackage)` overrides `packageName` for that one namespace. A
  namespace without a matching `alias()` falls back to `packageName`, or, if that's unset too, to
  the package id lowercased with `-` replaced by `_`.
- `alias()` matches one namespace exactly, unlike the filters: an included sub-namespace needs its own
  `alias()` line or it uses `packageName`. Several namespaces may alias to one package.
- A second `bind { }` block merges into the first (lists and aliases accumulate, `packageName` is
  last-wins), so groups of namespaces get their own packages through aliases:

```kotlin
dependency("TestDependency", version = "1.0.0") {
  bind {
    includeNamespaces("Test.Text")
    alias("Test.Text", "test.text")
    includeNamespaces("Test.Enums")
    alias("Test.Enums", "test.enums")
  }
}
```

See [The nuget {} DSL](nuget-dsl.md) for every `bind { }` property, its default, and whether it's
required.

## Shared feeds {id="shared-feeds"}

Declare feeds once at the `nuget {}` level and every dependency restores against them. Entries are feed URLs or directories of `.nupkg` files; relative directories resolve against the project directory. Repeated calls append.

```kotlin
nuget {
  sources("https://feed.example/v3/index.json", "../local-feed")
  dependencies { dependency("Acme.Text", "1.2.0") { bind { } } }
}
```

- The restore feeds are nuget.org, then the shared list, then each dependency's own `source`, with duplicates dropped.
- The feeds are a union. A per-dependency remote `source` adds a feed rather than replacing the list, and no feed is preferred for an id: if two remote feeds serve the same id and version, which one wins is unspecified.
- A directory entry behaves like a [local `source`](#binding-a-locally-built-package): same-version rebuilds are picked up, restore moves to `build/nuget-interop/packages`, and the build fails if a package that a local feed holds at that exact id and version was restored from anywhere else. A local feed holding only other versions of an id is ignored.
- A `.nupkg` file, a `file://` URL and a directory that does not exist fail the build. For a single package, set `source` on its dependency.
- Declaring nothing leaves restore to your own NuGet.Config.

## Binding a locally built package

Set `source` to a directory of `.nupkg` files or to one `.nupkg` file to bind a package you built
yourself. Relative paths resolve against the project directory.

```kotlin
dependency("Acme.Text", "1.2.0") { source = "../acme/artifacts"; bind { } }
dependency("Acme.Local") { source = "../acme/bin/Release/Acme.Local.1.0.0.nupkg"; bind { } }
```

- A directory source needs a `version`; without one, restore fails with `NU1015`.
- A `.nupkg` file source takes its id and version from the package. The `version` may be omitted; a different
  `version` or id fails the build.
- `file://` URLs are rejected; use a plain path.

Rebuilding the package under the same version is picked up on the next build: a project with a
local source restores into `build/nuget-interop/packages`, and the plugin refreshes the local
packages there before each restore. If the restored package is not the one from your source (for
example nuget.org serves the same id and version), the build fails naming the dependency and the
path instead of binding the wrong assembly.

A bound package restored from a local source is pinned at its version when you `packNuget`, so a
consumer needs a feed that serves it.

The path is read as plain text, so a task that builds the `.nupkg` in the same Gradle build is not
run for you; add a `dependsOn` from `nugetRestore` if you need that.

<seealso>
    <category ref="related">
        <a href="reverse-overview.md">Consuming C# in Kotlin</a>
        <a href="bind-nuget-package-for-kotlin.md">Bind a NuGet package for Kotlin</a>
        <a href="bridgeable-subset.md">The bridgeable subset</a>
    </category>
</seealso>
