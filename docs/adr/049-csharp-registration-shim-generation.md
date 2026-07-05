# ADR-049: C#-side registration shim generation — inverse type-mapping table, `[UnmanagedCallersOnly]` thunks, `[ModuleInitializer]` startup registration, and shim delivery via the packed nupkg

## Status

Accepted

## Context

ROADMAP Phase 8 line 138 is the C#-side counterpart of ADR-048: a Gradle task that reads the same
`reverse-ir.json` and emits, for each bound `RirClass`, the C# artifacts that satisfy the
registration contract ADR-048 already fixed:

- Export name `nuget_{ns_snake}_{type_snake}_register`.
- One `COpaquePointer`/thunk-pointer parameter per bridgeable method, **in `reverse-ir.json` method
  order** (same order the Kotlin generator used).
- `Marshal.StringToCoTaskMemUTF8` for string returns; the Kotlin side already frees with the
  platform-appropriate `freeManagedString`.

This ADR does not renegotiate that contract. It answers what ADR-041/048 left to "the next ROADMAP
item": the exact C# thunk signatures (including two non-obvious blittability corrections),
the `[ModuleInitializer]` shape, exception behaviour, the generated project's compiler flags, the
Gradle task shape, and — the least-settled question — **where the generated C# lives, how it
compiles, and how a consuming `.csproj` ends up running it at startup.**

### Prior decisions that constrain this ADR

**ADR-041** (foundational, do not contradict): `[UnmanagedCallersOnly]` thunks + `[ModuleInitializer]`
startup registration; per-type `@CName`/`[DllImport]` registration export; `requireNotNull` fail-fast
on the Kotlin side; `AllowUnsafeBlocks = true` required for the `delegate* unmanaged<>` address-of
syntax; exceptions from a C# thunk "may return sentinel values on exception with diagnostics
deferred to a later ADR" — v1 does **not** get full error propagation (that is Phase 11, mirror of
ADR-023/024).

**ADR-048**: the Kotlin-side generator, the exact contract this ADR must satisfy (export name,
parameter order, string ownership), and the v1 type-mapping table this ADR must invert. Also fixes
that `nugetGenerateBindings` already emits per-member diagnostics for skipped constructs — this ADR
must not duplicate those warnings.

**ADR-043**: v1 bridgeable subset — static methods only, `void`/`string`/primitive parameter and
return types. Overload sets, `ref struct`, open generics, `dynamic`, and DIMs are already filtered
out before either generator sees them.

**ADR-046**: the `RirFile`/`RirClass`/`RirMethod`/`RirTypeRef` model and `parseReverseIr()`. Both
generators read the identical JSON.

**ADR-045**: `build/nuget-interop/` is the existing scratch directory for the resolve pipeline
(`interop.csproj`, `obj/project.assets.json`). `nugetGenerateBindings` already added a `kotlin/`
subdirectory (ADR-048); this ADR adds a sibling `csharp/` subdirectory.

**ADR-001** (forward direction, already Accepted and implemented): the plugin **pre-generates**
`Interop.cs` into the packed nupkg's `contentFiles/cs/any/` folder so that a consuming C# project
needs no Gradle callback — "no consumer-side build callback" is a project-wide principle recorded
in `docs/research/nuget-plugin-architecture-synthesis.md` ("What we deliberately do differently").
`PackNugetTask.kt` already:
- Copies KSP-generated `.cs` files into `contentFiles/cs/any/`.
- Emits `build/{id}.targets` with `<AllowUnsafeBlocks>true</AllowUnsafeBlocks>` unconditionally.
- Emits a `.nuspec` with a `<contentFiles>` block (`buildAction="Compile"`) so any project that adds
  `<PackageReference Include="{id}"/>` gets the `.cs` files compiled into its own assembly
  automatically — [NuGet contentFiles reference](https://learn.microsoft.com/en-us/nuget/reference/nuspec#contentfiles),
  [MSBuild props and targets in a package](https://learn.microsoft.com/en-us/nuget/concepts/msbuild-props-and-targets).

This existing machinery is directly reusable for the reverse direction's shim delivery problem (see
Alternative 2 below) — the same "files-as-compile-items" NuGet convention, applied to a different
generator's output.

### The two non-obvious blittability corrections

ADR-041's own illustrative code and the parent task's framing both describe `bool`/`char` crossing
"directly," and strings crossing as `byte*`. Neither survives contact with
`[UnmanagedCallersOnly]`'s actual constraints:

**`bool` is not blittable.** The .NET interop docs list the blittable primitive set as `Byte`,
`SByte`, `Int16`, `UInt16`, `Int32`, `UInt32`, `Int64`, `UInt64`, `IntPtr`, `UIntPtr`, `Single`,
`Double` — `Boolean` is explicitly excluded because its default marshalled size (4-byte Win32
`BOOL`) differs by platform and by attribute
([Blittable and Non-Blittable Types](https://learn.microsoft.com/en-us/dotnet/framework/interop/blittable-and-non-blittable-types)).
Roslyn tracks `bool`/`char` parameters on `[UnmanagedCallersOnly]` methods as a hard compile error in
current SDKs, `InvalidProgramException` at runtime on older ones
([dotnet/roslyn#64086](https://github.com/dotnet/roslyn/issues/64086)). Kotlin/Native's C export of
`kotlin.Boolean` is a 1-byte value — this project's own forward-direction generator already works
around the identical mismatch with `[return: MarshalAs(UnmanagedType.I1)]` in
`CirObjectRenderer.kt:114`. The reverse thunk needs the mirror-image fix: use C# `byte` (0/1) as the
thunk-level type, not `bool`.

**`char` is not blittable either**, for the same reason (default ANSI 1-byte marshalling vs Kotlin's
2-byte UTF-16 `UShort` representation, per ADR-048). The reverse thunk uses C# `ushort`.

**Strings can cross as `IntPtr` instead of `byte*`.** `IntPtr` is blittable and pointer-sized;
`Marshal.PtrToStringUTF8`/`Marshal.StringToCoTaskMemUTF8` both accept/return `IntPtr` directly. Using
`IntPtr` instead of `byte*` avoids `unsafe` in every thunk body (only the `[ModuleInitializer]`'s
function-pointer address-of expression still needs `unsafe`), matching this project's existing
forward-direction convention — `.claude/agents/csharp-dev.md` states forward-direction bindings need
"No unsafe code... strings marshal via IntPtr + Marshal.PtrToStringUTF8 internally." The reverse
thunks follow the same house style.

### Ecosystem analogues for the registration-shape question

**Xamarin.Android JNI native-method registration** is the closest structural analogue to "a managed
runtime registering function pointers with a foreign runtime it does not control." Historically via
`[MonoPInvokeCallback]` (predecessor of `[UnmanagedCallersOnly]`) and `JNIEnv::RegisterNatives`, the
managed side hands the JVM a table of function pointers for a Java class's native methods. The
structural difference: JNI's registration is *pulled* — the JVM calls `RegisterNatives` when it loads
the Java class, and the managed static constructor supplies the table on demand. Our scheme is
*pushed* — the .NET `[ModuleInitializer]` proactively calls into Kotlin at .NET module load, without
Kotlin ever invoking back into C# to ask for it. The push model is required here because Kotlin has
no equivalent of "class loading" to hook into; C# must announce itself.

**C++/CLI "It Just Works" (IJW) thunks** are the opposite end of the spectrum: the C++/CLI compiler
auto-generates managed↔native thunks at every mixed-mode call site because both the managed and
native code are compiled by the *same* toolchain into the *same* module. No manual registration table
exists because there is no cross-toolchain boundary. This project has no such shared compiler, which
is precisely why an explicit runtime handshake (registration call) is structurally necessary rather
than a generation convenience.

**NativeAOT's own `[UnmanagedCallersOnly]` samples** (e.g. the officially documented NativeAOT
library-export pattern) use the same attribute, but for a fundamentally different scenario: a
NativeAOT-compiled library bakes `EntryPoint = "..."` directly into the *native* export table, so a
native caller can `dlopen`/`GetProcAddress` the symbol with zero runtime handshake. That shortcut is
unavailable to us because the C# side here is ordinary JIT/CLR-hosted code (the .NET host process
assumption from ADR-041), not an AOT-compiled binary with its own native export table. This confirms
the registration-table *shape* (thunk + pointer handoff, not a static export) is the right one for a
JIT-hosted, non-AOT C# side — validating ADR-041's choice rather than just accepting it on faith.

## Alternatives Considered

### 1. Inverse type-mapping table: `IntPtr` + narrowed integer types for non-blittable primitives (chosen)

| `RirTypeRef` | ADR-048 Kotlin `CFunction` type | C# `[UnmanagedCallersOnly]` type | Thunk-body conversion |
|---|---|---|---|
| `RirVoidType` | `Unit` | `void` | — |
| `RirStringType` (parameter) | `COpaquePointer?` | `IntPtr` | `Marshal.PtrToStringUTF8(ptr)!` |
| `RirStringType` (return) | `COpaquePointer?` | `IntPtr` | `Marshal.StringToCoTaskMemUTF8(result)` |
| `RirPrimitiveType("bool")` | `Boolean` | `byte` | `value != 0` / `result ? (byte)1 : (byte)0` |
| `RirPrimitiveType("byte")` | `UByte` | `byte` | direct (both unsigned 8-bit) |
| `RirPrimitiveType("short")` | `Short` | `short` | direct |
| `RirPrimitiveType("int")` | `Int` | `int` | direct |
| `RirPrimitiveType("long")` | `Long` | `long` | direct |
| `RirPrimitiveType("float")` | `Float` | `float` | direct |
| `RirPrimitiveType("double")` | `Double` | `double` | direct |
| `RirPrimitiveType("char")` | `UShort` | `ushort` | `(char)value` / `(ushort)result` |

**Pros:**
- Every entry is genuinely blittable — verified against the documented blittable set — so no entry
  risks the `InvalidProgramException`/compile error class documented in
  [dotnet/roslyn#64086](https://github.com/dotnet/roslyn/issues/64086).
- `IntPtr` for strings keeps `unsafe` confined to the one place it is unavoidable (the
  `[ModuleInitializer]` function-pointer address-of expression), matching the project's established
  "no unsafe in thunk bodies" style.
- `byte`/`ushort` narrowing for `bool`/`char` exactly mirrors the existing forward-direction fix
  (`[return: MarshalAs(UnmanagedType.I1)]` in `CirObjectRenderer.kt`) applied in the opposite
  direction — consistent house convention, not a new idea.

**Cons:**
- The table has two "gotcha" rows (`bool`, `char`) that a naive port of ADR-041's illustrative code
  would get wrong. Must be documented clearly in the generator's code comments so a future
  contributor doesn't "simplify" `byte` back to `bool`.

### 2. Raw `byte*` pointers for strings (rejected)

Match ADR-041's inline example literally: `private static unsafe byte* Format_Thunk(byte* ...)`.

**Rejected.** Functionally equivalent to `IntPtr` for the pointer value itself, but requires every
thunk method to be `unsafe` and the generated file to reason about pointer arithmetic it never
actually needs (the thunk never dereferences the pointer directly — it always goes through
`Marshal.PtrToStringUTF8`/`StringToCoTaskMemUTF8`, both of which accept `IntPtr`). Broadens the
`unsafe` surface for no benefit and diverges from the forward-direction convention documented in
`.claude/agents/csharp-dev.md`.

### 3. `bool`/`char` used directly as thunk parameter/return types (rejected)

**Rejected — not merely suboptimal but structurally invalid.** `System.Boolean` and `System.Char` are
non-blittable
([Blittable and Non-Blittable Types](https://learn.microsoft.com/en-us/dotnet/framework/interop/blittable-and-non-blittable-types)).
Current-generation Roslyn rejects them on `[UnmanagedCallersOnly]` methods at compile time; older
toolchains fail at runtime with `InvalidProgramException`
([dotnet/roslyn#64086](https://github.com/dotnet/roslyn/issues/64086)). This is not a style choice —
generating `bool`/`char` thunk signatures would produce code that does not build (or crashes) on any
supported SDK.

---

### 4. Shim delivery: merge into the same packed nupkg's `contentFiles/cs/any/` (chosen)

The generated `{TypeName}Registration.cs` files are copied into the exact same
`contentFiles/cs/any/` folder `PackNugetTask` already populates with the KSP-generated forward
`Interop.cs` (ADR-001). The `.nuspec`'s `<contentFiles>` block already marks every `.cs` file under
that folder `buildAction="Compile"`, so any project that references the packed package (whether via
a real `<PackageReference>` or, in the current dev-loop harness, via a manual `<Compile Include>`
pointing at the staged nupkg folder) gets the shim source compiled directly into *its own* assembly.

Two additional pieces are needed on top of the existing `PackNugetTask` machinery (tracked as
implementation work for the "Phase goal: consume … end-to-end" ROADMAP line, not this ADR):
1. `PackNugetTask.generatedCsDir: DirectoryProperty` becomes a `ConfigurableFileCollection` (or the
   task copies from two input directories) so it can merge the KSP output directory *and*
   `nugetGenerateShims`'s `build/nuget-interop/csharp/` output into one `contentFiles/cs/any/` folder.
2. The generated `.nuspec` gains a `<dependencies>` group
   ([nuspec `<dependencies>` reference](https://learn.microsoft.com/en-us/nuget/reference/nuspec#dependencies))
   listing each *bound* NuGet package at the **exact resolved version** recorded in
   `project.assets.json` (not a loose floor) — so that a consumer who adds only
   `<PackageReference Include="SampleLibrary"/>` automatically restores `Newtonsoft.Json` at the
   exact version the shim was generated against, with zero manual editing. Pinning the exact resolved
   version (rather than "any 13.x") matters because the shim's method signatures are frozen against
   one specific assembly's metadata at generation time.

**Why source-inclusion (not a compiled DLL) is the load-bearing decision, not just style:** a
`[ModuleInitializer]` "runs when a module is loaded for the first time... before any other code in
the module runs"
([Module initializers — C# 9.0 proposal](https://learn.microsoft.com/en-us/dotnet/csharp/language-reference/proposals/csharp-9.0/module-initializers)).
If the shim lived in a *separately compiled* assembly referenced via `ProjectReference`/
`PackageReference` (Alternative 5), the CLR only loads that assembly the first time JIT compilation
resolves a type/method token into it. Because the shim's only public surface is its own
`[ModuleInitializer]` and `internal` thunks — nothing a consumer's code ever references directly —
there would be **no IL reference forcing that assembly to load at all**, and the registration table
would never populate. Source-inclusion sidesteps this entirely: the `[ModuleInitializer]` becomes
part of the *consuming* module itself (the app's own `.exe`/`.dll`), which is unconditionally loaded
because it is the module being run.

**Pros:**
- Reuses `PackNugetTask`, the `.nuspec`, and the `build/{id}.targets` `AllowUnsafeBlocks` emission
  verbatim — the `AllowUnsafeBlocks` requirement from ADR-041/048 is *already satisfied* by existing
  forward-direction plumbing with no new code.
- Guarantees `[ModuleInitializer]` execution, per the argument above.
- Matches the project-wide "no consumer-side build callback" principle (ADR-001): one
  `PackageReference`, native lib + forward bindings + reverse shims + transitive C# NuGet deps all
  arrive together.

**Cons:**
- Couples two previously-independent Gradle code paths (`publish { }` / `packNuget` and
  `dependencies { bind { } }` / `nugetGenerateShims`) that today live in separate, non-interacting
  `afterEvaluate` blocks in `NugetPlugin.kt`. A project that declares `nuget { dependencies { } }`
  without a `publish { }` block has no packed nupkg to merge into — see Scope below; this ADR
  generates the shim *files*, wiring them into `packNuget` is deferred to the "Phase goal" item.

### 5. Separate compiled shim class library, referenced via `ProjectReference`/`PackageReference` (rejected)

Generate a small `SampleLibrary.Interop.csproj` that compiles the shim `.cs` files into their own
assembly; the consuming app adds a `ProjectReference` (dev loop) or `PackageReference` (published).

**Rejected** primarily for correctness, not preference: as argued above, nothing in a typical
consumer's code path forces that assembly to load, so its `[ModuleInitializer]` may simply never
run — silently leaving the Kotlin-side function-pointer table `null` until the first bridge call
throws `requireNotNull`'s diagnostic. This is exactly the kind of "surprises the developer at
runtime instead of at build time" failure mode the project's fail-fast convention exists to prevent.
Secondarily: an extra assembly to build, version, and distribute for no capability gain over
Alternative 4.

### 6. Separate shim-only micro-nupkg per bound dependency (rejected)

Package `SampleLibrary.Interop.Newtonsoft.Json.nupkg` as its own artifact, one per bound package.

**Rejected.** Adds a second (or Nth) nupkg the plugin must version, build, and publish in lockstep
with the main library package, with no independent reuse value — the shim is meaningless without the
exact `SampleLibrary` build it was generated against. Same rejection logic as ADR-046's Alternative 5
(one `reverse-ir.json` per package was rejected for the equivalent reason: unnecessary
fragmentation of a pipeline that is naturally single-pass).

### 7. Interim dev-loop wiring: manual `<Compile Include>` in `sample-app`'s `.csproj` (accepted as a transitional state, not a competing alternative)

Until Alternative 4's `PackNugetTask` merge is implemented (tracked under the separate "Phase goal"
ROADMAP line), `sample-app/SampleApp/SampleApp.csproj` is extended by hand exactly the way it already
includes the forward-direction `Interop.cs` — a `<Compile Include>` pointing at
`nugetGenerateShims`'s raw output directory, plus a manually-added `<PackageReference>` for the bound
NuGet package and `<AllowUnsafeBlocks>true</AllowUnsafeBlocks>`. This is not a rejected alternative;
it is the necessary bridge state while this ADR's task exists but the packaging merge does not yet.
The full worked example below shows both the interim and the target production shape.

---

### 8. Gradle task shape: new `nugetGenerateShims` task, sibling of `nugetGenerateBindings` (chosen)

A new task class parallels `NugetGenerateBindingsTask` exactly, differing only in the language it
emits and one extra input (the native library name for `[DllImport]`):

```kotlin
abstract class NugetGenerateShimsTask : DefaultTask() {
  @get:InputFile abstract val reverseIrFile: RegularFileProperty
  @get:Input abstract val nativeLibraryName: Property<String>   // for [DllImport] EntryPoint target
  @get:OutputDirectory abstract val csharpOutputDir: DirectoryProperty  // build/nuget-interop/csharp/
}
```

**Pros:**
- Single-responsibility per task/per-target-language, matching the precedent set by ADR-045's
  rejection of a merged `nugetSync` task ("the csproj generation step is a pure computation... should
  be independently up-to-date-checked").
- `nativeLibraryName` is meaningless to the Kotlin generator (which never emits a `[DllImport]`) — a
  merged task would force an irrelevant input onto Kotlin-stub generation, causing spurious
  re-generation when only the native lib's `baseName` changes.
- Both tasks are pure functions of `reverse-ir.json` (+ their own small config) and can run in
  parallel; neither depends on the other's output.

**Cons:**
- Two tasks to wire into `nugetImport` instead of one (minor).

### 9. Extend `NugetGenerateBindingsTask` to also emit C# (rejected)

**Rejected** for the same single-responsibility reasoning as ADR-045 Alternative 2 (merged
`nugetSync`). Mixing two output languages in one task action also means one task's up-to-date check
depends on inputs (`nativeLibraryName`) that are irrelevant to half of its own output.

---

### 10. Shared bridgeable-method extraction to prevent Kotlin/C# order drift (chosen)

`NugetGenerateBindingsTask` (ADR-048) already implements `isV1Bridgeable(method)` and filters+orders
`cls.methods` before deciding the registration export's parameter list. `NugetGenerateShimsTask` must
independently arrive at **the exact same filtered, ordered method list** for the same `RirClass`, or
the Kotlin registration export's parameter count/order and the C# `[ModuleInitializer]`'s pointer
argument order silently diverge — a mismatch that would compile cleanly on both sides yet corrupt
every call through the mismatched pointer slots at runtime.

**Decision:** extract the filter into a single shared, pure function (e.g.
`fun bridgeableStaticMethods(cls: RirClass): List<RirMethod>` in `RirParsing.kt` or a new
`RirBridging.kt`) and have **both** `generateKotlinStubs` and the new `generateCSharpShims` call it.
Diagnostics for skipped members stay solely in `nugetGenerateBindings` (ADR-048 already owns that
responsibility); `nugetGenerateShims` reuses the shared filter silently to avoid duplicate warnings
when both tasks run together under `nugetImport`.

**Pros:** eliminates an entire class of latent, hard-to-diagnose bugs (parameter-order drift) by
construction rather than by convention. **Cons:** none identified; this is a straightforward
refactor-for-safety, not a design trade-off.

### 11. Duplicate the filter logic in each generator (rejected)

**Rejected.** Two independently-maintained copies of the same predicate are exactly the kind of
"looks identical today, drifts silently tomorrow" risk the shared registration contract cannot
tolerate — a single method added to `isV1Bridgeable`'s allowed set in one copy but not the other
would break every existing call for that type without a compile error on either side.

---

### 12. Native library name source: reuse `sharedLib.baseName`, the same value threaded to the KSP forward generator (chosen)

The forward-direction KSP processor already receives the compiled shared library's `baseName` (e.g.
`"sample"`) via a Gradle-arg handshake (`NugetPlugin.kt`, `argMethod.invoke(ksp, "nuget.libraryName",
baseName ...)`), and every generated `[DllImport("sample", ...)]` in `CirClassRenderer.kt` uses it.
`nugetGenerateShims`'s `[DllImport]` for the Kotlin registration export must target the identical
library name — it is P/Invoking into the same compiled `.dll`/`.dylib`/`.so`. The plugin derives it
the same way: `kotlin.targets.filterIsInstance<KotlinNativeTarget>().flatMap { it.binaries
.filterIsInstance<SharedLibrary>() }.firstOrNull()?.baseName`, computed inside the (currently
separate) `dependencies`/`bind` `afterEvaluate` block, with a fail-fast `GradleException` if no
shared-lib binary exists on a project that declares `bind { }` (there is nothing to P/Invoke into
otherwise).

**Pros:** single source of truth for the library name across both directions; a rename of
`baseName` in the KMP `binaries { }` block automatically flows to both the forward `Interop.cs` and
the reverse shims. **Rejected alternative:** a separately user-configurable name in the `nuget { }`
DSL — rejected because it can drift from the actual compiled binary's name and produce a
`DllNotFoundException` at first bridge call, a strictly worse failure mode than deriving it from the
single existing source of truth.

## Decision

Use **Alternatives 1, 4, 8, 10, 12**: the corrected inverse type-mapping table (`IntPtr` +
narrowed-integer non-blittable fixes), shim delivery merged into the existing packed-nupkg
`contentFiles` mechanism (with the interim dev-loop `<Compile Include>` bridge state until the
packaging merge lands), a new sibling `nugetGenerateShims` task, a shared bridgeable-method
extraction function used by both generators, and the native library name sourced from the same
`sharedLib.baseName` the forward direction already uses.

### Generated artifact naming

Per bound `RirClass`, one file: `{TypeName}Registration.cs`, containing one `internal static class
{TypeName}Registration` in the **same namespace** as the original C# type (the RIR `namespace.name`
verbatim — no renaming, unlike the Kotlin side's package aliasing, because the shim calls the real
C# type directly and must compile in a namespace where that type is visible without an extra
`using`). One thunk method per bridgeable method, named `{MethodName}_Thunk`.

### Exception behaviour (v1: let it crash)

**v1 thunk bodies do not catch.** The thunk calls the real C# method directly; if that method
throws, the exception escapes the `[UnmanagedCallersOnly]` method. A managed exception **cannot
propagate across the managed↔native boundary** — the .NET runtime treats it as an unrecoverable
condition and tears the process down via `FailFast`/`__fastfail()` (confirmed by community reports,
e.g. discussion linked from [dotnet/runtime#97952](https://github.com/dotnet/runtime/issues/97952)).
For v1 this fast-fail *is* the chosen behaviour: a loud, immediate process termination that names the
originating C# exception in the crash output.

This is deliberately preferred over catch-and-sentinel. ADR-041's prose is permissive — "v1 thunks
**may** return sentinel values on exception with diagnostics deferred to a later ADR" — not
mandatory, and its own illustrative `Clamp_Thunk` (all-primitive) has no `try/catch`. The problem
with a catch-and-sentinel v1 is that a primitive-returning thunk's sentinel (`0`/`false`/`'\0'`) is
**indistinguishable from a legitimate result**, and a `void` thunk's swallow is invisible entirely —
turning a thrown exception into a silent wrong value. Fast-failing avoids that failure class outright:
you never get a silently-wrong answer, only an unmissable crash.

| Return shape | v1 behaviour on a thrown C# exception |
|---|---|
| `void` | exception escapes → process fast-fails (never a silent no-op) |
| `string` | exception escapes → process fast-fails (never a spurious `IntPtr.Zero`/null) |
| primitive | exception escapes → process fast-fails (never a silent `0`/`false`) |

**Trade-off:** any exception — including one a C# caller would normally handle (e.g. a
`JsonException` on malformed input) — terminates the whole host process rather than being surfaced
to Kotlin as a catchable error. This is acceptable only because v1's bridgeable surface is narrow
(static methods, primitive/string types) and the crash is diagnosable. **Graceful propagation — the
error-out ABI that lets Kotlin `catch` a C# exception — is Phase 11** (mirror of ADR-023/024),
already tracked in ROADMAP.

### `[ModuleInitializer]` and registration call shape

```csharp
[ModuleInitializer]
internal static unsafe void Initialize()
{
    nuget_{export}_register(
        (IntPtr)(delegate* unmanaged[Cdecl]<T1, T2, ..., TRet>)(&Method1_Thunk),
        (IntPtr)(delegate* unmanaged[Cdecl]<..., TRet>)(&Method2_Thunk),
        ...
    );
}
```

Arguments are passed in the exact order `bridgeableStaticMethods(cls)` returns — the same order both
generators derive from the shared function (Alternative 10). `unsafe` is confined to this one method;
thunk bodies stay ordinary (safe) C# because parameters/returns use `IntPtr`/blittable primitives only
(Alternative 1).

### `[DllImport]` for the registration export

```csharp
[DllImport("{nativeLibraryName}", CallingConvention = CallingConvention.Cdecl,
    EntryPoint = "nuget_{ns_snake}_{type_snake}_register")]
private static extern void nuget_{ns_snake}_{type_snake}_register(IntPtr ptr1, IntPtr ptr2, ...);
```

`{nativeLibraryName}` is the `sharedLib.baseName` value (Alternative 12) — for `sample-library`,
`"sample"`, matching every existing `[DllImport("sample", ...)]` the KSP forward generator already
emits.

### Required project settings

`<AllowUnsafeBlocks>true</AllowUnsafeBlocks>` is required (ADR-041) and is **already satisfied for
free** once Alternative 4's packaging merge lands, because `PackNugetTask.generateTargets()` already
unconditionally emits it in `build/{id}.targets` for the forward direction — no new MSBuild property
is introduced by this ADR. `net8.0` (the GOALS §5.1 floor) is already the synthetic `interop.csproj`'s
TFM (ADR-045); the shim's own compiled TFM is whatever the *consuming* project targets (`net8.0`+),
since the shim ships as source, not a prebuilt assembly.

### Gradle wiring

```kotlin
// Inside the existing `deps.isEmpty()` afterEvaluate block, after nugetExtractApi/nugetGenerateBindings.
// `nativeLibraryName` is resolved LAZILY via a Provider, not eagerly: an eager requireNotNull in the
// afterEvaluate block fails any project that declares `bind {}` without a configured shared-lib binary
// (which every plugin-wiring test does). The lazy form defers the same fail-fast to task-execution time,
// when `nugetGenerateShims` actually runs, rather than at configuration time for unrelated projects.
val nativeLibraryName: Provider<String> = project.provider {
    requireNotNull(
        kotlin?.targets?.filterIsInstance<KotlinNativeTarget>()
            ?.flatMap { it.binaries.filterIsInstance<SharedLibrary>() }
            ?.firstOrNull()?.baseName
    ) {
        "[nuget] No Kotlin/Native shared library binary configured. " +
        "nuget { dependencies { bind { ... } } } requires a `binaries { sharedLib { ... } }` " +
        "target to host the registered C# thunks."
    }
}

val nugetGenerateShims: TaskProvider<NugetGenerateShimsTask> =
    project.tasks.register("nugetGenerateShims", NugetGenerateShimsTask::class.java) { task ->
        task.group = "nuget"
        task.description = "Generates C#-side [UnmanagedCallersOnly] thunks and startup " +
            "registration shims from reverse-ir.json"
        task.reverseIrFile.set(nugetExtractApi.flatMap { it.reverseIrFile })
        task.nativeLibraryName.set(nativeLibraryName)
        task.csharpOutputDir.set(interopDir.map { it.dir("csharp") })
    }

nugetImport.configure { task -> task.dependsOn(nugetGenerateShims) }
```

### Updated task graph

```
nugetExtractApi             reverse-ir.json
    │
    ├──▶ nugetGenerateBindings   reverse-ir.json → Kotlin stubs + registration @CName (ADR-048)
    │                            output: build/nuget-interop/kotlin/
    │
    └──▶ nugetGenerateShims      reverse-ir.json → C# thunks + [ModuleInitializer] (this ADR)
                                 output: build/nuget-interop/csharp/
                                 (siblings; both depend only on nugetExtractApi;
                                  both call the shared bridgeableStaticMethods() filter)
    ▼
nugetImport                 umbrella task; dependsOn both

[separate ROADMAP "Phase goal" item — not this ADR:]
packNuget                   merges build/nuget-interop/csharp/*.cs into
                             contentFiles/cs/any/ alongside KSP's Interop.cs;
                             adds <dependencies> to the .nuspec for bound packages
```

### Full worked example — `Newtonsoft.Json.JsonConvert.SerializeObject(int): string`

Same RIR input as ADR-048:

```json
{
  "assemblies": [{
    "packageId": "Newtonsoft.Json",
    "assemblyName": "Newtonsoft.Json",
    "namespaces": [{
      "name": "Newtonsoft.Json",
      "types": [{
        "kind": "class",
        "name": "JsonConvert",
        "methods": [
          {
            "name": "SerializeObject",
            "isStatic": true,
            "returnType": { "kind": "string" },
            "parameters": [{ "name": "value", "type": { "kind": "primitive", "name": "int" } }]
          }
        ],
        "properties": []
      }]
    }]
  }]
}
```

Same DSL configuration as ADR-048 (`bind { packageName = "newtonsoft.json"; include("Newtonsoft.Json") }`
inside `dependency("Newtonsoft.Json") { version = "13.0.3" }`), on a project whose
`binaries { sharedLib { baseName = "sample" } }` matches `sample-library`.

**Generated `build/nuget-interop/csharp/JsonConvertRegistration.cs`:**

```csharp
// <auto-generated>
// Generated by nugetGenerateShims from reverse-ir.json (ADR-049). Do not edit by hand.
// C# registration shim for Newtonsoft.Json.JsonConvert.
// </auto-generated>
namespace Newtonsoft.Json
{
    using System;
    using System.Runtime.CompilerServices;
    using System.Runtime.InteropServices;

    internal static class JsonConvertRegistration
    {
        [DllImport("sample", CallingConvention = CallingConvention.Cdecl,
            EntryPoint = "nuget_newtonsoft_json_json_convert_register")]
        private static extern void nuget_newtonsoft_json_json_convert_register(IntPtr serializeObjectPtr);

        [ModuleInitializer]
        internal static unsafe void Initialize()
        {
            nuget_newtonsoft_json_json_convert_register(
                (IntPtr)(delegate* unmanaged[Cdecl]<int, IntPtr>)(&SerializeObject_Thunk));
        }

        // v1: no try/catch — a thrown C# exception escapes and fast-fails the host process
        // (loud failure preferred over a silently-wrong sentinel; graceful propagation is Phase 11).
        [UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
        private static IntPtr SerializeObject_Thunk(int value)
        {
            string result = JsonConvert.SerializeObject(value);
            return Marshal.StringToCoTaskMemUTF8(result);
        }
    }
}
```

**Interim dev-loop wiring — `sample-app/SampleApp/SampleApp.csproj`** (until the `packNuget` merge
from Alternative 4 lands; mirrors the existing manual `Interop.cs` inclusion already in this file):

```xml
<Project Sdk="Microsoft.NET.Sdk">

    <PropertyGroup>
        <OutputType>Exe</OutputType>
        <TargetFramework>net10.0</TargetFramework>
        <ImplicitUsings>enable</ImplicitUsings>
        <Nullable>enable</Nullable>
        <!-- New: required for the [ModuleInitializer] function-pointer address-of syntax -->
        <AllowUnsafeBlocks>true</AllowUnsafeBlocks>
    </PropertyGroup>

    <!-- Existing: pre-generated forward-direction C# bindings -->
    <ItemGroup>
        <Compile Include="../../sample-library/build/nuget/SampleLibrary.1.0.0/contentFiles/cs/any/Interop.cs" Link="Generated/Interop.cs" />
    </ItemGroup>

    <!-- New: generated reverse-direction registration shim -->
    <ItemGroup>
        <Compile Include="../../sample-library/build/nuget-interop/csharp/JsonConvertRegistration.cs" Link="Generated/JsonConvertRegistration.cs" />
    </ItemGroup>

    <!-- New: the real C# NuGet dependency the shim calls into -->
    <ItemGroup>
        <PackageReference Include="Newtonsoft.Json" Version="13.0.3" />
    </ItemGroup>

    <!-- Existing: native library copy -->
    <ItemGroup>
        <None Include="../../sample-library/build/nuget/SampleLibrary.1.0.0/runtimes/osx-arm64/native/libsample.dylib"
              Condition="$([MSBuild]::IsOSPlatform('OSX'))"
              CopyToOutputDirectory="PreserveNewest"
              Link="libsample.dylib" />
        <None Include="../../sample-library/build/nuget/SampleLibrary.1.0.0/runtimes/win-x64/native/sample.dll"
              Condition="$([MSBuild]::IsOSPlatform('Windows'))"
              CopyToOutputDirectory="PreserveNewest"
              Link="sample.dll" />
    </ItemGroup>

</Project>
```

**Target production shape** (once the `packNuget`/`.nuspec` merge from Alternative 4 is implemented,
tracked under the separate "Phase goal" ROADMAP item): `JsonConvertRegistration.cs` instead ships at
`sample-library/build/nuget/SampleLibrary.1.0.0/contentFiles/cs/any/JsonConvertRegistration.cs`
alongside `Interop.cs`, the `.nuspec` gains
`<dependencies><group targetFramework="net8.0"><dependency id="Newtonsoft.Json" version="13.0.3" /></group></dependencies>`,
and a real consumer needs only:

```xml
<PackageReference Include="SampleLibrary" Version="1.0.0" />
```

— NuGet's `contentFiles` convention compiles `JsonConvertRegistration.cs` into the consumer's own
assembly (guaranteeing `[ModuleInitializer]` execution per the load-bearing argument in Alternative
4), `build/SampleLibrary.targets` sets `AllowUnsafeBlocks`, and the `<dependencies>` entry pulls
`Newtonsoft.Json 13.0.3` transitively. No manual `.csproj` editing is required in the target state;
the interim state above exists only because that merge is out of this ADR's scope.

**Consumer C# code (unchanged from what a JsonConvert.SerializeObject caller would write without any
of this bridge existing):**

```csharp
// Ordinary C# code in SampleApp — completely unaware that Kotlin is involved.
// (This call happens to originate from Kotlin via the registered thunk; from the C#
// developer's perspective there is nothing to write here — the shim is invisible.)
```

There is deliberately no C#-authored call site for this feature: the shim's only "consumer" is
Kotlin code calling `JsonConvert.serializeObject(42)` (per ADR-048's worked example) — the C# side is
100% generated plumbing that a C# developer never touches or even sees in IntelliSense (`internal`
throughout).

## Consequences

### New `nuget/` Gradle plugin additions

- `NugetGenerateShimsTask.kt` — task class with `@InputFile reverseIrFile`, `@Input
  nativeLibraryName`, `@OutputDirectory csharpOutputDir`; a pure `generateCSharpShims(file: RirFile,
  nativeLibraryName: String): List<GeneratedFile>` function mirroring `generateKotlinStubs`'s shape.
- A new `rir/RirBridging.kt` holding the shared `bridgeableStaticMethods(cls: RirClass):
  List<RirMethod>` and `registrationExportName(namespaceName, typeName): String`, extracted from
  `NugetGenerateBindingsTask.kt`'s private `isV1Bridgeable`/`toTypeSnake` logic. Both generators now
  call these, closing the parameter-order and export-name drift risk identified in Alternative 10/11.
- Wiring in `NugetPlugin.kt`'s existing `deps.isEmpty()`-guarded `afterEvaluate` block: derive
  `nativeLibraryName` from `sharedLib.baseName` via a lazily-resolved `Provider<String>` (fail-fast
  at task-execution time if absent — see the Gradle wiring note above), register `nugetGenerateShims`,
  add `nugetImport.dependsOn(nugetGenerateShims)`.

### Deferred to the separate "Phase goal" ROADMAP item (not this ADR)

- Extending `PackNugetTask` to merge `build/nuget-interop/csharp/*.cs` into the packed nupkg's
  `contentFiles/cs/any/` alongside the KSP forward output.
- Emitting `<dependencies>` in the generated `.nuspec` for each bound package at its exact resolved
  version (requires reading `project.assets.json`'s resolved version, not just the DSL-declared
  version, which may be absent/floating).
- Updating `sample-app`/`sample-library` to demonstrate a real end-to-end
  `Newtonsoft.Json`-via-Kotlin round trip, exercised from `SampleApp.Tests`.
- Bridging the two currently-independent `afterEvaluate` blocks (`publish { }`/`packNuget` and
  `dependencies { }`/`nugetGenerateShims`) so a project using both blocks together produces one
  coherent nupkg.

### Documented v1 limitations (flagged, not silently accepted)

- **A thrown C# exception terminates the whole host process** rather than surfacing to Kotlin as a
  catchable error — even exceptions a C# caller would normally handle (e.g. a `JsonException` on
  malformed input). This is the accepted v1 trade-off: fast-fail is loud and diagnosable, and it
  avoids the silent-wrong-value class that catch-and-sentinel would introduce for primitive/`void`
  returns. Graceful propagation (the error-out ABI that lets Kotlin `catch` a C# exception) is Phase
  11 (mirror of ADR-023/024), already tracked in ROADMAP.
- The generator must **not** wrap thunk bodies in `try/catch`. A future contributor "hardening" a
  thunk by catching and returning a sentinel would silently convert crashes into wrong values for
  primitive/`void` returns — this must stay in generator code comments as a guardrail.

### Breaking changes

None. `nugetGenerateShims` is a new additive task producing new output files; no existing generated
artifact (forward or reverse) changes shape. `PackNugetTask` is unchanged by this ADR (its extension
is deferred, see above).

## Scope

**In v1 (this ADR):**
- `NugetGenerateShimsTask` generating one `{TypeName}Registration.cs` per bound `RirClass` with at
  least one v1-bridgeable static method (same bridgeable-subset rules as ADR-043/048, via the shared
  `bridgeableStaticMethods` function).
- The corrected inverse type-mapping table: `IntPtr` for strings, `byte`/`ushort` narrowing for
  `bool`/`char`, direct blittable mapping for the remaining primitives.
- `[UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]` thunks, one per bridgeable
  method, calling the real method directly with **no `try/catch`** — a thrown C# exception escapes and
  fast-fails the host process (loud failure over silent sentinel; graceful propagation is Phase 11).
- One `[ModuleInitializer] internal static unsafe void Initialize()` per `RirClass`, calling the
  Kotlin registration export with thunk addresses in `bridgeableStaticMethods` order.
- `[DllImport]` targeting `sharedLib.baseName`, the same value already used by the forward-direction
  KSP generator.
- Output directory `build/nuget-interop/csharp/`; `nugetGenerateShims` wired into `nugetImport`.
- Interim manual `<Compile Include>` wiring in `sample-app/SampleApp/SampleApp.csproj` as a stopgap
  consumption path for this repo's own dev loop.

**Deferred (post-v1, separate ROADMAP items):**
- Merging the generated shim files into `PackNugetTask`'s `contentFiles/cs/any/` output and emitting
  `<dependencies>` in the `.nuspec` — the "Phase goal: consume … end-to-end" ROADMAP line.
- Full exception propagation from a C# thunk back to a Kotlin exception (error-out ABI mirror of
  ADR-023/024) — Phase 11.
- Instance methods, object-handle parameters/returns (`GCHandle`) — Phase 9, blocked on the
  post-v1 `RirObjectHandleType` extension to the RIR (ADR-046 deferred scope).
- Static C# properties (getter/setter thunks) — same function-pointer pattern, deferred for scope
  only per ADR-048.
- `Task<T>`/`IAsyncEnumerable<T>`-returning methods — Phase 11/mirror of ADR-019/026.

## Judgment calls flagged for human confirmation

1. **`IntPtr` vs `byte*` for string parameters/returns** — this ADR chose `IntPtr` to minimise
   `unsafe` surface, diverging from the literal `byte*` framing in ADR-041's illustrative code (not
   its binding Decision text). Functionally equivalent; a style call, not a correctness call.
2. **Exception behaviour — RESOLVED (2026-07-05): let it crash.** v1 thunks do not catch; a thrown
   C# exception escapes and fast-fails the host process. Chosen over catch-and-sentinel because a
   primitive/`void` sentinel is indistinguishable from a real result (silent wrong value), whereas a
   crash is unmissable. ADR-041's "sentinel, deferred" prose is permissive ("may"), so this does not
   contradict it. Graceful propagation is deferred to Phase 11.
3. **Shim delivery via the packed nupkg's `contentFiles`, wiring deferred to a separate ROADMAP item**
   — this ADR decides the *mechanism* (merge into the existing `contentFiles` convention, not a
   compiled DLL) but does not implement the `PackNugetTask`/`.nuspec` merge itself, since that couples
   two previously-independent plugin code paths and is explicitly a separate, larger "Phase goal"
   ROADMAP line. Confirm this scope split is acceptable rather than folding the merge into this
   feature.
4. **Exact resolved dependency version in future `<dependencies>` entries** — flagged that the
   version must come from `project.assets.json`'s resolved version, not the (possibly absent/
   floating) DSL-declared version, to guarantee ABI match between the generated shim and the
   referenced package. This is a note for the deferred packaging work, not implemented here.
