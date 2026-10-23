#!/usr/bin/env bash
# usage: verify-lane.sh <worktree path>
set -uo pipefail
W="$1"; cd "$W"; w=$(basename "$W" | sed 's/^kn-p4f-//')
export NUGET_PACKAGES="$HOME/.nuget/kn-p4f-$w"
fail=0
bash scripts/verify.sh > /tmp/kn-p4f-$w-verify.log 2>&1; rc=$?
grep -E 'Passed!|Failed!' /tmp/kn-p4f-$w-verify.log | sed 's/^.*\(Passed!\|Failed!\)/\1/' | cut -c1-140
[ $rc -eq 0 ] || { echo "verify.sh FAILED rc=$rc (log /tmp/kn-p4f-$w-verify.log)"; fail=1; }
bash scripts/verify-features.sh || fail=1
added=$( { git diff origin/main -- . ; git diff --no-index /dev/null $(git ls-files --others --exclude-standard) 2>/dev/null; } | grep '^+' | grep -v '^+++' )
if grep -q $'\xe2\x80\x94' <<<"$added"; then echo "EM-DASH in added lines:"; grep $'\xe2\x80\x94' <<<"$added" | head; fail=1; fi
new=$(git diff origin/main -- ROADMAP.md | grep -c '^+- \[ \]' || true)
[ "$new" = 0 ] || { echo "NEW ROADMAP LINES: $new (no deferrals allowed)"; git diff origin/main -- ROADMAP.md | grep '^+- \[ \]' | cut -c1-160; fail=1; }
[ -d docs/backlog ] && n=$(git status --porcelain docs/backlog | grep -c '^??' || true) && [ "$n" != 0 ] && { echo "NEW BACKLOG FILES: $n"; fail=1; }
[ $fail = 0 ] && echo "LANE VERIFY OK" || echo "LANE VERIFY FAILED"
exit $fail
