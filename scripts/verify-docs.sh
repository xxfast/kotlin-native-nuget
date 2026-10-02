#!/usr/bin/env bash
set -euo pipefail

# Runs the real Writerside build-time checks locally: the same builder image and
# invocation as the Docs CI (.github/workflows/docs.yml via
# JetBrains/writerside-github-action@v4), then fails on any ERROR in the
# builder's report.json, which is what writerside-checker-action does in CI.
#
# Needs a Docker daemon. On this repo's dev machines that is colima:
#   brew install colima docker && colima start --memory 8
# The builder is a headless IntelliJ; colima's default 2GiB VM gets it
# OOM-killed mid-build. 8GiB is known good and colima remembers the setting.
# The first run pulls the builder image (several GB); later runs are quick.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

INSTANCE="docs/knn"
# Default image of writerside-github-action@v4; keep in lockstep with CI.
IMAGE="registry.jetbrains.team/p/writerside/builder/writerside-builder:232.10275"

if ! command -v docker >/dev/null 2>&1; then
  echo "docker CLI not found. Install it with: brew install colima docker && colima start --memory 8" >&2
  exit 1
fi
if ! docker info >/dev/null 2>&1; then
  echo "Docker daemon not reachable. Start it with: colima start --memory 8" >&2
  exit 1
fi

# Must live under $HOME: colima only shares $HOME with the VM, so a
# /var/folders mktemp dir would silently mount as an empty VM-local dir.
OUT="$(mktemp -d "$HOME/.cache/verify-docs.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT

echo "==> Building Writerside instance $INSTANCE with $IMAGE"
docker run --rm \
  -v "$ROOT":/opt/sources \
  -v "$OUT":/opt/out \
  "$IMAGE" \
  /bin/bash -c "
    export DISPLAY=:99
    Xvfb :99 >/dev/null 2>&1 &
    git config --global --add safe.directory /opt/sources >/dev/null 2>&1 || true
    /opt/builder/bin/idea.sh helpbuilderinspect -source-dir /opt/sources -product $INSTANCE --runner github -output-dir /opt/out/artifacts/ || true
  "

OUT="$OUT/artifacts"
if [ ! -f "$OUT/report.json" ]; then
  echo "docs check FAILED: builder produced no report.json (build did not run to completion)" >&2
  ls -la "$OUT" >&2 || true
  exit 1
fi

# The report check is a task in the root build.gradle.kts; it only reads report.json.
./gradlew checkWritersideReport -PwritersideReport="$OUT/report.json" --console=plain -q
