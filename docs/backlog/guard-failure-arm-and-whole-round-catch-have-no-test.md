# The failure arm of the Kotlin-half guards, and the whole-round catch, have no test

> Discovered while implementing [ADR-162](../adr/162-per-declaration-error-containment.md)
> (2026-09-22). Verified by reading; not reproduced.

[ADR-162](../adr/162-per-declaration-error-containment.md) installs `guarded(...)` at three
families: plan time, the Kotlin half (`generateCNameWrappers`'s per-declaration loops), and the C#
half (`CirTranslator.translate`'s per-declaration loops), plus a whole-`process()` catch as the last
resort. `ForwardDiagnosticGuardTest` pins the helper's own containment behaviour directly (a failing
block passed to `guarded(...)`), and the two new Tier 1 cells
(`Tier1EntryPointCollisionTest`'s dispose cells) exercise a real failure reaching a real diagnostic
end to end — but only through the C# half's `emitCsharpSignatureCollisions` producer, which is a
named diagnostic, not the generic catch.

No test exercises the **failure arm** of the Kotlin-half guard installations, or the whole-round
catch, because no legal Kotlin shape shipped in this repository reaches an uncaught exception at
either boundary today: every historical raw-throw reproducer (issue #52, ADR-080, ADR-081, ADR-097)
has since been fixed into a named, non-throwing skip. The ADR-162 research spikes reached these paths
only by injecting a throw directly into the source, which a shipped test may not do. If a future
regression reintroduces an unclassified `error(...)`/`require(...)`/`!!` on a Kotlin-half or
whole-round path, `guarded` will contain it (per the helper's own unit test), but nothing currently
proves the specific wiring at those two installation sites is correct beyond code review.
