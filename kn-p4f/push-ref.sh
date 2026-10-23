#!/usr/bin/env bash
# usage: push-ref.sh <worktree> <sha-or-ref> <branch>   (new branch, or fast-forward only)
set -euo pipefail
cd "$1"; sha=$(git rev-parse "$2"); b="$3"
TOKEN=$(gh auth token)
git -c credential.helper= -c "credential.helper=!f() { echo username=xxfast; echo password=$TOKEN; }; f" push origin "${sha}:refs/heads/${b}" 2>&1 | grep -E -- '->|error|rejected'
