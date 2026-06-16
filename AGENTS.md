# Agent Instructions

This file describes common issues and pain points that agents might encounter when they work in this project. 

If you ever encounter an issue specific to agents in the project, please update this file to help prevent future agents from having the same issue.

## Understand the Project Goals

Read [GOALS.md](GOALS.md) before making design decisions.

## Roadmap

Read [ROADMAP.md](ROADMAP.md) to understand the planned features and priorities for the project. This will help you align your work with the overall direction of the project and avoid working on features that are not currently a priority.

## Skills

- Read [.claude/skills/feature-dev/SKILL.md](.claude/skills/feature-dev/SKILL.md) for the feature development workflow                             
- Read [.claude/skills/kotlin-dev/SKILL.md](.claude/skills/kotlin-dev/SKILL.md) for Kotlin implementation guidance                                         
- Read [.claude/skills/csharp-dev/SKILL.md](.claude/skills/csharp-dev/SKILL.md) for C# test development guidance
- Read [.claude/skills/refactor-dev/SKILL.md](.claude/skills/refactor-dev/SKILL.md) for style cleanup and refactoring

## Follow Standard Coding Conventions

- For Kotlin, follow standard coding conventions as described here https://kotlinlang.org/docs/coding-conventions.html
- For C#, follow the standard coding conventions as described here https://learn.microsoft.com/en-us/dotnet/csharp/fundamentals/coding-style/coding-conventions

On top of that, we have some additional conventions that are specific to this repository here [STYLE.md](STYLE.md)

## Follow Repository Coding Conventions

- Naming is hard. Use shorten names when applicable and rely on the type to do the heavy lifting.
  - e.g:- `id: SomeId` instead of `someId: SomeId` 
  - e.g:- `fun get(id: SomeId): Something` instead of `fun getSomethingById(someId: SomeId): Something`
  - e.g:- `val something: List<Something>` instead of `val somethingList: List<Something>`
- Deter using scoping functions that introduce indentation (e.g:- `apply`, `with`, `run`)
- Prefer using `if` statements over `when` statements with just two branches
- When handling error states from `Result`, avoid using scoping functions (such as `.onFailure`) that introduce indentation. 
  - Instead, use explicit `if (result.isFailure)` checks with proper logging and error handling.

## Stay In Scope

- Always ensure that your changes are aligned with the scope of the JIRA ticket you are working on.
- Avoid making unrelated changes or improvements that are not directly related to the ticket, as this can make code reviews more difficult and can introduce unintended side effects.
- Don't touch unrelated code or files that are not necessary for the implementation of the ticket - no matter how small and easy it may look

## Fail Fast & Follow Defensive Programming

- Use `require` / `requireNotNull` / `check` with explicit messages to fail fast when preconditions are not met.
- Avoid silent failures or returning null without explanation.
- When catching exceptions,
  - Be specific about the exception types you catch; 
    - Avoid catching `Exception` or `Throwable` unless absolutely necessary.
    - Document why you are catching a broad exception if you must do so.
  - Log the error with sufficient context 
  - Rethrow if it cannot be handled gracefully.
