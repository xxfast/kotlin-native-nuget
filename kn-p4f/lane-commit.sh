#!/usr/bin/env bash
# usage: lane-commit.sh <worktree> <branch> <subject> [body-file]
set -euo pipefail
W="$1"; B="$2"; SUBJ="$3"; BODY="${4:-}"
cd "$W"
git add -A
if git diff --cached | grep '^+' | grep -q $'\xe2\x80\x94'; then echo "em-dash in staged lines"; exit 1; fi
if git diff --cached | grep '^+' | grep -v '^+++' | grep -niE '\b(lane [A-C][0-9]|DECISIONS\.md|kn-p4f|fable|the main thread|this run|after the rebase)\b' ; then echo "process words in staged lines"; exit 1; fi
cur=$(git branch --show-current || true)
[ -n "$cur" ] || git switch -q -c "$B"
MSG=$(mktemp); { echo "$SUBJ"; echo; [ -n "$BODY" ] && cat "$BODY" && echo; echo "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"; } > "$MSG"
git commit -q -F "$MSG"; rm "$MSG"
git log --oneline -1; git status --short | wc -l
