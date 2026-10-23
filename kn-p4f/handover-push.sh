#!/usr/bin/env bash
# pushes kn-p4f/ (minus snap/) as an orphan commit on wip/handover
set -euo pipefail
R=/c/Users/isuru/Developer/KMP/kotlin-native-nuget
S=/c/Users/isuru/Developer/KMP/kn-p4f
T=$(mktemp -d)
mkdir -p "$T"/kn-p4f && (cd "$S" && tar --exclude=snap -cf - .) | tar -xf - -C "$T"/kn-p4f
export GIT_INDEX_FILE=$(mktemp -u)
cd "$R"
GIT_WORK_TREE="$T" git add -A -f kn-p4f
tree=$(GIT_WORK_TREE="$T" git write-tree)
c=$(git commit-tree "$tree" -p "$(git rev-parse origin/wip/handover)" -m "WIP: Phase 4 follow-up handover notes" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>")
rm -f "$GIT_INDEX_FILE"; rm -rf "$T"
bash "$S/push-ref.sh" "$R" "$c" wip/handover
echo "$c"
