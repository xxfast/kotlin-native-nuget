package io.github.xxfast.kotlin.native.nuget.test.platform

// ADR-074 fixture: the macosArm64 actuals for `PlatformApi.kt`. Deliberately named differently
// from the mingwX64 file (`PlatformApiMingw.kt`) so Decision 3 (the C# static class name comes
// from the EXPECT's file, `PlatformApi`, not the actual's) is under test rather than accidentally
// satisfied by two identically-named files. The public surface here must stay identical to
// `PlatformApiMingw.kt`; only the returned values differ.

actual class Device actual constructor(private val name: String) {
  actual fun describe(): String = "$name on macos"
  actual val id: String = "macos-device"
}

actual class Sensor {
  actual fun reading(): Int = 42
}

/**
 * ADR-091: the default for `interval` is declared on the expect only; an actual may not restate
 * it.
 */
actual class Beacon actual constructor(
  private val name: String,
  private val interval: Int,
) {
  actual fun describe(): String = "$name every ${interval}s on macos"
}

actual fun platformName(): String = "macos"
actual val platformTag: String = "osx-arm64"

actual object PlatformRegistry {
  actual fun count(): Int = 1
}

actual typealias Clock = SystemClock

/** Redirect at a RETURN position: this IS an `actual`, so per Decision 3 it binds as
 *  `PlatformApi.defaultClock()` in the generated C#, returning `SystemClock` per Decision 2. */
actual fun defaultClock(): Clock = SystemClock()
