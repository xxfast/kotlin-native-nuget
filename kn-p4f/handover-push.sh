#!/usr/bin/env bash
# pushes kn-p4f/ (minus snap/) as an orphan commit on wip/handover
set -euo pipefail
R=/Users/xxfast/Developer/XXFAST/KMP/kotlin-native-nuget
S=/Users/xxfast/Developer/XXFAST/KMP/kn-p4f
T=$(mktemp -d)
rsync -a --exclude snap "$S"/ "$T"/kn-p4f/
export GIT_INDEX_FILE=$(mktemp -u)
cd "$R"
GIT_WORK_TREE="$T" git add -A -f kn-p4f
tree=$(GIT_WORK_TREE="$T" git write-tree)
c=$(git commit-tree "$tree" -m "WIP: Phase 4 follow-up handover notes" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>")
rm -f "$GIT_INDEX_FILE"; rm -rf "$T"
bash "$S/push-ref.sh" "$R" "$c" wip/handover
echo "$c"
