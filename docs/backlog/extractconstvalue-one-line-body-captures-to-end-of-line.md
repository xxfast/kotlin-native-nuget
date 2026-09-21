# `extractConstValue` captures to end of line, breaking a one-line `object`/companion body

**Symptom.** A one-line `companion object { const val DefaultName = "x" } }` (or the equivalent
one-line `object { const val ... } }`) generates illegal C#:

```
public const string Defaultname = "x" } };
```

The trailing `} };` is the rest of the source line, captured as part of the constant's value text.

**Cause.** `extractConstValue` (`nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/cir/CirTranslator.kt:1543-1551`)
matches the value with the regex group `(.+)`, which is greedy to the end of the *line*, not to the
end of the Kotlin expression. A `const val` declared on its own line is unaffected; only a body
written on one line trips it. This is a different defect from the one ADR-110's 2026-09-20
amendment fixed (that amendment corrected which *declaration* the whole-file regex search starts
from; it left the per-line capture behaviour untouched, and the bug reproduces against the current
`extractConstValue`, read 2026-09-22).

**Coverage gap.** No fixture in `test-library` declares a `const val` on the same line as its
enclosing `object`/companion braces; every existing fixture already puts each `const val` on its
own line, which is why this has not surfaced. `Issue285Sample.kt` (issue #285) deliberately keeps
each `const val` on its own line to avoid it.

**Discovered by:** the issue #285 research memo (`docs/research/roadmap/enum-entry-casing.md`,
finding 12, spike S3), verified by spike in a scratch worktree; the ADR-006 amendment for issue #285
did not touch or re-verify this code path.
