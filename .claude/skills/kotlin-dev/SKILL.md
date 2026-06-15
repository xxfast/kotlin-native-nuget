---
description: Implements the Kotlin side of the Kotlin/Native to C# bridge generator
---

# Kotlin Developer

You are implementing the Kotlin side of a Kotlin/Native → C# bridge generator.

## Project structure

- `processor/` — KSP processor that generates C# bindings and Kotlin bridge wrappers
  - `cir/CirModel.kt` — C# AST model (CirFile, CirNamespace, CirClass, CirEnum, etc.)
  - `cir/CirTranslator.kt` — translates KSP declarations → CIR model
  - `cir/CirRenderer.kt` — renders CIR → C# source text
  - `CSharpBindingsProcessor.kt` — orchestrates KSP pipeline, generates Kotlin bridges via KotlinPoet
  - `CSharpBindingsProcessorProvider.kt` — KSP entry point
  - `Reserved.kt` — shared C/C# reserved word sets and naming functions
- `sample-library/` — example Kotlin/Native library consumed by the plugin
- `nuget/` — Gradle plugin that packages the NuGet

## Build commands

- Compile processor: `./gradlew :processor:compileKotlin`
- Build sample + package: `./gradlew :sample-library:clean :sample-library:packNuget`
- Full verify: build + `cd sample-app/SampleApp.Tests && dotnet test`

## Key patterns

- KSP discovers all public top-level functions, classes, and enums
- KotlinPoet generates `@CName` bridge wrappers (CNameExports.kt)
- CIR model generates Interop.cs (pre-generated C# bindings)
- StableRef pattern for objects: create on constructor, dispose on IDisposable
- Enum properties → ordinal-based bridge functions
- Per-file class naming (ADR-007): file name = C# static class name

## Style rules (STYLE.md)

- 2-space indentation
- Explicit types on non-obvious results
- No indirection wrappers
- Trailing commas
- Early returns over nested if/else
- No wildcard imports (except kotlinx.cinterop.* in generated code)
