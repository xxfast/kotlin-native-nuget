# Feature Development

Implements a new feature using a 3-step TDD loop with subagents.

## Workflow

### Step 1: Research (no skills, plain agent)

- Investigate how the feature should work
- Research how Kotlin handles the same problem for Java interop, Swift Export, and ObjC Export
- Write an ADR if the decision is non-trivial
- Define the expected C# API for the consumer

### Step 2: Verify with the approach with humans

- Share 
  - Research findings and rationales
  - ADR (if any) 
  - Proposed API for
    - Sample app, library 
    - Sample tests
- Get feedback and iterate on the design before implementation
- This step is crucial to ensure we're building the right thing before writing code

### Step 3: Testing (csharp-dev agent, model: sonnet)

- Write failing C# tests that define the expected API
- Tests go in `sample-app/SampleApp.Tests/`
- Follow existing test patterns (xunit, `using var` for IDisposable)
- Add sample Kotlin source in `sample-library/` if needed

### Step 4: Implementation (kotlin-dev agent, model: sonnet)

- Make the failing tests pass
- Update the KSP processor (CirModel, CirTranslator, CirRenderer, CSharpBindingsProcessor)
- Verify all tests pass (existing + new)

## Rules

- Always write tests FIRST (step 2 before step 3)
- Subagents use `model: sonnet` (Sonnet 4.6)
- Research agents run in background when independent
- Implementation agents include the full context: what files to change, expected output, style rules
- After implementation, verify locally: `./gradlew :sample-library:clean :sample-library:packNuget && cd sample-app/SampleApp.Tests && dotnet test`
- Update README.md roadmap after feature is complete

## Prompting subagents

When delegating to subagents:
- Include the skill file content relevant to their role (csharp-dev.md or kotlin-dev.md)
- Describe what the expected C# API looks like (from the tests)
- List specific files to modify
- Include style rules inline (subagents don't see STYLE.md)
- Ask them to run the verify commands before reporting success
