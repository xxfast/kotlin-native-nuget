# ADR-092: Snapshot versioning in the publish DSL: unique local versions plus an MSBuild props handshake

## Status

Accepted

## Context

A .NET consumer iterating against a locally built `.nupkg` hits NuGet's immutable-version model:
successive `packNuget` runs produce a package with the same identity, so NuGet serves the cached
copy. The PeopleInSpace 0.3.0 integration (LIMITATIONS item 6) had to `packNuget`, delete the
package from `~/.nuget/packages`, and `dotnet restore --force --no-cache` on every Kotlin change.

The repository already solves this for its own fixtures (**verified**, read and exercised in CI):

- `test-library/build.gradle.kts` generates `1.0.0-fixture.<epochMillis>` at execution time via an
  always-out-of-date task (`outputs.upToDateWhen { false }`, `@DisableCachingByDefault`), feeds it
  into `packNuget.packageVersion` through a file-backed provider
  (`providers.fileContents(file).asText.map { it.trim() }`), and writes
  `build/FixtureVersions.props` with a `<TestLibraryVersion>` property.
- Root `Directory.Build.props` conditionally imports that props file; consumers reference
  `Version="$(TestLibraryVersion)"`.
- `scripts/verify-fixture-package-versioning.sh` proves (**verified**, executed in CI) that two
  successive builds resolve fresh with no cache clearing and no consumer `obj`/`bin` cleanup:
  a new package identity is sufficient.

This ADR promotes that mechanism from bespoke fixture wiring to a first-class option in
`nuget { publish { } }`.

### Version ordering

SemVer 2.0.0 rule 11 compares numeric prerelease identifiers numerically, and NuGet implements
SemVer2 precedence, so `1.0.0-snapshot.1754817000001` orders above `1.0.0-snapshot.1754817000000`
(**inferred** from [semver.org](https://semver.org/#spec-item-11) and
[NuGet package versioning docs](https://learn.microsoft.com/en-us/nuget/concepts/package-versioning),
not spiked). Ordering is not load-bearing for this design: the emitted props pins the exact
generated version, and the verify script proves resolution works through the pin, not through
prerelease precedence. The prerelease label itself (`snapshot` vs the fixture's `fixture`) carries
no ordering significance within one label scheme.

### MSBuild property naming (the load-bearing constraint)

MSBuild property names cannot contain dots, so `$(PeopleInSpace.KotlinVersion)` is not expressible.
**Verified** by spike (macOS, `dotnet msbuild`, .NET 8 SDK):

- `<PeopleInSpace.KotlinVersion>` in a props file fails with
  `error MSB5016: The name "PeopleInSpace.KotlinVersion" contains an invalid character "."`.
- A leading digit fails XML parsing: `<51DegreesVersion>` gives
  `error MSB4025: Name cannot begin with the '5' character`.
- Dashes and underscores are accepted, both in the element and in `$(My-PackageVersion)` expansion.

So the property name must be sanitized from the package id. Rule: drop every character outside
`[A-Za-z0-9_]` (dots and dashes removed, underscores kept), prefix `_` if the result starts with a
digit, append `Version`. Dashes are technically legal but removed anyway for one uniform shape.
Examples: `PeopleInSpace.Kotlin` becomes `PeopleInSpaceKotlinVersion`, `TestLibrary` stays
`TestLibraryVersion` (matching the shipped fixture), `51Degrees.mobi` becomes
`_51DegreesmobiVersion`.

## Alternatives Considered

### 1. `snapshot = true` flag alongside `version` (chosen)

```kotlin
nuget {
  publish {
    packageId = "PeopleInSpace.Kotlin"
    version = "1.0.0"
    snapshot = true
  }
}
```

**Pros:**
- Honest about timing: the real version does not exist at configuration time (the timestamp is
  minted when the version task runs), so no DSL shape should pretend `version` holds the final
  value. A flag says "derive from the base at execution time".
- Matches the Maven mental model: the base version stays declared, a mode switch makes it a
  moving snapshot.
- Trivially CI-controllable: `snapshot = project.hasProperty("snapshot")` flips a release build to
  a snapshot build without touching `version`.
- Zero type changes: `NugetPublishConfig` stays a plain Kotlin class (ADR-044 pattern).

**Cons:** two properties cooperate to produce one version; documented in `nuget-dsl.md`.

### 2. `snapshot("1.0.0")` function (rejected)

A second setter competing with `version =` for the same slot. What does
`version = "2.0.0"; snapshot("1.0.0")` mean? Last-wins rules for two differently named writers of
one value are a documentation tax with no benefit.

### 3. `version = snapshot("1.0.0")` marker (rejected)

`version` is `String?` and `String` is final, so this forces widening the property to `Any?` or a
custom holder type, breaking every existing consumer of `pub.version` for a purely cosmetic gain.

### 4. Consumer-side floating version instead of props (rejected)

Have consumers write `Version="1.0.0-snapshot.*"` and skip props emission. Rejected: floating
versions still consult NuGet's HTTP/file feed caches, push the moving-part decision onto every
consumer, and the repository's own verify script proves the props pin works with zero cache
mitigation. The props import stays the idiomatic handshake.

### 5. Props emission as a separate opt-in (`versionPropsFile` only when set) (rejected)

Without the props file a snapshot version is unusable: no consumer can know the minted version.
An opt-in would be a footgun where `snapshot = true` alone produces packages nothing can
reference. Props emission is therefore always on when `snapshot = true`; only the *path* is
configurable.

## Decision

Add to `NugetPublishConfig`:

```kotlin
var snapshot: Boolean = false
var versionPropsFile: File? = null  // default: <rootProject>/build/<PackageId>Versions.props
```

When `snapshot = true`, the plugin (in the existing `packNuget` afterEvaluate block):

1. Registers `nugetSnapshotVersion`: writes `build/nuget-snapshot-version.txt` containing
   `<version>-snapshot.<epochMillis>`. Carried verbatim from the fixture (**verified** in
   `test-library/build.gradle.kts`): `outputs.upToDateWhen { false }` in `init`, plus
   `@DisableCachingByDefault`, so every build mints a fresh version even under `--rerun-tasks`
   idioms and the build cache.
2. Wires `packNuget.packageVersion` to a file-backed provider,
   `providers.fileContents(versionFile).asText.map { it.trim() }`, resolving at execution time.
   Configuration-cache safe: the provider is serialized, not its value (**verified**: the same
   wiring ships in the fixture and CI runs it).
3. Registers `nugetSnapshotVersionProps`: writes the props file (default
   `<rootProject>/build/<PackageId>Versions.props`, override via `versionPropsFile`) with a single
   property named by the sanitization rule above (**verified** by spike, see Context):

   ```xml
   <Project>
     <PropertyGroup>
       <PeopleInSpaceKotlinVersion>1.0.0-snapshot.1754817000000</PeopleInSpaceKotlinVersion>
     </PropertyGroup>
   </Project>
   ```

   The version arrives as an `@get:Input` `Property<String>` from the same file-backed provider,
   so the task reruns whenever the version changes (**verified**: the fixture's
   `WriteFixtureVersions` needs no `upToDateWhen` for this reason). `packNuget` dependsOn both
   tasks.

When `snapshot = false` (default), nothing changes: `packNuget.packageVersion` is set from
`pub.version` directly, and neither task is registered.

The .NET consumer imports the props once (typically `Directory.Build.props`, matching the shipped
root `Directory.Build.props`, **verified**):

```xml
<Project>
  <Import Project="$(MSBuildThisFileDirectory)../kotlin/build/PeopleInSpace.KotlinVersions.props"
          Condition="Exists('$(MSBuildThisFileDirectory)../kotlin/build/PeopleInSpace.KotlinVersions.props')" />
</Project>
```

```xml
<PackageReference Include="PeopleInSpace.Kotlin" Version="$(PeopleInSpaceKotlinVersion)" />
```

`PackageReference Version="X"` is a minimum-inclusive range, not an exact pin (**inferred** from
NuGet docs), but with a freshly minted unique version and the local feed the resolver selects it;
the verify script demonstrates this end to end (**verified**).

## Consequences

- `.nupkg` files accumulate in `build/nuget`: every snapshot build adds a new
  `<id>.<version>.nupkg` and staging folder. `clean` removes them; a retention policy is out of
  scope.
- Snapshot packages also accumulate in the consumer's `~/.nuget/packages` (one folder per minted
  version). Same story as the fixtures today; acceptable for a dev loop.
- Consumers must add the props import themselves; the plugin cannot reach into a .NET repository.
  Documented in `nuget-dsl.md` alongside the DSL.
- `nuget-dsl.md` gains `snapshot` and `versionPropsFile` rows; `FEATURES.md`/`ROADMAP.md` updated
  on ship.
- The fixture's own wiring in `test-library/build.gradle.kts` can later migrate to
  `snapshot = true`, but that migration is not part of this feature: the fixture also shares the
  minted version with `PackTestDependency` and overrides `nugetGen.dependencyVersions`, which
  stays bespoke.

### Out of scope

- The fixture's `PackTestDependency` / `nugetGen.dependencyVersions` override (bespoke, stays).
- Multi-RID packaging changes; publishing to real feeds (`publishNuget`, tracked on ROADMAP).
- Merging or pruning previously built `.nupkg`s.
