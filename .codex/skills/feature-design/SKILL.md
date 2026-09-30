---
name: feature-design
description: Drive a Kotlin/Native-to-.NET bridge feature from research through consumer-side TDD, implementation, verification, and documentation. Use when adding or changing a bridge mapping, reverse NuGet binding, or Gradle plugin feature in this repository.
---

# Feature Design

Follow the repository's established feature workflow; keep `.claude` as its source of truth.

## Workflow

1. Read [AGENTS.md](../../../AGENTS.md), [GOALS.md](../../../GOALS.md), and [ROADMAP.md](../../../ROADMAP.md).
2. Read and follow the complete workflow in [`.claude/skills/feature-design/SKILL.md`](../../../.claude/skills/feature-design/SKILL.md).
3. Use the role briefs in [`.claude/agents/`](../../../.claude/agents/) when delegating research, C# tests, Kotlin implementation, refactoring, or documentation. Select the Codex model using the delegation guidance below.
4. Preserve the workflow's gates: research and human design review before implementation; consumer-side tests before implementation; `scripts/verify.sh` before the final refactor and documentation pass.

## Subagent models

Model pins checked on 2026-10-01 against the [official Codex model guidance](https://learn.chatgpt.com/docs/models#recommended-models) and this runtime's `spawn_agent` model list. Use `gpt-6.1-sol` for complex coding, and `gpt-6-luna` for focused refactoring and documentation. This workflow maps Claude `model: opus` to `gpt-6.1-sol` and `model: sonnet` to `gpt-6-luna`; this is a workflow choice, not an official equivalence between vendors. Research always inherits the parent model and reasoning effort. Reserve an explicit `gpt-6-astra` pin for a user request.

| Role (`agent_type`) | Current Claude brief | Codex `model` |
| --- | --- | --- |
| `research` | Unspecified | Inherit parent |
| `csharp-dev` | `opus` | `gpt-6.1-sol` |
| `kotlin-dev` | `opus` | `gpt-6.1-sol` |
| `refactorer` | `sonnet` | `gpt-6-luna` |
| `documenter` | `sonnet` | `gpt-6-luna` |

Read each role's current Claude brief before spawning; for roles other than research, if its model declaration changes, apply the mapping above rather than trusting the table's snapshot. For a new declaration, resolve the mapping before delegating.

For research, omit `model` and `reasoning_effort`, and use `fork_turns: "all"` to inherit the parent settings and conversation context. For other roles, pass the selected ID explicitly in `spawn_agent.model`; `agent_type` selects the role, not the model. In this runtime, set `fork_turns: "none"` and provide the task, repository or worktree path, relevant file paths, accepted design, budget, and verification requirements in `message`. Full-history forks (`fork_turns: "all"`, also the default) inherit the parent model and do not accept model overrides. A numeric history fork is also supported when recent conversation context is needed. Leave `reasoning_effort` inherited unless the task calls for an explicit setting.

Check the session's available model list before dispatching a pinned role. If a pin is unavailable, report the unavailable model and resolve a replacement with the user before spawning that role. Do not silently replace a pin with parent inheritance. Continue warm agents with `followup_task` rather than spawning a replacement.

## Scope

Do not duplicate or modify the `.claude` skill or agent definitions while using this wrapper. Update this Codex skill only when the Codex-specific invocation or delegation guidance changes.
