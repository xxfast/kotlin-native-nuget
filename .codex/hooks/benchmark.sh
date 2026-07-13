#!/usr/bin/env bash
# Records what a Codex feature-design run cost, into .codex/benchmark.csv.
#
# capture is a SubagentStop hook. It reads real model, token, tool-use, and timing figures from the
# subagent rollout. finalize is called once by the feature-design orchestrator with the feature
# metadata and notes that cannot be recovered from a rollout.

set -euo pipefail

root="${CODEX_BENCHMARK_ROOT:-$(git rev-parse --show-toplevel)}"
csv="${CODEX_BENCHMARK_CSV:-${root}/.codex/benchmark.csv}"
staging="${CODEX_BENCHMARK_STAGING:-${root}/.codex/benchmark-staging.jsonl}"
codex_skill="${root}/.codex/skills/feature-design/SKILL.md"
claude_skill="${root}/.claude/skills/feature-design/SKILL.md"
roster=(research csharp-dev kotlin-dev documenter refactorer)

command -v jq >/dev/null || { echo "benchmark.sh needs jq" >&2; exit 1; }

# Codex agent_type identifies the profile and is often shared by several roles. The task name in
# agent_path is the stable role identity: research_*, csharp_*, kotlin_*, document_*, refactor_*.
role_for() {
  local value
  value="$(printf '%s' "$1" | tr '[:upper:]-' '[:lower:]_')"
  value="${value##*/}"
  case "$value" in
    research|research_*) printf 'research' ;;
    csharp|csharp_*|csharp_dev|csharp_dev_*) printf 'csharp-dev' ;;
    kotlin|kotlin_*|kotlin_dev|kotlin_dev_*) printf 'kotlin-dev' ;;
    document|document_*|documenter|documenter_*) printf 'documenter' ;;
    refactor|refactor_*|refactorer|refactorer_*) printf 'refactorer' ;;
    *) return 1 ;;
  esac
}

# A subagent rollout begins with a timestamp-collapsed copy of the parent history. Restrict metrics to
# the final task_started/task_complete round so inherited work is never billed to the subagent. A
# resumed agent produces another capture for another final round, and finalize sums those rounds.
round_metrics() {
  local transcript="$1"
  jq -s '
    ([range(0; length) as $i
      | select(.[$i].type == "event_msg" and .[$i].payload.type == "task_started")
      | $i] | last // 0) as $start
    | .[$start:] as $lines
    | ([ $lines[]
         | select(.type == "event_msg" and .payload.type == "token_count")
         | .payload.info
         | select(.last_token_usage != null) ]
       | group_by(.total_token_usage.total_tokens)
       | map(last.last_token_usage.total_tokens // 0)
       | add // 0) as $tokens
    | ([ $lines[]
         | select(.type == "event_msg" and .payload.type == "task_complete")
         | .payload.duration_ms // 0 ] | last // 0) as $duration
    | {
        model: ([ $lines[]
                  | select(.type == "turn_context")
                  | .payload.model // empty ] | last // ""),
        tokens: $tokens,
        tool_uses: ([ $lines[]
                      | select(.type == "response_item")
                      | .payload.type // ""
                      | select(endswith("_call")) ] | length),
        wall_sec: ($duration / 1000 | round)
      }
  ' "$transcript"
}

# The parent transcript is not a fork, so its final cumulative usage and all tool calls represent the
# orchestrator. Codex input_tokens already includes cached input; adding cached_input_tokens again
# would double-count it.
all_metrics() {
  local transcript="$1"
  jq -s '
    ([ .[]
       | select(.type == "event_msg" and .payload.type == "token_count")
       | .payload.info.total_token_usage
       | select(. != null) ] | last // {}) as $usage
    | {
        model: ([ .[] | select(.type == "turn_context") | .payload.model // empty ] | last // ""),
        tokens: ($usage.total_tokens
                 // (($usage.input_tokens // 0) + ($usage.output_tokens // 0))),
        tool_uses: ([ .[]
                      | select(.type == "response_item")
                      | .payload.type // ""
                      | select(endswith("_call")) ] | length)
      }
  ' "$transcript"
}

cmd_capture() {
  local event transcript session agent_id agent_type agent_path role parent_transcript m

  event="$(cat)"
  transcript="$(printf '%s' "$event" | jq -r '.agent_transcript_path // ""')"
  [ -f "$transcript" ] || { printf '{}\n'; return; }

  agent_path="$(jq -sr '
    [ .[] | select(.type == "session_meta")
      | if .payload.agent_path then .payload.agent_path
        elif (.payload.source | type) == "object"
          then .payload.source.subagent.thread_spawn.agent_path // empty
        else empty
        end ]
    | last // ""
  ' "$transcript")"
  agent_type="$(printf '%s' "$event" | jq -r '.agent_type // ""')"
  role="$(role_for "$agent_path" 2>/dev/null || role_for "$agent_type" 2>/dev/null || true)"
  [ -n "$role" ] || { printf '{}\n'; return; }

  session="$(printf '%s' "$event" | jq -r '.session_id // ""')"
  agent_id="$(printf '%s' "$event" | jq -r '.agent_id // ""')"
  parent_transcript="$(printf '%s' "$event" | jq -r '.transcript_path // ""')"

  m="$(round_metrics "$transcript")"

  mkdir -p "$(dirname "$staging")"
  jq -nc \
    --arg session "$session" --arg agent "$role" --arg agent_id "$agent_id" \
    --arg transcript "$parent_transcript" --arg fallback_model "$agent_type" --argjson m "$m" \
    '{session: $session, agent: $agent, agent_id: $agent_id, transcript: $transcript,
      model: (if $m.model == "" then $fallback_model else $m.model end),
      tokens: $m.tokens, tool_uses: $m.tool_uses,
      wall_sec: $m.wall_sec}' >> "$staging"

  # SubagentStop requires JSON stdout even when the hook only observes the event.
  printf '{}\n'
}

cmd_finalize() {
  local feature="" phase="" direction="" notes="{}"

  while [ $# -gt 0 ]; do
    case "$1" in
      --feature) feature="$2"; shift 2 ;;
      --phase) phase="$2"; shift 2 ;;
      --direction) direction="$2"; shift 2 ;;
      --note) notes="$(jq -c --arg kv "$2" \
        '.[($kv | split("=")[0])] = ($kv | split("=")[1:] | join("="))' <<<"$notes")"
        shift 2 ;;
      *) echo "unknown flag: $1" >&2; exit 1 ;;
    esac
  done

  [ -n "$feature" ] || { echo "finalize needs --feature" >&2; exit 1; }
  [ -s "$staging" ] || {
    echo "nothing staged: no subagent of a Codex feature-design run was captured" >&2
    exit 1
  }

  local session parent_transcript orch skill_sha date
  session="$(jq -rs 'last | .session' "$staging")"
  parent_transcript="$(jq -rs --arg session "$session" \
    '[ .[] | select(.session == $session) | .transcript | select(. != "") ] | last // ""' "$staging")"
  if [ -f "$parent_transcript" ]; then
    orch="$(all_metrics "$parent_transcript")"
  else
    orch='{"model":"","tokens":0,"tool_uses":0}'
  fi

  # The Codex wrapper and the shared feature workflow jointly define the measured process.
  skill_sha="$(git -C "$root" log -1 --format=%h -- "$codex_skill" "$claude_skill" 2>/dev/null || echo "")"
  date="$(date +%F)"
  mkdir -p "$(dirname "$csv")"
  if [ ! -s "$csv" ]; then
    printf 'feature,agent,model,tool_uses,tokens,wall_min,date,phase,direction,skill_sha,notes\n' > "$csv"
  fi

  jq -rs \
    --arg feature "$feature" --arg phase "$phase" --arg direction "$direction" \
    --arg skill_sha "$skill_sha" --arg date "$date" --arg session "$session" \
    --argjson orch "$orch" --argjson notes "$notes" \
    --argjson roster "$(printf '%s\n' "${roster[@]}" | jq -R . | jq -sc .)" '
    def cell($n): if $n == null or $n == 0 then "" else ($n | tostring) end;
    def csv: map(if test("[\",\n]") then "\"" + gsub("\""; "\"\"") + "\"" else . end) | join(",");

    [ .[] | select(.session == $session) ] as $staged
    | ($roster
       | map(. as $role
             | ($staged | map(select(.agent == $role))) as $rows
             | {agent: $role,
                ran: ($rows | length > 0),
                model: ($rows | last | .model? // ""),
                tokens: ($rows | map(.tokens) | add // 0),
                tool_uses: ($rows | map(.tool_uses) | add // 0),
                wall_min: (($rows | map(.wall_sec) | add // 0) / 60 | round)})) as $agents
    | ($agents | map(select(.ran))) as $ran
    | {agent: "feature-design", model: $orch.model,
       tokens: (($ran | map(.tokens) | add // 0) + $orch.tokens),
       tool_uses: (($ran | map(.tool_uses) | add // 0) + $orch.tool_uses),
       wall_min: ($ran | map(.wall_min) | add // 0)} as $total
    | ("TOTAL: " + ($ran | length | tostring)
       + (if ($ran | length) == 1 then " subagent" else " subagents" end)
       + " + the orchestrator. tokens and tool_uses include the orchestrator; wall_min is summed "
       + "active subagent time only.") as $total_note
    | [($total + {note: ($total_note
                         + (if $notes["feature-design"] then " " + $notes["feature-design"] else "" end))})]
      + ($agents | map(. + {note: ($notes[.agent] // (if .ran then "" else "Did not run." end))}))
    | .[]
    | [$feature, .agent, .model, cell(.tool_uses), cell(.tokens), cell(.wall_min),
       $date, $phase, $direction, $skill_sha, .note]
    | csv
  ' "$staging" >> "$csv"

  : > "$staging"
  echo "Appended $(( ${#roster[@]} + 1 )) rows for \"${feature}\" to .codex/benchmark.csv"
}

case "${1:-}" in
  capture) shift; cmd_capture "$@" ;;
  finalize) shift; cmd_finalize "$@" ;;
  *) echo "usage: benchmark.sh capture
       benchmark.sh finalize --feature <name> --phase <n> --direction <forward|reverse> [--note <agent>=<text> ...]" >&2
     exit 1 ;;
esac
