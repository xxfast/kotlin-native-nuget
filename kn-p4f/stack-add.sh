#!/usr/bin/env bash
# usage: stack-add.sh <stack> <pr>
set -euo pipefail
export GH_TOKEN=$(gh auth token --user xxfast)
R=repos/xxfast/kotlin-native-nuget
printf '{"pull_requests":[%s]}' "$2" | gh api -X POST "$R/stacks/$1/add" --input - --jq '"added '"$2"', now \(.pull_requests | length)"'
gh api "$R/stacks/$1" --jq '"stack #\(.number) base=\(.base.ref) prs=\([.pull_requests[].number] | join(","))"'
