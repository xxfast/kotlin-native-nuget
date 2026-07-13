---
name: feature-design
description: Drive a Kotlin/Native-to-.NET bridge feature from research through consumer-side TDD, implementation, verification, and documentation. Use when adding or changing a bridge mapping, reverse NuGet binding, or Gradle plugin feature in this repository.
---

# Feature Design

Follow the repository's established feature workflow; keep `.claude` as its source of truth.

## Workflow

1. Read [AGENTS.md](../../../AGENTS.md), [GOALS.md](../../../GOALS.md), and [ROADMAP.md](../../../ROADMAP.md).
2. Read and follow the complete workflow in [`.claude/skills/feature-design/SKILL.md`](../../../.claude/skills/feature-design/SKILL.md).
3. Use the role briefs in [`.claude/agents/`](../../../.claude/agents/) when delegating research, C# tests, Kotlin implementation, refactoring, or documentation. Map every Claude agent declared with `model: sonnet` to a Codex `terra` subagent. This currently applies to `csharp-dev`, `kotlin-dev`, `refactorer`, and `documenter`.
4. Preserve the workflow's gates: research and human design review before implementation; consumer-side tests before implementation; `scripts/verify.sh` before the final refactor and documentation pass.
5. Give every spawned agent a task name prefixed by its benchmark role: `research_`, `csharp_`, `kotlin_`, `document_`, or `refactor_`. The Codex hook uses that prefix because `agent_type` identifies the shared agent profile, not the workflow role.
6. When the shared workflow says to finalize `.claude/benchmark.csv`, use the Codex equivalent instead:

   ```bash
   bash .codex/hooks/benchmark.sh finalize \
     --feature "<roadmap item or ADR title>" \
     --phase "<roadmap phase>" \
     --direction "<forward|reverse>" \
     --note "<agent>=<what affected this agent's result>"
   ```

   `.codex/hooks.json` captures each `SubagentStop` automatically. Do not call `capture` manually and do not write benchmark figures by hand.

## Scope

Do not duplicate or modify the `.claude` skill or agent definitions while using this wrapper. Update this Codex skill only when the Codex-specific invocation or delegation guidance changes.
