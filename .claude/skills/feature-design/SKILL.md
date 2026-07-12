---
name: feature-design
description: Implements a new bridge feature end to end using a TDD loop. Orchestrates the research, csharp-dev, kotlin-dev, refactorer and documenter agents from the main thread. Research first, verify the approach with humans, prove the path is passable with a walking skeleton, write failing tests on the consumer side of the feature, make them pass, then style-check and document in parallel.
---

# Feature Design

Implements a new feature using a TDD loop, delegating each step to the appropriate subagent.

You run in the main conversation thread, so you can spawn subagents and pause to check in with the human. Delegate to the appropriate agent for each step (research, testing, implementation, refactor, docs) and provide them the necessary context and instructions.

## Phase kickoff (batching the human gate)

Many phases (Phases 9–13 especially) are largely reverse-direction work that mirrors an already-decided forward ADR. Do not run the full per-feature loop (research, human gate, implement) one interruption at a time for every item. At the **start of a phase**, classify its roadmap items and batch the human gate:

- **"mirror" items**: annotated "mirror of ADR-XXX" in ROADMAP.md; the reverse mapping exactly mirrors an existing forward ADR.
  - Skip the heavy research step and the up-front Step 2 human gate. Run a **light** research pass to confirm the mirror actually applies (per the `research` agent's own "don't write an ADR" guidance), then go straight to Step 3 tests. The human reviews the **implemented result** rather than the plan.
  - If the mirror turns out **not** to hold cleanly, stop and escalate to the normal Step 2 check-in.
- **"needs-ADR" items**: no clean forward mirror, a real design decision.
  - Fan out one **background** `research` agent per item, in parallel.
  - Collect all their draft ADRs/findings and present them to the human in a **single Step 2 review sitting**, instead of one interruption per feature.

Then run the per-feature Step 3–6 loop below for each item.

## Workflow

### Step 1: Research (`research` agent)

- Delegate to the `research` agent
- Investigate how the feature should work
- Research how Kotlin handles the same problem for Java interop, Swift Export, and ObjC Export
- Write an [ADR](../../../docs/adr) if the decision is non-trivial
- Define the expected API for the consumer: C# for forward features, Kotlin for reverse (Phase 8) and Gradle plugin features

### Step 2: Verify the approach with humans

- Share
  - Research findings and rationales
  - ADR (if any)
  - Proposed API for
    - Sample app, library
    - Sample tests
- Get feedback and iterate on the design before implementation
- Call out any deferred scope and ask if we want to schedule this on the roadmap
- This step is crucial to ensure we're building the right thing before writing code
- Once the human agrees with the approach, move to the next step (the ADR is accepted later, in Step 6, once the feature is implemented and verified)

### Step 3: Walking skeleton (end-to-end spike)

Drive the **thinnest possible slice** of the feature (one member, one type, one assertion) all the way through `scripts/verify.sh` before anyone writes the full fixture or the full test suite.

- **Non-negotiable for reverse / ecosystem features (Phase 8+)**, where the round trip spans four artifacts: metadata reader → Kotlin stub gen → C# shim gen → NuGet packaging. Optional for a forward feature that only touches the KSP processor.
- Its first job is to prove the path is **passable**. Verify is where reality intrudes; a latent bug in that path should surface in the first 20 minutes against a two-line fixture, not after a 14-test fixture and both generator sides are already written.
- Its second job is to **validate the ADR's inferred mechanism claims**. The `research` agent cannot execute anything, so every claim about what a real assembly or toolchain does at runtime is labelled inferred (see [research](../../agents/research.md)). Check each one against the real artefact here, before an implementing agent follows it literally.
- Delegate to `kotlin-dev` + `csharp-dev` and keep them warm, they carry into Step 4. If the skeleton does not go green, fix or split (see Rules) before writing any more tests.

### Step 4: Testing

Write failing tests on the **consumer side** of the feature. Which side that is depends on the feature:

- **Forward bridge feature (Kotlin → C#)**: `csharp-dev` agent
  - Write failing C# tests that define the expected API
  - Tests go in `sample-app/SampleApp.Tests/`
  - Follow existing test patterns (xunit, `using var` for IDisposable)
  - Add sample Kotlin source in `sample-library/` if needed
- **Reverse / ecosystem feature (C# → Kotlin, Phase 8+)**: `kotlin-dev` + `csharp-dev` agents
  - There is **no runnable Kotlin-side unit test of the reverse bridge**: the generated Kotlin stubs fail fast unless the .NET host process has registered the function-pointer table (ADR-041/048), so Kotlin/Native code cannot exercise the reverse bridge standalone. Do not write "failing Kotlin tests that define the expected Kotlin-side API", there is nowhere for them to run. Use the two real test seams instead:
    - **Fast inner loop (where the TDD happens)**: `kotlin-dev` agent
      - Write failing generator-level unit tests in `nuget-plugin/src/test/kotlin`: a `reverse-ir.json` fixture in, expected Kotlin stub text and/or C# shim text out. Precedents: `NugetGenerateBindingsTaskTest`, `NugetGenerateShimsTaskTest`.
    - **Outer loop (end-to-end round trip)**: `csharp-dev` agent
      - Extend `sample-dependency/` (the standing C# fixture library, bound via the ADR-050 local feed) with the feature's fixture surface, a feature-scoped namespace/type kept inside the ADR-043 bridgeable subset. Do not hunt for a published package that fits the subset.
      - Add the ADR-050 round trip: sample-library Kotlin calls the bound `SampleDependency` API, is surfaced forward to C#, and is asserted in `SampleApp.Tests` xunit tests.
- **Gradle plugin feature (DSL, tasks, wiring)**: `kotlin-dev` agent
  - Write failing `ProjectBuilder` unit tests in `nuget-plugin/src/test/kotlin` that apply the plugin, configure the DSL as a consumer would, and assert the extension model / task wiring
  - Defer Gradle TestKit functional tests until there is task behavior that ProjectBuilder cannot reach

### Step 5: Implementation (`kotlin-dev` agent)

- Make the failing tests pass
- Update the KSP processor (CirModel, CirTranslator, CirRenderer, NugetProcessor) or the Gradle plugin (`nuget-plugin/`), whichever the feature lives in
- Verify all tests pass (existing + new)
- The loop iterates: tests fail, fix, re-run. Continue the same `kotlin-dev` instance via SendMessage rather than spawning a fresh agent each round. Same for `csharp-dev` if the tests themselves need adjusting.
- Ask `kotlin-dev` to report back the list of files it touched, you will hand that to the refactorer.

### Step 6: Style review and docs (last step, run in parallel)

Once the feature is implemented and verified, spawn both agents **in the same message**. They cannot
collide: the `refactorer` only touches Kotlin, the `documenter` only touches Markdown.

- **`refactorer` agent**: do not scan the changed files yourself. Hand it `kotlin-dev`'s reported list of touched files and let it judge against [STYLE.md](../../../STYLE.md) and fix any violations in one pass. It reports back the files it changed (or "no violations") plus the test result.
- **`documenter` agent**: hand it the feature, the ADR (if any), the ROADMAP item it completes, and the sample/test files that exercise it. It owns every doc surface:
  - the Writerside pages in `docs/topics/`: the mapping table, the snippets, and (most easily missed) deleting the now-false line from the page's **Limitations** section
  - ROADMAP.md: tick the completed item, link its ADR
  - FEATURES.md: add or amend the mapping row in its feature category, ADR link in the ADRs column (skip if the feature adds no bridge mapping, e.g. pure plugin/DSL work). The catalogue is bidirectional: each row carries a **direction** glyph (`→` Kotlin → C#, `←` C# → Kotlin, `⇄` both). For a reverse feature, flip an existing row's glyph toward `⇄` (or add a `←` row) and use the Notes to capture any asymmetry (`→ … · ← …`) when the directions diverge
  - mark the relevant ADR as `Accepted`

The `documenter` writes every snippet from code that compiles (`sample-library/`, the generated
`Interop.cs`, the generated reverse output under `build/nuget-interop/`, `SampleApp.Tests/`), so run
this step only after the feature is verified and the build artefacts are current.

## Rules

- You must delegate to the appropriate subagent
- Research agents run in background when independent
- After research is complete, share findings with humans for feedback before proceeding to implementation
- Walking skeleton before fixtures (step 3 before step 4), and tests before implementation (step 4 before step 5)
- Pass agents file paths and intent, not file contents. They have Read and know the project layout, so let them read what they need.
- Reuse warm agents (SendMessage) when iterating instead of spawning fresh ones
- After implementation, verify locally by running `scripts/verify.sh` (add `--plugin` for Gradle plugin changes to also run the `nuget-plugin/` plugin unit tests). The script also purges the stale `~/.nuget/packages/samplelibrary` cache, a known footgun where a re-pack silently resolves against the old package.
- **No manual path around `verify.sh`.** Never hand-edit generated output, hand-patch a generated shim, or copy files into `~/.nuget/packages/` to iterate faster. It manufactures bugs that look real, and you then pay to debug something that does not exist.
- **Instrument before hypothesizing.** When the round trip fails, get evidence about what actually executes (is registration even firing? what does the generated artefact really contain?) before forming a theory. Never spend a long agent round bisecting a hypothesis that a cheap probe could have falsified in minutes.
- **Split pre-existing bugs out.** A bug the fixture uncovers but the feature did not cause gets reported and split into its own commit/ticket immediately, it does not silently expand the feature's scope. A fixture that is the first to exercise some combination (first two bound classes in one namespace, first nullable-returning export that throws) *should* be expected to flush out latent bugs. That means the existing fixtures were unrealistic, it is not a distraction.
- **No orphaned builds.** Agents must not leave a Gradle build running in the background: it holds the project lock and silently starves every agent behind it. When an agent goes quiet, check `ps` and `./gradlew --status` for an orphaned build before assuming the agent is stuck or thinking.
- Step 6 is the last step, run only after the feature is verified. Spawn `refactorer` and `documenter` in parallel, in one message: the first owns the Kotlin, the second owns every doc surface (Writerside pages in `docs/topics/`, ROADMAP.md, FEATURES.md, ADR status).
- Never write the docs yourself. The `documenter` grounds every snippet in the real generated output; writing them from the main thread means writing them from memory.

## Prompting subagents

When delegating to subagents:
- Describe what the expected consumer-side API looks like (from the tests)
- List specific file paths to modify and let the agent read them, do not paste file contents
- Ask them to run the verify commands before reporting success
- Ask them to report the files they changed and the test result, not full diffs
- When continuing a warm agent, send only the new instruction (e.g. the failing test output)
