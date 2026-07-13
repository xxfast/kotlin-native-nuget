#!/usr/bin/env bash
# Records what a Grok feature-design run cost, into .grok/benchmark.csv.
#
# Two entry points (same contract as .claude/hooks/benchmark.sh and .codex/hooks/benchmark.sh):
#
#   capture    A SubagentStop hook. Reads real figures from the child session on disk
#              (~/.grok/sessions/.../{signals,updates,events}.json*). The model is never asked
#              for a number.
#
#   finalize   Called once by the feature-design orchestrator when the run completes. Supplies
#              feature/phase/direction/notes, stamps skill_sha and date, totals the run, appends
#              to the CSV, and clears staging.
#
# Definitions:
#   tokens     sum of turn_completed usage.totalTokens in updates.jsonl
#              (falls back to inputTokens+outputTokens; then signals.contextTokensUsed)
#   tool_uses  signals.toolCallCount, else count of tool_completed in events.jsonl
#   wall_min   active turn wall only (turn_started → turn_ended in events.jsonl), rounded minutes
#   model      signals.primaryModelId, else summary.current_model_id, else last turn model
#
# Role identity: prefer agent_type / subagent_type when it matches the roster. Otherwise parse the
# spawn description / task name for research_*, csharp_*, kotlin_*, document_*, refactor_* prefixes
# (same convention as .codex). Ad-hoc explore/general-purpose runs are ignored.

set -euo pipefail

root="${GROK_BENCHMARK_ROOT:-}"
if [ -z "$root" ]; then root="${GROK_WORKSPACE_ROOT:-}"; fi
if [ -z "$root" ]; then root="${CLAUDE_PROJECT_DIR:-}"; fi
if [ -z "$root" ]; then root="$(git rev-parse --show-toplevel 2>/dev/null || true)"; fi
if [ -z "$root" ]; then root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"; fi

csv="${GROK_BENCHMARK_CSV:-${root}/.grok/benchmark.csv}"
staging="${GROK_BENCHMARK_STAGING:-${root}/.grok/benchmark-staging.jsonl}"
grok_skill="${root}/.grok/skills/feature-design/SKILL.md"
claude_skill="${root}/.claude/skills/feature-design/SKILL.md"
grok_home="${GROK_HOME:-${HOME}/.grok}"
roster=(research csharp-dev kotlin-dev documenter refactorer)

command -v jq >/dev/null || { echo "benchmark.sh needs jq" >&2; exit 1; }

role_for() {
  local value
  value="$(printf '%s' "$1" | tr '[:upper:]-' '[:lower:]_')"
  value="${value##*/}"
  # strip bracketed label prefixes like [research]
  value="$(printf '%s' "$value" | sed -E 's/^\[[^]]+\][[:space:]]*//')"
  case "$value" in
    research|research_*) printf 'research' ;;
    csharp|csharp_*|csharp_dev|csharp_dev_*) printf 'csharp-dev' ;;
    kotlin|kotlin_*|kotlin_dev|kotlin_dev_*) printf 'kotlin-dev' ;;
    document|document_*|documenter|documenter_*) printf 'documenter' ;;
    refactor|refactor_*|refactorer|refactorer_*) printf 'refactorer' ;;
    *) return 1 ;;
  esac
}

# Resolve a session directory from a path that may be the dir itself or a file inside it.
session_dir_from_path() {
  local path="$1"
  [ -n "$path" ] || return 1
  if [ -d "$path" ] && { [ -f "$path/signals.json" ] || [ -f "$path/updates.jsonl" ] || [ -f "$path/summary.json" ]; }; then
    printf '%s' "$path"
    return 0
  fi
  if [ -f "$path" ]; then
    local dir
    dir="$(dirname "$path")"
    if [ -f "$dir/signals.json" ] || [ -f "$dir/updates.jsonl" ] || [ -f "$dir/summary.json" ]; then
      printf '%s' "$dir"
      return 0
    fi
  fi
  return 1
}

# Find ~/.grok/sessions/*/<id> for a session or agent id.
find_session_dir() {
  local id="$1"
  [ -n "$id" ] || return 1
  local match
  match="$(find "$grok_home/sessions" -mindepth 2 -maxdepth 2 -type d -name "$id" 2>/dev/null | head -1)"
  [ -n "$match" ] || return 1
  printf '%s' "$match"
}

# Reduce one Grok session dir to {model, tokens, tool_uses, wall_sec}.
session_metrics() {
  local dir="$1"
  local updates="$dir/updates.jsonl"
  local events="$dir/events.jsonl"
  local signals="$dir/signals.json"
  local summary="$dir/summary.json"

  local model="" tokens=0 tool_uses=0 wall_sec=0

  if [ -f "$updates" ]; then
    local from_updates
    from_updates="$(jq -s '
      [ .[]
        | select(.params.update.sessionUpdate == "turn_completed")
        | .params.update.usage
        | select(. != null)
      ] as $turns
      | {
          tokens: ([ $turns[]
                     | .totalTokens
                       // ((.inputTokens // 0) + (.outputTokens // 0))
                   ] | add // 0),
          model: ([ $turns[]
                    | (.modelUsage | keys[]?) // empty
                  ] | last // "")
        }
    ' "$updates" 2>/dev/null || echo '{"tokens":0,"model":""}')"
    tokens="$(printf '%s' "$from_updates" | jq -r '.tokens // 0')"
    model="$(printf '%s' "$from_updates" | jq -r '.model // empty')"
  fi

  if [ -f "$signals" ]; then
    local from_signals
    from_signals="$(jq -c '{
      model: (.primaryModelId // .modelsUsed[0] // ""),
      tool_uses: (.toolCallCount // 0),
      wall_sec: (.sessionDurationSeconds // 0),
      tokens: (.contextTokensUsed // 0)
    }' "$signals" 2>/dev/null || echo '{}')"
    tool_uses="$(printf '%s' "$from_signals" | jq -r '.tool_uses // 0')"
    if [ -z "$model" ] || [ "$model" = "null" ]; then
      model="$(printf '%s' "$from_signals" | jq -r '.model // empty')"
    fi
    # Prefer turn-summed tokens; only fall back to signals if updates had nothing.
    if [ "${tokens:-0}" -eq 0 ]; then
      tokens="$(printf '%s' "$from_signals" | jq -r '.tokens // 0')"
    fi
  fi

  if [ -f "$events" ]; then
    if [ "${tool_uses:-0}" -eq 0 ]; then
      tool_uses="$(jq -s '[ .[] | select(.type == "tool_completed") ] | length' "$events" 2>/dev/null || echo 0)"
    fi
    # Active wall: sum turn_started → turn_ended spans (excludes idle between turns).
    wall_sec="$(jq -s '
      def ep:
        if (.ts | type) == "string" then
          (.ts | sub("\\.[0-9]+Z$"; "Z") | fromdateiso8601)
        else 0 end;
      [ .[] | select(.type == "turn_started" or .type == "turn_ended") ]
      | reduce .[] as $e ({open: null, total: 0};
          if $e.type == "turn_started" then .open = ($e | ep)
          elif $e.type == "turn_ended" and .open != null then
            .total += (($e | ep) - .open) | .open = null
          else . end)
      | .total
    ' "$events" 2>/dev/null || echo 0)"
  fi

  if [ -f "$summary" ]; then
    if [ -z "$model" ] || [ "$model" = "null" ]; then
      model="$(jq -r '.current_model_id // empty' "$summary" 2>/dev/null || true)"
    fi
    # Fallback wall from summary timestamps if events had no turns.
    if [ "${wall_sec:-0}" -eq 0 ]; then
      wall_sec="$(jq -r '
        def ep: sub("\\.[0-9]+Z$"; "Z") | fromdateiso8601;
        if (.created_at != null) and (.updated_at != null) then
          ((.updated_at | ep) - (.created_at | ep))
        else 0 end
      ' "$summary" 2>/dev/null || echo 0)"
    fi
  fi

  # Last-resort wall from signals if still zero.
  if [ "${wall_sec:-0}" -eq 0 ] && [ -f "$signals" ]; then
    wall_sec="$(jq -r '.sessionDurationSeconds // 0' "$signals" 2>/dev/null || echo 0)"
  fi

  jq -nc \
    --arg model "${model:-}" \
    --argjson tokens "${tokens:-0}" \
    --argjson tool_uses "${tool_uses:-0}" \
    --argjson wall_sec "${wall_sec:-0}" \
    '{model: $model, tokens: $tokens, tool_uses: $tool_uses, wall_sec: $wall_sec}'
}

cmd_capture() {
  local event session agent_id agent_type description role \
    transcript child_dir parent_dir m prev_wall wall_delta

  event="$(cat)"

  agent_type="$(printf '%s' "$event" | jq -r '
    .agent_type // .agentType // .subagent_type // .subagentType // empty
  ')"
  description="$(printf '%s' "$event" | jq -r '
    .description // .task // .agent_name // .agentName // empty
  ')"
  role="$(role_for "$agent_type" 2>/dev/null || role_for "$description" 2>/dev/null || true)"
  [ -n "$role" ] || exit 0

  session="$(printf '%s' "$event" | jq -r '
    .session_id // .sessionId // empty
  ')"
  [ -n "$session" ] || session="${GROK_SESSION_ID:-}"

  agent_id="$(printf '%s' "$event" | jq -r '
    .agent_id // .agentId // .subagent_id // .subagentId // empty
  ')"

  transcript="$(printf '%s' "$event" | jq -r '
    .agent_transcript_path // .agentTranscriptPath
    // .transcript_path // .transcriptPath
    // .session_dir // .sessionDir
    // empty
  ')"

  child_dir=""
  if child_dir="$(session_dir_from_path "$transcript" 2>/dev/null)"; then
    :
  elif [ -n "$agent_id" ] && child_dir="$(find_session_dir "$agent_id" 2>/dev/null)"; then
    :
  else
    # Last resort: parent session only (metrics would double-count orchestrator; skip).
    exit 0
  fi

  parent_dir=""
  if [ -n "$session" ]; then
    parent_dir="$(find_session_dir "$session" 2>/dev/null || true)"
  fi
  [ -n "$parent_dir" ] || parent_dir="$(dirname "$child_dir")"

  m="$(session_metrics "$child_dir")"

  # Resumed agent: bill only the new active-wall delta; tokens/tools stay cumulative (finalize takes last).
  prev_wall=0
  if [ -f "$staging" ] && [ -n "$agent_id" ]; then
    prev_wall="$(jq -s --arg id "$agent_id" \
      '[ .[] | select(.agent_id == $id) | .cumulative_wall_sec // 0 ] | max // 0' "$staging" 2>/dev/null || echo 0)"
  fi
  wall_delta="$(jq -n --argjson cur "$(printf '%s' "$m" | jq '.wall_sec')" --argjson prev "$prev_wall" \
    'if $cur > $prev then $cur - $prev else $cur end')"

  mkdir -p "$(dirname "$staging")"
  jq -nc \
    --arg session "$session" --arg agent "$role" --arg agent_id "$agent_id" \
    --arg child_dir "$child_dir" --arg parent_dir "$parent_dir" --argjson m "$m" \
    --argjson wall_sec "$wall_delta" \
    '{session: $session, agent: $agent, agent_id: $agent_id,
      child_dir: $child_dir, parent_dir: $parent_dir,
      model: $m.model, tokens: $m.tokens, tool_uses: $m.tool_uses,
      wall_sec: $wall_sec, cumulative_wall_sec: $m.wall_sec}' >> "$staging"
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
    echo "nothing staged: no subagent of a Grok feature-design run was captured" >&2
    exit 1
  }

  local session parent_dir orch skill_sha date
  session="$(jq -rs 'last | .session' "$staging")"
  parent_dir="$(jq -rs --arg session "$session" \
    '[ .[] | select(.session == $session) | .parent_dir | select(. != "") ] | last // ""' "$staging")"

  if [ -d "$parent_dir" ]; then
    orch="$(session_metrics "$parent_dir")"
  else
    orch='{"model":"","tokens":0,"tool_uses":0,"wall_sec":0}'
  fi

  # Wrapper + shared workflow jointly define the measured process.
  skill_sha="$(git -C "$root" log -1 --format=%h -- "$grok_skill" "$claude_skill" 2>/dev/null || echo "")"
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
                # resumed agents are cumulative: tokens/tools from last capture per agent_id
                tokens: ($rows | group_by(.agent_id) | map(last | .tokens) | add // 0),
                tool_uses: ($rows | group_by(.agent_id) | map(last | .tool_uses) | add // 0),
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
  echo "Appended $(( ${#roster[@]} + 1 )) rows for \"${feature}\" to ${csv#"$root"/}"
}

case "${1:-}" in
  capture) shift; cmd_capture "$@" ;;
  finalize) shift; cmd_finalize "$@" ;;
  *) echo "usage: benchmark.sh capture
       benchmark.sh finalize --feature <name> --phase <n> --direction <forward|reverse> [--note <agent>=<text> ...]" >&2
     exit 1 ;;
esac
