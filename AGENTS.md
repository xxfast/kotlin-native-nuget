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
- **Never hand-copy files into `~/.nuget/packages/`**, and never hand-patch a generated shim, to skip a rebuild. Agents have done both to iterate faster and it backfires: MSBuild resolves the `<Compile>` item set from the restored package at restore time, so a hand-edited cache produces symptoms that look exactly like real compiler or generator bugs, and you burn hours chasing a bug that does not exist. Always go through `scripts/verify.sh`.

## Don't Leave Gradle Builds Running

- An agent can finish its task and report success while leaving a `./gradlew` run alive in the background. It holds the project lock, so every build behind it queues silently. Nothing errors, things just go quiet.
- If a build seems to hang, or an agent has gone quiet for a long time, check `./gradlew --status` for a busy daemon and `ps` for a stray `gradlew` / `GradleWorkerMain` before assuming the agent is stuck or thinking. Kill the orphan.
- Don't background a Gradle build unless you wait for it and stop it before reporting back.

## Don't Trust Build Artifacts as Evidence

- A generated file, a packaged `.nupkg`, a compiled `.dll`, a `project.assets.json`: none of these are evidence of what the *source* does. They are evidence of what some earlier build did. In the ADR-053 feature, **two of the four "bugs" found were phantoms of stale build state**, and hours went into debugging code that was already correct.
- Staleness has two layers here, and purging one is not enough. `~/.nuget/packages/{samplelibrary,sampledependency}` is the first. The consumers' own `obj/project.assets.json` is the second: `dotnet build` reports "All projects are up-to-date for restore" and skips re-resolving the `<Compile>` item set entirely, because NuGet's restore-skip check hashes the project and lock file and cannot see that a version-pinned (`1.0.0`) package's *contents* changed underneath it. `scripts/verify.sh` now clears both. Nothing else does.
- Before you conclude "the generator emits the wrong thing" or "the compiler is omitting my code", rebuild clean and re-check. If a finding cannot survive `scripts/verify.sh` from a purged state, it is not a finding.

## Instrument Before Hypothesizing

- When something fails at the bridge, get evidence about what actually executes before forming a theory. A plausible story about freshly-changed code is not evidence, and chasing one cost a 45-minute agent round that ended with every file byte-identical to where it started.
- Ask the cheap decisive question first. "Did registration fire at all?" is one command. "Is my new storage class subtly wrong?" is a bisect. Do not spend the bisect until the cheap question is answered.
- The reverse bridge is currently unobservable (no way to see which registrations landed), which is exactly why guessing is tempting. That is tracked in [ROADMAP.md](ROADMAP.md) under Tooling & Test Integrity. Until it lands, instrument by hand rather than by intuition.

## Label Design-Doc Claims: Verified or Inferred

- The `research` agent cannot execute anything. Any claim it makes about what a real assembly, compiler or toolchain *actually does* at runtime (metadata encodings, attribute shapes, handle kinds, marshalling behaviour) is **inferred from documentation** unless repo code proves it, and the ADR must say so.
- This is not pedantry. ADR-053 asserted that `NullableAttribute`'s constructor is always a `MethodDefinitionHandle` (true only when the attribute is compiler-synthesized; on net8.0 it ships in the BCL and is a `MemberReferenceHandle`). An implementing agent followed it literally, silently decoded every annotation as oblivious, and had to debug its way back out.
- If you are implementing against an ADR and a mechanism claim does not match reality, **the ADR is wrong**. Say so, fix it, and do not bend the code to match the doc.

## Fail Fast & Follow Defensive Programming

- Use `require` / `requireNotNull` / `check` with explicit messages to fail fast when preconditions are not met.
- Avoid silent failures or returning null without explanation.
- When catching exceptions,
  - Be specific about the exception types you catch; 
    - Avoid catching `Exception` or `Throwable` unless absolutely necessary.
    - Document why you are catching a broad exception if you must do so.
  - Log the error with sufficient context 
  - Rethrow if it cannot be handled gracefully.
