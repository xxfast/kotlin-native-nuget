#!/usr/bin/env bash
# PreToolUse guard for Edit/Write.
#
# Refuses to write into build output or the NuGet package cache. Both rules are stated in
# AGENTS.md and both were violated in the very session that wrote them, which is why they are
# enforced here rather than left to good intentions.
#
# Hand-editing either location produces symptoms that look exactly like real compiler or generator
# bugs (MSBuild resolves the <Compile> set from the restored package at restore time), and agents
# have burned hours debugging a bug that did not exist.

set -euo pipefail

input="$(cat)"
path="$(printf '%s' "$input" | jq -r '.tool_input.file_path // empty')"

[ -z "$path" ] && exit 0

deny() {
  jq -n --arg reason "$1" '{
    hookSpecificOutput: {
      hookEventName: "PreToolUse",
      permissionDecision: "deny",
      permissionDecisionReason: $reason
    }
  }'
  exit 0
}

case "$path" in
  "$HOME"/.nuget/packages/*)
    deny "Blocked: never hand-copy files into ~/.nuget/packages/ ($path).

MSBuild resolves the <Compile> item set from the restored package at restore time, so a hand-edited
cache produces symptoms that look exactly like real compiler or generator bugs. Agents have done
this to iterate faster and lost hours to a bug that did not exist.

Change the source and repack through scripts/verify.sh. See AGENTS.md."
    ;;
  */build/*)
    deny "Blocked: $path is generated build output, not source.

A generated file, a packaged .nupkg, a compiled .dll: none of these are evidence of what the source
does, and hand-patching one is silently overwritten by the next build. In the ADR-053 feature, two
of the four 'bugs' found were phantoms of stale build state.

Edit the generator instead (nuget-processor/ for forward, nuget-plugin/ for reverse) and re-run
scripts/verify.sh. See AGENTS.md."
    ;;
esac

exit 0
