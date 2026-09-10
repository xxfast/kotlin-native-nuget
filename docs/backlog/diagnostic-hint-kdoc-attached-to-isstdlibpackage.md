# `diagnosticHint`'s `@param` KDoc renders on a private helper it does not document

**An IDE tooltip or generated KDoc for `ForwardPlanSkipReason.diagnosticHint(detail, scope,
parameter)` shows no parameter documentation at all**, while the block describing `detail` and
`parameter` (which reason reads which slot, and what each carries) shows up attached to
`isStdlibPackage()`, a private one-line `String` predicate with no parameters matching either name.

`ForwardDiagnostic.kt:471-486` is a KDoc block opening `ADR-064: an actionable per-reason hint,
kept alongside the mapping above it documents` with `@param detail` and `@param parameter` tags.
It sits directly above `private fun String.isStdlibPackage()` (`:489`), separated from it by only
`isStdlibPackage`'s own one-line `/** ... */` comment. `diagnosticHint` itself, the function the
block is about, is declared at `:552` with no KDoc above it at all: a blank line, then `internal
fun ForwardPlanSkipReason.diagnosticHint(...)`.

This is pre-existing, not introduced by ADR-064's 2026-09-10 amendment: the block predates it. The
amendment's own new `diagnosticReason(detail, parameter)` sibling, immediately below at `:492-511`,
correctly carries its own `@param` block and does not repeat the mistake.

It went unnoticed because Kotlin's KDoc tooling does not flag a `@param` tag naming a parameter the
documented declaration does not have as an error, only IntelliJ's non-blocking "unresolved doc
parameter reference" inspection would, and nothing in `scripts/verify.sh` renders or lints KDoc.

Fix shape: move the `:471-486` block down to sit directly above `internal fun
ForwardPlanSkipReason.diagnosticHint(...)` at `:552`, with no other change. Discovered alongside
[ADR-064](../adr/064-forward-unsupported-declaration-diagnostics.md)'s 2026-09-10 amendment (*the
reason sentence lives on the reason*).

A second, harmless duplicate in the same lane: `NugetProcessor.kt` imports
`io.github.xxfast.kotlin.native.nuget.processor.forward.diagnosticHint` twice, at `:88` and `:108`.
Compiles fine (Kotlin tolerates a repeated import), but one line is dead weight.
