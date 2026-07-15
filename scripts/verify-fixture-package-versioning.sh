#!/usr/bin/env bash
set -euo pipefail

# Regression harness for ROADMAP.md's fixture-package versioning item. It deliberately leaves
# NuGet caches and each consumer's obj/bin untouched between builds: a new package identity must
# make both restores resolve the freshly packed TestLibrary without any cleanup mitigation.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

feed="$ROOT/test-library/build/nuget"
props="$ROOT/build/FixtureVersions.props"

consumer_version() {
  sed -n 's|.*<TestLibraryVersion>\([^<]*\)</TestLibraryVersion>.*|\1|p' "$props"
}

assert_build() {
  local expected_version="$1"

  test -f "$feed/TestDependency.$expected_version.nupkg"
  test -f "$feed/TestLibrary.$expected_version.nupkg"
  test -f "$props"
  grep -F "<TestLibraryVersion>$expected_version</TestLibraryVersion>" "$props"
  grep -F "<dependency id=\"TestDependency\" version=\"[$expected_version]\" />" \
    "$feed/TestLibrary.$expected_version/TestLibrary.nuspec"

  dotnet build GeneratedBindingsCheck
  dotnet build IntegrationTests
}

./gradlew :test-library:clean :test-library:packNuget
first_version="$(consumer_version)"
test -n "$first_version"
assert_build "$first_version"

# --rerun-tasks makes this a second fixture build while preserving every NuGet and MSBuild cache.
./gradlew --rerun-tasks :test-library:packNuget
second_version="$(consumer_version)"
test -n "$second_version"
test "$first_version" != "$second_version"
assert_build "$second_version"
