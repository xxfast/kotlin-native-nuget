# `include(...)` scope and transitive dependency admission pull in different directions

**The one knob that admits a dependency type into ADR-066's reachability closure is the same
package-prefix `include(...)` that replaces the module's own `rootPackage` roots, so widening the
scope to reach one third-party value type means exporting, owning and versioning that package's
whole public shape, and the alternative is a member that vanishes with a warning only the producer
sees.** Filed as [#247](https://github.com/xxfast/kotlin-native-nuget/issues/247); the correction
comment there narrows the original transitive-export ask to this precedence question.

What the two ADRs say, each correct on its own:

- [ADR-063](../adr/063-forward-declaration-level-export-scoping.md): an explicit `include` replaces
  the `rootPackage` default on purpose, mirroring the reverse side's `bind { include }`. Kept in #60
  when #55 asked for it to add instead; the hint now spells the whole line
  (`include("sample", "io.ktor.http")`) so the replacement is visible.
- [ADR-066](../adr/066-forward-export-reachability-closure.md): the closure admits a reachable
  dependency declaration iff its package passes that same predicate. Blast radius is "bounded by
  construction" because third-party packages never sit under `rootPackage`, and "the escape hatch
  already exists and is one line: `include(...)`". The unbounded walk is Alternative 3, rejected.

Where they meet: `include` is doing two jobs with one shape. For the module's own files it picks
roots. For the classpath it picks admission. Both are package prefixes, so per-type admission does
not exist: `Foo.url: io.ktor.http.Url` can only be reached by admitting `io.ktor.http`, which the
closure then walks (`Url`'s members reach `URLProtocol`, `Parameters`, ...), and every one of those
becomes part of the published C# surface. A closed enum from a dependency (`kermit.Severity`,
ktor's `LogLevel`) has no outward edges and would map by value, but it has no admission route other
than its package either.

The default is the other half of the question. Today an out-of-scope type in a public signature is
a named skip (`SKIPPED_UNEXPORTED_DEPENDENCY_TYPE`, warning, `NugetDiagnostics.json`), and the
declaration is absent from `Interop.cs` with no trace at the site (#249 covers the trace). #247
asks whether that should fail the build by default, with `include` as the explicit widening and an
opt-out for authors who accept the amputation, so the choice is made once per type by the library
author rather than discovered by a consumer.

Not verified here: whether `include("sample", "co.touchlab.kermit")` actually bridges a real klib
enum today. ADR-066's 2026-09-13 amendment notes an enum entry read from a klib throws
`Internal KSP Error` out of `getVisibility()`, worked around in the member walk only.

Candidate shapes, none chosen:

- a per-type admission verb (`include("io.ktor.http.Url")` by qualified name, the way #53 already
  lets `exclude` match a qualified name), admitting the type without walking its package
- admit any reachable `enum class` regardless of scope, since an enum carries no edges
- a strict mode that turns `SKIPPED_UNEXPORTED_DEPENDENCY_TYPE` into an error
- the custom type mapper escape hatch already listed under Future Improvements (`Url` is a string
  underneath, `HttpClient` is not bridgeable at all and wants the #249 remark instead)

Decide the default and the admission unit together; a strict mode with only package-prefix
widening makes the trap in #55 mandatory.
