package io.github.xxfast.kotlin.native.nuget.test

/**
 * Issue #233: a platform-suffixed file name, the standard KMP spelling for an `actual`. Before the
 * ADR-007 amendment the stem reached C# verbatim as `class Compass.native`, which does not parse.
 * It is now the `CompassNative` holder.
 *
 * No `expect`/`actual` pair: `nativeMain` is shared by every target this library builds, so
 * `.native` is the honest suffix and the file still exercises the naming path, which is what is
 * under test.
 */
fun compassPlatform(): String = "native"
