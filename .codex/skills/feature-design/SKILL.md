---
name: feature-design
description: Drive a Kotlin/Native-to-.NET bridge feature from research through consumer-side TDD, implementation, verification, and documentation. Use when adding or changing a bridge mapping, reverse NuGet binding, or Gradle plugin feature in this repository.
---

# Feature Design

Follow the repository's established feature workflow; keep `.claude` as its source of truth.

## Workflow

1. Read [AGENTS.md](../../../AGENTS.md), [GOALS.md](../../../GOALS.md), and [ROADMAP.md](../../../ROADMAP.md).
2. Read and follow the complete workflow in [`.claude/skills/feature-design/SKILL.md`](../../../.claude/skills/feature-design/SKILL.md).
3. Use the role briefs in [`.claude/agents/`](../../../.claude/agents/) when delegating research, C# tests, Kotlin implementation, refactoring, or documentation.
4. Preserve the workflow's gates: research and human design review before implementation; consumer-side tests before implementation; `scripts/verify.sh` before the final refactor and documentation pass.

## Scope

Do not duplicate or modify the `.claude` skill or agent definitions while using this wrapper. Update this Codex skill only when the Codex-specific invocation or delegation guidance changes.
