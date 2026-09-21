# A generic method whose type parameter never appears in its own signature maps as non-generic

> Discovered by the reverse dogfooding census over real published NuGet packages
> (`nuget-plugin/src/test/resources/dogfood/SUMMARY.md`), 2026-09-22.

`NugetMetadataReader/Program.cs` checks for generic parameters on **types** (`:1206`, `ExtractStruct`)
but a generic **method** is only ever rejected when the signature decoder actually meets its type
parameter (`skipped_open_generic`, `:3767`/`:3777`). A method whose type parameter appears nowhere in
its parameter list or return type — `UnregisterClassMap<TMap>()` on CsvHelper's `CsvContext`, which
also declares `UnregisterClassMap()` and `UnregisterClassMap(Type)` — never reaches that decoder path,
so it maps as if it were an ordinary non-generic method.

Two different consequences, both verified:

- **A crashing sibling collision.** `CsvContext` has both `UnregisterClassMap()` and
  `UnregisterClassMap<TMap>()`; mapped non-generic, they produce the same canonical managed signature
  and `ValidateManagedSignatures` (`Program.cs:2711-2721`, called from `:1178` and `:1845`) throws
  `InvalidDataException`. This is not a per-type skip: the reader process exits 1 and the **whole
  package** is unbindable (`nugetExtractApi` fails with "metadata reader failed (exit code 1)",
  `NugetExtractApiTask.kt:69-75`). `bind { exclude(...) }` cannot route around it because the namespace
  filter runs before type mapping (`Program.cs:172`, `IsNamespaceIncluded` at `:2449`) and `CsvContext`
  sits in the root `CsvHelper` namespace. Verified by execution: the committed golden
  `nuget-plugin/src/test/resources/dogfood/CsvHelper.33.0.1.census.json` records
  `"reader": "failed"` with this exact `readerError` message.
- **A silent wrong binding when there is no sibling to collide with.** Serilog declares
  `Log.ForContext<TSource>()` and `ILogger.ForContext<TSource>()` with no parameterless non-generic
  overload. Mapped non-generic (verified against the real RIR in the memo's spike), the generator
  emits, verbatim, `ILogger? result = Log.ForContext();` — a call with no way to infer `TSource`. The
  census golden shows Serilog's row as `"generation": "ok"` (`Serilog.4.2.0.census.json`), because
  nothing here throws; the resulting CS0411 (type argument cannot be inferred) at `nugetCompileInterop`
  is **inferred**, not compiled in the census harness, so a consumer binding Serilog would get a build
  failure in generated code with no diagnostic naming the member.

Fix shape (not yet built): check `method.GetGenericParameters().Count > 0` on the method itself, the
same test `ExtractStruct` already does on the declaring type, and emit `skipped_open_generic` for it
before the signature decoder is ever reached. That turns the CsvHelper case into a normal named skip
(package binds, one method missing) instead of a whole-package crash, and it stops the Serilog case
from binding at all.
