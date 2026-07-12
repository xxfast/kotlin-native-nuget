#!/usr/bin/env bash
# Stop hook: warn about an orphaned Gradle build left behind by an agent.
#
# An agent can finish its task and report success while leaving a ./gradlew run alive. It holds the
# project lock, so every build behind it queues silently. Nothing errors, things just go quiet. One
# orphan held the lock for 25 minutes and starved the next agent, which just looked like the agent
# was thinking.
#
# This hook reports, it does not kill: a Gradle process you started deliberately is indistinguishable
# from an agent's orphan, and killing the wrong one loses real work. Killing is your call.
#
# Deliberately narrow:
#   - Ignores the Gradle daemon (GradleDaemon). It is long-lived, shared and legitimate.
#   - Only considers processes whose working directory is THIS project, so a build running in
#     another checkout is not reported.

set -euo pipefail

root="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"

strays=""

for pid in $(pgrep -f 'GradleWorkerMain|gradlew' 2>/dev/null || true); do
  # The daemon is not an orphan.
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
