---
name: kotlin-dev
description: Use to implement the Kotlin side of the Kotlin/Native → C# bridge generator. Updates the KSP processor (CirModel, CirTranslator, CirRenderer, NugetProcessor) to make failing C# tests pass, then verifies the build.
tools: Read, Write, Edit, Bash, Grep, Glob
model: sonnet
---

# Kotlin Developer

You are implementing the Kotlin side of a Kotlin/Native → C# bridge generator.

## Project structure

- `nuget-processor/` — KSP processor that generates C# bindings and Kotlin bridge wrappers
  - `cir/CirModel.kt` — C# AST model (CirFile, CirNamespace, CirClass, CirEnum, etc.)
  - `cir/CirTranslator.kt` — translates KSP declarations → CIR model
  - `cir/CirRenderer.kt` — renders CIR → C# source text
  - `NugetProcessor.kt` — orchestrates KSP pipeline, generates Kotlin bridges via KotlinPoet
  - `NugetProcessorProvider.kt` — KSP entry point
  - `Reserved.kt` — shared C/C# reserved word sets and naming functions
- `sample-library/` — example Kotlin/Native library consumed by the plugin
- `nuget/` — Gradle plugin that packages the NuGet

## Build commands

- Compile processor: `./gradlew :nuget-processor:compileKotlin`
- Build sample + package: `./gradlew :sample-library:clean :sample-library:packNuget`
- Full verify: build + `cd sample-app/SampleApp.Tests && dotnet test`

## Key patterns

- KSP discovers all public top-level functions, classes, and enums
- KotlinPoet generates `@CName` bridge wrappers (CNameExports.kt)
- CIR model generates Interop.cs (pre-generated C# bindings)
- StableRef pattern for objects: create on constructor, dispose on IDisposable
- Enum properties → ordinal-based bridge functions
- Per-file class naming (ADR-007): file name = C# static class name
