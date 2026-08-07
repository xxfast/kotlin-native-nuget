#!/usr/bin/env bash
set -euo pipefail

# Runs the real Writerside build-time checks locally: the same builder image and
# invocation as the Docs CI (.github/workflows/docs.yml via
# JetBrains/writerside-github-action@v4), then fails on any ERROR in the
# builder's report.json, which is what writerside-checker-action does in CI.
#
# Needs a Docker daemon. On this repo's dev machines that is colima:
#   brew install colima docker && colima start
# The first run pulls the builder image (several GB); later runs are quick.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

INSTANCE="docs/knn"
# Default image of writerside-github-action@v4; keep in lockstep with CI.
IMAGE="registry.jetbrains.team/p/writerside/builder/writerside-builder:232.10275"

if ! command -v docker >/dev/null 2>&1; then
  echo "docker CLI not found. Install it with: brew install colima docker && colima start" >&2
  exit 1
fi
if ! docker info >/dev/null 2>&1; then
  echo "Docker daemon not reachable. Start it with: colima start" >&2
  exit 1
fi

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

echo "==> Building Writerside instance $INSTANCE with $IMAGE"
docker run --rm \
  -v "$ROOT":/opt/sources \
  -v "$OUT":/opt/target \
  "$IMAGE" \
  /bin/bash -c "
    export DISPLAY=:99
    Xvfb :99 >/dev/null 2>&1 &
    git config --global --add safe.directory /opt/sources >/dev/null 2>&1 || true
    /opt/builder/bin/idea.sh helpbuilderinspect -source-dir /opt/sources -product $INSTANCE --runner github -output-dir /opt/target/ || true
  "

if [ ! -f "$OUT/report.json" ]; then
  echo "docs check FAILED: builder produced no report.json (build did not run to completion)" >&2
  ls -la "$OUT" >&2 || true
  exit 1
fi

python3 - "$OUT/report.json" <<'EOF'
import json
import sys

with open(sys.argv[1]) as f:
    report = json.load(f)

# The report nests problems per file/check; walk it and pick up anything
# carrying a severity, the same set writerside-checker-action reports.
problems = []
def walk(node):
    if isinstance(node, dict):
        if "severity" in node:
            problems.append(node)
        for value in node.values():
            walk(value)
    elif isinstance(node, list):
        for value in node:
            walk(value)
walk(report)

def describe(problem):
    parts = []
    for key in ("code", "id", "type"):
        if problem.get(key):
            parts.append(str(problem[key]))
            break
    for key in ("title", "message", "description", "text"):
        if problem.get(key):
            parts.append(str(problem[key]))
            break
    for key in ("file", "path", "location"):
        if problem.get(key):
            parts.append(str(problem[key]))
            break
    return ": ".join(parts) or json.dumps(problem)

errors = [p for p in problems if str(p.get("severity", "")).upper() in ("ERROR", "FATAL")]
for problem in problems:
    label = "ERROR" if problem in errors else str(problem.get("severity", "INFO")).upper()
    print(f"{label}: {describe(problem)}")

if errors:
    print(f"\ndocs check FAILED: {len(errors)} error(s)")
    sys.exit(1)
print("docs check passed")
EOF
