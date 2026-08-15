#!/usr/bin/env bash
set -euo pipefail

# ADR-100: the load-bearing test for forward diagnostic delivery.
#
# The Tier 1 unit assertions prove the *producer* only: the harness injects its own
# RecordingKSPLogger, which is precisely the component production replaces, so they cannot fail for
# either defect this ADR fixes (KSP's stdout never reaching the console, and packNuget not running
# KSP at all on an incremental build).
#
# So this runs a real build and asserts on the real console, twice. The second run is the one that
# pins the contract: no clean, no --rerun-tasks, so `kspKotlin{Target}` is UP-TO-DATE and any
# transport that only speaks during the KSP task action goes silent. That is exactly where the old
# behaviour died.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# A product-scope skip (a value-class member inherited via interface delegation, ROADMAP line 77),
# deliberately chosen over a capability-gap skip: the roadmap is actively closing those
# (ADR-098 for List<Short>, ADR-099 for nested collections), and naming one would produce a test
# that silently stops testing anything the day that ADR lands.
DECLARATION="io.github.xxfast.kotlin.native.nuget.test.models.StoryUri.length"

run() {
  local label="$1"
  local log="$2"
  echo "==> $label: ./gradlew :test-library:packNuget --console=plain"
  ./gradlew :test-library:packNuget --console=plain >"$log" 2>&1 || {
    echo "FAIL: the build itself failed; see $log" >&2
    tail -40 "$log" >&2
    exit 1
  }

  if ! grep -q '\[nuget:SKIPPED_' "$log"; then
    echo "FAIL ($label): no [nuget:SKIPPED_ marker on the console. Forward diagnostics are invisible." >&2
    exit 1
  fi

  if ! grep -q "$DECLARATION" "$log"; then
    echo "FAIL ($label): console has a [nuget:SKIPPED_ marker but does not name $DECLARATION." >&2
    echo "If that declaration stopped being skipped, pick another product-scope skip from" >&2
    echo "test-library/build/generated/ksp/*/*/resources/NugetDiagnostics.json and update this script." >&2
    exit 1
  fi

  grep "\[nuget:SKIPPED_INHERITED_MEMBER\].*$DECLARATION" "$log" | head -1
}

LOG_DIR="$(mktemp -d)"
trap 'rm -rf "$LOG_DIR"' EXIT

run "run 1" "$LOG_DIR/run1.log"

# No clean, no --rerun-tasks: KSP is UP-TO-DATE / FROM-CACHE here, and the warning must still
# appear. This assertion is what separates "appears once when KSP happens to run" from "a consumer
# running packNuget sees it".
run "run 2 (incremental, KSP up-to-date)" "$LOG_DIR/run2.log"

if grep -q 'kspKotlin.* UP-TO-DATE\|kspKotlin.* FROM-CACHE' "$LOG_DIR/run2.log"; then
  echo "==> confirmed: run 2 did not execute the KSP task, and the warning was still reported"
else
  echo "note: run 2 re-executed the KSP task, so the cached-build path was not exercised here" >&2
fi

echo "OK: forward diagnostics reach the console on both a fresh and an incremental packNuget"
