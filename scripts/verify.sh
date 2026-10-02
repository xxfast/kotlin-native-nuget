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

  echo "==> Publish plugin + processor + runtime to build/local-repo"
  ./gradlew :nuget-processor:publishAllPublicationsToLocalTestRepository \
    :nuget-runtime:publishAllPublicationsToLocalTestRepository \
    :nuget-annotations:publishAllPublicationsToLocalTestRepository \
    :nuget-plugin:publishAllPublicationsToLocalTestRepository

  # Exercises the maven-coordinate fallback in NugetPlugin that this repo's own builds skip,
  # because here `findProject(":nuget-processor")` always resolves.
  echo "==> Consume the plugin by coordinate (smoke-test)"
  ./gradlew -p smoke-test verifyProcessorResolvesByCoordinate verifyRuntimeResolvesByCoordinate
fi

echo "==> Purge the fixed-version local contract cache"
# Fixture packages now receive a unique version on every pack, so NuGet resolves each build under
# a new cache key. Keep this purge as a clean-room verification precaution; it is no longer the
# mechanism that prevents fixture packages from going stale.
cache="${NUGET_PACKAGES:-$HOME/.nuget/packages}"
if [ -d "$cache" ]; then
  cache="$(cd "$cache" && pwd)"
  test -n "$cache" && test "$cache" != /
  rm -rf "$cache/kotlin.native.interop"
fi

# Consumer projects resolve the new exact fixture version on the next restore, so their existing
# assets files cannot silently retain an older package's contentFiles. Keep the clean output here
# to make the full verification run self-contained.
rm -rf GeneratedBindingsCheck/obj GeneratedBindingsCheck/bin
rm -rf IntegrationTests/obj IntegrationTests/bin
rm -rf LeakTests/obj LeakTests/bin
rm -rf MultiPackageTests/obj MultiPackageTests/bin
rm -rf SharedExceptionTests/obj SharedExceptionTests/bin
rm -rf FirstPublisherConsumer/obj FirstPublisherConsumer/bin
rm -rf SecondPublisherConsumer/obj SecondPublisherConsumer/bin

echo "==> Shared contract API tests and package"
dotnet test ContractTests
dotnet pack Kotlin.Native.Interop -c Release -o build/nuget
bash "$ROOT/scripts/verify-contract-version-ranges.sh"

# ADR-128: the `launchForCSharp` / `collectForCSharp` runtime helpers are ordinary Kotlin/Native
# code, so they are driven in-process here before the link that would only exercise them via C#.
echo "==> Runtime helper tests (:nuget-runtime:allTests)"
./gradlew :nuget-runtime:allTests

echo "==> Pack both independent Kotlin NuGet publishers"
./gradlew :test-library:clean :test-companion:clean \
  :test-library:packNuget :test-companion:packNuget

# ADR-127: the fixed `nuget_*` ABI now reaches the binary from the `nuget-runtime` klib through
# the plugin's `export()`, not from a regenerated block. This is the check that the export really
# happened, on the linked library rather than on generated text.
echo "==> Runtime exports present in the linked library (scripts/verify-runtime-exports.sh)"
"$ROOT/scripts/verify-runtime-exports.sh"

# ADR-100: forward diagnostics must reach the console on a fresh *and* an incremental packNuget.
# Runs after the pack above, so both of its runs exercise the cached path this feature exists for.
echo "==> Forward diagnostic delivery (scripts/verify-forward-diagnostics.sh)"
"$ROOT/scripts/verify-forward-diagnostics.sh"

echo "==> Check generated bindings compile as a consumer (net10.0, warnings as errors)"
dotnet build GeneratedBindingsCheck

# ADR-150: the fixture's KDoc must reach the documentation XML a consumer's compiler emits, not
# just the text of Interop.cs. One entry is enough here; IntegrationTests/XmlDocTests.cs asserts the
# whole tag mapping.
echo "==> Generated bindings carry XML doc comments (ADR-150)"
grep -q '<member name="M:TestLibrary.Kdoc.BoardingDesk.Book(System.Int32,System.String)">' \
  GeneratedBindingsCheck/obj/Debug/net10.0/GeneratedBindingsCheck.xml

echo "==> C# consumer tests (dotnet test in IntegrationTests)"
cd "$ROOT/IntegrationTests"
dotnet test

# ADR-120's live-handle counter is process-global, so the leak harness runs in its own test
# assembly: sharing a process with the rest of the suite let other tests' cleaners move the count
# mid-measurement, which showed up as CI flakes on rows that mint no handle at all.
echo "==> Leak harness (dotnet test in LeakTests)"
cd "$ROOT/LeakTests"
dotnet test

echo "==> Independent native runtimes and shared contracts"
cd "$ROOT"
dotnet test MultiPackageTests
dotnet test SharedExceptionTests

echo "==> Execute NativeAOT with both publishers"
case "$(uname -s)" in
  MINGW* | MSYS*)
    powershell.exe -NoProfile -File "$ROOT/scripts/verify-aot.ps1"
    ;;
  Darwin)
    dotnet publish AotSmokeTest -r osx-arm64 -c Release -p:PublishAot=true
    ./AotSmokeTest/bin/Release/net10.0/osx-arm64/publish/AotSmokeTest
    ;;
esac

echo "==> Verify complete"
