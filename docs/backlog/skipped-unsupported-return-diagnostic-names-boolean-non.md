# A nullable return's skip diagnostic still names no type

> Extracted verbatim from `ROADMAP.md` (Phase 4: Rich type support) in the 2026-08-31 roadmap slim-down.

**What breaks**: `SKIPPED_UNSUPPORTED_RETURN`'s hint never names the actual nullable type it refused,
regardless of what it is. `ForwardPlanSkipReason.NULLABLE.diagnosticHint()`
(`forward/ForwardDiagnostic.kt`) was originally hardcoded to say "Boolean" for every nullable type,
because `NULLABLE` was asserted to only ever arise at the nullable-Boolean-return site.

**Verified**: observed in real `kspKotlinMacosArm64` output against `Instant?`-returning members
before [ADR-076](docs/adr/076-instant-mapping.md) taught the classifier about `Instant`. Predates
ADR-076 and is unrelated to it; discovered only because ADR-076's failing tests were the first to
run an unsupported nullable non-Boolean return through this path.

**Progress so far**: the false "Boolean" was fixed first, replaced by "instead of a nullable value
at this position". [ADR-064](docs/adr/064-forward-unsupported-declaration-diagnostics.md)'s
2026-09-09 amendment (issue [#131](https://github.com/xxfast/kotlin-native-nuget/issues/131)) closed
the *input*-position half: `NULLABLE` now carries the offending parameter's name at an input
position, and the hint says `the nullable parameter \`events\` has no wire at an input position`
instead of the unnamed sentence.

**What's still open**: a *return*-position `NULLABLE` still names no type at all. There is no
`BridgeType`-to-Kotlin-spelling renderer anywhere in the planner, so even with a position tag now
available, the return half has nothing to print; naming a return's type needs that renderer built
first, a larger piece of plumbing than the input half's fix.
