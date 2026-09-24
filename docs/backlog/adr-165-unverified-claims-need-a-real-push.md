# ADR-165's three Unverified claims need one real push to nuget.org and one to GitHub Packages

[ADR-165](../adr/165-publish-nuget-task.md)'s `publishNuget` implementation rests on three claims
inferred from documentation, never spiked past authentication:

- nuget.org rejects a push lacking `X-NuGet-Protocol-Version: 4.1.0` (the spike behind the ADR
  never got past a `403` on a bogus key).
- Azure Artifacts accepts a basic-auth PAT on the `PUT` itself, without going through its
  credential provider.
- A third-party server needs the multipart part's `name`/`filename` fields at all; NuGet's own docs
  say so for the protocol, but BaGet, Artifactory, and GitHub Packages are unverified.

Each failure mode is a clear HTTP error rather than silent wrong output, so none of these block
using the feature, but all three should be checked before the next release: one real push to
nuget.org and one to GitHub Packages would confirm or correct them.
