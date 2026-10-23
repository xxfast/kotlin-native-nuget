# Phase 4 follow-up: handover (2026-10-23)

Goal: build every deferral the Phase 4 and Phase 7 stacks added. NO deferrals: no new `- [ ]` ROADMAP lines and no new named skips. Design calls go to the research agent, or take the recommended option; don't ask the user. Push each lane as a PR as soon as it's verified.

Read these first: `RULES.md` (binding for every agent), `LEDGER.md` (source of truth, newest lines at the bottom), `DECISIONS.md` (design rulings), `memos/`.

## Open PRs (18), all verified with `scripts/verify.sh` + NativeAOT

| Stack | Base | PRs bottom to top |
|---|---|---|
| #588 | `main` | #582 → #583 → #584 → #591 → #598 (`ir/flow-setters-and-hot-flow-handback`) |
| #589 | `main` | #585 → #586 → #587 → #590 → #599 (`ir/lambda-properties-every-owner`, C4, BREAKING) |
| #597 | `ir/flow-on-static-owners` (#586) | #592 → #593 → #594 → #595 → #596 → #600 → #601 → #602 (`ir/value-class-char-sealed-star`) |

Add a PR to a stack with `stack-add.sh <stack> <pr>`, one PR per call (see the `pr-stack` skill).

## In flight: WIP branches (red tests + partial green, uncommitted at hand-off)

| Branch | Lane | Base (parent of WIP commit) | PR target when done | State when stopped |
|---|---|---|---|---|
| `wip/a5-any-wire` | A5, `Any` wire, ADR-237 | `7c974e09` (#598) | on `ir/flow-setters-and-hot-flow-handback`, stack #588 | Core runtime and generator pieces written, not yet packed. Was adding the fail-fast catch to the `FreeManaged` thunk. Red notes: the A5 red report in LEDGER (Flow<*> renders `KotlinFlow<Any>`, CS0246, blocks the pack). |
| `wip/c5-returned-lambdas` | C5, returned lambdas as BCL delegates + callback result vocabulary, ADR-239 (BREAKING) | `2c75bb37` (#599) | on `ir/lambda-properties-every-owner`, stack #589 | Mid-implementation; was on the `suspend` lambda parameter's result. Scope: every owner, `Chanter` implementable, `Routine.Tumble.flip()` silent drop, stale `ClassExports.kt:257-261` text. |
| `wip/a6-every-receiver` | A6, extensions on every receiver, ADR-238 | `c406f9f5` (#601) | REBASE onto `1e8ef49a` (#602) first, then on `ir/value-class-char-sealed-star`, stack #597 | Mid-implementation; was admitting `IFeedable?` at an extension receiver only. Two generator bugs found: member extension loses its receiver; ADR-229 enum receiver `_async` passes the raw ordinal. |

To pick one up: check out the branch, run `git reset --soft HEAD~1` (back to uncommitted on the base), and finish it. One commit per PR, no `WIP:` subject.

## Per-lane loop (feature-design skill)

1. kotlin-dev makes it green, then `./gradlew :nuget-processor:test` and `verify-lane.sh <worktree>` print LANE VERIFY OK.
2. Snapshot `test-library/build/{generated,nuget-interop}` to `snap/<lane>` (snap/ is not in this branch, so regenerate it).
3. Run the documenter (ADR, topics, supported-features, ROADMAP) and the refactorer in parallel. The refactorer reruns `verify-lane.sh`.
4. `lane-commit.sh <wt> <branch> <subject>`, then `push-ref.sh`, then a general agent writes `pr/<n>.md` in the shape of `pr/14.md`, then `open-pr.sh <n> <head> <base>`, then `stack-add.sh`.
5. A BREAKING lane's PR carries a before/after table (see `pr/15.md`).

## After A5, C5, A6: sweep lane Z (last)

Re-add the held rows in `held/` (B4 wrapper, C4 leap, A4b whistle). Then build everything still listed under "lane Z" in LEDGER:
- `MutableStateFlow<List<T>?>`;
- a flow property on an enum member;
- `IWhistle` nullable and suspend lambda slots;
- `LeapAsync`;
- a generic value class at an `Any` position;
- an interface member returning `Sticker<*>`, erased through the A5 wire;
- the flaky `SealedArmCallbackPairTests.AddWatcher_InterfaceBridgePairOnADataObjectArm_ReceivesWake`.

Then:
1. grep ADRs 213 to 240 for "Deferred", "Declined" and named skips;
2. grep for leftover `#if` fences;
3. count added `- [ ]` lines in the stacks' ROADMAP diffs (must be 0);
4. once merged, run `/cleanup`.

## Windows notes

- Scripts here are bash and perl, so run them from Git Bash. They hard-code macOS paths (`/c/Users/isuru/Developer/KMP/...`, worktrees `kn-p4f-{a,b,c,d}`), so fix the paths first.
- `push-ref.sh` and `open-pr.sh` use `gh auth token --user xxfast`. On a single-account machine plain `gh` works; check with `gh auth status`.
- `verify-lane.sh` wraps `scripts/verify.sh`. NativeAOT on Windows is `scripts/verify-aot.ps1`. Keep the per-worktree `NUGET_PACKAGES` (CLAUDE.md, cache race).
- `featlint.pl` exists only because macOS awk can't run `scripts/verify-features.sh`. On Git Bash's gawk the real script should work.
- PowerShell gotchas are in CLAUDE.md: `-creplace`, quote dotted `-P` args, use `$LASTEXITCODE`.
- Never run `./gradlew --stop`. No em-dashes anywhere.
