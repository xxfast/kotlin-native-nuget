# ADR-181: Reverse direction opt-in: an ERROR-level `@ExperimentalNugetBindingApi` on every consumer-facing generated binding, no Gradle switch

## Status

Accepted

## Context

At 1.0.0 the forward direction (Kotlin to C#) is stable and the reverse direction (C# to Kotlin: `bind {}`, RIR, generated Kotlin stubs) is experimental and may break in 1.x (ROADMAP.md, "First stable release (1.0.0)"). Semver can only exclude it if a consumer has explicitly acknowledged that, so the 0.9.0 item asks for "the marker or DSL switch that makes the reverse direction experimental".

Constraints found in the repo:

- Generated reverse stubs compile inside the consumer's own module, as extra `srcDir`s of `nativeMain` and the per-target source sets (`NugetPlugin.kt:206-215`, verified by reading). Whatever gates them must gate same-module use.
- The forward KSP output calls author functions whose signatures name bound C# interfaces (ADR-088; fixture `Farm.kt:43`, `fun adopt(feedable: IFeedable)`, verified by reading).
- ADR-115 refuses any forward declaration or type carrying a `@RequiresOptIn` marker, through one chokepoint, `optInMarkerName` (`ForwardOptInMarkers.kt:35-50`, verified by reading), unless `publish { exportMarkers(...) }` waives it.
- Sibling 0.9.0 items (ADR-180 `Property<T>` DSL, target framework, include/exclude rename, task names) are rewriting `NugetExtension.kt`, `NugetBindConfig.kt` and `NugetPlugin.kt` in parallel.

## Alternatives Considered

### 1. Marker only: `@RequiresOptIn(level = ERROR)` stamped on every generated public declaration (chosen)

The cinterop precedent: generated foreign bindings carry `kotlinx.cinterop.ExperimentalForeignApi`, which is `@RequiresOptIn(level = RequiresOptIn.Level.ERROR)` with `@Target(TYPEALIAS, FUNCTION, PROPERTY, CLASS)` (verified by reading `kotlin-native/Interop/Runtime/src/main/kotlin/kotlinx/cinterop/Annotations.kt:62-65` on JetBrains/kotlin master). That cinterop stamps it on every generated declaration since 1.9.20 is inferred from https://kotlinlang.org/docs/native-c-interop-stability.html, not re-read. Cinterop has no Gradle gate.

Pros: the acknowledgment is at the use site and in IntelliJ; standard Kotlin mechanics; semver exclusion is exactly "anything behind this marker"; touches none of the sibling DSL files. Cons: every generated file and the forward processor must cooperate (below); two fixture builds must opt in.

### 2. Gradle switch only (`bind { experimental.set(true) }` or a `kotlin.mpp`-style gradle property)

Pros: one line, sits on the ADR-180 `Property<Boolean>` DSL. Cons: invisible in Kotlin code and the IDE; `bind {}` is already an explicit build-script act, so a second flag beside it adds friction without information. Swift Export gates on a gradle property because its output has no Kotlin use site; this output does. Overlaps the sibling DSL rewrite.

### 3. Both, the switch auto-adding the compiler `optIn`

Cons: the marker never appears in consumer code, so the visibility the marker exists for is lost; it is option 2 with extra machinery.

### 4. Marker at `WARNING`

Cons: does not force acknowledgment. Kotlin itself splits the tiers this way (`BetaInteropApi` is `WARNING`, `ExperimentalForeignApi` is `ERROR`, same file, verified); "may break in 1.x" is the `ERROR` tier.

### 5. Marker in `nuget-runtime` beside `NugetRuntimeApi`

Cons: `nuget-runtime` is generator-facing (ADR-127) and reaches only `${target}MainApi`; this marker is written by hand in consumer code, like `@CSharpName` (ADR-179).

## Decision

**The marker.** A new file in `nuget-annotations` (wired `commonMainApi` by the plugin, `NugetPlugin.kt:250-252`, verified):

```kotlin
package io.github.xxfast.kotlin.native.nuget.annotations

@RequiresOptIn(
  level = RequiresOptIn.Level.ERROR,
  message = "Bindings generated from a NuGet package by `bind {}` are experimental and may " +
      "change in any 1.x release.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
  AnnotationTarget.CLASS, AnnotationTarget.TYPEALIAS, AnnotationTarget.FUNCTION,
  AnnotationTarget.PROPERTY, AnnotationTarget.CONSTRUCTOR,
)
@MustBeDocumented
public annotation class ExperimentalNugetBindingApi
```

**The generator** (`NugetGenerateBindingsTask.kt`) stamps `@ExperimentalNugetBindingApi`, fully qualified, on every consumer-facing generated declaration: the static-class stub `object`, the wrapper `class`, the struct `data class`, the generic `class` and its per-instantiation factory functions, the `enum class`, the delegate `typealias`, and the interface. Visibility does not decide this: the stubs compile inside the consumer's own module (`NugetPlugin.kt:206-215`), so the `internal` wrapper, stub and struct declarations are consumer surface exactly like the `public` enums. Glue is not marked: the `*Bindings`/`*Bridge` registration objects, the `*Handle` interface implementations, the shared runtime files and the public `@CName` register exports (C# calls those, and the forward processor already skips every `@CName` function). The generator also adds the marker to every generated Kotlin file's `@file:OptIn`: the 16 existing `@file:OptIn` sites plus the six files that had none (enum, delegate typealias, interface, `NugetEnums.kt`, `NugetRegistry.kt`, and the generic bridge interface), so "every generated file opts in" holds without exception. Both layers are required, verified by spike (kotlinc 2.4.10, JVM frontend): without the declaration marker the consumer is not gated; without `@file:OptIn` the generated, unmarked registration glue fails to compile:

```
gen2.kt:13:20: error: reverse bindings are experimental
```


**What the consumer sees**, verified by spike, same module and with the marker in a separate jar:

```kotlin
fun a() = newtonsoft.json.JsonConvert.serializeObject(1)        // error
fun b(): String { val s = newtonsoft.json.registerAll(); return s.serialize("x") }  // error at both calls
class Dog : newtonsoft.json.IFeedable { override fun feed() {} }  // error at the supertype
```

Opting in, per file or module-wide (with the module-wide form the same compile prints nothing, verified):

```kotlin
@file:OptIn(ExperimentalNugetBindingApi::class)
```

```kotlin
kotlin {
  compilerOptions {
    optIn.add("io.github.xxfast.kotlin.native.nuget.annotations.ExperimentalNugetBindingApi")
  }
}
```

The spike ran on the JVM frontend; opt-in checking is a frontend check shared by every backend, and klib propagation of a `BINARY` marker across modules is already proven in this repo by `NugetRuntimeApi` (ADR-127). Kotlin/Native behaviour is therefore inferred from those two, not spiked separately.

**The forward processor**, two one-line changes, both load-bearing:

1. `CNameExports.kt` names the marker unconditionally in its `@file:OptIn` list (`NugetProcessor.kt:2686-2698`). That one file-level opt-in also covers the ADR-084 interface bridge factory export: `addInterfaceBridgeFactoryExport` adds its function to the same `CNameExports.kt` `FileSpec` (`NugetProcessor.kt:2586`), and its own function-level `@OptIn` (`InterfaceBridgeFactoryExports.kt:80`) exists only for `ExperimentalNativeApi` on `@CName`, so it needs no change. (The memo inferred it did; reading the call site disproved that.) Verified by spike that this is required: an `@OptIn`-annotated function whose signature names a marked type still requires opt-in at its call sites, so the generated caller of `fun adopt(feedable: IFeedable)` would otherwise fail:
   ```
   user\Opted2.kt:8:33: error: reverse bindings are experimental
   ```
   Unconditional, for the reason the list already names `NugetRuntimeApi` unconditionally: `nuget-annotations` is always on the classpath, and an unused opt-in costs nothing.
2. `optInMarkerName` (`ForwardOptInMarkers.kt:35-50`) answers null for `ExperimentalNugetBindingApi`, a built-in waiver, not an `exportMarkers` entry. Without it an author who propagates the marker (`@ExperimentalNugetBindingApi fun adopt(f: IFeedable)`) instead of `@OptIn` loses the ADR-088 export to a `SKIPPED_OPT_IN_MARKER` warning, and a bound non-interface stub at a forward position switches from today's scope refusal to the opt-in hint (`ForwardBridgeTypeClassifier.kt:222-232`, after the bound-interface branch at `:162`). Both verified by reading; the waiver and the file-level opt-in together are pinned by the Tier 1 cell `Tier1BindingMarkerExemptionTest` (a marked class and function export with no `SKIPPED_OPT_IN_MARKER`, and the generated Kotlin compiles). Exporting is correct here: the marker describes the stability of the Kotlin-side binding, not of the author's forward API.

**No Gradle switch and no Gradle hint.** `NugetExtension.kt`, `NugetBindConfig.kt` and `NugetPlugin.kt` are untouched, and the plugin logs nothing when `bind {}` is declared: the `RequiresOptIn` message already names the marker and the remedy.

Decided at the 2026-10-02 gate: the name `ExperimentalNugetBindingApi`, in `nuget-annotations`, package `io.github.xxfast.kotlin.native.nuget.annotations`; `RequiresOptIn.Level.ERROR`; `BINARY` retention; no Gradle switch and no Gradle hint; the forward processor waives the marker automatically, with no `exportMarkers` entry.

## Consequences

- Breaking for every reverse consumer in 0.9.0: a module that calls generated bindings stops compiling until it opts in. The compiler message names the marker.
- `test-library` and `test-companion` add the module-wide `optIn` to their build scripts; their xunit coverage over `Farm` then proves the forward waiver and the `CNameExports.kt` opt-in end to end.
- Semver at 1.0.0 excludes every declaration carrying the marker. Graduating a reverse shape later means removing the marker from that declaration kind, which is additive.
- `ExperimentalNugetBindingApi` is the only opt-in marker `nuget-annotations` ships; no other marker ships in this module.
- Tier 1's compile step puts the real `nuget-annotations` jar on its classpath, mirroring the plugin's unconditional `commonMainApi` wiring, because every `CNameExports.kt` now names the marker.
- Deferred: marking glue (`*Bindings`, `*Handle`, runtime files). Not planned: a Gradle-side hint when `bind {}` is declared (decided at the gate; the compiler message names the marker and the remedy).
