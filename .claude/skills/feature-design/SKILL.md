---
name: feature-design
description: Implements a new bridge feature end to end using a TDD loop. Orchestrates the research, csharp-dev, kotlin-dev, refactorer and documenter agents from the main thread. Research first, verify the approach with humans, write failing tests on the consumer side of the feature, make them pass, then document and style-check.
---

# Feature Design

A TDD loop run from the main thread: research, human gate, failing tests, implementation, docs and style. Delegate each step to its agent with the context it needs.

## Step 0: Validate the item before you design anything (hard gate, every run)

No later step can catch a wrong-item run: from Step 1 on every artefact is derived from the design, so tests, code and docs all agree with each other while disagreeing with the request.

0. **Bind the agent roster.** `ls .claude/agents/` and confirm the five agents (`research`, `csharp-dev`, `kotlin-dev`, `refactorer`, `documenter`). A present file means the agent exists, even when the session's "Available agent types" list omits it (observed for `csharp-dev` and `documenter`). Never substitute or absorb the work into the main thread.
1. **Restate the item from its own words, with every linked ADR still closed.** One sentence: what does a consumer get? Name the **direction** (Kotlin → C# forward, C# → Kotlin reverse) and **which side declares** the type or contract versus merely consumes it.
2. **Read the item's neighbours and section heading.** `git log -S"<item text>" -- ROADMAP.md` shows what arrived alongside it. If the section's stated direction contradicts your restatement, stop and ask.
3. **Check the item is reachable.** If it needs a later phase or an unticked sibling it is **blocked**: report what it needs and stop. Never build the nearest reachable thing against a blocked item's checkbox.
4. **If an ADR is linked, verify its scope matches the restatement.** An ADR is evidence about *how*, never authority over *what*. On mismatch, stop and escalate with both side by side; do not adopt the ADR's framing for being more detailed.
5. **State the restatement back to the human before Step 1**, with reachability.

Carry the restatement forward verbatim: Step 2 is judged against it, and Step 5 may tick the item only if what shipped matches it.

## Phase kickoff (batching the human gate)

At the start of a phase (Phases 9–13 especially), classify its items instead of running one full loop per item:

- **"mirror" items** (annotated "mirror of ADR-XXX"): skip heavy research and the up-front Step 2 gate. Run a light research pass to confirm the mirror applies, then go straight to Step 3; the human reviews the implemented result. If the mirror does not hold cleanly, escalate to the normal Step 2 check-in.
- **"needs-ADR" items**: fan out one **background** `research` agent per item in parallel, then present all findings in a single Step 2 sitting.

## Budgets

A budget is a checkpoint, not a ceiling, and goes **in the agent's prompt**: run `date` at start and re-check it before each new expensive line of work. At the deadline the agent stops and reports, naming every unresolved question, rather than continuing quietly; if a load-bearing question is open it asks for an extension with a specific reason (what is unresolved, what the time buys, what breaks without it). Agents have no `AskUserQuestion`, so **you relay the ask** with your own recommendation, then resume the **same** agent via `SendMessage`. Grant extensions for a mechanism claim that would silently produce wrong output if wrong; decline them for more prior art or confirmation.

## Model selection

No agent file pins a `model:`; you choose per dispatch (the Agent tool's `model` parameter, omit to inherit the main-thread model). `research` always inherits: see Step 1. `kotlin-dev` inherits for real features; downgrade only for mechanical mirror items. `documenter` and `refactorer` usually run downgraded fine. Whether a larger model converges in fewer steps is unmeasured (the ADR-076 implementation ran on sonnet: 202 steps, 50 minutes), so record each agent's model and duration in your report.

## Workflow

### Step 1: Research (`research` agent). Budget: 20 minutes

- Investigate how the feature should work; check how Kotlin handles the same problem for Java interop, Swift Export and ObjC Export
- Write an [ADR](../../../docs/adr) if the decision is non-trivial
- Define the expected consumer API: C# for forward features, Kotlin for reverse (Phase 8) and Gradle plugin features

Spend the budget in this order (left alone, research spends it in reverse, because a prior-art sweep always has one more ecosystem while a spike has an end):

1. **Spike the claim the design rests on.** For each mechanism claim ask: if this is wrong, does the implementation silently produce wrong output? Spike the ones that answer yes, only those; a wrong one otherwise surfaces mid-implementation at many times the cost ([CLAUDE.md](../../../CLAUDE.md) records several).
2. **The repo's own constraints, read in source.** Cheap and always decision-relevant.
3. **Prior art, only to the depth that changes the decision.** Stop at the first precedent that settles it; the rest is confirmation, and confirmation is what the budget gets cut from.

**Never downsize `research` to fit the budget.** A cheap wrong ADR is the most expensive artefact this workflow can produce, because everything downstream derives from it. Cut scope, not capability.

### Step 2: Verify the approach with humans

- Share the research findings, the ADR (if any), and the proposed consumer API with sample tests; iterate before implementation
- **Lead with the Step 0 restatement and say plainly whether the design still satisfies it.** Research reshapes features; put the restatement and what you are about to build side by side.
- **Ask at least one *what* question, not only *how* questions.** A gate made only of implementation choices validates nothing about scope. If you have no *what* question, state "this still delivers <restatement>" so the human can contradict it.
- **At a scope fork, recommend the narrowest option that satisfies the restatement** (see Rules). A design that grew during research still gets the narrow recommendation, and "the deferred half shares a code path" never justifies pulling in an adjacent capability.
- Call out deferred scope and ask whether to schedule it on the roadmap
- The ADR is accepted later, in Step 5, once the feature is implemented and verified

### Step 3: Testing (failing tests on the consumer side)

- **Forward feature (Kotlin → C#)**: `csharp-dev` writes failing xunit tests in `IntegrationTests/` that define the expected API (follow existing patterns, `using var` for IDisposable), adding Kotlin sample source in `test-library/` if needed.
- **Reverse / ecosystem feature (C# → Kotlin, Phase 8+)**: there is **no runnable Kotlin-side unit test of the reverse bridge**; the generated stubs fail fast unless the .NET host has registered the function-pointer table (ADR-041/048). Use the two real seams:
  - **Fast inner loop** (`kotlin-dev`, where the TDD happens): failing generator-level tests in `nuget-plugin/src/test/kotlin`, a `reverse-ir.json` fixture in, expected Kotlin stub / C# shim text out. Precedents: `NugetGenerateBindingsTaskTest`, `NugetGenerateShimsTaskTest`.
  - **Outer loop** (`csharp-dev`): extend `TestDependency/` (the standing ADR-050 local-feed fixture) inside the ADR-043 bridgeable subset, then assert the ADR-050 round trip in `IntegrationTests` xunit tests. Do not hunt for a published package that fits the subset.
- **Gradle plugin feature (DSL, tasks, wiring)**: `kotlin-dev` writes failing `ProjectBuilder` tests in `nuget-plugin/src/test/kotlin` that apply the plugin and assert the extension model / task wiring. Defer TestKit functional tests until there is behavior `ProjectBuilder` cannot reach.

### Step 4: Implementation (`kotlin-dev` agent). Budget: 25 minutes

- Make the failing tests pass. A forward **ordinary synchronous** callable goes through the ADR-062 forward callable plan (`forward/`: classify into `BridgeType`, extend the planner, both halves project from the one plan); a **specialized protocol** (suspend, `Flow`, lambda/callback, sealed, generics) stays on its named legacy route in `exports/` + `cir/`, both halves updated in the same change.
- Iterate warm: continue the same `kotlin-dev` via `SendMessage` each round rather than spawning fresh (same for `csharp-dev` if the tests need adjusting).
- **Compile first, but a green compile does not mean "all sites found".** Make the `BridgeType` change, run `:nuget-processor:compileKotlin` (25 seconds), and work the exhaustiveness errors; put that in the prompt. The ADR-076 run instead spent 110 of 202 tool calls on `grep`/`find`/`Read`. But measured with a dummy variant, the compiler flags only 5 sites in 3 files against the 17 files ADR-076 touched: 16 `else ->` branches over `BridgeType` swallow a new variant, so the emitters and projections never error. Build, fix the error list, then search for the `else`-swallowed sites, and let the failing tests arbitrate completeness. The durable fix is code, its own ROADMAP item: make those `else ->`s explicit so the compiler *can* enumerate a variant.
- An implementation still grinding at 50 minutes has stopped converging.
- Have it report the **diff** it produced (`git diff --stat` plus file list), which you hand to the refactorer.

### Step 5: Docs and style (last step; `documenter` first, then `refactorer`, never parallel)

The `refactorer`'s verify starts with `:test-library:clean`, which deletes the `build/` output the `documenter` lifts every snippet from; in parallel the docs silently lose snippets. After `kotlin-dev`'s green verify `build/` is current, so the `documenter` runs first, purely read-only, then the `refactorer` cleans and rebuilds with nobody reading behind it.

- **`documenter`**: hand it the feature, the ADR, the ROADMAP item, the sample/test files, and **every bug split out but not fixed** (they exist only in the implementing agents' reports; if you do not forward them they are lost). Give symptom, root cause, `file:line`, and whether verified. It owns every doc surface: the Writerside pages in `docs/topics/` (mapping table, snippets, and deleting the now-false **Limitations** line), the ROADMAP tick with ADR link, the FEATURES.md row with its direction glyph (`→` forward, `←` reverse, `⇄` both, Notes for asymmetry), and marking the ADR `Accepted`. **Before instructing the tick, re-read the item's text against the Step 0 restatement**: the `documenter` never saw the request and will tick whatever line you name. Delivered-but-different work gets its own item, and a parent line that contradicts its sub-bullets is a mislabel to escalate, not tick around.
- **`refactorer`**: budget 10 minutes. Hand it the **diff** (`git diff main...HEAD`), never a path list, judging only diff-touched lines against [STYLE.md](../../../STYLE.md). Measured: 16 paths cost 15 minutes and a fifth of the feature's output tokens to wrap ten lines, because a path list makes it read large pre-existing files in full. It reports the files it changed (or "no violations") plus the test result.

## Rules

- Delegate every step to its subagent; never absorb the work into the main thread.
- **Build the item you were asked for, or say you are not.** The Step 0 restatement is the contract. If the item is blocked or an ADR's scope mismatches it, stop and tell the human before designing anything. Delivered work never consumes the checkbox of an item it does not satisfy.
- **The restatement is a ceiling as well as a floor.** Every other rule here pushes toward completeness; this one stops you shipping four times the right thing. At any scope fork put **Recommended** on the narrowest option that satisfies the restatement and price both in concrete terms ("two `when` branches" against "most of an ADR"). Sharing a code path is not sharing a request. ADR-075 began as a two-branch crash fix and shipped as four subsystems, widened twice on the assistant's own recommendation.
- **Cost scales with surface touched, not build time.** `scripts/verify.sh` is 38s clean-room, 18s warm (ROADMAP; a `--fast` mode was rejected, do not re-add it), so build time is never why a feature took long. What costs is agent tokens, which scale with files read and written: ADR-075 ran six agents for ~97 minutes and 115k output tokens (~3M with cache writes) for a change whose reported bug needed three files. Price a proposed widening in files touched, and **measure before asserting any timing claim**. Corollary: a 25-second build is the cheapest experiment in this repo, so run builds to learn things (the "5 errors in 3 files" figure above was measured that way).
- **Check ROADMAP.md before proposing an item**, including as a *rejected* one; re-proposing a settled decision wastes the human's time.
- Tests before implementation (Step 3 before Step 4); research findings go to the human before implementation.
- **The fixture crosses every seam the feature actually crosses, and no more.** Build it once, complete, before implementation. The "simplest" type is routinely the one needing no work at the seam under test (an `int` component needs no conversion), so the fixture goes green while wrong: include one type that needs conversion and one that does not. But speculation in a fixture is a commitment: one "nobody has checked that shape" cell in ADR-075 pulled an unsupported subsystem into the change. Unknown adjacent territory is a ROADMAP item, not a fixture cell.
- Pass agents file paths and intent, not file contents; reuse warm agents via `SendMessage` when iterating.
- After implementation run `scripts/verify.sh` (add `--plugin` for Gradle plugin changes). Fixture packs mint a fresh immutable version on every pack, so a re-pack cannot silently resolve against the old package.
- CLAUDE.md's rules bind every agent here too, no restating needed: no manual path around `verify.sh`, instrument before hypothesizing, no stale-artifact debugging.
- **Split pre-existing bugs out** into their own commit/ticket; a first-of-its-kind fixture flushing latent bugs means the old fixtures were unrealistic. Track every one and hand the list to the `documenter` in Step 5; a split-out bug forgotten is worse than never found.
- **Two Gradle-driving agents never run in parallel**: one takes the project lock, the other queues silently. Parallel fan-out is safe only for agents touching neither Gradle nor `build/` (e.g. `research` at phase kickoff). When an agent goes quiet, check for an orphaned build (CLAUDE.md) before assuming it is thinking.
- Never write the docs yourself; the `documenter` grounds every snippet in real generated output, the main thread would write them from memory.

## Prompting subagents

- Describe what the expected consumer-side API looks like (from the tests)
- List specific file paths to modify and let the agent read them; do not paste file contents
- Ask them to run the verify commands before reporting success
- Ask them to report the files they changed and the test result, not full diffs
- When continuing a warm agent, send only the new instruction (e.g. the failing test output)
