#!/usr/bin/env bash
# Runs the reverse dogfooding census: the real plugin pipeline (dotnet restore, deriveDllPaths, the
# NugetMetadataReader subprocess, parseReverseIr, the bridgeable* filters, the diagnostics, both
# generators) over the nine pinned published NuGet packages in
# nuget-plugin/src/test/resources/dogfood/packages.json, compared exactly against the committed
# `*.census.json` goldens.
#
# This is NOT part of scripts/verify.sh on purpose: it reaches nuget.org, and a feed outage must not
# turn the local gate or a PR red. See the `dogfood` job in .github/workflows/ci.yml.
#
#   scripts/verify-dogfood.sh            check the goldens
#   scripts/verify-dogfood.sh --update    rewrite the goldens and SUMMARY.md, then review the diff
#
# A golden diff IS the signal. When a bridge change starts (or stops) binding real members, rerun
# with --update and put the diff in the pull request.
set -euo pipefail

cd "$(dirname "$0")/.."

UPDATE=false
for arg in "$@"; do
  case "$arg" in
    --update) UPDATE=true ;;
    *)
      echo "usage: scripts/verify-dogfood.sh [--update]" >&2
      exit 2
      ;;
  esac
done

if ! command -v dotnet >/dev/null 2>&1; then
  echo "verify-dogfood: dotnet is not on PATH. The census measures the real reader; it does not" >&2
  echo "have a no-op mode. Install the .NET SDK and rerun." >&2
  exit 1
fi

echo "==> reverse census over nine pinned published NuGet packages (update=$UPDATE)"
if [ "$UPDATE" = true ]; then
  ./gradlew -p nuget-plugin dogfoodCensus -Pdogfood.update=true
  echo
  echo "==> goldens rewritten; review the diff before committing:"
  git --no-pager diff --stat -- nuget-plugin/src/test/resources/dogfood
else
  ./gradlew -p nuget-plugin dogfoodCensus
fi
