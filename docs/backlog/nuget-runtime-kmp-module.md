# The fixed part of `CNameExports.kt` is regenerated into every library instead of shipped once

> Added 2026-09-10 under Future Improvements.

**Roughly 500 of the fixture's 21.7k generated Kotlin lines are declaration-independent and come
out byte-identical for every project that applies the plugin.** They are the `nuget_*` ABI the
C#-side `NugetMarshal` talks to, and they live in the consumer's own module only because the
generator has nowhere else to put them:

- `NugetHandles` (`retain`/`release`, the [ADR-120](../adr/120-live-stableref-counter-and-leak-harness.md)
  live counter) and the `nuget_live_handles` export
- `NugetError`/`buildError` and the seven `nuget_error_*` accessors (type, message, stack trace, cause chain)
- the 13 `nuget_unwrap_*` and 13 `nuget_wrap_*` scalar exports, `nuget_list_*`/`nuget_map_*`/`nuget_set_*`,
  `nuget_dispose`
- callback invocation: `nuget_func0_invoke`/`nuget_func1_invoke`, `nuget_suspend_func0..3_invoke`
- coroutine plumbing: `nuget_scope_create/cancel/dispose/drain`, `nuget_job_cancel/dispose`,
  `nuget_stateflow_collect`, `nuget_stateflow_value`
- `toDotNetTicks`/`instantFromDotNetTicks`/`durationFromDotNetTicks` (the per-site `.toDotNetTicks()` calls stay
  in the generated code)
- `nuget_gc_collect`, `nuget_csharp_token` and the `NugetCSharpBridge` interface behind it
- on the reverse side, the `slotCount`/`contractHash` registration guard
  ([ADR-054](../adr/054-reverse-bridge-registration-observability.md)) around each per-type table

That is 64 `nuget_*` exports in the fixture. A second, separate step: the per-declaration bodies that
are templated rather than fixed. Every `*_async` export repeats the same ~20-line
`CoroutineScope(Dispatchers.Default).launch(start = ATOMIC) { try { retain; fn.invoke } catch ... }`
shape (14 copies in the fixture), and the Flow collect thunks and `*_bridge_create` thunks for a
C#-implemented interface have the same shape. A runtime `launchForCSharp(callback, userData) { body }`
helper collapses each to a few lines; that is a generator change on top of the runtime, not part of the
first move.

Publishing that block once as a small Kotlin/Native library (`nuget-runtime`, targets `macosArm64`,
`macosX64`, `linuxX64`, `mingwX64`) would give the `nuget_*` ABI its own version, shrink every
generated `CNameExports.kt`, and make the project indexable on [klibs.io](https://klibs.io), which
lists only artifacts carrying `kotlin-tooling-metadata.json`; today both published artifacts are
plain JVM jars (a KSP processor and a Gradle plugin), so nothing qualifies.

Three things make it more than a file move, each needing a spike before an ADR:

1. **`@CName` exports in a dependency klib do not reach the final `.dylib`/`.dll`** unless the
   consumer's `sharedLib {}` binary `export()`s that module. The plugin would have to add the
   `export(...)` itself (it already walks every `SharedLibrary` in `NugetPlugin.kt`), or every
   `nuget_*` P/Invoke fails at first call with `EntryPointNotFoundException`. Inferred from the
   Kotlin/Native docs, not spiked.
2. **`NugetHandles` is `internal`** and the generated code calls it from the consumer module, so it
   becomes public API (or `@PublishedApi internal`), a stability commitment the generated-per-project
   model deliberately avoided.
3. **Generator and runtime can now skew.** The ADR-054 contract check covers a C# shim against a
   native library from a different build; it would need a generator-vs-runtime arm too, or the plugin
   pins the runtime version and the author never types it (the KSP processor coordinate is already
   resolved that way).

**The C# side is the bigger volume but a different artifact.** `Interop.cs` carries roughly 1.8k fixed
lines (`NugetMarshal`, `NugetBridge`/`NugetBridgeState`, the `Nuget*Native` statics, the `KotlinException`
family, `KotlinFlow<T>`/`KotlinStateFlow<T>`/`KotlinMutableStateFlow<T>` and their enumerator,
`NugetSubscription`, `INugetHandle`, the [ADR-094](../adr/094-reflection-free-generic-dispatch.md)
`BoxNative`/`SlotNative` dispatch, the delegate declarations). That cannot be a klib; it would be a runtime
NuGet package. One hard constraint: every `DllImport` names the library at compile time, and a .NET
process can load several Kotlin libraries, so a shared runtime assembly cannot P/Invoke `nuget_*`
directly. It needs a `DllImportResolver` keyed per library, or stays source-shipped. That is the same fork
the opt-in compiled-assembly packaging mode under Future Improvements sits on; decide the two together.

Related: the shared-library size gate under Performance & Resource Hygiene would show the per-target
saving; the opt-in compiled-assembly packaging mode is the C#-side twin of the same "ship once, not
per project" question.
