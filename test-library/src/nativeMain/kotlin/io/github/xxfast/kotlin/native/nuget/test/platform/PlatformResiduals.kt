package io.github.xxfast.kotlin.native.nuget.test.platform

// ADR-074 amendment fixture: the shapes the ADR's "What is deferred" list left un-exercised —
// `expect sealed class` (item 1) and `expect interface` / `expect enum class` /
// `expect value class` (item 6). They are predicted to work by construction (the `isExpect`
// filter runs before every root bucket, so each `actual` reaches exactly the route the same
// non-expect declaration would), but nothing ever crossed them at integration level.
//
// Deliberately a SEPARATE file from `PlatformApi.kt` so the existing ADR-074 fixture is left
// untouched; per Decision 3 the generated C# static class for the top-level `expect fun`s below
// is named after THIS file (`PlatformResiduals`), never after either actual's file. No type in
// this package is named `PlatformResiduals`, so no `Kt` suffix applies (cf. `ObservationKt`).
//
// Cat framing, since the collar fixture next door already established it: Oreo and Mylo wear
// radio collars. The collar reports a `Signal` (strong, with a reading, or lost), answers a
// `Transponder.ping()`, sits on a `Band`, and transmits on a `Frequency`.
//
// As with `PlatformApi.kt`, the two target files (`PlatformResidualsMacos.kt` /
// `PlatformResidualsMingw.kt`) MUST declare an identical public surface and differ only in the
// values they return — that is ADR-074's cross-target divergence constraint, and it is what
// proves the `actual` body is the one that ran.

/**
 * Item 1: an `expect sealed class` whose subclasses are declared on the `actual` side (Kotlin
 * permits them in any module on the dependency path between the expect and the actual; nesting
 * them in the actual's body keeps them in the actual's source set). Every subclass enumeration in
 * the forward sealed route is `getSealedSubclasses()` on the exported declaration, i.e. the
 * actual — a missing inheritor is loud, not silent, because the generated Kotlin
 * `signal_get_type` renders an exhaustive `when` that would not compile.
 */
expect sealed class Signal

/**
 * The sealed base at the precedented top-level RETURN position (mirrors `openBox`/`peekBox` for
 * the non-expect `Observation`). Negative readings mean the collar dropped out.
 *
 * NOT named `signal`: a top-level Kotlin function exports under its bare name, and `signal` is a
 * C standard library function (`<signal.h>`), which is the most plausible cause though it was not
 * confirmed (the CRT-thunk-vs-our-symbol question was not settled, and only mingwX64 was built).
 * Whatever the cause, the failure is SILENT up to runtime: KSP, the
 * generated `@CName("signal")` export and the C# `DllImport` all succeed, but the name is absent
 * from the built DLL's export table (`objdump -p`: every sibling export is present, `signal` is
 * not), so the call fails at runtime with `EntryPointNotFoundException`. A pre-existing
 * forward-bridge naming hazard, independent of `expect`/`actual`; recorded, not pinned here,
 * because this file's subject is the sealed shape.
 */
expect fun collarSignal(dbm: Int): Signal

/**
 * Item 6a: an `expect interface`. The member is declared on both halves; the forward interface
 * route reads its members off the actual.
 */
expect interface Transponder {
  fun ping(): String
}

/**
 * Interface at a RETURN position (ADR-040): the generated C# is `ITransponder` backed by a
 * generated `sealed class Transponder`. Each target's implementing class is `internal`, so its
 * per-target name never reaches `Interop.cs` and the packaged C# stays identical across targets.
 *
 * Not named `Beacon`: `PlatformApi.kt` in this same package already declares an ADR-091
 * `expect class Beacon`, and a second top-level `Beacon` here would be a redeclaration.
 */
expect fun transponder(): Transponder

/**
 * Item 6b: an `expect enum class`. Entries must be present on the actual (Kotlin's `EnumEntries`
 * compatibility rule); the forward enum route reads them from the actual's own declarations, so
 * the ordinal wire is the actual's.
 */
expect enum class Band {
  LOW,
  HIGH,
}

/** The enum at a top-level return position; differs per target. */
expect fun band(): Band

/**
 * Item 6c: an `expect value class`. Native-only, so no `@JvmInline` (that annotation is a JVM
 * requirement; `isValueClass()` keys on the `VALUE` modifier, which the plain form carries). A
 * value class must declare its primary constructor, so the `ctors = 0` hazard from ADR-074's
 * spike finding 6 cannot arise here.
 */
expect value class Frequency(val hertz: Int)

/** The value class at a top-level return position; differs per target. */
expect fun frequency(): Frequency
