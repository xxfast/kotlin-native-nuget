package io.github.xxfast.kotlin.native.nuget.test.platform

// ADR-074 fixture: the mingwX64 actuals for `PlatformApi.kt`. Deliberately named differently from
// the macosArm64 file (`PlatformApiMacos.kt`) so Decision 3 (the C# static class name comes from
// the EXPECT's file, `PlatformApi`, not the actual's) is under test rather than accidentally
// satisfied by two identically-named files. The public surface here must stay identical to
// `PlatformApiMacos.kt`; only the returned values differ.

actual class Device actual constructor(private val name: String) {
  actual fun describe(): String = "$name on mingw"
  actual val id: String = "mingw-device"
}

actual class Sensor {
  actual fun reading(): Int = 24
}

/**
 * ADR-091: the default for `interval` is declared on the expect only; an actual may not restate
 * it.
 */
actual class Beacon actual constructor(
  private val name: String,
  private val interval: Int,
) {
  actual fun describe(): String = "$name every ${interval}s on mingw"
}

actual fun platformName(): String = "mingw"

/**
 * ADR-096: the default for `level` is declared on the expect only; an actual may not restate it.
 */
actual fun beaconLabel(prefix: String, level: Int): String = "$prefix at level $level on mingw"
actual val platformTag: String = "win-x64"

actual object PlatformRegistry {
  actual fun count(): Int = 1
}

actual typealias Clock = SystemClock

/** Redirect at a RETURN position: this IS an `actual`, so per Decision 3 it binds as
 *  `PlatformApi.defaultClock()` in the generated C#, returning `SystemClock` per Decision 2. */
actual fun defaultClock(): Clock = SystemClock()
