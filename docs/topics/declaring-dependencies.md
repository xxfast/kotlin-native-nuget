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

## Limitations

- A private feed is set per package with `source = "https://.../index.json"` inside `dependency()`;
  there's no extension-level shared feed list yet.
- There's no local `.nupkg` or path-based dependency source yet, only registry resolution.

<seealso>
    <category ref="related">
        <a href="reverse-overview.md">Consuming C# in Kotlin</a>
        <a href="bind-nuget-package-for-kotlin.md">Bind a NuGet package for Kotlin</a>
        <a href="bridgeable-subset.md">The bridgeable subset</a>
    </category>
</seealso>
