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

# Candidates in host order: whichever target this machine actually linked is the one to check.
# The ADR-127 spike only ever ran on macosArm64; mingwX64 is the leg that can regress silently,
# so the Windows binary is a first-class candidate here rather than something you pass by hand.
CANDIDATES=(
  "test-library/build/bin/macosArm64/releaseShared/libtest.dylib"
  "test-library/build/bin/mingwX64/releaseShared/test.dll"
  "test-library/build/bin/linuxX64/releaseShared/libtest.so"
)

if [ ! -f "$RUNTIME_SOURCE" ]; then
  echo "runtime source not found: $RUNTIME_SOURCE" >&2
  exit 1
fi

LIBRARY="${1:-}"
if [ -z "$LIBRARY" ]; then
  for candidate in "${CANDIDATES[@]}"; do
    if [ -f "$candidate" ]; then
      LIBRARY="$candidate"
      break
    fi
  done
fi

if [ -z "$LIBRARY" ]; then
  {
    echo "no linked shared library found (pack the sample first: ./gradlew :test-library:packNuget)"
    echo "looked for:"
    for candidate in "${CANDIDATES[@]}"; do
      echo "  $candidate"
    done
  } >&2
  exit 1
fi

if [ ! -f "$LIBRARY" ]; then
  echo "shared library not found: $LIBRARY (pack the sample first: ./gradlew :test-library:packNuget)" >&2
  exit 1
fi

# GNU `nm` for the PE/ELF branches. Kotlin/Native ships one in its own msys2 toolchain dependency,
# which is present on every machine that has linked mingwX64, so the Windows leg needs no extra
# install and no `dumpbin` from a Visual Studio prompt.
find_gnu_nm() {
  local konan="${KONAN_DATA_DIR:-$HOME/.konan}"
  local candidates=()
  shopt -s nullglob
  candidates=("$konan"/dependencies/msys2-mingw-w64-x86_64-*/bin/nm.exe)
  shopt -u nullglob
  if [ ${#candidates[@]} -gt 0 ]; then
    local newest=""
    for candidate in "${candidates[@]}"; do
      if [ -z "$newest" ] || [ "$candidate" -nt "$newest" ]; then
        newest="$candidate"
      fi
    done
    echo "$newest"
    return 0
  fi
  if command -v llvm-nm >/dev/null 2>&1; then
    echo "llvm-nm"
    return 0
  fi
  if command -v nm >/dev/null 2>&1; then
    echo "nm"
    return 0
  fi
  {
    echo "no symbol lister found for $LIBRARY; tried, in order:"
    echo "  \$KONAN_DATA_DIR/dependencies/msys2-mingw-w64-x86_64-*/bin/nm.exe (looked under $konan)"
    echo "  llvm-nm on PATH"
    echo "  nm on PATH"
  } >&2
  return 1
}

EXPECTED="$(grep -o '@CName("nuget_[a-z0-9_]*")' "$RUNTIME_SOURCE" | sed 's/@CName("//; s/")//' | sort -u)"
EXPECTED_COUNT="$(printf '%s\n' "$EXPECTED" | wc -l | tr -d ' ')"

case "$LIBRARY" in
  # PE (x86_64) and ELF exports carry no leading underscore, so the names come out verbatim.
  # The lookup is its own statement: folded into the command position, a failed lookup would run
  # the empty string and bury the message above under a `: command not found`.
  *.dll | *.so)
    NM_TOOL="$(find_gnu_nm)"
    ACTUAL="$("$NM_TOOL" --defined-only --extern-only "$LIBRARY" | awk '{print $NF}' | sort -u)"
    ;;
  # Mach-O: BSD nm, and every C symbol is prefixed with an underscore.
  *) ACTUAL="$(nm -gU "$LIBRARY" | awk '{print $NF}' | sed 's/^_//' | sort -u)" ;;
esac

# Matched with a here-string, not a pipe: `grep -q` exits at the first hit, and under `pipefail`
# the `printf` feeding it dies of SIGPIPE once the symbol list outgrows the pipe buffer, so a
# piped form reports present symbols as missing - nondeterministically, by library size.
MISSING=""
while read -r name; do
  if ! grep -qx -- "$name" <<< "$ACTUAL"; then
    MISSING="$MISSING $name"
  fi
done <<< "$EXPECTED"

if [ -n "$MISSING" ]; then
  echo "FAIL: $LIBRARY is missing runtime exports:$MISSING" >&2
  exit 1
fi

echo "OK: all $EXPECTED_COUNT nuget-runtime exports present in $LIBRARY"
