# ADR-191: Shared feed list: `nuget { sources(...) }`, one union through `<RestoreSources>`

## Status

Accepted

## Gate

Decided by a stand-in reviewer agent on 2026-10-03 while the owner was asleep, not by the owner. The two decisions most open to being overruled: (1) shipping a union and splitting per-dependency exclusivity (package source mapping) out to a follow-up, so a remote per-dependency `source` does not win; (2) the vararg `sources("...")` spelling over the ROADMAP's `sources { url("...") }` block. The route mints no handle, so there is no `LiveHandleTests` row.

## Context

Target: a Kotlin author declares feeds once at the `nuget {}` level and every dependency resolves
against them. ROADMAP Phase 8; reserved at the extension root by ADR-044
(`044-nuget-dependency-dsl.md:38-39`, `:522-523`). Stacks on ADR-190.

What "per-dependency `source`" means today (verified by reading, 2026-10-03): it is not
per-dependency. `generateCsproj` puts nuget.org plus every declared `source` into one
`<RestoreSources>` list, so every package, bound or transitive, resolves against the union. ADR-045
chose that knowingly and deferred per-package routing (`045-nuget-resolution-pipeline.md:147-156`).

Publish-side feeds are a separate concept and stay where they are:
`publish { repositories { nuget("name") { url; apiKey } } }` (ADR-165, `NugetRepository.kt`) names
push targets with credentials. ADR-044's note that a root `sources` block would serve "both publish
and consume" predates ADR-165 and no longer holds: the shared list is consume-only.

### Package source mapping findings (for the follow-up)

The draft of this ADR chose to make a per-dependency `source` exclusive for its id through a
generated `NuGet.Config` with `packageSourceMapping`. That is deferred to its own ROADMAP line
(blocked on an authenticated-feed spike, see Unverified). The spike findings stay here so the
follow-up starts from them. Scratch dir, .NET SDK 10.0.300, macOS arm64; two folder feeds A and B
each holding a different `Probe.Local 1.0.0`. **Verified by spike** (research memo, since removed), not re-run for this ADR:

| # | Probe | Observed |
|---|-------|----------|
| T5 | `<RestoreSources>` listing A then B, and B then A, no mapping | The first-listed feed's package was restored each time. Order decides, and nothing reports the overlap |
| T6 | `NuGet.Config` via `RestoreConfigFile`: A mapped `*`; B mapped `*` and `Probe.Local` | B's package restored. The exact id beats `*` regardless of order |
| T7 | T6 plus `<RestoreSources>` listing A then B as raw paths | Still B. The mapping applies to sources given through `RestoreSources` when the config names a source with the same value |
| T10 | A `NuGet.Config` beside `interop.csproj`, no `RestoreConfigFile`, no `<RestoreSources>`, same mapping | B restored. `configFilePaths` in the assets file lists the sibling config **and** the user-level `~/.nuget/NuGet/NuGet.Config`: the sibling file is auto-discovered and merged |
| T11 | T10's config in a child directory, with a parent `NuGet.Config` that adds feed A and maps `Probe.Local` and `Probe.*` to it | B restored. The parent config is loaded but the assets file's `sources` holds only B and nuget.org: `<clear />` in `packageSources` drops the parent's feed |

Open for the follow-up, all inferred: `<clear />` inside `packageSourceMapping` in isolation (the
T11 control without it gave the same result); a parent mapping keyed on a name the plugin also
generates; the SDK's implicit `library-packs` source under a mapping; and credentials, below.

## Alternatives Considered

### 1. Union only: append the shared list to `<RestoreSources>` (chosen)

The shared list joins nuget.org and the per-dependency feeds in today's one `<RestoreSources>`
list. Every package resolves against the union, as it already did for per-dependency feeds.

- Pro: no new mechanism, no generated `NuGet.Config`, so nothing changes for credentials (whatever
  works through `<RestoreSources>` today keeps working, whatever does not keeps not working).
- Pro: nothing declared leaves the generated csproj byte-identical to before.
- Con: a feed is not preferred for any id. Two feeds serving the same id and version is
  unspecified (T5 for folder feeds: order decides; inferred for remote feeds).

### 2. Union plus an exact-id pin for a dependency with its own `source`

Every feed mapped `*`, plus a dependency's own id on its own feed, through a generated
`NuGet.Config` (T6, T10). Makes "a per-dependency `source` wins" a property NuGet enforces. Not
chosen for this ADR: package source mapping has no MSBuild property form, so it needs a generated
`NuGet.Config`, and source mapping keys credentials by source **name**; whether a user's
`packageSourceCredentials` apply under a generated key has never been verified. Deferred to its own
ROADMAP line, blocked on an authenticated-feed spike. The T5 to T11 findings above are its starting
point.

### 3. Strict routing: each dependency only from its own feed, the rest from the shared list

Rejected: a pinned package's transitive dependencies that live on the same private feed stop
resolving unless the author repeats the feed in the shared list.

### DSL shape: a `sources { url("...") }` block (the ROADMAP spelling)

CocoaPods `specRepos { url(...) }` shape (cited from memory). One verb per kind. Not chosen: an
entry has one field, and the house style since
ADR-180/182 is a `ListProperty` plus a vararg verb (`includeNamespaces(...)`, `NugetBindConfig.kt`).
The block earns its keep only when a consume-side feed needs a name and credentials, which is
deferred; if that arrives it can mirror `repositories { nuget("name") { } }` and sit beside the
list.

## Decision

**DSL.**

```kotlin
nuget {
  sources("https://pkgs.example.com/nuget/v3/index.json", "../local-feed")

  dependencies {
    dependency("Acme.Core", "2.0.0") { bind { } }   // no `source`: resolves against the union
  }
}
```

`NugetExtension.sources: ListProperty<String>` (convention: empty) and
`fun sources(vararg source: String)`, which appends; a second call adds to the first. Root
placement, as ADR-044 reserved.

**Entries.** Resolved at wiring time against the project directory, as ADR-190 resolves a
dependency's `source`. An entry is a feed URL or a directory, classified by ADR-190's rules. A
`.nupkg` entry is rejected when `nugetGenerateRestoreProject` runs, with a message pointing at
`dependency("<id>") { source = "..." }`; a `file://` entry and a directory that does not exist fail
the same way.

**Feed order.** When anything is declared, `<RestoreSources>` lists nuget.org, then the shared list
in declaration order, then each per-dependency feed, de-duplicated. The order is for readability
and determinism of the generated file; it is **not** a precedence promise (see Unverified). When
nothing is declared anywhere, no `<RestoreSources>` is emitted and the user's `NuGet.Config` chain
decides, exactly as before; the repo fixtures rely on this and still restore into the global
packages folder.

**Implicit nuget.org** stays whenever anything is declared (ADR-045). No opt-out.

**A shared directory joins ADR-190's local-feed set.** It is an `@InputFiles` of
`nugetGenerateRestoreProject` and `nugetRestore`, its ids are evicted from the project-local
packages folder before restore, and declaring one moves restore into
`build/nuget-interop/packages`. A remote-only shared list keeps the global folder.
`nugetCompileInterop` gets the same feeds (shared first) and the same packages folder.

**The post-restore check for a package no `source` names.** ADR-190's check covers dependencies
with their own local `source` (the restored bytes must be that source's, or the build fails).
A dependency with no `source`, served by a shared directory, needs its own rule, chosen between
two failure modes:

- "No silent stale bindings": if a shared directory holds the package and something else got
  extracted (an eviction miss, or another feed winning the same id and version), binding it
  silently is the ADR-190 failure again.
- "No false failures": a shared directory is a feed among several. nuget.org legitimately serves
  a package the directory also carries in another version, and that must not fail.

Rule (`verifyFeedPackages`): after restore, for every library in the assets file, if any local
feed (the staged feed, a dependency's directory source, a shared directory) holds a `.nupkg` with
the **exact id and version** restore resolved, the extracted `.nupkg` must be byte-identical
(sha512) to one of those copies; otherwise the task fails naming the package, the restored file
and the local copies. A local feed that holds only **other** versions of that id says nothing,
and the package is left alone. Versions are compared in NuGet's normalized form (no build
metadata, at least three parts, no zero fourth part). The rule applies to transitive packages too,
since eviction does.

What the rule deliberately fails: a shared directory and nuget.org both holding the same id and
version with different bytes, where nuget.org won. That is the dev loop's "my local build of a
published version", and binding the published copy is the silent wrong answer.

### "A per-dependency `source` wins": what is and is not delivered

- **Remote feeds: not delivered.** The union is a union today and stays one. A remote
  per-dependency `source` is one more feed in the list; it is not preferred for its id, and
  neither is any shared feed. Exclusivity is the deferred package-source-mapping line.
- **A local `source`** (ADR-190): it wins or the build fails. ADR-190's post-restore check
  requires the restored bytes to be that source's, so a remote feed serving the same id and
  version can no longer win silently.

### Verification record

Verified by running, 2026-10-03, macOS arm64, .NET SDK 10.0.300:

- **A shared directory serves a dependency with no `source`, and a same-version repack there is
  rebound.** `NugetRestoreIntegrationTest` "a shared directory feed serves a dependency without a
  source and rebinds a repack" packs `Probe.Local 1.0.0` with real `dotnet pack`, declares it only
  through `nuget.sources`, drives the `nugetGenerateRestoreProject`, `nugetRestore` and
  `nugetExtractApi` task actions, repacks under the same version, and asserts the second
  extraction names `MarkerTwo` and not `MarkerOne`. Before the shared list was wired, restore
  failed (`dotnet restore failed (exit code 1)`, the package not found).
- **The feed check is the backstop for the shared form.** With eviction disabled by hand, the same
  test fails in `verifyFeedPackages` ("Probe.Local 1.0.0 restored '...', but the local feed copy
  [...] has different bytes").
- **No false failure when nuget.org legitimately serves it.** "a shared directory holding another
  version does not fail a package nuget org serves": a shared directory holding
  `Newtonsoft.Json 12.0.1`, a dependency on `13.0.3` with no `source`; restore succeeds from
  nuget.org into the project-local folder and the check passes.
- The rule's two halves in isolation (same id and version with different bytes fails, other
  version passes, `1.0` matches `1.0.0`): `NugetGenerateRestoreProjectTaskTest`.
- Feed order and de-duplication, the `.nupkg` rejection, the missing-directory failure:
  `NugetGenerateRestoreProjectTaskTest`. Wiring (resolution, inputs, packages folder present for a
  shared directory and absent for a remote-only list): `NugetDslLazinessTest`. The vararg appends:
  `NugetExtensionTest`.
- **`nuget { sources("../../feed") }` compiles in a real `.kts`.** A throwaway two-project Gradle
  build outside the repo, configuration cache on, bound `Probe.Local` through the shared list only;
  `:app:nugetGenerateBindings` generated `MarkerOne.kt`, and the next run was UP-TO-DATE for all
  four tasks with the configuration cache reused. The generated csproj held
  `<RestoreSources>https://api.nuget.org/v3/index.json;/private/tmp/.../feed</RestoreSources>`.

**Unverified:**

- **Credentials through `<RestoreSources>`.** Never verified by anyone, before or after this ADR:
  how `dotnet restore` finds `packageSourceCredentials` (keyed by source name in a user config) for
  a feed given only by URL in `<RestoreSources>`. This ADR changes nothing about it; it is the
  blocker for the package-source-mapping follow-up.
- **Precedence among feeds for the same id and version.** Unspecified. T5 observed declaration
  order deciding for two folder feeds; for remote feeds NuGet documents no order (inferred). When
  a local feed is involved the outcome is now loud if it is the wrong one; between two remote
  feeds it is silent, as it was before this ADR.
- Windows and Linux: the integration tests skip without `dotnet`, so a green CI tick is not
  evidence until a log shows them executing.
- Version normalization covers the common forms; an exotic `.nuspec` version that NuGet normalizes
  differently would make the check skip that package. Inferred rare (`dotnet pack` writes the
  normalized form), not probed.

## Consequences

- `nuget { sources(...) }` is a new public DSL member, documented in `declaring-dependencies.md` and `nuget-dsl.md`.
- A shared directory makes restore project-local and evicts the ids it serves, like ADR-190's
  local `source`.
- A shared directory that holds the same id and version as another feed, with different bytes,
  now fails the build when the other feed wins. Intended.
- Deferred, each its own ROADMAP line: a per-dependency `source` exclusive for its id via package
  source mapping (blocked on an authenticated-feed spike); named consume-side feeds with
  credentials; dropping nuget.org; prefix patterns on a shared feed; precedence among shared feeds.
