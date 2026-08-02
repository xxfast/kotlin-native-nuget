package io.github.xxfast.kotlin.native.nuget.test.platform

// ADR-074 fixture ("Forward `expect`/`actual` declarations: the `actual` is the export root").
// This is the `expect` side (plus the `actual typealias` target, `SystemClock`, and the
// module-local function that references the expect type from shared code). It deliberately
// crosses every mechanism the ADR names in one package rather than the fewest types:
//
//   - an `expect class` with an explicit constructor + method + property (Device)
//   - an `expect class` with NO explicit constructor — `ctors=0` on the expect side, which is
//     exactly why the pre-fix crash lands on the first method rather than on `<init>` (Sensor)
//   - a top-level `expect fun` / `expect val` (differs per target, and per Decision 3 the C#
//     static class name must come from THIS file, `PlatformApi`, never the actual's file). The
//     file is deliberately NOT named `Platform.kt`: the package is `...test.platform`, which maps
//     to C# namespace `TestLibrary.Platform`, and a class named `Platform` in a namespace segment
//     also named `Platform` is legal but needlessly fragile and incidental to this feature.
//   - an `expect object` (PlatformRegistry)
//   - an `expect class` actualized by `actual typealias` (Clock -> SystemClock), redirected at a
//     PARAMETER position via [labelOf] (declared here, not itself expect/actual) and at a RETURN
//     position via [defaultClock] (an `expect fun` whose actual bodies live per-target) — two
//     distinct mechanisms, since the property loop and the callable loop are separate code paths.
//
// The two target files (`PlatformApiMacos.kt` / `PlatformApiMingw.kt`) declare an IDENTICAL
// public surface and differ only in returned values — that is both the point of the fixture (it
// proves the `actual` body is what actually runs) and a deliberate demonstration of the ADR's
// accepted cross-target divergence hazard, so don't let the two actuals drift apart. All three
// file names in this package are deliberately distinct from one another and from the namespace
// segment, so Decision 3 (the C# static class name comes from the EXPECT's file) stays genuinely
// under test rather than satisfied by accident.

/** Explicit ctor + method + property row. */
expect class Device(name: String) {
  fun describe(): String
  val id: String
}

/** Implicit ctor row: `ctors=0` on the expect side (spike finding 6). */
expect class Sensor {
  fun reading(): Int
}

/** Top-level `expect fun`; the C# static class name must be `PlatformApi` (Decision 3). */
expect fun platformName(): String

/** Top-level `expect val`; same file-naming rule as [platformName]. */
expect val platformTag: String

/** `expect object` row. */
expect object PlatformRegistry {
  fun count(): Int
}

/** Actualized by `actual typealias Clock = SystemClock` on both targets (Decision 2). */
expect class Clock {
  fun label(): String
}

/** The alias target: an ordinary module-local class, exported under its own name. */
class SystemClock {
  fun label(): String = "system-clock"
}

/**
 * Redirect at a RETURN position, with the body supplied per-target: an `expect fun` whose return
 * type is an alias-actualized expect class. Must land in `PlatformApi` via Decision 3 exactly
 * like [platformName]/[platformTag], because it IS an `actual`, unlike a plain per-target helper.
 */
expect fun defaultClock(): Clock

/**
 * Redirect at a PARAMETER position, referenced from shared (`nativeMain`) code: a reference to
 * `Clock` here must resolve to `SystemClock` in the generated C#, even though this function is
 * not itself `expect`/`actual`.
 */
fun labelOf(clock: Clock): String = clock.label()
