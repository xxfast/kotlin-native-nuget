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
  echo "==> Gradle plugin tests (:nuget-plugin:test)"
  ./gradlew :nuget-plugin:test

  echo "==> Publish plugin + processor to build/local-repo"
  ./gradlew :nuget-processor:publishAllPublicationsToLocalTestRepository \
    :nuget-plugin:publishAllPublicationsToLocalTestRepository

  # Exercises the maven-coordinate fallback in NugetPlugin that this repo's own builds skip,
  # because here `findProject(":nuget-processor")` always resolves.
  echo "==> Consume the plugin by coordinate (smoke-test)"
  ./gradlew -p smoke-test verifyProcessorResolvesByCoordinate
fi

echo "==> Purge stale TestLibrary + TestDependency NuGet caches"
# Fixture packages now receive a unique version on every pack, so NuGet resolves each build under
# a new cache key. Keep this purge as a clean-room verification precaution; it is no longer the
# mechanism that prevents fixture packages from going stale.
rm -rf ~/.nuget/packages/samplelibrary ~/.nuget/packages/sampledependency

# Consumer projects resolve the new exact fixture version on the next restore, so their existing
# assets files cannot silently retain an older package's contentFiles. Keep the clean output here
# to make the full verification run self-contained.
rm -rf GeneratedBindingsCheck/obj GeneratedBindingsCheck/bin
rm -rf IntegrationTests/obj IntegrationTests/bin

echo "==> Pack TestLibrary NuGet (:test-library:clean :test-library:packNuget)"
./gradlew :test-library:clean :test-library:packNuget

echo "==> Check generated bindings compile as a consumer (net8.0, warnings as errors)"
dotnet build GeneratedBindingsCheck

echo "==> C# consumer tests (dotnet test in IntegrationTests)"
cd "$ROOT/IntegrationTests"
dotnet test

echo "==> Verify complete"
