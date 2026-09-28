# An empty `nuget.namespace` breaks the whole generated file

**Symptom.** Setting the processor's namespace option to the empty string does not fall back to
the documented `Interop` default. The generated `Interop.cs` opens with a bare `namespace ` (no
name at all) and every cross-type reference that would otherwise be `global::Interop.Foo` renders
`new global::.WireCage(...)` instead: not one declaration, the whole file.

**Cause (inferred, not spiked as a consumer build).** `ForwardBridgeTypeClassifier.kt:501-506`
takes the bare-name branch (no `global::` qualifier at all) whenever `context.rootNamespace` is
empty; it does not substitute the `Interop` default there. `NugetProcessorProvider.kt:31` does
default the *option* to `"Interop"` when the Gradle plugin never sets it, but
`NugetPlugin.kt:312` passes `pub?.packageId ?: ""`: if a consumer's `nuget { }` block resolves
`packageId` to an explicitly empty string, the plugin hands the processor `""` directly, which is
a set value, not an absent one, so the processor-side default never applies.

**Coverage gap.** Found by a Tier 1 spike run with `processorOptions = mapOf("nuget.namespace" to
"")` while researching ADR-133's 2026-09-28 amendment; no fixture or Tier 1 cell exercises an
empty namespace today.

**Fix shape, not investigated.** Either have `NugetPlugin.kt` never pass an empty string (fall
back to `"Interop"` itself before invoking the processor), or have
`ForwardBridgeTypeClassifier.kt`'s bare-name branch substitute the same default the processor
option does. Whichever route, a Tier 1 cell should assert the resulting namespace directly rather
than relying on `compiledClean`, which does not compile the generated C# at all (Tier 1 is
Kotlin-side only).

**Discovered alongside:** [ADR-133](../adr/133-nested-types.md)'s 2026-09-28 amendment, while
spiking a since-disproved shadowing guess on ROADMAP Phase 4.
