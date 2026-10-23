#!/usr/bin/env bash
# usage: wip-push.sh <worktree> <branch> <subject>  (commits all changes as WIP and pushes; leaves worktree at WIP commit)
set -euo pipefail
W="$1"; B="$2"; S="$3"
cd "$W"
git add -A
if git diff --cached | grep '^+' | grep -q $'\xe2\x80\x94'; then echo "em-dash in staged lines (WIP, continuing)"; fi
git commit -q -m "WIP: $S" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
bash /c/Users/isuru/Developer/KMP/kn-p4f/push-ref.sh "$W" HEAD "$B"
git log --oneline -2
