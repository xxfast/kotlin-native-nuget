# Kotlin range CI coverage gaps

From [ADR-195](../adr/195-kotlin-version-range.md). The `consumer` job runs a `[floor, tested]` matrix.

- **macOS only.** Floor and tested legs run on `macos-latest`; Windows (`mingwX64`) was not exercised at either.
- **Unrecognised version branch.** `checkKotlinVersion`'s warning for a Kotlin version string with no leading `major.minor.patch` has unit tests but no end-to-end run.
