#!/usr/bin/env bash
set -euo pipefail

# Full verify sequence for the Kotlin/Native <-> C# bridge generator.
# Run from anywhere; the repo root is resolved from this script's own location.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

RUN_PLUGIN=false
if [ "$#" -gt 0 ]; then
  if [ "$1" = "--plugin" ]; then
    RUN_PLUGIN=true
  else
    echo "usage: scripts/verify.sh [--plugin]" >&2
    exit 1
  fi
fi

if [ "$#" -gt 1 ]; then
  echo "usage: scripts/verify.sh [--plugin]" >&2
  exit 1
fi

if [ "$RUN_PLUGIN" = true ]; then
  echo "==> Gradle plugin tests (:nuget:test)"
  ./gradlew :nuget:test
fi

echo "==> Pack SampleLibrary NuGet (:sample-library:clean :sample-library:packNuget)"
./gradlew :sample-library:clean :sample-library:packNuget

echo "==> Purge stale SampleLibrary + SampleDependency NuGet caches"
# sample-dependency keeps version 1.0.0 while its fixture surface grows per feature,
# so its cache goes stale exactly like samplelibrary's does.
rm -rf ~/.nuget/packages/samplelibrary ~/.nuget/packages/sampledependency

echo "==> C# consumer tests (dotnet test in sample-app/SampleApp.Tests)"
cd "$ROOT/sample-app/SampleApp.Tests"
dotnet test

echo "==> Verify complete"
