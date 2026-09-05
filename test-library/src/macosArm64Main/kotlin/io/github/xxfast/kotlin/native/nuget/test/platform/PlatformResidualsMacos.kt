package io.github.xxfast.kotlin.native.nuget.test.platform

// ADR-074 amendment fixture: the macos actuals for `PlatformResiduals.kt`. The public surface
// here must stay IDENTICAL to `PlatformResidualsMingw.kt`; only the returned values differ
// (ADR-074's cross-target divergence constraint). Named differently from the other target's file
// so Decision 3 (the C# static class comes from the EXPECT's file, `PlatformResiduals`) stays
// genuinely under test.

/** Item 1: the subclasses of the `expect sealed class` live here, in the actual's source set. */
actual sealed class Signal {
  /** Oreo's collar came back with a reading. */
  data class Strong(val dbm: Int) : Signal()

  /** ...or it did not come back at all. */
  data object Lost : Signal()
}

/** The macos boost (42) exists only on this side, so a wrong reading means the wrong body ran. */
actual fun collarSignal(dbm: Int): Signal = if (dbm < 0) Signal.Lost else Signal.Strong(dbm + 42)

/** Item 6a: the `actual interface`, members restated as `actual`. */
actual interface Transponder {
  actual fun ping(): String
}

/**
 * `internal`, so this per-target name never enters the export set (`rootClasses` requires
 * PUBLIC) and the packaged C# is identical across targets — only `Transponder` and
 * `transponder()` are bound, and the handle behind `ITransponder` is this type at runtime.
 */
internal class MacosTransponder : Transponder {
  override fun ping(): String = "pong from macos"
}

actual fun transponder(): Transponder = MacosTransponder()

/** Item 6b: the `actual enum class`; entries match the expect's. */
actual enum class Band {
  LOW,
  HIGH,
}

actual fun band(): Band = Band.LOW

/** Item 6c: the `actual value class`; no `@JvmInline` — this is a native-only source set. */
actual value class Frequency actual constructor(actual val hertz: Int)

actual fun frequency(): Frequency = Frequency(2400)
