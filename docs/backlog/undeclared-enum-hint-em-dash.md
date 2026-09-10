# The `UNDECLARED_ENUM` hint string ships a raw em-dash into diagnostic output

**The hint that follows every `SKIPPED_UNSUPPORTED_TYPE`/`UNDECLARED_ENUM` diagnostic contains a
literal U+2014 em-dash, so it reaches the KSP build log, `NugetDiagnostics.json`, and every doc
snippet that quotes the message verbatim, in violation of this project's no-em-dash rule.** The
string, unedited, from `ForwardDiagnostic.kt:760`:

```
"its file <U+2014> or, if it already is top level, bring its package into the export scope"
```

It is part of the `diagnosticHint()` arm for `ForwardPlanSkipReason.UNDECLARED_ENUM`
(`ForwardDiagnostic.kt:755-761`), not the `diagnosticReason()` sentence this feature touched; the
two functions are siblings, and this item's own change left the hint untouched by design (memo 02,
section 4: "each sentence is followed by the existing hint").

It went unnoticed because nothing in the test suite asserts on the hint's exact character content,
only on substrings like `"UNDECLARED_ENUM"` or `"top level"` (`Tier1NestedClassSkipTest.kt:124`,
`Tier1ReachabilityClosureTest.kt:165`), and no lint or CI check scans generated diagnostic text for
`U+2014`. Verified by grep: `grep -n $'<U+2014>' nuget-processor/src/main/kotlin/.../ForwardDiagnostic.kt`
finds it at `:760`, plus several more in doc comments elsewhere in the same file that never reach
runtime output and so are out of scope for this item.

Discovered alongside [ADR-064](../adr/064-forward-unsupported-declaration-diagnostics.md)'s
2026-09-11 amendment, which re-lifted every Writerside snippet quoting a `diagnosticReason()`
sentence for the eleven reasons and, in doing so, re-verified the `UNDECLARED_ENUM` snippets against
real `NugetDiagnostics.json` output, carrying this em-dash forward verbatim rather than silently
editing a quoted line.
