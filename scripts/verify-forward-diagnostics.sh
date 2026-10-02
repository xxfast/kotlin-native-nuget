#!/usr/bin/env bash
set -euo pipefail

# ADR-100: the load-bearing test for forward diagnostic delivery.
#
# The Tier 1 unit assertions prove the *producer* only: the harness injects its own
# RecordingKSPLogger, which is precisely the component production replaces, so they cannot fail for
# either defect this ADR fixes (KSP's stdout never reaching the console, and packNuget not running
# KSP at all on an incremental build).
#
# This runs a real build and asserts on the real console, twice. When invoked by verify.sh, both
# runs follow its clean packs; standalone execution may build fresh outputs. The second run
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
SHARED_TYPE="io.github.xxfast.kotlin.native.nuget.test.models.TopStory"
FORWARD_SKIP='\[nuget:SKIPPED_[A-Z0-9_]+\] Skipping [a-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+[:(]'

run() {
  local label="$1"
  local log="$2"
  echo "==> $label: pack both publishers with --console=plain --no-configuration-cache"
  ./gradlew :test-library:packNuget :test-companion:packNuget --console=plain --no-configuration-cache >"$log" 2>&1 || {
    echo "FAIL: the build itself failed; see $log" >&2
    tail -40 "$log" >&2
    exit 1
  }

  # ADR-182: reverse lines carry the same `[nuget:SKIPPED_` bracket, so the bracket alone no longer
  # proves forward delivery. A forward location is a dotted Kotlin name (lowercase package first);
  # a reverse one is `<packageId>/<Type>...`, so the `/` rules it out.
  if ! grep -Eq "$FORWARD_SKIP" "$log"; then
    echo "FAIL ($label): no forward [nuget:SKIPPED_ line on the console. Forward diagnostics are invisible." >&2
    exit 1
  fi

  if ! grep -Eq "\\[nuget:SKIPPED_[A-Z0-9_]+\\] Skipping ${DECLARATION//./\\.}:" "$log"; then
    echo "FAIL ($label): console has a [nuget:SKIPPED_ marker but does not name $DECLARATION." >&2
    echo "If that declaration stopped being skipped, pick another product-scope skip from" >&2
    echo "test-library/build/generated/ksp/*/*/resources/NugetDiagnostics.json and update this script." >&2
    exit 1
  fi

  # ADR-109: match code, declaration and sibling publisher on one console line.
  for publisher in TestCompanion TestLibrary; do
    if ! grep -E "\\[nuget:WARNING_DUPLICATED_DEPENDENCY_TYPE\\].*$SHARED_TYPE:.*$publisher NuGet package" "$log" >/dev/null; then
      echo "FAIL ($label): no duplicate TopStory warning naming $publisher." >&2
      exit 1
    fi
  done

  # Disable configuration caching above to rerun the ordering sentinel, while preserving
  # task caching for the incremental KSP assertion.
  # The sentinel runs after the reader plugin callback and before companion publish exists.
  if ! grep -Fq "ADR-109: reader evaluated before companion publish configuration" "$log"; then
    echo "FAIL ($label): late-publisher ordering sentinel did not run." >&2
    exit 1
  fi

  # Issue #235: a diagnostic may only ever describe something the consumer can act on. Nobody
  # wrote `Carton.Companion.serializer` or `Carton.$serializer`; the kotlinx.serialization compiler
  # plugin did, on a `@Serializable` type in `:test-models`. Grepped on the real packNuget console
  # rather than in a unit test because the plugin only runs in a real build: KSP shows nothing at
  # all for an in-module `@Serializable` type, so this surface exists solely across a klib boundary.
  if grep -q '\[nuget:.*serializer' "$log"; then
    echo "FAIL ($label): a diagnostic names a compiler-synthesized serialization declaration." >&2
    grep '\[nuget:.*serializer' "$log" >&2
    exit 1
  fi

  grep "\[nuget:SKIPPED_INHERITED_MEMBER\].*$DECLARATION" "$log" | head -1
}

# Issue #223, the other half of the same root cause: `$` is not a legal C# identifier character, so
# a declared `$serializer` is six compiler diagnostics per site. Pinned by absence, on the generated
# file rather than the console.
assert_no_synthesized_serializer_in_interop() {
  local found
  found="$(find "$ROOT/test-library/build/generated/ksp" -name Interop.cs -print 2>/dev/null)"
  if [ -z "$found" ]; then
    echo "FAIL: no generated Interop.cs under test-library/build/generated/ksp to inspect." >&2
    exit 1
  fi
  if echo "$found" | tr '\n' '\0' | xargs -0 grep -l 'serializer' 2>/dev/null | grep -q .; then
    echo "FAIL: a generated Interop.cs declares a compiler-synthesized serializer." >&2
    echo "$found" | tr '\n' '\0' | xargs -0 grep -n 'serializer' >&2
    exit 1
  fi
  echo "==> confirmed: no synthesized serializer in any generated Interop.cs"
}

LOG_DIR="$(mktemp -d)"
trap 'rm -rf "$LOG_DIR"' EXIT

run "run 1" "$LOG_DIR/run1.log"

# No clean, no --rerun-tasks: KSP is UP-TO-DATE / FROM-CACHE here, and the warning must still
# appear. This assertion is what separates "appears once when KSP happens to run" from "a consumer
# running packNuget sees it".
run "run 2 (incremental, KSP up-to-date)" "$LOG_DIR/run2.log"

for module in test-library test-companion; do
  if ! grep -E "^> Task :$module:kspKotlin[^ ]+ (UP-TO-DATE|FROM-CACHE)$" "$LOG_DIR/run2.log" >/dev/null ||
      grep -E "^> Task :$module:kspKotlin[^ ]+$" "$LOG_DIR/run2.log" >/dev/null; then
    echo "FAIL: run 2 executed KSP for $module; cached diagnostic delivery was not proved." >&2
    exit 1
  fi
done
echo "==> confirmed: both publishers delivered warnings while KSP was cached"

# Inspect outputs from the real builds above, never pre-existing package-cache copies.
python3 - "$ROOT" "$SHARED_TYPE" <<'PYTHON'
import json
import pathlib
import sys
root = pathlib.Path(sys.argv[1])
for module, sibling in (("test-library", "TestCompanion"), ("test-companion", "TestLibrary")):
    generated = root / module / "build/generated/ksp"
    manifests = list(generated.rglob("NugetDiagnostics.json"))
    interops = list(generated.rglob("Interop.cs"))
    assert manifests and interops, f"No real generated outputs for {module}"
    for manifest in manifests:
        data = json.loads(manifest.read_text())
        # ADR-182: a versioned object root, not the pre-0.9.0 bare array.
        assert isinstance(data, dict) and data.get("schemaVersion") == 1, \
            f"{manifest} has no schemaVersion 1 object root"
        entries = data["diagnostics"]
        assert any(entry["kind"] == "WARNING_DUPLICATED_DEPENDENCY_TYPE"
                   and entry["declaration"] == sys.argv[2]
                   and f"{sibling} NuGet package" in entry["message"]
                   for entry in entries), f"Missing shared TopStory warning in {manifest}"
    for interop in interops:
        assert "class TopStory" in interop.read_text(), f"Missing publisher's TopStory copy: {interop}"
print("==> confirmed: both manifests warn and both publishers retain generated TopStory copies")
PYTHON

assert_no_synthesized_serializer_in_interop

echo "OK: forward diagnostics reach both packNuget consoles, including cached KSP"
