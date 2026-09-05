# `suspend fun load(): Result<T>` on a class emits unresolvable C# with no diagnostic.

**What breaks**: `class Service { suspend fun load(): Result<String> = Result.success("ok") }`
compiles clean on the Kotlin side and produces a generated `public Task<Result> LoadAsync(...)`
in `Interop.cs`, a `TaskCompletionSource<Result>`, and `new Result(resultPtr)`, all over the bare
simple name `Result` with no generic argument and no `TestLibrary.Result` type ever generated. A
consumer referencing the package gets `CS0246: The type or namespace name 'Result' could not be
found`, with no KSP warning pointing at `load` at all.

**Root cause**: `suspend fun` is not plan-routed. The legacy suspend route
(`cir/CirClassTranslator.kt`, `~:551-590`) reads the KSP `returnType` directly:
`method.returnType?.resolve()?.expandAliases()?.declaration?.simpleName?.asString()` gives
`"Result"`, and `asyncReturnType = KOTLIN_TO_CSHARP_PARAM[methodReturn] ?: methodReturn` falls
through the lookup miss straight to the raw string `"Result"`, with no bridgeable-subset check at
all on this route (unlike the plan-routed callables `ForwardCallablePlanner`/
`ForwardDiagnostic.kt` cover).

**Why it went unnoticed**: [ADR-108](docs/adr/108-result-return-mapping.md) only rewrites the
plan-routed return position (`ForwardCallablePlanner.planOrSkip`); the suspend route is a
structurally separate code path that never reaches `planOrSkip`, so the ADR's `Result<T>` → `T`
lowering does not apply here, and nothing on this route validates the return type name before
splicing it into the generated signature. No prior fixture combined `suspend` with a `Result`
return, so the unmapped-simple-name emission was never exercised.

**Verified**: pinned as a red (`@XFail`) test,
`Tier1ResultReturnTest.kt`'s `` `suspend method returning Result either binds correctly or skips
named` ``: the weakest honest contract (bind correctly, or skip named) still fails today; the
shape instead emits a C# member typed over the bare `Result`.

**Discovered alongside** [#56](https://github.com/xxfast/kotlin-native-nuget/issues/56) part 1,
[ADR-108](docs/adr/108-result-return-mapping.md), which explicitly scopes the suspend route out
(§Scope table, "legacy suspend route ... not plan-routed"). Fix shape: either route `suspend fun`
returning `Result<T>` through the same lowering ADR-108 gives ordinary returns, or give the legacy
route its own bridgeable-subset check so an unmappable return type skips named
(`SKIPPED_UNSUPPORTED_TYPE`) instead of splicing an unresolvable C# type name.
