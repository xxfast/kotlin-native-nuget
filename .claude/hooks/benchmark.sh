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
# Definitions, so rows stay comparable:
#   tokens     input + output + cache_creation + cache_read, summed over unique requests.
#              Assistant lines repeat one usage block across their thinking/text/tool_use parts, so
#              this dedupes by requestId. A naive sum triple-counts.
#   tool_uses  every tool_use content block in the transcript.
#   wall_min   active time only. A resumed agent (SendMessage) is captured again with a cumulative
#              transcript, so tokens and tool_uses are taken from its LAST capture, while wall_min
#              sums each round's span and excludes the idle gap between rounds.
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

command -v jq >/dev/null || { echo "benchmark.sh needs jq" >&2; exit 1; }

in_roster() {
  local a
  for a in "${roster[@]}"; do [ "$a" = "$1" ] && return 0; done
  return 1
}

# Reduce one transcript to {model, tokens, tool_uses, first, last}. `since` (epoch seconds, 0 for
# none) restricts `first` to the round that started after the previous capture, so a resumed agent's
# idle gap is not billed as wall time. `sidechain_only` picks the subagent turns out of a transcript
# that also holds main-thread ones.
metrics() {
  local transcript="$1" since="${2:-0}" sidechain_only="${3:-false}"
  jq -s --argjson since "$since" --argjson sidechain "$sidechain_only" '
    def epoch: .timestamp | sub("\\.[0-9]+Z$"; "Z") | fromdateiso8601;

    [ .[] | select(.timestamp != null)
          | select(if $sidechain then .isSidechain == true else (.isSidechain // false) | not end) ]
      as $lines
    | [ $lines[] | select(.type == "assistant") ] as $asst
    | [ $asst[] | select(.requestId != null) ] as $req

    # One usage block is repeated across a message'"'"'s content parts. Take it once per request.
    | ( $req | group_by(.requestId)
             | map( map(select(.message.usage != null)) | first )
             | map(select(. != null)) ) as $uniq

    | {
        model: ( [ $asst[] | .message.model // empty ] | last // "" ),
        tokens: ( [ $uniq[] | .message.usage
                    | (.input_tokens // 0) + (.output_tokens // 0)
                    + (.cache_creation_input_tokens // 0) + (.cache_read_input_tokens // 0)
                  ] | add // 0 ),
        tool_uses: ( [ $asst[] | .message.content[]? | select(.type == "tool_use") ] | length ),
        first: ( [ $lines[] | epoch | select(. > $since) ] | min // 0 ),
        last:  ( [ $lines[] | epoch ] | max // 0 )
      }
  ' "$transcript"
}

# claude-sonnet-5 -> sonnet-5. claude-haiku-4-5-20251001 -> haiku-4-5.
short_model() {
  printf '%s' "$1" | sed -e 's/^claude-//' -e 's/-[0-9]\{8\}$//'
}

cmd_capture() {
  local event transcript session agent agent_id project_dir prev_end m

  event="$(cat)"
  agent="$(printf '%s' "$event" | jq -r '.agent_type // ""')"
  in_roster "$agent" || exit 0

  transcript="$(printf '%s' "$event" | jq -r '.transcript_path // ""')"
  [ -f "$transcript" ] || exit 0

  session="$(printf '%s' "$event" | jq -r '.session_id // ""')"
  agent_id="$(printf '%s' "$event" | jq -r '.agent_id // ""')"
  project_dir="$(dirname "$transcript")"

  # A resumed agent is captured again over a cumulative transcript. Bill only the new round's wall.
  prev_end=0
  if [ -f "$staging" ] && [ -n "$agent_id" ]; then
    prev_end="$(jq -s --arg id "$agent_id" \
      '[ .[] | select(.agent_id == $id) | .last ] | max // 0' "$staging")"
  fi

  # A subagent transcript may be its own file (all sidechain) or share the session file. Prefer the
  # sidechain turns when there are any, so the orchestrator's own turns are never billed to an agent.
  local has_sidechain
  has_sidechain="$(jq -s 'any(.[]; .isSidechain == true)' "$transcript")"
  m="$(metrics "$transcript" "$prev_end" "$has_sidechain")"

  jq -nc \
    --arg session "$session" --arg agent "$agent" --arg agent_id "$agent_id" \
    --arg project_dir "$project_dir" --argjson m "$m" \
    '{session: $session, agent: $agent, agent_id: $agent_id, project_dir: $project_dir,
      model: $m.model, tokens: $m.tokens, tool_uses: $m.tool_uses,
      wall_sec: (if $m.first > 0 and $m.last > $m.first then $m.last - $m.first else 0 end),
      last: $m.last}' >> "$staging"
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
    orch="$(metrics "$main_transcript" 0 false)"
  else
    orch='{"model":"","tokens":0,"tool_uses":0,"first":0,"last":0}'
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

    # Per agent role. A resumed agent is cumulative, so tokens and tool_uses come from its last
    # capture; wall sums every round. Two agents of the same role in one run sum across both.
    | ( $roster
        | map(. as $role
              | ($staged | map(select(.agent == $role))) as $rows
              | { agent: $role,
                  ran: ($rows | length > 0),
                  model: ($rows | last | .model? // "" | if . == "" then "" else shorten end),
                  tokens: ( $rows | group_by(.agent_id) | map(last | .tokens) | add // 0 ),
                  tool_uses: ( $rows | group_by(.agent_id) | map(last | .tool_uses) | add // 0 ),
                  wall_min: ( ($rows | map(.wall_sec) | add // 0) / 60 | round ) } )
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
