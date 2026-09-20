# The nuget {} DSL

Reference for the `nuget {}` extension.

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
| `rootPackage` | `String?` | yes | the Kotlin package the generated C# namespaces are rooted at; sub-packages map relative to it. Also the default export scope: see below |
| `include(vararg packages: String)` | function | no | empty; when set, only these package prefixes (and their sub-packages) are bridged |
| `exclude(vararg packages: String)` | function | no | empty; a package prefix or a qualified declaration name (a class, object, sealed base, or top-level function, plus everything nested under it); applied after `include`, and always wins over it |
| `admit(vararg types: String)` | function | no | empty; additively admits a dependency-module type into [the cross-module export closure](#cross-module-export-closure) by qualified name or package prefix, without touching `include`/`rootPackage`; see below |
| `strictDependencyTypes` | `Boolean` | no | `false`; when `true`, an un-admitted dependency type in a public signature fails the build (`ERROR_UNEXPORTED_DEPENDENCY_TYPE`) instead of warning; see below |
| `snapshot` | `Boolean` | no | `false`; when `true`, `packNuget` mints `<version>-snapshot.<epochMillis>` at execution time instead of using `version` literally, and always writes an MSBuild props file. Requires `packageId` and a non-blank `version` |
| `versionPropsFile` | `File?` | no | `null`; only consulted when `snapshot` is `true`. Default `<rootProject>/build/<packageId>Versions.props` |
| `prebuiltRuntimes` | `File?` | no | `null`; a directory laid out `<rid>/native/*.{dll,dylib,so}`, exactly the `runtimes/` tree `packNuget` stages, merged with the RIDs this host links itself into one package |

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

### Snapshot versioning

With `snapshot = true`, every `packNuget` run mints a fresh, immutable version instead of reusing
`version` as-is, so a .NET consumer's next restore always sees a new version and never serves
NuGet's cached copy of the previous build:

```kotlin
nuget {
  publish {
    // packageId, version, authors, description, rootPackage as above
    snapshot = true
  }
}
```

The minted version (`1.0.0-snapshot.<epochMillis>`) is pinned in an MSBuild props file under a
property name derived from `packageId`: every character outside `[A-Za-z0-9_]` is dropped, a
leading digit gets a `_` prefix, and `Version` is appended (`MyCatLib` becomes `MyCatLibVersion`).
See [Publish a Kotlin/Native library as NuGet](publish-kotlin-library-as-nuget.md) for the full
local-iteration flow and the consumer-side props import.

### Multi-RID packages

`packNuget` only links the native targets this host's Kotlin/Native toolchain can build, but a
package worth publishing needs every RID your CI matrix produces. `prebuiltRuntimes` points at a
directory built by another host and merges it into this host's own pack, so one `packNuget` run
still produces one package covering both:

```kotlin
nuget {
  publish {
    packageId = "MyCatLib"
    version = "1.0.0"
    prebuiltRuntimes = file("build/prebuilt-runtimes")  // <rid>/native/*.dll|*.dylib|*.so
  }
}
```

A typical two-host CI flow: a Windows leg runs `packNuget` and uploads its staged `runtimes/`
folder as an artifact, then a macOS leg downloads it, points `prebuiltRuntimes` at it, and runs
`packNuget` itself, producing one package with both RIDs.

<note>
<p>A target whose link task is disabled on the packing host (say <code>mingwX64</code> declared but
not linkable here) is excluded from the locally linked set, with a lifecycle log naming the RID and
pointing at <code>prebuiltRuntimes</code> as the way to still ship it. A host with every local link
disabled and <code>prebuiltRuntimes</code> set still gets a <code>packNuget</code> task: a
pack-only host is a supported shape.</p>
</note>

`packNuget` validates the merge rather than silently dropping anything:

- A locally linked RID with no `.dll`/`.dylib`/`.so` to copy fails the build naming the RID and the
  directory scanned.
- A `prebuiltRuntimes` directory with no RID subdirectories, or a RID subdirectory whose `native/`
  folder is missing or empty, fails naming the expected layout
  (`<prebuiltRuntimes>/<rid>/native/*.dll|*.dylib|*.so`).
- The same RID arriving both locally linked and prebuilt fails naming both sources: pick one
  producer per RID.
- A prebuilt RID name this plugin version does not know how to build is a warning only, since the
  RID set NuGet accepts is open and the tree may come from a newer plugin.

### Export scoping

By default `NugetProcessor` bridges every public declaration in the module. `include`/`exclude`
scope that down: a package prefix match (`pkg == p || pkg.startsWith("$p.")`), with `exclude`
always winning over `include`. `exclude` additionally matches a qualified declaration name and
everything nested under it: `exclude("com.contoso.api.Shape")` drops the `Shape` class (and, for a
sealed base, its subclasses) from the export set, so a member that references it is skipped with a
named diagnostic and the rest of the package still bridges. `include` stays package-level.

```kotlin
nuget {
  publish {
    packageId = "Contoso.Api"
    rootPackage = "com.contoso.api"
    include("com.contoso.api")            // whitelist of package prefixes
    exclude("com.contoso.api.internal")   // exclude wins
    exclude("com.contoso.api.Legacy")     // one type, by qualified name
  }
}
```

When `include` is empty, the effective include set defaults to `rootPackage` if set, otherwise to
everything. This makes `rootPackage` a scoping knob, not just a renaming one: a public declaration
outside `rootPackage` is not bridged unless named explicitly in `include`. A scope that admits none
of the module's public declarations (for example a stray `include("kotlin")`) warns once with
`SKIPPED_ALL_DECLARATIONS` instead of silently shipping a package with no `Interop.cs`.

A package that is only reached via `dependencies { dependency(...) { bind { } } }` (the reverse
stub packages generated for a consumed C# dependency) is always exported regardless of
`include`/`exclude`, since a module that both publishes forward and consumes a NuGet dependency
needs those bound types reachable from its own forward return types to keep compiling.

### Cross-module export closure

`include`/`exclude`/`rootPackage`/`admit` decide what crosses a Gradle module boundary. The export
set is a reachability closure from the module's own admitted declarations: the processor walks
return types, parameter types, property types, type arguments of an admitted carrier (`Flow<T>`,
`List<T>`/`Set<T>`/`Map<K,V>`), sealed subclasses, and primary-constructor parameter types, and
admits every discovered declaration through the same predicate: `include`/`rootPackage` for a whole
package, or an additive `admit` entry for one type or package, minus anything `exclude` names,
whether it lives in this module or in a dependency module pulled in with
`implementation(project(":models"))`. A `:models` module under the same `rootPackage` is admitted
automatically, with no extra config.

```kotlin
nuget {
  publish {
    packageId = "TestLibrary"
    rootPackage = "io.github.xxfast.kotlin.native.nuget.test"
    // io.github.xxfast.kotlin.native.nuget.test.models is under rootPackage: admitted with no
    // extra config. dev.other.core is not: reachable types from it are skipped unless named here.
    include("dev.other.core")
  }
}
```

`include(...)` widens admission by whole package, which is right when your own API genuinely
reaches most of a dependency's models. For a single third-party type (a ktor `Url`, a kermit
`Severity`) that would mean owning and versioning a whole package you don't control just to
reach one class or enum. `admit(vararg types: String)` is the finer-grained, additive alternative:
it takes a qualified type name or a package prefix, the same matcher `exclude` uses, adds entries
to the closure's admission predicate only, and never picks roots or replaces the `rootPackage`
default the way an explicit `include` does.

```kotlin
nuget {
  publish {
    packageId = "TestLibrary"
    rootPackage = "io.github.xxfast.kotlin.native.nuget.test"
    // admit ONE type by name: only Url itself is admitted, not io.ktor.http.
    admit("io.ktor.http.Url")
    // admit a whole package by prefix: the other arm of the same matcher.
    admit("co.touchlab.kermit")
  }
}
```

Admitting a type admits only that declaration, not its package: a sibling type or a member typed
with something you didn't also `admit` still skips named, with a hint that says
`add admit("<qualified type>")`, never `include(...)` (an additive verb has no replacement trap to
word around). `exclude` still wins over `admit` the same way it wins over `include`. Use `include`
to widen a whole dependency package your own API is built around; use `admit` to pull in one or a
few specific types from a package you otherwise leave alone; use `exclude` to record either
omission as deliberate.

Admitting a **nested** type (`admit("dep.edge.Ledger.Entry")`) doesn't work: the closure climbs
from a nested declaration to its owner before admitting anything, so that entry refuses `Ledger`
and the refusal propagates onto `Entry`. Admit the outermost type instead
(`admit("dep.edge.Ledger")`), which also declares `Entry` as its nested type once `Ledger` is
admitted. `exclude`, by contrast, is tested on the declaration itself before that climb, so it
still takes the full nested name (`exclude("dep.edge.Ledger.Entry")`).

By default an un-admitted dependency type is a warning plus a named skip (below). Set
`strictDependencyTypes = true` to fail the build instead, once every dependency type reached by a
public signature is either admitted or excluded by name:

```kotlin
nuget {
  publish {
    rootPackage = "com.example.sdk"
    admit("io.ktor.http.Url")
    strictDependencyTypes = true
  }
}
```

Under `strictDependencyTypes`, a type the closure refused because it was never admitted, or
because `admit`/`include`/`rootPackage` are all unset so the closure never crosses the module
boundary at all, becomes `ERROR_UNEXPORTED_DEPENDENCY_TYPE` and stops the build. A type refused
because you `exclude`d it stays a warning: that omission is already deliberate, so escalating it
would make strict mode unsatisfiable for any dependency the build genuinely amputates.

A dependency-module type the closure refuses to admit is skipped, and the diagnostic names the
actual reason (not admitted, excluded, cross-module admission off entirely, or an `expect`
declaration whose actualization lives in the dependency and can't be brought into scope at all)
with an actionable fix for each. See
[Publishing Kotlin to C#](forward-overview.md#where-these-messages-appear) for the full set of
messages.

A dependency type nested inside another declaration (`Broadcast.Schedule`) is declared nested under
its owner in the generated C#, exactly like a module-local nested type, once the owner
(`Broadcast`) is admitted. A member naming only the nested type still admits the owner (climbing the
chain first), and a declared nested type's own member types are walked too, so
`Broadcast.Schedule.timetable(): Timetable` admits the top-level dependency type `Timetable` on the
strength of a member declared two levels down. A dependency type nested under a still-deferred owner
shape (`inner class`, a generic, an `enum class`, an `interface`, or a sealed base/arm) is refused
admission outright, the same as a module-local one under the same deferred shape; see
[Classes and objects: Nested types](classes-and-objects.md#nested-classes-and-objects).

Every cross-namespace type reference in the generated `Interop.cs` is `global::Namespace.Name`
qualified, so the file compiles with no `using` needed regardless of how many packages a
namespace-crossing member touches:

```C#
public Issue41Bundle(IReadOnlyList<global::TestLibrary.Issue41.Issue41Thing> things, global::TestLibrary.Issue41.Issue41Thing one)
```

<warning>
<p>Two published packages that each independently admit the same dependency type get two unrelated
C# types for one Kotlin type, with no conversion between them: two published packages are two
separate Kotlin/Native runtimes in the consumer's process, so a handle minted in one is meaningless
to the other's exports. The build warns with <code>WARNING_DUPLICATED_DEPENDENCY_TYPE</code> and the
type still exports. The remedy is structural: publish a single umbrella module that depends on
both, or <code>exclude("&lt;pkg&gt;")</code> from one of the publishers. See
<a href="forward-overview.md#duplicate-type-hazard">Publishing Kotlin to C#</a> for the rendered
message.</p>
</warning>

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
specific C# namespace to a Kotlin package, overriding both `packageName` and the id-derived default
for that namespace only.

```kotlin
dependency("TestDependency", version = "1.0.0") {
  bind {
    include("Test.Text")
    exclude("Test.Text.Internal")
    alias("Test.Text", "sample.text")
  }
}
```

Only one `bind { }` block is supported per dependency (a second call overwrites the first). For
what actually gets bound once a namespace is included, see
[Consuming C# in Kotlin](reverse-overview.md).
