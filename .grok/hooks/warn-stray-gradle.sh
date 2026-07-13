#!/usr/bin/env bash
# Grok Stop hook: warn about an orphaned Gradle build left behind by an agent.
#
# An agent can finish and report success while leaving a ./gradlew run alive. It holds the project
# lock, so every build behind it queues silently. This hook reports; it does not kill.
#
# Deliberately narrow:
#   - Ignores the Gradle daemon (GradleDaemon).
#   - Only considers processes whose cwd is this project.

set -euo pipefail

input="$(cat)"
root="$(printf '%s' "$input" | jq -r '.workspaceRoot // .cwd // empty')"
if [ -z "$root" ]; then
  root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
fi

strays=""

for pid in $(pgrep -f 'GradleWorkerMain|gradlew' 2>/dev/null || true); do
  if ps -o command= -p "$pid" 2>/dev/null | grep -q 'GradleDaemon'; then
    continue
  fi

  cwd="$(lsof -a -d cwd -p "$pid" -Fn 2>/dev/null | sed -n 's/^n//p' | head -1)"
  [ "$cwd" = "$root" ] || continue

  strays="${strays} ${pid}"
done

[ -z "$strays" ] && exit 0

jq -n --arg pids "${strays# }" '{
  systemMessage: ("Stray Gradle process still running in this project: " + $pids
    + "\nIt holds the project lock, so the next build will queue silently behind it."
    + "\nCheck with `./gradlew --status`, then `kill " + $pids + "` if it is an orphan.")
}'
