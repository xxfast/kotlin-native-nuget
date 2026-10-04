# Kotlin range CI coverage gaps

From [ADR-195](../adr/195-kotlin-version-range.md). The `consumer` job runs a `[floor, tested]` matrix.

- **Pinned artifacts linked by X is untested.** The root `kotlinVersion` override rebuilds the runtime klib with X, so the dotnet tests prove "this repo behaves on X". The smoke consumer proves "artifacts built on the pinned compiler link from X". Nothing runs the behaviour of pinned-compiler artifacts linked by X. Closing it means building `test-library` as a true consumer outside the root build.
- **macOS only.** Floor and tested legs run on `macos-latest`; Windows (`mingwX64`) was not exercised at either.
- **Unrecognised version branch.** `checkKotlinVersion`'s warning for a Kotlin version string with no leading `major.minor.patch` has unit tests but no end-to-end run.
