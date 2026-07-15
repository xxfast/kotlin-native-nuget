#!/usr/bin/env bash
# Records what a feature-design run cost, into .claude/benchmark.csv.
#
# Two entry points, because the metrics and the identity of a run live in different places:
#
#   capture    A SubagentStop hook. Reads the subagent's transcript and stages its real figures.
#              The model is never asked for a number, so it cannot lose one or invent one. This is
#              what the blank cells in the existing rows were: an orchestration surface that did not
#              report per-agent figures, and a research agent whose figures went into a compaction.
#              The transcript has them regardless.
#
#   finalize   Called once by the feature-design orchestrator in Step 5. Supplies the four things
#              only it knows (feature, phase, direction, and the notes), stamps skill_sha and date,
#              totals the run, appends to the CSV, and clears staging.
#
# Each subagent writes its own transcript at <projects>/<session>/subagents/agent-<agent_id>.jsonl.
# That file, not the SubagentStop event's transcript_path, is what capture measures. The event's
# transcript_path is the MAIN session file, which in current Claude Code holds only the orchestrator's
# turns (no isSidechain lines) -- measuring it billed every agent the orchestrator's running totals,
# which is why an earlier run logged opus for every agent and token counts that only ever grew.
#
# Definitions, so rows stay comparable:
#   tokens     input + output + cache_creation, summed over unique requests. cache_read is left out
#              on purpose: it re-reads context already counted when it was created, so including it
#              would count the same prefix once per turn and make the total scale with turn count
#              instead of work. Assistant lines repeat one usage block across their thinking/text/
#              tool_use parts, so this dedupes by requestId. A naive sum triple-counts.
#   tool_uses  every tool_use content block in the transcript.
#   wall_min   ACTIVE time, not span. The sum of gaps between consecutive events, with each gap
#              capped at idle_cap and the gap before a resume dropped entirely. This excludes the
#              three ways an agent sits idle without working: waiting on a permission prompt, waiting
#              to be resumed between rounds, and an overnight pause. A resumed agent's own file is
#              cumulative, so its last capture already holds every round; tokens, tool_uses and
#              active are all taken from that last capture.
#   model      the exact model from the transcript (claude-sonnet-5 -> sonnet-5), never an alias.
#              An alias silently changes meaning as versions roll, which is what skill_sha exists to
#              prevent. A token count means nothing without it.
#
# Rows written by capture that never get finalized are harmless: finalize only ever reads the
# session it is finalizing, and truncates staging when it is done.

set -euo pipefail

root="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
csv="${root}/.claude/benchmark.csv"
staging="${root}/.claude/benchmark-staging.jsonl"
skill="${root}/.claude/skills/feature-design/SKILL.md"

# The agents a feature-design run is made of. SubagentStop fires for every subagent in every
# session, so anything outside this roster (an ad-hoc Explore, a general-purpose search) is ignored
# rather than logged as if it were part of a feature.
roster=(research csharp-dev kotlin-dev documenter refactorer)

# A gap between two consecutive transcript events longer than this is treated as idle, not work, and
# clamped to this many seconds. It has to sit above the longest legitimate single tool call in this
# repo (a full scripts/verify.sh or a kotlinc-native compile, a few minutes) and below the shortest
# thing we want to exclude (a permission wait or a resume gap, many minutes to hours).
idle_cap=600

command -v jq >/dev/null || { echo "benchmark.sh needs jq" >&2; exit 1; }

in_roster() {
  local a
  for a in "${roster[@]}"; do [ "$a" = "$1" ] && return 0; done
  return 1
}

# Reduce one transcript to {model, tokens, tool_uses, active}. `sidechain` picks the subagent turns
# (true) or the orchestrator turns (false). `agentid` isolates a single agent when the file holds more
# than one; "" keeps them all. `active` is the sum of inter-event gaps, each capped at `cap`, with the
# gap preceding a resume (a user message that is not a tool_result) dropped, so idle time never counts.
metrics() {
  local transcript="$1" sidechain="${2:-true}" agentid="${3:-}" cap="${4:-600}"
  jq -s --argjson sidechain "$sidechain" --arg agentid "$agentid" --argjson cap "$cap" '
    def epoch: .timestamp | sub("\\.[0-9]+Z$"; "Z") | fromdateiso8601;

    [ .[] | select(.timestamp != null)
          | select(if $sidechain then .isSidechain == true else (.isSidechain // false) | not end)
          | select($agentid == "" or .agentId == $agentid) ]
      as $lines
    # A synthetic line is harness-injected, not the model working; keep it out of model and counts.
    | [ $lines[] | select(.type == "assistant")
                 | select((.message.model // "") != "<synthetic>") ] as $asst
    | [ $asst[] | select(.requestId != null) ] as $req

    # One usage block is repeated across a message'"'"'s content parts. Take it once per request.
    | ( $req | group_by(.requestId)
             | map( map(select(.message.usage != null)) | first )
             | map(select(. != null)) ) as $uniq

    # Events in time order, each tagged as a resume boundary (a user turn carrying no tool_result).
    | ( [ $lines[]
          | { t: epoch,
              boundary: ( .type == "user" and
                ( [ (.message.content // empty)
                    | if type == "array" then .[] else . end | objects
                    | select(.type == "tool_result") ] | length ) == 0 ) } ]
        | sort_by(.t) ) as $ev

    | {
        model: ( [ $asst[] | .message.model // empty ] | last // "" ),
        tokens: ( [ $uniq[] | .message.usage
                    | (.input_tokens // 0) + (.output_tokens // 0)
                    + (.cache_creation_input_tokens // 0)
                  ] | add // 0 ),
        tool_uses: ( [ $asst[] | .message.content[]? | select(.type == "tool_use") ] | length ),
        active: ( reduce range(1; ($ev | length)) as $i
                    (0;
                     . + ( ($ev[$i].t - $ev[$i-1].t) as $g
                           | if $ev[$i].boundary then 0
                             elif $g <= 0 then 0
                             elif $g > $cap then $cap
                             else $g end ) ) )
      }
  ' "$transcript"
}

# claude-sonnet-5 -> sonnet-5. claude-haiku-4-5-20251001 -> haiku-4-5.
short_model() {
  printf '%s' "$1" | sed -e 's/^claude-//' -e 's/-[0-9]\{8\}$//'
}

cmd_capture() {
  local event transcript session agent agent_id project_dir sub m

  event="$(cat)"
  agent="$(printf '%s' "$event" | jq -r '.agent_type // ""')"
  in_roster "$agent" || exit 0

  transcript="$(printf '%s' "$event" | jq -r '.transcript_path // ""')"
  [ -f "$transcript" ] || exit 0

  session="$(printf '%s' "$event" | jq -r '.session_id // ""')"
  agent_id="$(printf '%s' "$event" | jq -r '.agent_id // ""')"
  project_dir="$(dirname "$transcript")"

  # Measure the subagent's own transcript, not the event's transcript_path (the main session file).
  # It is cumulative across resumes, so this capture reads every round the agent has run so far, and
  # finalize keeps the last capture per agent. Fall back to the main file for a Claude Code version
  # that writes subagent turns into it; the agentId filter isolates this agent either way.
  sub="${project_dir}/${session}/subagents/agent-${agent_id}.jsonl"
  if [ -n "$agent_id" ] && [ -f "$sub" ]; then
    m="$(metrics "$sub" true "$agent_id" "$idle_cap")"
  else
    m="$(metrics "$transcript" true "$agent_id" "$idle_cap")"
  fi

  jq -nc \
    --arg session "$session" --arg agent "$agent" --arg agent_id "$agent_id" \
    --arg project_dir "$project_dir" --argjson m "$m" \
    '{session: $session, agent: $agent, agent_id: $agent_id, project_dir: $project_dir,
      model: $m.model, tokens: $m.tokens, tool_uses: $m.tool_uses, active_sec: $m.active}' >> "$staging"
}

cmd_finalize() {
  local feature="" phase="" direction="" notes="{}"

  while [ $# -gt 0 ]; do
    case "$1" in
      --feature)   feature="$2"; shift 2 ;;
      --phase)     phase="$2"; shift 2 ;;
      --direction) direction="$2"; shift 2 ;;
      # --note agent=text, repeatable. Notes are prose about what was and was not the skill, so only
      # the orchestrator can write them.
      --note)      notes="$(jq -c --arg kv "$2" \
                     '.[($kv | split("=")[0])] = ($kv | split("=")[1:] | join("="))' <<<"$notes")"
                   shift 2 ;;
      *) echo "unknown flag: $1" >&2; exit 1 ;;
    esac
  done

  [ -n "$feature" ] || { echo "finalize needs --feature" >&2; exit 1; }
  [ -s "$staging" ] || { echo "nothing staged: no subagent of a feature-design run was captured" >&2; exit 1; }

  local session project_dir main_transcript orch skill_sha date
  session="$(jq -rs 'last | .session' "$staging")"
  project_dir="$(jq -rs 'last | .project_dir' "$staging")"

  # The orchestrator's own cost. ADR-056 had to call its total a floor because this was "not reported
  # to it" -- but it is sitting in the main transcript, which is the session file.
  main_transcript="${project_dir}/${session}.jsonl"
  if [ -f "$main_transcript" ]; then
    orch="$(metrics "$main_transcript" false "" "$idle_cap")"
  else
    orch='{"model":"","tokens":0,"tool_uses":0,"active":0}'
  fi

  skill_sha="$(git -C "$root" log -1 --format=%h -- "$skill" 2>/dev/null || echo "")"
  date="$(date +%F)"

  jq -rs \
    --arg feature "$feature" --arg phase "$phase" --arg direction "$direction" \
    --arg skill_sha "$skill_sha" --arg date "$date" --arg session "$session" \
    --argjson orch "$orch" --argjson notes "$notes" \
    --argjson roster "$(printf '%s\n' "${roster[@]}" | jq -R . | jq -sc .)" '

    def shorten: sub("^claude-"; "") | sub("-[0-9]{8}$"; "");
    def cell($n): if $n == null or $n == 0 then "" else ($n | tostring) end;
    # Quote only what has to be quoted, so these rows read like the ones already in the file.
    def csv: map(if test("[\",\n]") then "\"" + gsub("\""; "\"\"") + "\"" else . end) | join(",");

    [ .[] | select(.session == $session) ] as $staged

    # Per agent role. Each agent has one cumulative file, so every figure comes from its last
    # capture. Two distinct agents of the same role in one run sum across both.
    | ( $roster
        | map(. as $role
              | ($staged | map(select(.agent == $role))) as $rows
              | ($rows | group_by(.agent_id)) as $byagent
              | { agent: $role,
                  ran: ($rows | length > 0),
                  model: ($rows | last | .model? // "" | if . == "" then "" else shorten end),
                  tokens: ( $byagent | map(last | .tokens) | add // 0 ),
                  tool_uses: ( $byagent | map(last | .tool_uses) | add // 0 ),
                  wall_min: ( ($byagent | map(last | .active_sec) | add // 0) / 60 | round ) } )
      ) as $agents

    | ( $agents | map(select(.ran)) ) as $ran

    | { agent: "feature-design",
        model: ($orch.model | if . == "" then "" else shorten end),
        tokens: ( ($ran | map(.tokens) | add // 0) + $orch.tokens ),
        tool_uses: ( ($ran | map(.tool_uses) | add // 0) + $orch.tool_uses ),
        wall_min: ( $ran | map(.wall_min) | add // 0 ) } as $total

    | ( "TOTAL: " + ($ran | length | tostring)
        + (if ($ran | length) == 1 then " subagent" else " subagents" end) + " + the orchestrator. "
        + "tokens and tool_uses include the orchestrator'"'"'s own; wall_min is the sum of subagent "
        + "wall times, active only." ) as $total_note

    | [ ( $total + { note: ($total_note + (if $notes["feature-design"] then " " + $notes["feature-design"] else "" end)) } ) ]
      + ( $agents | map(. + { note: ($notes[.agent] // (if .ran then "" else "Did not run." end)) }) )

    | .[]
    | [ $feature, .agent, .model, cell(.tool_uses), cell(.tokens), cell(.wall_min),
        $date, $phase, $direction, $skill_sha, .note ]
    | csv
  ' "$staging" >> "$csv"

  : > "$staging"
  echo "Appended $(( ${#roster[@]} + 1 )) rows for \"${feature}\" to .claude/benchmark.csv"
}

case "${1:-}" in
  capture)  shift; cmd_capture "$@" ;;
  finalize) shift; cmd_finalize "$@" ;;
  *) echo "usage: benchmark.sh capture   (SubagentStop hook, reads the event on stdin)
       benchmark.sh finalize --feature <name> --phase <n> --direction <forward|reverse> [--note <agent>=<text> ...]" >&2
     exit 1 ;;
esac
