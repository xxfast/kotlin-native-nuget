---
name: feature-design
description: Implements a new bridge feature end to end using a TDD loop. Orchestrates the research, csharp-dev, kotlin-dev, refactorer and documenter agents from the main thread. Research first, verify the approach with humans, write failing tests on the consumer side of the feature, make them pass, then document and style-check.
---

# Feature Design

Implements a new feature using a TDD loop, delegating each step to the appropriate subagent.

You run in the main conversation thread, so you can spawn subagents and pause to check in with the human. Delegate to the appropriate agent for each step (research, testing, implementation, refactor, docs) and provide them the necessary context and instructions.

## Step 0: Validate the item before you design anything (hard gate, every run)

No later step can catch a wrong-item run: from Step 1 on, every artefact is derived from the design, so tests, code and docs all agree with each other while disagreeing with the request. Do this before any research agent, any ADR, and any design question.

**0. Bind the agent roster.** `ls .claude/agents/` and confirm the five agents this skill dispatches to (`research`, `csharp-dev`, `kotlin-dev`, `refactorer`, `documenter`). If a file is present the agent exists, call it, regardless of whether the session's injected "Available agent types" list mentions it: that list has been observed to omit `csharp-dev` and `documenter`. Never substitute a different agent for one you think is missing, and never absorb the C# or docs work into the main thread because the roster "didn't list it".

**1. Restate the item from its own words, with every linked ADR still closed.** One sentence: what does a consumer get? For a bridge feature the restatement must name the **direction** (Kotlin → C# forward, or C# → Kotlin reverse) and **which side declares** the type or contract versus merely consumes it. A title alone reads either way once an ADR has primed you.

**2. Read the item's neighbours and its section heading.** `git log -S"<the item text>" -- ROADMAP.md` shows what arrived alongside it; items added together disambiguate each other. **If the section's stated direction contradicts your restatement, stop and ask.**

**3. Check the item is reachable.** Does it need machinery from a later phase, or a sibling item still unticked? If so it is **blocked**: stop, and report what it needs, which item or phase owns that, and what you could do instead. Never build the nearest reachable thing against a blocked item's checkbox.

**4. If an ADR is linked, verify its scope matches your restatement.** An ADR is evidence about *how*, never authority over *what*. If its subject and your restatement differ, **stop and escalate** with both side by side. Do not reconcile it yourself, and do not adopt the ADR's framing for being more detailed or more confident.

**5. State the restatement back to the human before Step 1**, with reachability. This is the only point where the request and the plan are compared directly.

Carry the restatement forward verbatim: Step 2 is judged against it, and Step 5 may tick the item only if what shipped matches it. Work worth doing that is **not** this item gets its own item, and the requested one stays open.

## Phase kickoff (batching the human gate)

Many phases (Phases 9–13 especially) are largely reverse-direction work that mirrors an already-decided forward ADR. Do not run the full per-feature loop (research, human gate, implement) one interruption at a time for every item. At the **start of a phase**, classify its roadmap items and batch the human gate:

- **"mirror" items**: annotated "mirror of ADR-XXX" in ROADMAP.md; the reverse mapping exactly mirrors an existing forward ADR.
  - Skip the heavy research step and the up-front Step 2 human gate. Run a **light** research pass to confirm the mirror actually applies (per the `research` agent's own "don't write an ADR" guidance), then go straight to Step 3 tests. The human reviews the **implemented result** rather than the plan.
  - If the mirror turns out **not** to hold cleanly, stop and escalate to the normal Step 2 check-in.
- **"needs-ADR" items**: no clean forward mirror, a real design decision.
  - Fan out one **background** `research` agent per item, in parallel.
  - Collect all their draft ADRs/findings and present them to the human in a **single Step 2 review sitting**, instead of one interruption per feature.

Then run the per-feature Step 3–5 loop below for each item.

## Workflow

### Step 1: Research (`research` agent)

- Delegate to the `research` agent
- Investigate how the feature should work
- Research how Kotlin handles the same problem for Java interop, Swift Export, and ObjC Export
- Write an [ADR](../../../docs/adr) if the decision is non-trivial
- Define the expected API for the consumer: C# for forward features, Kotlin for reverse (Phase 8) and Gradle plugin features

**Budget: 20 minutes.** Put it in the prompt. Tell the agent to run `date` when it starts and to check it again before opening any new expensive line of investigation, not only at the end. At the deadline it stops and reports what it has, naming every question it did not resolve, rather than quietly continuing. A partial ADR you can read at Step 2 beats a complete one that lands fifteen minutes later, because Step 2 is a human gate either way and unresolved questions are exactly what that gate is for.

**The budget is a checkpoint, not a ceiling.** Some features genuinely deserve more, and the agent is the one best placed to know. Tell it that if it hits 20 minutes with a load-bearing question still open, it should **ask for an extension** in its report rather than either guessing or padding out what it has. The ask has to be specific enough to decide on: what is unresolved, what it would spend the extra time doing, and what goes wrong downstream if it proceeds without the answer. "I'd like more time" is not an ask.

The agent cannot reach the human itself, it has no `AskUserQuestion`, so **you relay it**. Put its request to the human with your own read on whether it is worth granting, then resume the **same** agent with `SendMessage` rather than spawning a fresh one, so its context survives and the extension buys only the new work instead of paying for a cold restart.

Grant it for a mechanism claim that would silently produce wrong output if wrong. Decline it for more prior art, more alternatives, or more confirmation of something already decided. That is the same line item 1 below draws, applied at the checkpoint instead of at the start.

Spend the budget in this order. Left alone, research tends to spend it in reverse, because a prior-art sweep always has one more ecosystem to check while a spike has an end:

1. **The spike on the claim the design rests on.** This is what the budget is *for*. Ask of each mechanism claim: if this is wrong, does the implementation silently produce wrong output? Spike only the ones that answer yes. A wrong mechanism claim surfaces mid-implementation otherwise, at many times the cost, and this repo has [paid that](../../../CLAUDE.md) more than once.
2. **The repo's own constraints, read in source.** Cheap, fast, and always decision-relevant.
3. **Prior art, only to the depth that changes the decision.** One precedent that clearly settles it is worth more than four that agree. Stop at the first that decides the question; the rest is confirmation, and confirmation is what the budget gets cut from.

**Do not downsize the model to fit the budget.** `research` is the one step every later artefact is derived from, so a cheap wrong ADR is the most expensive thing this workflow can produce: tests, code and docs all end up agreeing with each other while disagreeing with reality, and nothing downstream can catch it. `.claude/agents/research.md` deliberately declares no `model:`, so it inherits the main thread's. Leave it that way. Cut scope, not capability.

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
- **Lead with the Step 0 restatement and say plainly whether the design still satisfies it.** Research routinely reshapes a feature, and the reshaped version can drift off the item without anybody noticing, because from here on every artefact is derived from the design rather than from the request. Put the restatement and what you are about to build next to each other and confirm they match.
- **Ask at least one *what* question, not only *how* questions.** A gate made entirely of implementation choices ("which plan model?", "which handle-extraction strategy?") reads as thorough and validates nothing about scope: every option builds the same possibly-wrong feature. If you have no *what* question, say explicitly "this still delivers <restatement>" so the claim is on the record and the human can contradict it.
- Once the human agrees with the approach, move to the next step (the ADR is accepted later, in Step 5, once the feature is implemented and verified)

### Step 3: Testing

Write failing tests on the **consumer side** of the feature. Which side that is depends on the feature:

- **Forward bridge feature (Kotlin → C#)**: `csharp-dev` agent
  - Write failing C# tests that define the expected API
  - Tests go in `IntegrationTests/`
  - Follow existing test patterns (xunit, `using var` for IDisposable)
  - Add sample Kotlin source in `test-library/` if needed
- **Reverse / ecosystem feature (C# → Kotlin, Phase 8+)**: `kotlin-dev` + `csharp-dev` agents
  - There is **no runnable Kotlin-side unit test of the reverse bridge**: the generated Kotlin stubs fail fast unless the .NET host process has registered the function-pointer table (ADR-041/048), so Kotlin/Native code cannot exercise the reverse bridge standalone. Do not write "failing Kotlin tests that define the expected Kotlin-side API", there is nowhere for them to run. Use the two real test seams instead:
    - **Fast inner loop (where the TDD happens)**: `kotlin-dev` agent
      - Write failing generator-level unit tests in `nuget-plugin/src/test/kotlin`: a `reverse-ir.json` fixture in, expected Kotlin stub text and/or C# shim text out. Precedents: `NugetGenerateBindingsTaskTest`, `NugetGenerateShimsTaskTest`.
    - **Outer loop (end-to-end round trip)**: `csharp-dev` agent
      - Extend `TestDependency/` (the standing C# fixture library, bound via the ADR-050 local feed) with the feature's fixture surface, a feature-scoped namespace/type kept inside the ADR-043 bridgeable subset. Do not hunt for a published package that fits the subset.
      - Add the ADR-050 round trip: test-library Kotlin calls the bound `TestDependency` API, is surfaced forward to C#, and is asserted in `IntegrationTests` xunit tests.
- **Gradle plugin feature (DSL, tasks, wiring)**: `kotlin-dev` agent
  - Write failing `ProjectBuilder` unit tests in `nuget-plugin/src/test/kotlin` that apply the plugin, configure the DSL as a consumer would, and assert the extension model / task wiring
  - Defer Gradle TestKit functional tests until there is task behavior that ProjectBuilder cannot reach

### Step 4: Implementation (`kotlin-dev` agent)

- Make the failing tests pass
- Update the KSP processor or the Gradle plugin (`nuget-plugin/`), whichever the feature lives in. For a forward feature, an **ordinary synchronous** callable goes through the ADR-062 forward callable plan (`forward/`: classify into `BridgeType`, extend the planner, both halves project from the one plan); a **specialized protocol** (suspend, `Flow`, lambda/callback, sealed, generics) stays on its named legacy route in `exports/` + `cir/`, where you update both halves in the same change
- Verify all tests pass (existing + new)
- The loop iterates: tests fail, fix, re-run. Continue the same `kotlin-dev` instance via SendMessage rather than spawning a fresh agent each round. Same for `csharp-dev` if the tests themselves need adjusting.
- Ask `kotlin-dev` to report back the list of files it touched, you will hand that to the refactorer.

### Step 5: Docs and style review (last step, run serially)

Once the feature is implemented and verified, run the `documenter` **first**, then the `refactorer`. Do
**not** run them in parallel.

They look disjoint (the `refactorer` only touches Kotlin, the `documenter` only touches Markdown), but
file ownership is not the axis that matters. The `refactorer`'s verify begins with
`./gradlew :test-library:clean`, which **deletes `test-library/build/`**, and that directory is the
`documenter`'s entire evidence base: every snippet it writes is lifted from the generated `Interop.cs`
and the reverse output under `build/nuget-interop/`. Run them together and the `documenter` greps a
directory that is being deleted and rebuilt underneath it. It does not crash, it silently drops the
snippet it could not back with real code, and then it reaches for `./gradlew :test-library:packNuget`
to regenerate what the `refactorer` just removed, which queues on the Gradle project lock.

Running the `documenter` first avoids all of it: `build/` is already current from `kotlin-dev`'s passing
verify, so the `documenter` is pure read-only and never touches Gradle. The `refactorer` then cleans and
rebuilds with nobody reading behind it.

- **`documenter` agent** (first): hand it the feature, the ADR (if any), the ROADMAP item it completes, the sample/test files that exercise it, and **every bug the feature discovered but did not fix**. That last one is yours to pass on: the split-out bugs exist only in the implementing agents' reports, which the `documenter` cannot see, so if you do not forward them they are lost. Give it the symptom, the root cause and `file:line` if an agent established one, and whether it was actually verified. It owns every doc surface:
  - the Writerside pages in `docs/topics/`: the mapping table, the snippets, and (most easily missed) deleting the now-false line from the page's **Limitations** section
  - ROADMAP.md: tick the completed item, link its ADR. **Before instructing the tick, re-read the item's own text and confirm what shipped satisfies the Step 0 restatement.** You are the only one who can: the `documenter` never saw the original request and will tick whatever line you name. If the delivered work is good but is not this item, it gets its **own** item and the requested one stays open, with a note on it saying what does and does not cover it. Also confirm the item's sub-bullets say the same thing as its parent line, since a parent that contradicts its own children is a mislabel to escalate, not to tick around
  - FEATURES.md: add or amend the mapping row in its feature category, ADR link in the ADRs column (skip if the feature adds no bridge mapping, e.g. pure plugin/DSL work). The catalogue is bidirectional: each row carries a **direction** glyph (`→` Kotlin → C#, `←` C# → Kotlin, `⇄` both). For a reverse feature, flip an existing row's glyph toward `⇄` (or add a `←` row) and use the Notes to capture any asymmetry (`→ … · ← …`) when the directions diverge
  - mark the relevant ADR as `Accepted`
- **`refactorer` agent** (second, only once the `documenter` has reported back): do not scan the changed files yourself. Hand it `kotlin-dev`'s reported list of touched files and let it judge against [STYLE.md](../../../STYLE.md) and fix any violations in one pass. It reports back the files it changed (or "no violations") plus the test result.

The `documenter` writes every snippet from code that compiles (`test-library/`, the generated
`Interop.cs`, the generated reverse output under `build/nuget-interop/`, `IntegrationTests/`), so run
this step only after the feature is verified and the build artefacts are current.

## Rules

- You must delegate to the appropriate subagent
- **Build the item you were asked for, or say you are not.** Step 0's restatement is the contract; a linked ADR is evidence about how, never authority over what. If the item is blocked, or the ADR's scope does not match it, stop and tell the human before designing anything. Delivered work never consumes the checkbox of an item it does not satisfy. This is the one failure the rest of the workflow structurally cannot catch: from Step 1 onward every artefact is derived from the design, so tests, code and docs will all agree with each other while disagreeing with the request.
- Research agents run in background when independent
- After research is complete, share findings with humans for feedback before proceeding to implementation
- Tests before implementation (step 3 before step 4)
- **The fixture must cross every mechanism, not the fewest types.** Build it once, complete, before implementation starts. A fixture trimmed to the "simplest" type routinely picks the one type that needs no work at the seam the feature is actually about (an `int` struct component needs no marshalling conversion, so a generator that open-codes conversion passes anyway). That is worse than no fixture: it goes green, it is wrong, and the implementation gets shaped around the triviality. Pick the fixture that forces every seam to exist: one type that needs conversion, one that does not.
- Pass agents file paths and intent, not file contents. They have Read and know the project layout, so let them read what they need.
- Reuse warm agents (SendMessage) when iterating instead of spawning fresh ones
- After implementation, verify locally by running `scripts/verify.sh` (add `--plugin` for Gradle plugin changes to also run the `nuget-plugin/` plugin unit tests). Fixture packages mint a fresh `1.0.0-fixture.<epoch-ms>` version on every pack, so a re-pack can no longer silently resolve against the old package. The script still wipes consumer `obj`/`bin` to keep the run self-contained.
- **No manual path around `verify.sh`.** Never hand-edit generated output, hand-patch a generated shim, or copy files into `~/.nuget/packages/` to iterate faster. It manufactures bugs that look real, and you then pay to debug something that does not exist.
- **Instrument before hypothesizing.** When the round trip fails, get evidence about what actually executes (is registration even firing? what does the generated artefact really contain?) before forming a theory. Never spend a long agent round bisecting a hypothesis that a cheap probe could have falsified in minutes.
- **Split pre-existing bugs out.** A bug the fixture uncovers but the feature did not cause gets reported and split into its own commit/ticket immediately, it does not silently expand the feature's scope. A fixture that is the first to exercise some combination (first two bound classes in one namespace, first nullable-returning export that throws) *should* be expected to flush out latent bugs. That means the existing fixtures were unrealistic, it is not a distraction. **Track every one you split out and hand the list to the `documenter` in Step 5**, which records them in ROADMAP.md. Splitting a bug out and then forgetting it is worse than never finding it: the next feature pays to rediscover it.
- **No orphaned builds.** Agents must not leave a Gradle build running in the background: it holds the project lock and silently starves every agent behind it. When an agent goes quiet, check `ps` and `./gradlew --status` for an orphaned build before assuming the agent is stuck or thinking.
- Step 5 is the last step, run only after the feature is verified. Run `documenter` first, then `refactorer`, **serially**. The `refactorer`'s verify starts with `:test-library:clean`, which deletes the `build/` output the `documenter` reads its snippets from; in parallel they race and the docs quietly lose snippets.
- **Two agents that both drive Gradle cannot run in parallel in this repo.** One takes the project lock and the other queues silently. Parallel fan-out is safe only for agents that touch neither Gradle nor `build/` (e.g. several `research` agents at phase kickoff).
- Never write the docs yourself. The `documenter` grounds every snippet in the real generated output; writing them from the main thread means writing them from memory.

## Prompting subagents

When delegating to subagents:
- Describe what the expected consumer-side API looks like (from the tests)
- List specific file paths to modify and let the agent read them, do not paste file contents
- Ask them to run the verify commands before reporting success
- Ask them to report the files they changed and the test result, not full diffs
- When continuing a warm agent, send only the new instruction (e.g. the failing test output)
