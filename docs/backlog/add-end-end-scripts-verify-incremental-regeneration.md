# Add an end-to-end `scripts/verify-incremental-regeneration.sh` backstop for KSP incremental correctness.

> Extracted verbatim from `ROADMAP.md` (Tooling & Test Integrity) in the 2026-08-31 roadmap slim-down.

**Add an end-to-end `scripts/verify-incremental-regeneration.sh` backstop for KSP incremental correctness.** The in-process `Tier1SuspendOnlyFileTest` cells (see the item above) are the contract for the `ALL_FILES` fix, but nothing guards the real `:test-library:packNuget` path end to end. Shape it like `scripts/verify-fixture-package-versioning.sh`: clean pack, count generated classes, touch a source file, pack again without `clean`, assert the suspend classes are still present. Deliberately deferred, not forgotten, when the `ALL_FILES` fix landed
