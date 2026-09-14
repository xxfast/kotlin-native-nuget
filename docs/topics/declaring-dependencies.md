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
        include("MimeMapping")
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

`include()`, `exclude()`, and `alias()` scope and rename what a `bind { }` block generates:

```kotlin
nuget {
  dependencies {
    dependency("TestDependency", version = "1.0.0") {
      bind {
        include("Test.Text")
        alias("Test.Text", "test.text")
      }
    }
  }
}
```

- No `include()` call means every public namespace in the package is a candidate.
- `include()` narrows candidates to the named namespaces and their sub-namespaces; `exclude()` then
  removes from that set and always wins on a conflict.
- A namespace outside the final set gets no Kotlin at all: no stub, no import, nothing to call.
- `alias(csharpNamespace, kotlinPackage)` overrides `packageName` for that one namespace. A
  namespace without a matching `alias()` falls back to `packageName`, or, if that's unset too, to
  the package id lowercased with `-` replaced by `_`.

See [The nuget {} DSL](nuget-dsl.md) for every `bind { }` property, its default, and whether it's
required.

## Limitations

- Only one `bind { }` block per `dependency()` is supported: a second `bind { }` call on the same
  dependency replaces the first rather than merging with it.
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
