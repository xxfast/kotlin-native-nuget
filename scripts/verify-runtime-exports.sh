#!/usr/bin/env bash
set -euo pipefail

# ADR-127 seam 3: every `nuget_*` name the `nuget-runtime` klib declares must be present in the
# consumer's linked shared library. The runtime reaches the binary only through the `export()`
# the Gradle plugin adds (verified in the ADR's spike: an `api` dependency alone is not enough),
# so a regression here is a missing export, and the symptom in C# would be an
# `EntryPointNotFoundException` at the first bridge call rather than a build failure.
#
# The expected list is derived from the runtime source, never hardcoded here: one source of truth
# for the ABI, so adding an export to the runtime extends this check for free.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

RUNTIME_SOURCE="nuget-runtime/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/runtime/NugetRuntime.kt"
LIBRARY="${1:-test-library/build/bin/macosArm64/releaseShared/libtest.dylib}"

if [ ! -f "$RUNTIME_SOURCE" ]; then
  echo "runtime source not found: $RUNTIME_SOURCE" >&2
  exit 1
fi

if [ ! -f "$LIBRARY" ]; then
  echo "shared library not found: $LIBRARY (pack the sample first: ./gradlew :test-library:packNuget)" >&2
  exit 1
fi

EXPECTED="$(grep -o '@CName("nuget_[a-z0-9_]*")' "$RUNTIME_SOURCE" | sed 's/@CName("//; s/")//' | sort -u)"
EXPECTED_COUNT="$(printf '%s\n' "$EXPECTED" | wc -l | tr -d ' ')"

case "$LIBRARY" in
  *.dll) ACTUAL="$(llvm-nm --defined-only --extern-only "$LIBRARY" | awk '{print $NF}' | sort -u)" ;;
  *) ACTUAL="$(nm -gU "$LIBRARY" | awk '{print $NF}' | sed 's/^_//' | sort -u)" ;;
esac

MISSING=""
while read -r name; do
  if ! printf '%s\n' "$ACTUAL" | grep -qx "$name"; then
    MISSING="$MISSING $name"
  fi
done <<< "$EXPECTED"

if [ -n "$MISSING" ]; then
  echo "FAIL: $LIBRARY is missing runtime exports:$MISSING" >&2
  exit 1
fi

echo "OK: all $EXPECTED_COUNT nuget-runtime exports present in $LIBRARY"
