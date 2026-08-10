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
| `rootPackage` | `String?` | yes | the Kotlin package the generated C# namespaces are rooted at; sub-packages map relative to it. Also the default export scope: see below |
| `include(vararg packages: String)` | function | no | empty; when set, only these package prefixes (and their sub-packages) are bridged |
| `exclude(vararg packages: String)` | function | no | empty; applied after `include`, and always wins over it |
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

With `snapshot = true`, every `packNuget` run produces a fresh, immutable package identity instead
of reusing `version` as-is, so a .NET consumer's next restore always sees a new version and never
serves NuGet's cached copy of the previous build:

```kotlin
nuget {
  publish {
    packageId = "MyCatLib"
    version = "1.0.0"
    authors = "yourname"
    description = "My Kotlin/Native library"
    rootPackage = "com.example.cats"
    snapshot = true
  }
}
```

`packNuget` then depends on two extra tasks: `nugetSnapshotVersion` mints
`1.0.0-snapshot.<epochMillis>` at execution time, and `nugetSnapshotVersionProps` writes an MSBuild
props file pinning it under a property name derived from `packageId`. MSBuild property names
cannot contain dots or start with a digit, so the id is sanitized: every character outside
`[A-Za-z0-9_]` is dropped, a leading digit gets a `_` prefix, and `Version` is appended
(`MyCatLib` becomes `MyCatLibVersion`, `PeopleInSpace.Kotlin` becomes `PeopleInSpaceKotlinVersion`).

See [Publish a Kotlin/Native library as NuGet](publish-kotlin-library-as-nuget.md) for the full
local-iteration flow and the consumer-side props import, and
[ADR-092](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/092-snapshot-versioning-dsl.md)
for the version-ordering and property-naming rationale.

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
folder as an artifact, then a macOS leg downloads that artifact into a local directory, points
`prebuiltRuntimes` at it, and runs `packNuget` itself, once, producing a single package with both
RIDs (for example `osx-arm64` and `win-x64`).

<note>
<p>A target whose link task is disabled on the packing host (say <code>mingwX64</code> declared but
not linkable here) is excluded from the locally linked set at configuration time, with a lifecycle
log naming the RID and pointing at <code>prebuiltRuntimes</code> as the way to still ship it. With
every local link disabled and <code>prebuiltRuntimes</code> set, the host still gets a
<code>packNuget</code> task: a pack-only host is a supported shape, not just a linking one.</p>
</note>

`packNuget` validates the merge rather than silently dropping anything:

- A locally linked RID with no `.dll`/`.dylib`/`.so` to copy fails the build naming the RID and the
  directory scanned: the link task ran and produced nothing, which is a broken build, not a
  host limitation.
- A `prebuiltRuntimes` directory with no RID subdirectories at all, or a RID subdirectory whose
  `native/` folder is missing or empty, fails naming the expected layout
  (`<prebuiltRuntimes>/<rid>/native/*.dll|*.dylib|*.so`).
- The same RID arriving both locally linked and prebuilt fails naming both sources: pick one
  producer per RID, either by disabling the local link or dropping the RID from the prebuilt tree.
- A prebuilt RID name this plugin version does not know how to build is a warning only, not an
  error: the RID set NuGet accepts is open, and the tree may come from a newer plugin.

See
[ADR-093](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/093-multi-rid-package-inputs.md)
for the full validation semantics and the alternatives considered.

### Export scoping

By default `NugetProcessor` bridges every public declaration in the module. `include`/`exclude` on
`publish {}` scope that down, mirroring `bind { include/exclude }` on the reverse side: a package
prefix match (`pkg == p || pkg.startsWith("$p.")`), with `exclude` always winning over `include`.

```kotlin
nuget {
  publish {
    packageId = "Contoso.Api"
    rootPackage = "com.contoso.api"
    include("com.contoso.api")            // whitelist of package prefixes
    exclude("com.contoso.api.internal")   // exclude wins
  }
}
```

<note>
<p>When <code>include</code> is empty, the effective include set defaults to <code>rootPackage</code> if
set, otherwise to everything (today's behaviour with no scoping configured at all). This makes
<code>rootPackage</code> a scoping knob, not just a renaming one: a public declaration outside
<code>rootPackage</code> is no longer bridged unless named explicitly in <code>include</code>.</p>
</note>

A package that is only reached via `dependencies { dependency(...) { bind { } } }` (the reverse
stub packages generated for a consumed C# dependency) is always exported regardless of `include`/
`exclude`, since a module that both publishes forward and consumes a NuGet dependency needs those
bound types reachable from its own forward return types to keep compiling.

See [ADR-063](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/063-forward-declaration-level-export-scoping.md)
for the full predicate and the `rootPackage` behaviour-change rationale.

### Cross-module export closure

`include`/`exclude`/`rootPackage` also decide what crosses a Gradle module boundary. The export set
is a **reachability closure** from the module's own admitted declarations (the roots above): the
processor walks return types, parameter types, property types, type arguments of an admitted carrier
(`Flow<T>`, `List<T>`/`Set<T>`/`Map<K,V>`), sealed subclasses, and primary-constructor parameter
types, and admits every discovered declaration through the *same* `include`/`exclude`/`rootPackage`
predicate, whether it lives in this module or in a dependency module pulled in with
`implementation(project(":models"))`. No separate DSL verb is needed: a `:models` module under the
same `rootPackage` is admitted automatically.

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

A reachable dependency-module type whose package falls outside the effective include set is skipped
with `SKIPPED_UNEXPORTED_DEPENDENCY_TYPE`, naming the exact `include(...)` line that would admit it,
rather than silently dropping the member or leaking an unusable handle. When at least one type is
admitted from a dependency module, the processor also emits one `INFO_EXPORTED_FROM_DEPENDENCY` line
per KSP run, naming the whole admitted set, since a per-type warning would be noise at this scale.

<note>
<p>With neither <code>rootPackage</code> nor <code>include</code> set, the closure never crosses a
module boundary at all: an empty effective include set means "everything" for the module's own
files (unchanged from ADR-063) but "nothing" for a dependency module, otherwise a project with no
scoping configured would pull in every reachable type from every dependency on the classpath,
including <code>kotlinx.coroutines</code> and <code>kotlin.*</code> themselves.</p>
</note>

<warning>
<p>Two published packages that each independently admit the same dependency type get two unrelated
C# types for one Kotlin type (<code>PackageA.Models.Foo</code> and <code>PackageB.Models.Foo</code>),
with no conversion between them and no diagnostic, because neither KSP run can see the other
package's export set. This is an accepted limitation, not a bug: it is the same hazard Kotlin/Native
framework export already has when two frameworks each <code>export()</code> a shared dependency. See
<a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/066-forward-export-reachability-closure.md">ADR-066</a>'s
Consequences section.</p>
</warning>

Deferred, each needing its own decision: supertype edges (an admitted class implementing an admitted
interface does not gain `: IFoo` in C#), cross-module generic classes, and cross-module
`suspend`/`Flow`-returning members on an admitted dependency type (a root may still return
`Flow<DepType>`; it's the dependency type's *own* suspend/`Flow` members that are skipped).

See
[ADR-066](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/066-forward-export-reachability-closure.md)
for the full admission and edge-walking rules.

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
