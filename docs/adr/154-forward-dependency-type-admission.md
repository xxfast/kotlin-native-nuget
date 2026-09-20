# ADR-154: Forward dependency-type admission: an additive `admit(...)` verb by package or qualified type name, warn-and-skip stays the default

## Status

Accepted (2026-09-20)

## Context

[ADR-063](063-forward-declaration-level-export-scoping.md) made `publish { include(...) }` the
scoping verb, and an explicit `include` *replaces* the `rootPackage` default. Issue #55 asked for it
to add instead; #60 kept the replacement on purpose and made the hint spell the whole line.
[ADR-066](066-forward-export-reachability-closure.md) then reused the same predicate as the
admission rule of the reachability closure: a reachable klib declaration is admitted iff its
*package* passes `isExported`.

So `include` does two jobs with one shape (issue #247):

- for the module's own files it picks **roots**;
- for the compile classpath it picks **admission**.

Both are package prefixes. `Foo.url: io.ktor.http.Url` can only be reached by admitting
`io.ktor.http`, which the closure then walks, and every type it reaches in that package becomes
published C# surface the author owns and versions. A closed enum (`kermit.Severity`, ktor's
`LogLevel`) has no admission route other than its package either. The alternative is a named skip
(`SKIPPED_UNEXPORTED_DEPENDENCY_TYPE`, warning). #247 asks which should be the default, and whether
the un-admitted case should fail the build.

Facts this decision rests on (full evidence in
`docs/research/roadmap/include-scope-vs-transitive-dependency-admission.md`):

- **Verified by spike** (real klib, `:test-models` -> `:test-library`,
  `:test-library:compileKotlinMingwX64` and `:test-library:nugetCompileInterop` both EXIT 0): a
  top-level klib `enum class`, plain or with constructor properties and a companion, admitted through
  an included package, is declared as a C# `enum` (properties as extension methods) and referenced at
  return, parameter, property, nullable and `List<>` positions; KSP raised no
  `Internal KSP Error`. Runtime execution was **not** run (inferred to match a module-local enum).
- **Verified by spike** (Tier 1, JVM jar dependency, generated text read, C# not compiled): with a
  class admitted and the types its members mention *not* admitted, the class, enum and interface
  gates skip each such member with a named diagnostic, keep the class, and the Kotlin compiles. This
  is exactly the state per-type admission produces.
- **Verified by the same spike:** a top-level dependency **value class** outside the scope is spelled
  (`global::Lib.Token`) and never declared, with no diagnostic. Inferred: CS0246 in C#. This is the
  open ROADMAP item "a closure-refused TOP-LEVEL dependency value class is still spelled by
  `valueClass()` with no dependency gate", and per-type admission makes it far easier to reach.
- **Verified by reading:** `PackageScope.excludes` already matches a qualified type name
  (`ForwardPublishedScope.kt:25-28`, issue #53) through `isUnderPackage` (`PackageNames.kt:19-20`,
  equality or dotted prefix); the include half of `covers` tests the package only (`:30-34`).
- **Verified by reading:** the closure takes one `isExported` lambda and uses it for both the
  module-local branch and the klib branch (`ForwardReachabilityClosure.kt:249`, `:268`). That shared
  lambda is where the two jobs are fused.
- **Verified by reading:** `kotlin.Result` is not an intrinsic terminal of the closure, so it is
  recorded as refused `NOT_INCLUDED`; a plain "was it refused" gate in `valueClass()` would break
  [ADR-108](108-result-return-mapping.md).
- **Verified by reading (`origin/main`):** since #276, every skip attaches a `<remarks>` paragraph
  to the owning C# declaration (`cir/CirSkipRemarks.kt`), so the un-admitted default is no longer
  invisible to the C# consumer, which was the premise of #247's fail-the-build proposal.
- A property-position skip is reported under `SKIPPED_UNSUPPORTED_PROPERTY`, not
  `SKIPPED_UNEXPORTED_DEPENDENCY_TYPE` (**verified by spike**), so the dependency-scope condition is
  identified by `ForwardPlanSkipReason.UNEXPORTED_DEPENDENCY_TYPE`, never by diagnostic kind.

## Alternatives Considered

### 1. A separate additive `admit(...)` verb, by package prefix or qualified type name (chosen)

`include` keeps today's behaviour byte for byte (roots, and package-level admission, and the
replacement rule). `admit(...)` adds entries to the closure's admission predicate only; it never
picks roots and never replaces anything.

Pros: separates the two jobs without touching the #60 decision; the skip hint becomes one additive
line that cannot empty the export set (the #55 trap disappears for the dependency case instead of
being documented around); the unit is as fine as a single type; it is the "layers cleanly on top by
contributing additional admitted package prefixes to the same predicate" extension ADR-066
Alternative 2 anticipated; matches Kotlin's own precedent that the dependency-export verb is separate
from, and additive to, the own-API selector (framework `export(...)`, Swift Export `export(...)`,
inferred from documentation). Cons: a second verb to learn; about 9 production files.

### 2. Let `include` match a qualified type name (one verb)

One clause in `PackageScope.covers`, plus hint text. About 3 production files. Cons: the jobs stay
fused, so the hint must still spell the full replacement line; `include("sample.Foo")` silently
becomes a per-class root picker for the module's own files, a per-declaration opt-in ADR-063
Alternative 2 rejected; nothing is additive. It is a strict subset of Alternative 1's matcher change
and is a legitimate first slice if the verb decision is deferred.

### 3. Admit any reachable closed `enum class` regardless of scope

Cons: an enum is not edge-free (constructor properties, companion members, verified in the spike);
it publishes a third party's type under the author's package id with no declaration of intent,
re-opening ADR-066 Alternative 3's blast-radius objection in miniature; one `admit(...)` line buys the
same result deliberately. Rejected.

### 4. Fail the build by default on an un-admitted dependency type

Cons: contradicts ADR-066 section 4 ("never a hard error"); breaks existing builds on upgrade; with
package-only widening it would make the #55 trap mandatory; and #276 removed the silent-to-consumer
premise. Offered as opt-in under Alternative 1 instead. Rejected as the default. **This is the
headline question for the human.**

### 5. Custom type mapper (`Url` as `string`)

The right long-term answer for string-shaped values and already a Future Improvements item. Out of
scope; the only constraint recorded is that a mapped type would terminate the closure walk like an
intrinsic, which `admit` does not foreclose.

## Decision

```kotlin
nuget {
  publish {
    rootPackage = "com.example.sdk"
    admit("io.ktor.http.Url")
    admit("co.touchlab.kermit.Severity", "io.ktor.client.plugins.logging.LogLevel")
    exclude("io.ktor.client.HttpClient")   // accepted amputation, stated once
    // strictDependencyTypes = true        // opt-in
  }
}
```

1. **Matcher.** An `admit` entry matches a klib declaration when
   `isUnderPackage(packageName, entry) || isUnderPackage(qualifiedName, entry)`, the exact rule
   `exclude` uses today. `exclude` still wins. Opt-in markers (ADR-115) still refuse.
2. **Closure.** `ForwardReachabilityClosure` takes a second predicate for the klib branch:

   ```kotlin
   isAdmitted(d) = (effectiveInclude.isNotEmpty() && isExportedAndUnmarked(d)) ||
                   (admit.isNotEmpty() && admitMatches(d) && !isMarkedOptIn(d))
   ```

   **The `effectiveInclude.isNotEmpty()` guard on the first disjunct is load-bearing.**
   `PackageScope.covers` returns `true` for every non-excluded package when its include list is
   empty (`ForwardPublishedScope.kt:32`, verified by reading). Today that is harmless for klib
   declarations only because ADR-066 admission rule 4 never lets the klib branch reach the predicate
   in that state. With `rootPackage` unset, no `include`, and `admit("io.ktor.http.Url")`, an
   unguarded `isExportedAndUnmarked || ...` would answer `true` for every reachable klib declaration
   and the closure would walk the whole classpath: ADR-066 Alternative 3, silently. For the same
   reason `admitMatches` must not be implemented as `PackageScope(include = emptyList()).covers`;
   an empty `admit` list means "no admission", the asymmetry ADR-066 Alternative 1 already records
   for `include`. The module-local branch (`containingFile != null`) keeps the roots predicate, so
   `admit` can never resurrect an own-module type `include`/`exclude` filtered out.
   `crossModuleAdmissionAllowed` becomes `effectiveInclude.isNotEmpty() || admit.isNotEmpty()`
   (rule 4 otherwise unchanged: neither set, no crossing). A Tier 1 cell for "no `rootPackage`, only
   `admit`" must assert the manifest lists exactly the admitted names.
3. **No package walk.** Admitting `io.ktor.http.Url` admits that declaration (and lets ADR-133's
   owner walk declare its nested types). Its members' types are visited and refused `NOT_INCLUDED`
   unless separately admitted; those members skip named. Verified by spike for class, enum and
   interface member types.
4. **The value-class gate ships with this.** `valueClass()` refuses a top-level value class whose
   closure refusal is a scope refusal, with an explicit carve-out for `kotlin.Result`. Inferred, not
   verified: that `kotlin.Result` is the only stdlib value class reaching that arm (`Duration` and
   `Uuid` are closure terminals). An implementer must add a Tier 1 regression cell for `Result<T>`
   and prove the gate against the real klib fixture, because a klib value class reports
   `Modifier.INLINE` rather than `VALUE` (ADR-066) and the Tier 1 jar does not reproduce that.
5. **Default.** Unchanged: warning, named skip, `NugetDiagnostics.json`, #276 remark. The hint
   becomes `add admit("<qualified type>")` (additive), with `exclude("<qualified type>")` named as
   the way to record the omission as deliberate. The collection-element route's literal
   `"the dependency's package"` fallback is fixed in the same arm.
6. **Strict, opt-in.** `strictDependencyTypes = true` escalates every skip whose reason is
   `ForwardPlanSkipReason.UNEXPORTED_DEPENDENCY_TYPE` with refusal `NOT_INCLUDED` or
   `CROSS_MODULE_ADMISSION_DISABLED`, on both the callable and property routes, to an `ERROR_*`
   diagnostic. `EXCLUDED_BY_CONFIG` stays a warning, so under strict every dependency type in a
   public signature is either admitted or excluded by name: the once-per-type decision #247 asks for.
7. **Plumbing.** One new KSP option, `nuget.admit`, comma-joined like `nuget.includePackages`.
   Inferred: no Kotlin qualified name contains a comma, so the existing join is safe.

## Implementation notes (2026-09-20)

Three details the implementation settled that the Decision above did not spell out:

1. **The `CROSS_MODULE_ADMISSION_DISABLED` hint changed too.** §2 makes `admit(...)` open admission
   rule 4's gate on its own, so that refusal's remedy is now one additive entry rather than the
   `include(...)` line it used to spell — a line that also replaces the everything-in-this-module
   default and silently drops the author's own files. Both dependency hints are now `admit`-shaped
   and neither prints `include(`. The `scope` parameter of `diagnosticHint` (ADR-063's include list,
   read only by the un-admitted arm per issue #55) was removed with it, since an additive verb needs
   no replacement line echoed back.
2. **The matched `exclude` entry reaches the hint through the hint's own parameter, not through
   `Skipped.detail`.** ROADMAP line 37's route would have threaded the entry from the closure through
   `ForwardReachabilityResult`, the classifier context, `BridgeType.Unsupported`, the planner and a
   `detail` encoding that `diagnosticReason` would also have to split — six files to carry a string
   the producers already hold. Instead the author's own `exclude(...)` list rides the freed `scope`
   slot, and the hint picks the first entry matching by the same `isUnderPackage` rule, in the same
   order, the closure refused on. Same answer, one parameter.
3. **A nested refused type is admitted through its OWNER, and the hint says so.** `admit` entries
   match by prefix, and the closure climbs from a nested declaration to its owner before admitting
   anything (ADR-066 amendment, edge A), so `admit("dep.edge.Ledger.Entry")` refuses `Ledger`
   (`NOT_INCLUDED`) and propagates that refusal back onto `Entry`: pasting the line changes nothing.
   The `admit("...")` argument is therefore the package segments plus the outermost type segment.
   The `exclude("...")` argument beside it keeps the full nested name, because `isExcluded` is
   tested on the declaration itself, ahead of the climb.
4. **One shared matcher.** `exclude` and `admit` both go through `List<String>.matchesDeclaration`,
   which returns the entry that matched (so the hint can quote it) and answers `null` for an empty
   list — making §2's "an empty `admit` means no admission" structural rather than a condition a
   later change can forget.

## Consequences

- ADR-063 is untouched; ADR-066's admission predicate gains one disjunct and its "the escape hatch
  is `include(...)`" sentence is amended to name `admit(...)`.
- The ROADMAP items on the top-level dependency value class and on the `EXCLUDED_DEPENDENCY_TYPE`
  hint not knowing which exclude entry matched (#53) close with this feature; the nested-generic
  klib owner item does not (different route).
- ADR-109's cross-publisher duplicate warning lowers scopes by package; a by-name `admit` in another
  publisher is not seen. Documented gap.
- Deferred: custom type mappers; `export(project(...))`; a supertype edge (ADR-101); letting
  `include` match a qualified name for own-module roots.
- Not verified by anyone: behaviour against the real ktor and kermit klibs. If an admitted
  third-party class carries a member shape the fixtures do not, the failure mode is non-compiling C#
  caught by `nugetCompileInterop` at pack time (ADR-138), not silent wrong output.
