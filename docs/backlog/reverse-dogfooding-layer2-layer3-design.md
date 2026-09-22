# Reverse end-to-end dogfooding (layer 2) and forward real-klib admission (layer 3)

> Carried forward from `docs/research/roadmap/real-package-dogfooding.md` (deleted 2026-09-22) so the
> design survives the memo. The reverse diagnostics census (layer 1) shipped:
> `nuget-plugin/src/test/resources/dogfood/SUMMARY.md`, `scripts/verify-dogfood.sh`, the `dogfood` CI
> job. This file is the remaining two layers.

## Layer 2: reverse end to end, standalone `dogfood/` build

Mirrors `smoke-test/`: its own `settings.gradle.kts`, consumes the plugin by coordinate from
`build/local-repo` (reuses the smoke-test job's publish step, so it also proves the by-coordinate path
with a real `bind {}`), binds two packages (`NuGet.Versioning`, `Humanizer.Core`), has one Kotlin
function per package that calls the bound API, runs `packNuget`, and a `DogfoodTests` xunit project
calls those Kotlin functions from a .NET host. That is one smoke round trip per package: C# host to
Kotlin to C# package and back.

Asserts, none of which the layer 1 census can: the generated Kotlin compiles (`compileKotlin<Target>`),
`nugetCompileInterop` compiles the shims, the smoke call returns the right value. This closes the gap
the census's `SUMMARY.md` header states explicitly: `"generation": "ok"` means both generators returned
without throwing, not that anything compiles. Serilog is a known case where it does not (see
[`csvhelper-open-generic-method-signature-collision.md`](csvhelper-open-generic-method-signature-collision.md)).

Cost inferred at 8 to 12 minutes on one macOS leg (one Kotlin/Native link); run nightly plus
`workflow_dispatch` only, same reasoning as the layer 1 job: a feed or build outage must not turn a
required PR check red.

A cheaper intermediate, if this slips: `dotnet build` the `generateCSharpShims` output against the
restored package inside the existing census job — no Kotlin/Native needed for the C# half alone.

## Layer 3: forward real klibs, same `dogfood/` build

Add `publish { admit(...) }` (ADR-154) for a published klib type, one per PR because each is expected to
surface a bug:

| Klib | Admit | What it stresses |
|---|---|---|
| `co.touchlab:kermit` | `co.touchlab.kermit.Severity` | The exact ADR-154 spiked shape (plain top-level enum) from a published artifact instead of a project dependency: klib origin resolution by coordinate, plus runtime execution, which ADR-154 never ran. Smallest possible first forward case. |
| `io.ktor:ktor-client-logging` | `io.ktor.client.plugins.logging.LogLevel` | Enum with constructor properties (`info`, `headers`, `body`). Large transitive closure (ktor-client-core, ktor-io, coroutines) stresses closure walk time. |
| `io.ktor:ktor-http` | `io.ktor.http.Url` | A class with a non-public constructor, many `String`/`Int` properties, and members mentioning unadmitted siblings (`URLProtocol`, `Parameters`). Proves the per-type unit: the class is kept, those members skip named with an `add admit("...")` hint. |
| `org.jetbrains.kotlinx:kotlinx-datetime` | `kotlinx.datetime.LocalDate` | `Comparable<T>` supertype edge (ADR-101 deferral), companion factories, `@Serializable(with=...)` annotations, expect/actual in a published klib (ADR-074 trap), and the `Instant` move to `kotlin.time`. |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | `kotlinx.serialization.json.JsonElement` and arms | A sealed hierarchy read from a klib: `JsonObject : Map<String, JsonElement>` and `JsonArray : List<JsonElement>` by delegation, `JsonNull` object arm, `JsonPrimitive` itself sealed. Stresses sealed arm discovery across a module boundary and collection-implementing classes. Most likely to find a real bug. |
| `com.squareup.okio:okio` | `okio.ByteString` | `ByteArray` in and out (ADR-151 blit), `operator get`, `Comparable`, companion extension functions (`String.encodeUtf8()`), `internal`/`@JvmField` noise. |

Asserts: `packNuget` succeeds, a `GeneratedBindingsCheck`-style net8.0 warnings-as-errors compile of the
generated `Interop.cs`, a golden of `NugetDiagnostics.json` (ADR-100's forward diagnostic file is
already a census; `NugetReportDiagnosticsTask.kt` parses it), and one xunit round trip (`Severity.Warn`
crosses and comes back).

kermit first, then ktor's `LogLevel` and `Url`; datetime, serialization-json and okio follow.

## Decisions (approved by the human under the batch rule; not pending)

- One `dogfood/` build for both directions, not two: a real consumer does both in one module (`bind {}`
  and `publish { admit() }` together, by coordinate), and that composition is itself untested outside
  `test-library`.
- Layer 2 ships before layer 3.

## Alternatives rejected

- Put real packages in `test-library`'s `nuget { dependencies { } }`: every PR's required `test` job
  would then depend on more of nuget.org and on third-party surfaces the change did not touch;
  `MimeMapping` is enough there.
- Run either layer inside `verify.sh`: feed flake in the local gate, and minutes added to a script
  already run many times a day. `scripts/verify-dogfood.sh` is the right seam and layer 2/3 extend it
  (`--e2e`), not `verify.sh` itself.
- Vendor `.nupkg` files into the repo for full offline runs: licence and size cost; an exact-version pin
  plus an Actions cache gives the same determinism.
- Float klib/package versions to catch upstream drift: makes a red build mean "they released" rather
  than "we broke something". Bump pins deliberately, one PR each.

## Unspiked, spike before building

1. Does the generated output compile for a real package, end to end (Kotlin and C# both)? Only
   "does not throw" was measured by the layer 1 census. Serilog is known bad (see
   [`csvhelper-open-generic-method-signature-collision.md`](csvhelper-open-generic-method-signature-collision.md));
   if the compile step is red for most packages, layer 2's first run will be red across the board.
2. The forward direction entirely: `admit(...)` against a published klib (resolved by coordinate, not
   `project(...)`) was never run. Nobody has verified the ADR-154 origin resolution even finds a klib
   resolved this way; if it doesn't, layer 3 fails loudly (no admitted type), not silently.
3. CI cold timing on the runner class layer 2/3 will use, and whether `actions/cache` of
   `~/.nuget/packages` / the Gradle klib cache behaves the same way the layer 1 job's does.
4. A Windows leg for layer 2 (path separators in `asset`, `mingwX64` link) — not spiked at all.

## Deferred past layer 2/3

- Transitive binding: every layer 1 shortlist package has an empty net8.0 dependency closure on
  purpose; a package whose public API mentions types from another package (e.g.
  `Microsoft.Extensions.Logging.Abstractions`) is a separate hazard, not covered by either layer as
  scoped here.
- A `subject` field on `RirDiagnostic` so the unbound-type-reference histogram in `SUMMARY.md` stops
  parsing `reason` prose text.
- A user-facing `nugetCensus` report task built on the layer 1 `census(rir)` function.
