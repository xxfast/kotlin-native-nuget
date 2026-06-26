---
description: Implements a new feature using a 3-step TDD loop with subagents
---

# Feature Development

Implements a new feature using a 3-step TDD loop with subagents.

You must delegate to the appropriate subagent for each step (research, testing, implementation, refactor) 

Provide them with the necessary context and instructions.

## Workflow

### Step 1: Research (research skill subagent, model: sonnet)

- Delegate to the [research skill](../research/SKILL.md)
- Investigate how the feature should work
- Research how Kotlin handles the same problem for Java interop, Swift Export, and ObjC Export
- Write an [ADR](../../../docs/adr) if the decision is non-trivial
- Define the expected C# API for the consumer

### Step 2: Verify with the approach with humans

- Share 
  - Research findings and rationales
  - ADR (if any) 
  - Proposed API for
    - Sample app, library 
    - Sample tests
- Get feedback and iterate on the design before implementation
- Call out any deferred scope and ask if we want to schedule this on the roadmap
- This step is crucial to ensure we're building the right thing before writing code

### Step 3: Testing (csharp-dev skill subagent, model: sonnet)

- Write failing C# tests that define the expected API
- Tests go in `sample-app/SampleApp.Tests/`
- Follow existing test patterns (xunit, `using var` for IDisposable)
- Add sample Kotlin source in `sample-library/` if needed

### Step 4: Implementation (kotlin-dev agent, model: sonnet)

- Make the failing tests pass
- Update the KSP processor (CirModel, CirTranslator, CirRenderer, NugetProcessor)
- Verify all tests pass (existing + new)

### Checkpoint: Style review

- Scan all files created or modified in step 4 against [STYLE.md](../../../STYLE.md)
- If even one violation is found, delegate to the [refactor-dev skill](../refactor-dev/SKILL.md) with the list of files
- If no violations, report success immediately without refactor step

## Step 5: Refactor (Optional, if determined by previous step) (refactor-dev skill subagent, model: sonnet)

- Refactor the specified files to fix style violations
- Verify all tests still pass after refactor

## Rules

- You must delegate to the appropriate subagent
- Subagents use `model: sonnet` (Sonnet 4.6)
- Research agents run in background when independent
- After research is complete, share findings with humans for feedback before proceeding to implementation
- Run subagent to write tests FIRST (step 3 before step 4)
- Implementation agents include the full context: what files to change, expected output, style rules
- After implementation, verify locally: `./gradlew :sample-library:clean :sample-library:packNuget && cd sample-app/SampleApp.Tests && dotnet test`
- Update the roadmap in ROADMAP.md after feature is complete

## Prompting subagents

When delegating to subagents:
- Include the skill file content relevant to their role (csharp-dev.md or kotlin-dev.md)
- Describe what the expected C# API looks like (from the tests)
- List specific files to modify
- Include style rules inline (subagents don't see STYLE.md)
- Ask them to run the verify commands before reporting success
