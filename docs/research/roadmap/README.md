# ROADMAP research memos

One memo per ROADMAP item that has been researched ahead of implementation. A memo is the output of the feature-design skill's Step 1 (`research` agent) when the item is not being built right away, so the findings survive the session that produced them and the next run of the skill starts at Step 2 instead of Step 1.

The files one level up in `docs/research/` are project-level research (the original interop study, plugin architecture notes), a different kind of document; they are not memos and never close.

## Lifecycle

- **Created** by the `research` agent at the end of Step 1, named `docs/research/roadmap/<slug>.md` where the slug is the branch slug the item will use (`ir/<slug>`).
- **Read** at Step 0 of a later feature-design run: if a memo exists for the item, the main thread checks its date against the ROADMAP line and the ADRs it cites, then goes to Step 2 with it instead of re-dispatching research. A memo older than a change to any file it cites by `file:line` is stale: re-run research, do not trust the line numbers.
- **Deleted** by the `documenter` in Step 5 when the item closes, in the same commit that deletes the ROADMAP line. Its durable content by then lives in the ADR, FEATURES.md and the topic pages. A memo whose item is struck from the ROADMAP without shipping (settled as "not a bug", folded into a backlog file) is deleted in that same commit.

## Format

```markdown
# <ROADMAP item, one line>

- ROADMAP: line text as of <date> (quote the first sentence)
- Researched: <date>, budget used
- Restatement: what a consumer gets, direction, which side declares
- Verdict: fix | pin and close | strike | blocked, plus the ADR number if drafted (Proposed, in docs/adr/)

## Findings
Each with file:line and **verified** (by reading, by spike) or **inferred**.

## Recommendation
Narrowest option satisfying the restatement, priced in files touched. Alternatives rejected, one line each.

## Files an implementation touches
Used to group worktrees; list processor/plugin files, fixtures, tests, leak rows, docs.

## Sample test
The consumer-side test (xunit for forward, Tier 1 cell for generator-only, ProjectBuilder for plugin).

## Deferred scope

## Open what-questions
With the main thread's recommendation, and the human's decision once given.
```

No em-dashes anywhere in a memo.
