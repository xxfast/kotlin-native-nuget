#!/usr/bin/env bash
# usage: open-pr.sh <n> <head> <base>  -> prints URL
set -euo pipefail
cd /c/Users/isuru/Developer/KMP/kotlin-native-nuget
export GH_TOKEN=$(gh auth token)
P=/c/Users/isuru/Developer/KMP/kn-p4f/pr/$1.md
grep -q $'\xe2\x80\x94' "$P" && { echo "em-dash in $P"; exit 1; }
t=$(head -1 "$P" | sed 's/^TITLE: //'); tail -n +3 "$P" > /tmp/kn-body-$1.md
gh pr create --repo xxfast/kotlin-native-nuget --head "$2" --base "$3" --title "$t" --body-file /tmp/kn-body-$1.md
