# ADR-165: `publishNuget` pushes the packed `.nupkg` from Gradle over plain HTTP, no .NET SDK

## Status

Accepted, 2026-09-24.

Three deviations from this ADR's Decision, discovered during implementation:

- `NugetRepository`'s credentials (`apiKey`, `username`, `password`) are `Provider<String>?` vars,
  not `Property<String>`: `NugetRepository` is a plain class instantiated inside `nuget(name) { }`,
  not a Gradle-managed object, so it has no `objects.property(String::class)` factory to call.
- A repository declared without `url` fails at **configuration** time (`registerPublishing` reads
  `repository.url` eagerly while building the task graph), not when the task runs, unlike the
  credential lookup below it.
- `--dryRun` still requires the API key to resolve: `PublishNugetTask.publish()` reads
  `apiKey.orNull` before branching on `dryRun`, so a dry run with no key configured fails the same
  way a real push would, rather than validating everything else first.

## Context

`packNuget` writes a real `.nupkg` with no .NET SDK (`PackNugetTask.kt:254`, `ZipOutputStream`;
**verified** by reading). The push is then a hand-off: `docs/topics/publish-kotlin-library-as-nuget.md:301`
tells the author to run `dotnet nuget push ... --api-key ...`. That one step is the only place the
forward workflow needs a .NET SDK, which cuts against GOALS.md goal 1 ("just add the plugin, configure
the target and publish your library as a NuGet package") and the ROADMAP's own "no .NET SDK
prerequisite" edge (ROADMAP.md:307). ROADMAP.md:311 asks for "a `publishNuget` task + DSL mirroring
Gradle's Maven publishing APIs, so authors never invoke `dotnet nuget push` themselves".

The protocol is small. The NuGet v3 `PackagePublish/2.0.0` resource is one `PUT` to the `@id` named
in the feed's service index, `Content-Type: multipart/form-data`, the first part holding the raw
`.nupkg` bytes, header `X-NuGet-ApiKey`; 201/202 success, 400 invalid, 409 already exists
(**inferred**, <https://learn.microsoft.com/en-us/nuget/api/package-publish-resource>). nuget.org's
service index lists `PackagePublish/2.0.0 https://www.nuget.org/api/v2/package` and a `PUT` with a
bogus key answers `403` (**verified** by spike, `curl`, 2026-09-24): the endpoint is reachable from
any HTTP client and rejects on auth, not on client identity. Third-party clients pushing to nuget.org
must also send `X-NuGet-Protocol-Version: 4.1.0` (**inferred**,
<https://learn.microsoft.com/en-us/nuget/api/nuget-protocols>). Trusted publishing changes nothing
here: CI exchanges its OIDC token at `POST https://www.nuget.org/api/v2/token` for a one-hour API
key (`NuGet/login` action; endpoint reachability **verified** by spike, bogus bearer returns `401`)
and the push is then an ordinary keyed push.

Precedents split by protocol weight, not by ecosystem. Tools facing a heavy native CLI hand off
(Kotlin CocoaPods `podPublishXCFramework` stops at podspecs, maturin now says "use `uv publish`",
npm-publish's `NpmPublishTask` shells out to `npm publish`, **verified** in
`Kotlin/npm-publish` source). Tools facing a one-call protocol own it in pure JVM (gradle-docker-plugin
via docker-java, KMMBridge via `maven-publish`). NuGet is the second kind. Full table in
`docs/research/roadmap/publish-nuget.md`.

## Decision

**A `publishNuget` task family that pushes `packNuget`'s `.nupkg` over `java.net.http`, with named
repositories in the `nuget { publish { } }` DSL and credentials resolved the way Gradle's own
`maven-publish` resolves them.**

### DSL

```kotlin
nuget {
  publish {
    packageId = "MyCatLib"
    version = "1.0.0"
    repositories {
      nuget("nugetOrg") {
        url = "https://api.nuget.org/v3/index.json"
        // apiKey unset: looked up as the `nugetOrgApiKey` Gradle property
      }
      nuget("github") {
        url = "https://nuget.pkg.github.com/xxfast/index.json"
        apiKey = providers.environmentVariable("GITHUB_TOKEN")
      }
    }
  }
}
```

- `nuget(name) { url; apiKey; username; password; skipDuplicate }` on a new `repositories { }` block
  of `NugetPublishConfig`. `url` is the feed's v3 service index. Every credential is a
  `Property<String>`, so a provider is the natural value.
- Credential lookup mirrors Gradle's `credentials(PasswordCredentials::class)` convention
  (**inferred**, <https://docs.gradle.org/current/userguide/supported_repository_protocols.html>):
  when unset, `apiKey` falls back to the `<name>ApiKey` Gradle property, `username`/`password` to
  `<name>Username`/`<name>Password`, each reachable through `gradle.properties`, `-P`, or the
  `ORG_GRADLE_PROJECT_<name>ApiKey` environment variable, via `providers.gradleProperty(...)`. A
  missing key is a configuration-time error only when the task actually runs. `username`/`password`
  emit HTTP basic auth beside the key header, for Azure Artifacts (PAT, `api-key` "any string",
  **inferred**) and GitHub Packages (**inferred**).

### Tasks

- `publishNugetTo<Name>Repository`, one per repository, `dependsOn(packNuget)`, never up to date
  (a push is a side effect, the Itiviti plugin's `upToDateWhen { false }`).
- `publishNuget`, aggregate over every repository, in the `publishing` group.
- `--dryRun`: resolve the service index, validate the file exists and the credential resolves, log
  the request it would make, send nothing (npm-publish's `--dry` shape).
- `--skipDuplicate` (also settable per repository): a 409 logs a warning and the task succeeds,
  `dotnet nuget push --skip-duplicate` semantics. Without it a 409 fails the task naming id and
  version.

### The request

1. `GET <url>`, parse JSON, take `@id` of the resource whose `@type` is `PackagePublish/2.0.0`;
   fail with a named error if absent (a v2-only feed).
2. `PUT <@id>` with `Content-Type: multipart/form-data; boundary=...`, one part
   `Content-Disposition: form-data; name="package"; filename="<id>.<version>.nupkg"`,
   `Content-Type: application/octet-stream`, body = the `.nupkg` bytes.
3. Headers: `X-NuGet-ApiKey: <key>` always; `X-NuGet-Protocol-Version: 4.1.0` always (cheap, and
   nuget.org's stated requirement for third-party clients); `Authorization: Basic ...` when
   `username`/`password` resolve; a `User-Agent` naming the plugin and version.
4. 201/202 success; 409 per `skipDuplicate`; anything else fails with status, body excerpt and the
   feed name. Timeout 300 s, `dotnet nuget push`'s default. The key is never logged, including in
   `--dryRun` and `--info`.

No NuGet.Config reading, no credential providers, no interactive sign-in: the plugin is a CI-shaped
pusher with a key, and the docs keep the `dotnet nuget push` snippet for anything else.

## Alternatives Considered

- **Wrap `dotnet nuget push`** (Itiviti `gradle-nuget-plugin`, npm-publish shape). Gets NuGet.Config,
  credential providers and Azure interactive sign-in for free. Rejected: it reintroduces the .NET SDK
  as a prerequisite for the only step a Kotlin author cannot otherwise do from Gradle, the exact
  thing the ROADMAP item exists to remove; `nugetCompileInterop` already treats `dotnet` as optional
  (`NugetPlugin.kt:530`).
- **Docs-only**, keep the snippet. Zero cost. Rejected: GOALS.md goal 1 says "publish", and every
  other Kotlin-side step already runs without the SDK.
- **`publishToLocalFeed` only.** `build/nuget/` already is a flat local feed
  (getting-started.md section 5) and ADR-092 snapshot versions solve the re-publish cache hazard; a
  hierarchical layout needs a `.nuspec` and `.sha512` beside the package whose necessity is
  unverified. Rejected as the item; cheap to add later as a `localFeed(dir)` repository kind.

## Consequences

- New: `PublishNugetTask`, a `repositories { }` block and `NugetRepository` type on
  `NugetPublishConfig`, task registration in `NugetPlugin.kt` next to `packNuget`, a multipart
  writer (~200 lines total), TestKit tests against an in-process `com.sun.net.httpserver` fake that
  asserts method, headers and the first part. No new dependency.
- Docs: `publish-kotlin-library-as-nuget.md` section 5 leads with `./gradlew publishNuget`, keeps
  `dotnet nuget push` as the fallback for NuGet.Config and credential-provider flows;
  `nuget-dsl.md` and `gradle-tasks.md` gain the new entries.
- Symbol packages are out of scope: `packNuget` emits no `.snupkg` (**verified** by reading).
- Trusted publishing needs nothing in the plugin; a later `oidc` repository option could do the
  `api/v2/token` exchange itself, deferred.
- One manual push to a real feed before release, since no test here exercises a real server.

## Unverified

Claims the implementation rests on that no spike has proven. If one is wrong the failure is a clear
HTTP error, not silent wrong output, so none blocks the ADR, but each must be checked on first use.

- **inferred**: nuget.org rejects a push lacking `X-NuGet-Protocol-Version: 4.1.0`. The spike never
  got past auth. Send it unconditionally; if the claim is false nothing is lost.
- **inferred**: Azure Artifacts accepts a basic-auth PAT on the `PUT` without the credential
  provider. If false, Azure users fall back to `dotnet nuget push`; the docs keep that snippet.
- **inferred**: the multipart part's `name`/`filename` are ignored by every server. Docs say so for
  the protocol; BaGet, Artifactory and GitHub Packages are unverified. Emit both to be safe.
- **inferred**: hierarchical local feeds need `.nuspec` and `.sha512` beside the package. Only
  matters for the rejected `localFeed` alternative.
