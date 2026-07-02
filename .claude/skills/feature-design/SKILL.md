---
name: feature-design
description: Implements a new bridge feature end to end using a 3-step TDD loop. Orchestrates the research, csharp-dev, kotlin-dev, and refactorer agents from the main thread — research first, verify the approach with humans, write failing tests on the consumer side of the feature, make them pass, then style-check.
---

# Feature Design

Implements a new feature using a 3-step TDD loop, delegating each step to the appropriate subagent.

You run in the main conversation thread, so you can spawn subagents and pause to check in with the human. Delegate to the appropriate agent for each step (research, testing, implementation, refactor) and provide them the necessary context and instructions.

## Workflow

### Step 1: Research (`research` agent)

- Delegate to the `research` agent
- Investigate how the feature should work
- Research how Kotlin handles the same problem for Java interop, Swift Export, and ObjC Export
- Write an [ADR](../../../docs/adr) if the decision is non-trivial
- Define the expected API for the consumer — C# for forward features, Kotlin for reverse (Phase 8) and Gradle plugin features

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

### Step 3: Testing

Write failing tests on the **consumer side** of the feature. Which side that is depends on the feature:

- **Forward bridge feature (Kotlin → C#)** — `csharp-dev` agent
  - Write failing C# tests that define the expected API
  - Tests go in `sample-app/SampleApp.Tests/`
  - Follow existing test patterns (xunit, `using var` for IDisposable)
  - Add sample Kotlin source in `sample-library/` if needed
- **Reverse / ecosystem feature (C# → Kotlin, Phase 8)** — `kotlin-dev` agent
  - Write failing Kotlin tests that define the expected Kotlin-side API for consuming the C# surface
  - Add sample C# source on the .NET side if needed (`csharp-dev` can help)
- **Gradle plugin feature (DSL, tasks, wiring)** — `kotlin-dev` agent
  - Write failing `ProjectBuilder` unit tests in `nuget/src/test/kotlin` that apply the plugin, configure the DSL as a consumer would, and assert the extension model / task wiring
  - Defer Gradle TestKit functional tests until there is task behavior that ProjectBuilder cannot reach

### Step 4: Implementation (`kotlin-dev` agent)

- Make the failing tests pass
- Update the KSP processor (CirModel, CirTranslator, CirRenderer, NugetProcessor) or the Gradle plugin (`nuget/`), whichever the feature lives in
- Verify all tests pass (existing + new)
- The loop iterates: tests fail, fix, re-run. Continue the same `kotlin-dev` instance via SendMessage rather than spawning a fresh agent each round. Same for `csharp-dev` if the tests themselves need adjusting.
- Ask `kotlin-dev` to report back the list of files it touched, you will hand that to the refactorer.

### Step 5: Style review (`refactorer` agent)

- Do not scan the changed files yourself. Hand `kotlin-dev`'s reported list of touched files to the `refactorer` agent and let it judge against [STYLE.md](../../../STYLE.md) and fix any violations in one pass.
- The refactorer reports back the files it changed (or "no violations") plus the test result. If it reports no violations, you are done.

### Step 6: Finalize docs (last step)

Once the feature is implemented and verified, update the docs in the same pass:

- ROADMAP.md — tick the completed item (and link its ADR)
- FEATURES.md — add or amend the mapping row for the newly supported construct, with the ADR link (skip if the feature adds no bridge mapping, e.g. pure plugin/DSL work)
- Mark the relevant ADR as `Accepted`

## Rules

- You must delegate to the appropriate subagent
- Research agents run in background when independent
- After research is complete, share findings with humans for feedback before proceeding to implementation
- Run subagent to write tests FIRST (step 3 before step 4)
- Pass agents file paths and intent, not file contents. They have Read and know the project layout, so let them read what they need.
- Reuse warm agents (SendMessage) when iterating instead of spawning fresh ones
- After implementation, verify locally: `./gradlew :sample-library:clean :sample-library:packNuget && cd sample-app/SampleApp.Tests && dotnet test`. For Gradle plugin features, also run the plugin unit tests (`./gradlew` `test` in `nuget/`)
- Step 6 is the last step, run only after the feature is verified:
  - ROADMAP.md — tick the completed item (and link its ADR)
  - FEATURES.md — add or amend the mapping row for the newly supported Kotlin construct, with the ADR link
  - Mark the relevant ADR as `Accepted`

## Prompting subagents

When delegating to subagents:
- Describe what the expected consumer-side API looks like (from the tests)
- List specific file paths to modify and let the agent read them, do not paste file contents
- Ask them to run the verify commands before reporting success
- Ask them to report the files they changed and the test result, not full diffs
- When continuing a warm agent, send only the new instruction (e.g. the failing test output)
