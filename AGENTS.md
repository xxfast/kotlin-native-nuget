# Agent Instructions

This file describes common issues and pain points that agents might encounter when they work in this project. 

If you ever encounter an issue specific to agents in the project, please update this file to help prevent future agents from having the same issue.

## Understand the Project Goals

Read [GOALS.md](GOALS.md) before making design decisions.

## Roadmap

Read [ROADMAP.md](ROADMAP.md) to understand the planned features and priorities for the project. This will help you align your work with the overall direction of the project and avoid working on features that are not currently a priority.

## Feature workflow

- Run the [feature-design skill](.claude/skills/feature-design/SKILL.md) to drive the end-to-end TDD feature workflow. It orchestrates the agents below from the main thread.

## Agents

- Delegate to the [research agent](.claude/agents/research.md) to research how a feature should map across the bridge: Kotlin → C# (forward) or C# → Kotlin (reverse, NuGet consumption)
- Delegate to the [csharp-dev agent](.claude/agents/csharp-dev.md) for C# test development
- Delegate to the [kotlin-dev agent](.claude/agents/kotlin-dev.md) for Kotlin implementation
- Delegate to the [refactorer agent](.claude/agents/refactorer.md) for style cleanup and refactoring
- Delegate to the [documenter agent](.claude/agents/documenter.md) to document a shipped feature: the Writerside pages in `docs/topics/`, ROADMAP.md, FEATURES.md, ADR status. Runs in parallel with the refactorer (Markdown vs Kotlin, no file overlap)

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

## Keep the C# Test Project Cross-Platform

- `sample-app/SampleApp.Tests/SampleApp.Tests.csproj` pins a `<RuntimeIdentifier>` so MSBuild copies the matching `runtimes/{rid}/native/*` asset next to the test host (a RID-less framework-dependent build does not, causing `DllNotFoundException` on the `[DllImport]` P/Invoke).
- Do **not** hardcode a specific RID (e.g. `win-x64`). Use `$(NETCoreSdkRuntimeIdentifier)` so it resolves to the host platform. CI runs on `macos-latest` (`osx-arm64`); a hardcoded `win-x64` builds fine but aborts at `dotnet test` with `Could not find 'dotnet' host for the 'X64' architecture`.
- When verifying locally, remember NuGet caches `SampleLibrary` by version (`~/.nuget/packages/samplelibrary/1.0.0`). After re-running `:sample-library:packNuget`, clear that cache before `dotnet build`, or the stale package will surface as missing types (`Cat`, `IPet`, ...).
- The same staleness bites `SampleDependency`, and earlier: `:sample-library:packNuget` runs `nugetExtractApi`, which resolves `sample-dependency` from `~/.nuget/packages/sampledependency/1.0.0` and feeds its DLL to the metadata reader. A reverse feature that changes `sample-dependency`'s API (e.g. making a constructor `public`) keeps version `1.0.0`, so the stale cached DLL regenerates the reverse bindings against the *old* API and `sample-library` fails to compile. Purge `~/.nuget/packages/sampledependency` **before** packing, not just before `dotnet test`. `scripts/verify.sh` does this in the right order; a manual `packNuget` does not.

## Fail Fast & Follow Defensive Programming

- Use `require` / `requireNotNull` / `check` with explicit messages to fail fast when preconditions are not met.
- Avoid silent failures or returning null without explanation.
- When catching exceptions,
  - Be specific about the exception types you catch; 
    - Avoid catching `Exception` or `Throwable` unless absolutely necessary.
    - Document why you are catching a broad exception if you must do so.
  - Log the error with sufficient context 
  - Rethrow if it cannot be handled gracefully.
