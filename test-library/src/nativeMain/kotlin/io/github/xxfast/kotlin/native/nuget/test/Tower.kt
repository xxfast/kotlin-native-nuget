package io.github.xxfast.kotlin.native.nuget.test

/*
 * ROADMAP line 48 fixture: a top-level function whose name happens to be a C runtime symbol.
 *
 * `signal` is exported today as `@CName("signal")`. Verified by spike on mingwX64 (research memo
 * `export-symbol-package-qualification.md`, finding 5): `nm` shows `T signal` defined in the built
 * DLL, and the DLL's export name table does not list it, because `ld.lld`'s MinGW auto-exporter
 * drops any name that an import library on the link line already exports. The generated
 * `[DllImport(EntryPoint = "signal")]` therefore throws `EntryPointNotFoundException` at runtime
 * with no build-time diagnostic. A hand-prefixed `lib_signal` in the same spike IS exported, so a
 * non-empty leading segment on every symbol fixes the class by mechanism rather than by luck.
 *
 * Declared in the ROOT test package on purpose: the relative package is empty here, so a fix that
 * only package-qualifies the symbol would leave this one bare and still broken. This is the cell
 * that forces the library-name segment.
 *
 * File is `Tower.kt` rather than `Signal.kt` so the ADR-007 file class (`Tower`) does not share a
 * name with its own `Signal` member and the ADR-110 rename is not also in play.
 *
 * The cat tower by the window has the best reception in the house. Oreo sits on the top platform,
 * Mylo takes the one below.
 */
private var lastDbm: Int = 0

/**
 * Records a signal strength in dBm and returns what it recorded, so one call proves the crossing
 * both ways. Today: `EntryPointNotFoundException` on mingwX64.
 */
fun signal(dbm: Int): Int {
  lastDbm = dbm
  return lastDbm
}

/** Reads back the last value [signal] recorded, so the test can prove the call had an effect. */
fun lastSignal(): Int = lastDbm
