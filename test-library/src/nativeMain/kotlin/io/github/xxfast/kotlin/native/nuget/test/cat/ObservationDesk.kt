package io.github.xxfast.kotlin.native.nuget.test.cat

/**
 * Fixture for a `value class` **over a sealed class** at a *property* position:
 * [ObservationResult] wraps [Observation], and every property below is typed as the wrapper
 * rather than as the sealed base.
 *
 * `ForwardPropertyPlanner` rewrites a property type through `sealedAsHandle()`, which recurses
 * through `Nullable` and the `Collection` components but deliberately **not** through
 * `BridgeType.ValueClass.underlying` (ADR-105 Consequences). So `isPlannable` still sees a
 * `SpecializedProtocol` underlying and drops all three properties with
 * `SKIPPED_UNSUPPORTED_PROPERTY`: today the generated `TestLibrary.Cat.ObservationDesk` has no
 * `Result`, no `Maybe` and no `Current` at all. The feature must recurse into the underlying and
 * route the C# reconstruction through `Observation.FromHandle(...)`, so `val result:
 * ObservationResult` reads exactly like a bare `val o: Observation` does, only wrapped in the
 * `readonly record struct ObservationResult`.
 *
 * Every seam the recursion crosses, once each:
 * - [ObservationDesk.result] is the bare getter:
 *   `new ObservationResult(Observation.FromHandle(p))`, the arm that fails CS0144 today if the
 *   wrapper is reconstructed with `new Observation(handle)`
 *   against the abstract base,
 * - [ObservationDesk.maybe] is the nullable getter **and** the nullable setter: null crosses
 *   in-band on the pointer, and the write hands over `value?.Observation._handle ?? IntPtr.Zero`,
 * - [ObservationDesk.current] is the non-null setter, a distinct generated line
 *   (`value.Observation._handle`, no null fan-out) and the only cell that proves the value class
 *   survives a write rather than only a read,
 * - [ObservationDesk.observe] is the **control**: the same sealed base at a member return, which
 *   already binds under ADR-105 and must stay green so a regression here is distinguishable from
 *   the recursion being missing.
 *
 * [ObservationDesk.currentDescription] observes [current] Kotlin-side through
 * [ObservationResult.describe], so a C# write only reads back correctly if the handle arrived as a
 * genuinely re-wrapped [ObservationResult] instead of being echoed by the getter that wrote it.
 *
 * The arms come from the existing [openBox] / [peekBox] helpers rather than new constructors, so
 * this fixture adds no second way to build an [Observation].
 *
 * Fixture disjointness: the wrapper itself ([ObservationResult]) and its `describe` already bind at
 * ordinary positions, pinned by `IntegrationTests/ReferenceValueClassTests.cs`. `Issue54Sample.kt`
 * owns the bare sealed base at property positions and its KDoc pins that disjointness, so nothing
 * here touches it. No collection of the wrapper, no cross-namespace hop, no nullable payload inside
 * an arm.
 *
 * Oreo gets the desk: he is the cat you can see, alive on the windowsill. Mylo is whatever the box
 * says he is today.
 */
class ObservationDesk {
  /** Bare `val` typed as the wrapper. Oreo, observed alive, via [openBox]. */
  val result: ObservationResult = ObservationResult(openBox("Oreo"))

  /** Nullable `var` typed as the wrapper. Starts absent: nobody has looked in the box yet. */
  var maybe: ObservationResult? = null

  /** Non-null `var` typed as the wrapper. Starts at [peekBox]'s superposition arm. */
  var current: ObservationResult = ObservationResult(peekBox())

  /** Control: the sealed base itself at a member return, so C# can build a wrapper of its own. */
  fun observe(): Observation = result.observation

  /** Kotlin-side observation of [current], so a C# write is read back through Kotlin. */
  fun currentDescription(): String = current.describe()
}

/**
 * Factory for [ObservationDesk]. The class has a parameterless constructor that binds on its own,
 * but the fixture hands one out by name so a test reads the same way as [openBox] / [peekBox].
 * ADR-007 renames the file class to `ObservationDeskKt`, since `ObservationDesk` is taken by the
 * class, so C# calls this as `ObservationDeskKt.ObservationDesk()`.
 */
fun observationDesk(): ObservationDesk = ObservationDesk()
