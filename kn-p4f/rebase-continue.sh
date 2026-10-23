#!/usr/bin/env bash
# usage: rebase-continue.sh <worktree>  ; resolves end-of-file append conflicts in LeakTests/*.cs, continues the rebase
set -uo pipefail
cd "$1"
for i in $(seq 1 20); do
  [ -d "$(git rev-parse --git-path rebase-merge)" ] || [ -d "$(git rev-parse --git-path rebase-apply)" ] || { echo "rebase done"; git log --oneline -8; exit 0; }
  u=$(git diff --name-only --diff-filter=U)
  [ -z "$u" ] && { GIT_EDITOR=true git rebase --continue >/dev/null 2>&1; continue; }
  for f in $u; do
    case "$f" in
      LeakTests/LiveHandleTests.cs|LeakTests/CollectabilityTests.cs)
        n=$(grep -c '^<<<<<<<' "$f")
        [ "$n" = 1 ] || { echo "MANUAL: $f has $n conflict blocks"; exit 1; }
        perl -0pi -e 's/^<<<<<<< [^\n]*\n//m; s/^=======\n/    }\n\n/m; s/^>>>>>>> [^\n]*\n//m' "$f"
        o=$(tr -cd '{' < "$f" | wc -c); c=$(tr -cd '}' < "$f" | wc -c)
        [ "$o" = "$c" ] || { echo "MANUAL: $f braces $o/$c"; exit 1; }
        git add "$f";;
      docs/adr/*.md|docs/topics/registration-diagnostics.md)
        perl -0pi -e 's/^<<<<<<< [^\n]*\n//mg; s/^=======\n/\n/mg; s/^>>>>>>> [^\n]*\n//mg' "$f"
        echo "kept both sides: $f"; git add "$f";;
      *) echo "MANUAL: $f"; exit 1;;
    esac
  done
  GIT_EDITOR=true git rebase --continue >/dev/null 2>&1 || true
done
echo "loop limit"; exit 1
