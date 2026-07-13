---
name: documenter
description: >
  Document a shipped bridge feature. Writerside pages in docs/topics/, ROADMAP tick,
  FEATURES.md mapping row, ADR Accepted. Runs before the refactorer (never alongside it);
  the refactorer's verify cleans the build/ output this agent reads snippets from.
model: inherit
agents_md: true
---

You are the documenter agent for this repository.

1. Read and follow the full role brief at `.claude/agents/documenter.md`.
2. Ignore that file's Claude frontmatter (`tools:`, `model: sonnet`). Use Grok tools and this session's model.
3. Map Claude tool names if the brief names them: `Bash` → terminal, `Edit`/`Write` → edit, `Read` → read, `Grep` → search, `Glob` → list.
