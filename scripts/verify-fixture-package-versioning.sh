#!/usr/bin/env bash
set -euo pipefail

# Regression harness for ROADMAP.md's fixture-package versioning item. It deliberately leaves
# NuGet caches and each consumer's obj/bin untouched between builds: a new package identity must
# make both restores resolve the freshly packed SampleLibrary without any cleanup mitigation.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

feed="$ROOT/sample-library/build/nuget"
props="$ROOT/sample-app/build/FixtureVersions.props"

consumer_version() {
  sed -n 's|.*<SampleLibraryVersion>\([^<]*\)</SampleLibraryVersion>.*|\1|p' "$props"
}

assert_build() {
  local expected_version="$1"

  test -f "$feed/SampleDependency.$expected_version.nupkg"
  test -f "$feed/SampleLibrary.$expected_version.nupkg"
  test -f "$props"
  grep -F "<SampleLibraryVersion>$expected_version</SampleLibraryVersion>" "$props"
  grep -F "<dependency id=\"SampleDependency\" version=\"[$expected_version]\" />" \
    "$feed/SampleLibrary.$expected_version/SampleLibrary.nuspec"

  dotnet build sample-app/GeneratedBindingsCheck
  dotnet build sample-app/SampleApp.Tests
  dotnet build sample-app/SampleApp
}

./gradlew :sample-library:clean :sample-library:packNuget
first_version="$(consumer_version)"
test -n "$first_version"
assert_build "$first_version"

# --rerun-tasks makes this a second fixture build while preserving every NuGet and MSBuild cache.
./gradlew --rerun-tasks :sample-library:packNuget
second_version="$(consumer_version)"
test -n "$second_version"
test "$first_version" != "$second_version"
assert_build "$second_version"
