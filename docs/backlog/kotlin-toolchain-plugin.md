# A Kotlin Toolchain plugin, once the toolchain can publish plugins

> Added 2026-10-08 under Future Improvements. Post-1.0; nothing in it changes the 0.9.0 to 1.0.0 scope.

**JetBrains now recommends the Kotlin Toolchain for most new KMP projects** ([0.13 announcement](https://blog.jetbrains.com/kotlin/2026/10/start-your-next-kmp-app-with-kotlin-toolchain-0-13/), 2026-10). A standalone toolchain project has no Gradle underneath it and cannot apply third-party Gradle plugins, so `nuget-plugin` has no way into one. Every claim below about the toolchain is read from its docs or the announcement, not run; none of it has been spiked.

## What survives and what has no home

The plugin's work, per `NugetPlugin.kt`, splits into three kinds:

| Piece | Toolchain status (inferred from docs) |
|---|---|
| The KSP processor (`nuget-processor`) | Supported in principle. Processors are listed under `settings.kotlin.ksp.processors`, KSP2 only (we are already on KSP2). The docs warn of "gaps in support, such as issues with native targets". Generated output is per-platform, which suits us: the forward output is C#, not shared Kotlin. |
| `nuget-runtime` and `nuget-annotations` on `nativeMain` | Plain dependencies the user declares. Fine. |
| Everything else: the `nuget {}` DSL, `sharedLib` wiring, KSP args, `packNuget`, `publishNuget`, the restore project, contract compile, shim generation, diagnostics, and the whole reverse pipeline (`nugetExtractApi`, generated `nativeMain` sources) | No home. Toolchain plugins exist (`plugin.yaml`, custom commands, settings classes), but the toolchain "doesn't support plugin publication yet", so a third party cannot ship one. |

So today the forward reader would run in a toolchain project and leave C# in a KSP output directory with nothing to pack it. That is the same shape as [compose-nav-graph #31](https://github.com/skydoves/compose-nav-graph/issues/31): KSP runs, the Gradle-plugin-driven steps do not.

## Blockers, in order

1. Toolchain plugin publication. Hard blocker; nothing can ship before it.
2. A toolchain hook for native `sharedLib` binaries (output path, target list), or an equivalent the plugin can read. The 0.13 native work is SwiftPM import and compiler caches; nothing about shared-library binaries is documented.
3. KSP on native targets without the documented gaps. Spike this first when the time comes: it is one `project.yaml` with `nuget-processor` listed and a `@NuGet`-annotated class.

## Design pressure on the Gradle plugin now

- Keep the plugin's value in the KSP processor and in tasks that already shell out to `dotnet` with explicit file inputs and outputs. The thinner the Gradle-specific glue, the smaller a future `plugin.yaml` wrapper is. `NugetTooling.kt` already resolves `dotnet` independently of Gradle; that is the right shape.
- The Future Improvements item to swap the forward reader from KSP to a K2/IR compiler plugin cuts against this. In the toolchain, third-party compiler plugins are configured by raw ID and Maven coordinate, and the docs say that is "not really meant to be the final way to configure compiler plugins for end users". KSP has a first-class list; compiler plugins do not. Weigh that before taking the swap.
- The Amper/toolchain docs keep a Gradle-based variant with full Gradle interop. That variant is covered by the existing plugin for free, so the gap is only the standalone variant.

## Not in scope

- Migrating this repo's own build to the toolchain. It is a Gradle composite with included builds, a `fixture-consumer` second root and `dotnet` steps everywhere; no reason to move.
- Any Gradle DSL change for the sake of the toolchain before plugin publication exists.

## Sources

- [Start your next KMP app with Kotlin Toolchain 0.13](https://blog.jetbrains.com/kotlin/2026/10/start-your-next-kmp-app-with-kotlin-toolchain-0-13/)
- [Toolchain user guide: KSP](https://kotlin-toolchain.org/dev/user-guide/advanced/ksp/)
- [Toolchain user guide: compiler plugins](https://kotlin-toolchain.org/dev/user-guide/advanced/kotlin-compiler-plugins/)
- [Amper FAQ, Gradle interop](https://github.com/JetBrains/amper/blob/release/0.3/docs/FAQ.md)
