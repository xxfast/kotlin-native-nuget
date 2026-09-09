package io.github.xxfast.kotlin.native.nuget.test.issue121

import io.github.xxfast.kotlin.native.nuget.test.issue113.InternalApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Issue #121: ADR-115's opt-in gate lives in the planners, and the lambda and Flow property arms do
 * not go through them. `ClassExports.kt` and `SealedClassExports.kt` ask the catalog for a plan and
 * fall through to a legacy arm when there is none, but `no plan` answers two opposite questions:
 * "no shape for this type", which the legacy arm exists for, and "the planner refused this
 * declaration", which nothing may emit. So a marked lambda property was reported
 * `SKIPPED_OPT_IN_MARKER` and exported anyway, on both sides.
 *
 * **This fixture is the criterion-3 test.** It is compiled by `packNuget` with no `optIn` or
 * `-opt-in=` flag anywhere in any build script. Before the fix the generated `CNameExports.kt` read
 * these marked properties without opting in and the module did not compile, so the fixture existing
 * and the build being green is itself the assertion. `IntegrationTests/Issue121Tests.cs` then owns
 * the C# absence half.
 *
 * **The absence trap**, inherited from [io.github.xxfast.kotlin.native.nuget.test.issue113]: this
 * pipeline drops members for many unrelated reasons, and a lambda property is easier to drop than
 * the `String` that fixture leans on. So every marked property here has an **unmarked sibling of
 * exactly the same type** in the same class. Those controls are the only thing that can catch a
 * gate that fires too wide, which absence assertions structurally cannot.
 *
 * | Cell | Member | What it pins |
 * |---|---|---|
 * | 1 | [Feed.onRetry] | the issue's literal shape: `@property:` marker on a plain `() -> Unit` |
 * | 2 | [Feed.onRefresh] | the suspend arm, a separate copy of the same fall-through |
 * | 3 | [Feed.onTicks] | the `Flow` arm, which shares the fall-through and the issue does not name |
 * | 4 | [Panel.Live.onClose] | the sealed-subclass copy, in `SealedClassExports` |
 * | C1 | [Feed.onVisible] | control for cell 1, unmarked, same type |
 * | C2 | [Feed.onSettle] | control for cell 2, unmarked, same type |
 * | C3 | [Feed.onPulse] | control for cell 3, unmarked, same type |
 * | C4 | [Panel.Live.onOpen] | control for cell 4, unmarked, same type |
 * | C5 | [Feed.retry] / [Feed.refresh] | criterion 4: the unmarked delegates are the intended surface and must still work |
 *
 * Oreo naps through the refresh. Mylo retries anyway.
 */
class Feed(val items: Int) {

  /** Cell 1: the issue's literal reported shape. */
  @property:InternalApi
  val onRetry: () -> Unit = { retries++ }

  /** Cell 2: the suspend twin, through the other copy of the arm. */
  @property:InternalApi
  val onRefresh: suspend () -> Unit = { refreshes++ }

  /** Cell 3: the Flow arm of the same fall-through. */
  @property:InternalApi
  val onTicks: Flow<Int> = flowOf(1, 2, 3)

  /** C1, C2, C3: unmarked siblings of the same three types, which must keep binding. */
  val onVisible: () -> Unit = { }
  val onSettle: suspend () -> Unit = { }
  val onPulse: Flow<Int> = flowOf(7, 8, 9)

  var retries: Int = 0

  var refreshes: Int = 0

  /**
   * C5: criterion 4. The unmarked delegates are the intended surface, and cost the consumer
   * nothing once the marked properties are gated.
   *
   * `@OptIn` is required here and is not a workaround: reading an opt-in-required property needs
   * opting in even inside the declaring class, and this is the library author doing so knowingly
   * at one point of use. The thing issue #121 forbids is opting in on the *generated* file, which
   * would ship the marked member to C# anyway. Same shape as `Cattery.consumesMarked` in
   * [io.github.xxfast.kotlin.native.nuget.test.issue113]: a marker consumer stays exported, a
   * marker member does not.
   */
  @OptIn(InternalApi::class)
  fun retry() = onRetry()

  @OptIn(InternalApi::class)
  suspend fun refresh() = onRefresh()
}

/** Cell 4 and C4: the sealed-subclass copy of the same arm, in `SealedClassExports`. */
sealed class Panel {

  data class Live(val label: String) : Panel() {

    @property:InternalApi
    val onClose: () -> Unit = { }

    val onOpen: () -> Unit = { }
  }

  data class Off(val reason: String) : Panel()
}
