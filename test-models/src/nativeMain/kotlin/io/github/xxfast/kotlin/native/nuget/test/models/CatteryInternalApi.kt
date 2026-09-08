package io.github.xxfast.kotlin.native.nuget.test.models

/**
 * ADR-115 cell 8: an opt-in marker declared **one Gradle module away**, in `:test-models`, and
 * applied to a declaration in `test-library`.
 *
 * This is the cell that retires ADR-115's single `Inferred` claim. Finding 4 verified the two-hop
 * `annotationType.resolve().declaration.annotations` lookup against a JVM `.jar` on `libraries`,
 * because the KSP2 programmatic entry point the spike used is `KSPJvmConfig`. Nothing in the spike
 * proves the same resolution survives a **Kotlin/Native klib** read, and ADR-115 itself notes that
 * the forward pipeline reads no annotations at all today, so the repo has no prior evidence either
 * way. If klib metadata does not carry the marker's own `@RequiresOptIn` meta-annotation through,
 * every declaration behind a dependency-module marker keeps leaking exactly as it does now, and it
 * leaks *silently*: the absence assertions for cells 1-7 would still all pass.
 *
 * Deliberately `ERROR` level, like the module-local [InternalApi] in `issue113`, so this cell also
 * carries failure (1) from issue #113: pre-fix, the generated `CNameExports.kt` reads the marked
 * declaration and does not compile.
 *
 * The cattery keeps notes on both residents. Oreo's file is thicker.
 */
@RequiresOptIn(level = RequiresOptIn.Level.ERROR, message = "Cattery bookkeeping, not a public API")
annotation class CatteryInternalApi
