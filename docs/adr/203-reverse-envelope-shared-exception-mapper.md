# ADR-203: The reverse envelope maps like a forward call: one shared mapper in `Kotlin.Native.Interop`, keyed on the row the module's own classifier matched

## Status

Accepted

Amends [ADR-177](177-exception-mapping-by-class-hierarchy.md) ("Reverse envelope", "Scope of the IO
row") and [ADR-087](087-kotlin-slot-exceptions.md) (the duplicated switch); closes the exclusion in
[ADR-202](202-runtime-route-exception-mapping.md). Folds in the Tooling item "forward `BuildMapped`
is `private`".

## Context

When C# calls a Kotlin-implemented slot (a member of a C# interface that Kotlin implements) and the
Kotlin body throws, the Kotlin side hands C# an error envelope and the generated reverse shim
(`NugetKotlinErrors.Build`) turns it into a .NET exception. That path lagged the forward one twice:

- Kotlin side. `nugetKotlinError` classified with `::nugetStdlibMappedType`, so the envelope never
  carried the module's optional `kotlinx.io.IOException` row.
- C# side. The shim read no matched row. Its own `Map` switched on the concrete Kotlin class name
  across eight exact rows, a copy of the pre-ADR-177 forward `BuildMapped`, which was `private`.

So on a reverse slot a Kotlin subclass of a mapped type, `NullPointerException`,
`NoWhenBranchMatchedException`, `CancellationException` and `kotlinx.io.IOException` all arrived as
a bare `KotlinException`, while the same exception on a forward call mapped. ADR-177 and ADR-202
each deferred this to the shared `BuildMapped` item.

## Decision

1. One mapper. The `mappedType` switch had no per-library content (the rows are the fixed
   `KOTLIN_EXCEPTION_TYPES` table), so it moved into the shared library as
   `Kotlin.Native.Interop.KotlinException.CreateMapped(kotlinType, mappedType, message, stackTrace,
   inner)`. It is public and `[EditorBrowsable(Never)]`, like `Create`. Both generated copies are
   deleted: the forward `BuildMapped` and the reverse shim's `Map`. `NugetErrorNative.BuildException`
   and `NugetKotlinErrors.Build` both call it. A processor test reads the contract source and pins
   its rows, their order and their .NET types to `KOTLIN_EXCEPTION_TYPES`.
2. The reverse Kotlin site classifies with the module's own `nugetMappedType` (ADR-177 hierarchy
   matching), not the runtime's. The processor now emits that classifier even when the module exports
   nothing, in a classifier-only `CNameExports.kt`, because a module that only binds a NuGet package
   still has reverse slots.
3. A new plugin-generated per-library export, `nuget_kotlin_error_cause_mapped_type(handle, index)`,
   carries the matched row to C# (index 0 is the top exception, the same shape as the forward
   `nuget_error_cause_mapped_type`). The shim reads it for the top exception and every cause.

No runtime change: `NUGET_RUNTIME_EXPORTS`, `NugetRuntimeAbi1` and the ADR-054 contract hash are
untouched (the hash covers registered member signatures only; the accessor is a plain `DllImport`).

A bind-only project (`bind {}` with no `publish {}`) gets the full mapping with no fallback, because
the mapper lives in the contract package every generated package already references.

## Alternatives Considered

### 1. `::nugetRuntimeMappedType` at the reverse site (rejected)

ADR-202 installs the module classifier only as the first statement of three forward mint exports. A
reverse slot can throw before any of them has run, and the runtime then silently falls back to
stdlib rows.

### 2. Keep the mapper generated and make it `internal` (rejected)

The forward `BuildMapped` would be `internal` and the shim would call it through the forward
namespace. A bind-only project has no forward `Interop.cs`, so the shim would need a second,
pinned stdlib fallback switch, plus a cross-namespace `internal` reference that holds only while both
files land in one assembly. This is the cheaper change now and leaves a duplicate switch in the
plugin.

### 3. Install the classifier from the reverse module initializer, or in every forward export (rejected)

An extra export and a load-time native call, or a write on every hot forward call, for a need that a
direct reference to the generated classifier meets at compile time.

### 4. Two switches keyed on `mappedType`, pinned by a test (rejected)

Fixes the behaviour and keeps the duplicate.

## Consequences

- **Breaking in the ADR-177 sense on reverse slots.** A slot that throws `kotlinx.io.IOException`
  surfaces in C# as `KotlinIOException`; a Kotlin subclass of a mapped row surfaces as that row's
  type; `NullPointerException`, `NoWhenBranchMatchedException` and `CancellationException` (now
  `KotlinOperationCanceledException`) map too. An unmapped exception is still `KotlinException`. A
  consumer who caught only `KotlinException` there no longer catches these. Which release carries it
  is not decided here.
- **The contract package grows a public member.** `CreateMapped` is new public API on
  `Kotlin.Native.Interop`, and any future mapping row needs a contract release instead of a
  processor-only change. The version stays 1.0.0 and `INTEROP_CONTRACT_RANGE` stays `[1.0.0,2.0.0)`:
  the contract package was introduced by #362 (ADR-178), which is not in the 0.8.0 tag, and
  nuget.org has no such package, so 1.0.0 has never been released (checked when this change was
  made, not re-checked here). The human should confirm it was not published to another feed.
  Once 1.0.0 is released, adding a member means 1.1.0.
- A new row in `KOTLIN_EXCEPTION_TYPES` reaches both directions with one edit to the contract switch.
- The Kotlin classifier that earlier gated on "something to export" is now emitted for every module.
  Nothing else in a no-export module changes.
- ADR-054 reverse trace. The reverse shim writes the twin of the forward error line
  (`[nuget:shim] error <kotlinType> -> <type> (row <row>): <message>`, no member name; see the
  [ADR-129](129-nuget-runtime-version-export.md) amendment of the same date). That write is wrapped
  in a catch: before this change the reverse trace write was unguarded, so with a trace file set two
  slots failing at once could replace the mapped exception with a file `IOException`.
- Not covered: an ADR-161-style original-fault recovery on the reverse shim; the reverse trace flag is
  read once at startup, so no test flips it in process (only the generated text is checked).

## Evidence

Verified:

- `IntegrationTests/MenagerieRoundTripTests.cs` (probe `Sanctuary.DescribeFault` in
  `TestDependency/Menagerie.cs`, which catches inside C# so the shim's own type is observed, not the
  ADR-104 re-wrap): a Kotlin slot throwing `kotlinx.io.IOException` is `KotlinIOException`; one
  throwing a subclass of `IllegalStateException` is `KotlinInvalidOperationException`; one throwing
  an unmapped exception stays `KotlinException`.
- `ContractTests` pins `CreateMapped` against the mapped types; a processor test pins its switch to
  `KOTLIN_EXCEPTION_TYPES`; `Tier1ExceptionHierarchyMappingTest` pins the classifier emitted for a
  module with nothing to export and the forward builder calling the shared mapper; plugin tests pin
  the accessor, the `::nugetMappedType` reference and the shim calling `CreateMapped`, including a
  bind-only project.
- Native pipeline with the plugin flag: processor suite 1807, plugin tests 823, `ContractTests` 5,
  `IntegrationTests` 3212, `LeakTests` 194, 8 AOT shapes.
- No `LeakTests` row: the envelope is a raw, deliberately uncounted handle, and the new accessor
  allocates only a string that is freed.

Not covered by a test: an in-process on/off test of the reverse trace line, and a real native build
of a bind-only module (a Tier 1 and plugin-generation cell only).
