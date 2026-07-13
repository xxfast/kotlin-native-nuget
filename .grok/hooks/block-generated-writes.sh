#!/usr/bin/env bash
# Grok PreToolUse guard for search_replace / write tools.
#
# Refuses to write into build output or the NuGet package cache. Both rules are stated in
# AGENTS.md. Hand-editing either produces symptoms that look like real compiler or generator bugs
# (MSBuild resolves the <Compile> set from the restored package at restore time).
#
# Grok payload: toolInput.file_path, deny via { "decision": "deny", "reason": "..." }.

set -euo pipefail

input="$(cat)"
path="$(printf '%s' "$input" | jq -r '
  .toolInput.file_path
  // .toolInput.path
  // empty
')"

[ -z "$path" ] && exit 0

deny() {
  jq -n --arg reason "$1" '{ decision: "deny", reason: $reason }'
  exit 0
}

case "$path" in
  "$HOME"/.nuget/packages/*)
    deny "Blocked: never hand-copy files into ~/.nuget/packages/ ($path).

MSBuild resolves the <Compile> item set from the restored package at restore time, so a hand-edited
cache produces symptoms that look exactly like real compiler or generator bugs.

Change the source and repack through scripts/verify.sh. See AGENTS.md."
    ;;
  */build/*)
    deny "Blocked: $path is generated build output, not source.

A generated file, a packaged .nupkg, a compiled .dll: none of these are evidence of what the source
does, and hand-patching one is silently overwritten by the next build.

Edit the generator instead (nuget-processor/ for forward, nuget-plugin/ for reverse) and re-run
scripts/verify.sh. See AGENTS.md."
    ;;
esac

exit 0
