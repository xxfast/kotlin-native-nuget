---
name: feature-design
description: Drive a Kotlin/Native-to-.NET bridge feature from research through consumer-side TDD, implementation, verification, and documentation. Use when adding or changing a bridge mapping, reverse NuGet binding, or Gradle plugin feature in this repository.
---

# Feature Design

Follow the repository's established feature workflow; keep `.claude` as its source of truth.

## Workflow

1. Read [AGENTS.md](../../../AGENTS.md), [GOALS.md](../../../GOALS.md), and [ROADMAP.md](../../../ROADMAP.md).
2. Read and follow the complete workflow in [`.claude/skills/feature-design/SKILL.md`](../../../.claude/skills/feature-design/SKILL.md).
3. Delegate with `subagent_type` set to the role name: `research`, `csharp-dev`, `kotlin-dev`, `documenter`, `refactorer`.
   - Thin wrappers live in [`.grok/agents/`](../../agents/); each points at the full brief under [`.claude/agents/`](../../../.claude/agents/). Do not paste or duplicate those briefs.
   - Prefer the registered type over `general-purpose`. Description prefixes (`research_`, `csharp_`, …) are only needed if you must fall back to a shared profile.
   - All roles use `model: inherit` (session default, currently `grok-4.5`).
4. Preserve the workflow's gates: research and human design review before implementation; consumer-side tests before implementation; `scripts/verify.sh` before the final documentation and refactor pass.

## Scope

Do not duplicate or modify the `.claude` skill or agent definitions while using this wrapper. Update this Grok skill only when Grok-specific invocation or delegation guidance changes.
