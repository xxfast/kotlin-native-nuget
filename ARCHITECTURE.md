# Architecture

How the bridge is built and how calls cross it at runtime. For a high-level overview see the
[README](README.md#architecture); for individual design decisions see [docs/adr/](docs/adr/).

## Build pipeline

```
Gradle Plugin (Kotlin side)          NuGet Package       C# Consumer
┌─────────────────────────┐     ┌────────────────┐     ┌──────────────┐
│ Compile Kotlin/Native   │     │ native libs    │     │ Add package  │
│ KSP → CIR → Interop.cs  │────>│ Interop.cs     │────>│ Build        │
│ KotlinPoet → Bridges.kt │     └────────────────┘     │ Run          │
│ Link shared libraries   │                            └──────────────┘
│ Package as .nupkg       │
└─────────────────────────┘
```

- **Gradle plugin** compiles Kotlin/Native, runs KSP to generate C# bindings (via the CIR model) and
  Kotlin bridge wrappers (via KotlinPoet), links shared libraries, and packages everything.
- **NuGet package** ships native libs + pre-generated `Interop.cs`. No consumer-side tooling required.
- **Consumer** just includes the package — bindings are ready at build time.

## Two mirrored intermediate representations (CIR ↔ RIR)

Bindings are generated through an intermediate representation (IR) in **each** direction, and the two
are deliberate mirrors. In both, an IR sits in the middle: a *reader* fills it from one language's
metadata, and a *renderer* emits the other language's source.

```
   Forward:  Kotlin → C#  (implemented)       Reverse:  C# → Kotlin  (Phase 8, in progress)

   Kotlin source                              NuGet package (.dll)
        │  KSP reads Kotlin symbols                │  NugetMetadataReader reads ECMA-335 metadata
        ▼                                          ▼
   CIR  (CirModel)                            RIR  (RirModel)  ──►  reverse-ir.json
        │  CirRenderer                             │  Kotlin-stub + C#-shim codegen *
        ▼                                          ▼
   Interop.cs  +  Bridges.kt                  Kotlin stubs  +  C# registration shims *
                                              * = not yet implemented (next roadmap item)
```

| Role | Forward — CIR | Reverse — RIR |
|---|---|---|
| Middle IR | `CirModel` (in `nuget-processor/`) | `RirModel` (in `nuget-plugin/`) |
| Reader that fills it | KSP processor, over Kotlin symbols | `NugetMetadataReader` C# tool, over ECMA-335 assembly metadata ([ADR-042](docs/adr/042-assembly-metadata-extraction.md)) |
| Handoff | in-memory, same JVM process | `reverse-ir.json` across a process boundary ([ADR-046](docs/adr/046-reverse-ir-model-and-json-contract.md)) |
| Renderer that emits source | `CirRenderer` → `Interop.cs` / `Bridges.kt` | Kotlin-stub + C#-shim codegen * |
| Runtime direction | C# calls Kotlin | Kotlin calls C# |
| Decision record | [ADR-004](docs/adr/004-cir-intermediate-representation.md) | [ADR-046](docs/adr/046-reverse-ir-model-and-json-contract.md) |

The reverse *reader* and the `RirModel` now exist (the `nugetExtractApi` task drives `NugetMetadataReader`
to produce `reverse-ir.json`); the reverse *renderer* — generating Kotlin stubs and C# registration
shims — is the next roadmap item. Grows-per-feature logic lives only in the IR + reader/renderer;
the surrounding task plumbing is stable in both directions.

## Runtime call flow

Once packaged, calls cross the C ABI in **both** directions at runtime:

```
          C# Consumer                               Kotlin / Native        
┌─────────────────────────────┐             ┌─────────────────────────────┐
│ Func<> / Action<> argument  │             │ @CName export               │
│ pinned via GCHandle         │ --1 call--> │ reinterpret<CFunction<>>    │
│                             │  (P/Invoke) │ wraps the ptr as a lambda   │
│                             │             │                             │
│ your C# delegate is         │ <-2 invoke- │ Kotlin invokes the fn ptr   │
│ called back (reverse)       │   (fn ptr)  │ inside filter / map / ...   │
│                             │             │                             │
│ 3 receives the result       │  <-result-- │ returns a StableRef handle  │
└─────────────────────────────┘             └─────────────────────────────┘
```

- **Forward** (all prior phases) — C# calls Kotlin via P/Invoke: a `DllImport` entry point bound to a generated `@CName` export.
- **Reverse interop** (Phase 7) — C# passes a `Func<>`/`Action<>` as a delegate, pins it with `GCHandle`, and hands it over as a function pointer (`Marshal.GetFunctionPointerForDelegate`). Kotlin `reinterpret`s it to `CPointer<CFunction<…>>` and invokes it — e.g. inside `filter`/`map` — calling back into your C# code. Arguments and results cross as `StableRef` opaque handles. See [ADR-036](docs/adr/036-reverse-interop-mechanism.md).
