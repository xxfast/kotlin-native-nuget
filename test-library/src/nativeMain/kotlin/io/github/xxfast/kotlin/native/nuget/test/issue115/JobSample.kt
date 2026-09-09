package io.github.xxfast.kotlin.native.nuget.test.issue115

import io.github.xxfast.kotlin.native.nuget.test.issue54.NestedListenerOwner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

/**
 * Top-level interface, the ADR-040 binding half of the interface-return pair below. Declared here
 * because `rootInterfaces` only ever collects top-level interfaces; its nested twin,
 * [NestedListenerOwner.Listener], is the half that must skip.
 */
interface JobListener {
  fun onEvent(): String
}

/**
 * Fixture for issue [#115](https://github.com/xxfast/kotlin-native-nuget/issues/115) / ADR-116: a
 * public **member function declared on a sealed subclass** is never exported, and nothing says so.
 *
 * The sealed route (`SealedClassExports`, `translateSealedClass`, `CirSealedRenderer`) reads
 * `getAllProperties()` only, and `CirSealedSubclass` has no `methods` at all, so an arm's C# class
 * arrives populated with properties, data methods and `Dispose()`, and inert. Nothing diagnoses it
 * either: the planner never plans the member, so there is no `Skipped` entry for
 * `warnDroppedForwardCallables` to name. ADR-116 moves those members onto the ADR-062 callable
 * plan, under the export prefix the property getters already use (`job_running_cancel` beside
 * `job_running_get_progress`).
 *
 * ### Cells, one per mechanism the new route has to cross
 * The point is the widest set of seams, not the fewest members: a fixture trimmed to one `Int`
 * method would go green against a route that only ever binds `Int`.
 * - [Job.Running.cancel] — an `Int` return, **no conversion at all** at the seam. Catches an
 *   open-coded conversion in the new route.
 * - [Job.Running.label] — `String` **in and out** on one member, the UTF8 marshalling pair on a
 *   parameter and a return at once.
 * - [Job.Running.step] — an **overload pair** (ADR-090). Both keep the C# name `Step`; the second
 *   must take the `_2` symbol/export suffix, so a route that forgets the `occurrences` counter
 *   collides at the native symbol rather than in C#.
 * - [Job.Running.next] — the sealed **base** at a return position: ADR-105 `sealedAsHandle`, read
 *   back through `Job.FromHandle` and discriminated onto an arm.
 * - [Job.Running.finish] — a **sibling nested arm** spelled as its own concrete type, the
 *   `ObjectHandle` construction site (`new Job.Done(handle)`).
 * - [Job.Running.pick] — a **top-level interface** return, nullable: ADR-040 binds it as
 *   `IJobListener?`. It answers with a real implementation above zero progress and `null` at zero,
 *   so the dispatch branch and the nullable branch are both crossed. An anonymous
 *   `object : JobListener`, the `strayPet()` precedent: no generated C# wrapper of its own exists,
 *   so the only way through is interface dispatch.
 * - [Job.Running.pickNested] — ROADMAP line 39's literal example, a **nested** interface return.
 *   The classifier refuses it (`isUndeclaredNested`), so it must be **absent** from C# with a named
 *   `SKIPPED_UNSUPPORTED_TYPE`. It reuses [NestedListenerOwner.Listener] rather than declaring a
 *   second nested interface, so this fixture cannot drift from the gate that owns that rule. It
 *   sits beside [Job.Running.pick] on purpose: a top-level interface return binds, a nested one
 *   does not, and only the pair tells the two apart.
 * - [Job.Running.pause] — `suspend` on an arm (ADR-118): binds as `Task<int> PauseAsync()` under
 *   the arm's own export prefix (`job_running_pause_async`), where ADR-116 left it absent and
 *   named `SKIPPED_UNSUPPORTED_COMBINATION`.
 * - [Job.Running.pause] again, taking `millis` — a `suspend` **overload pair on an arm**, the cell
 *   that pins `sealedSubclassEntries`' own occurrence counter and the sealed post-process's copy of
 *   the numbered symbol: the second takes `job_running_pause_2_async` / `Native_Pause_2Async`. Its
 *   body is `progress + millis`, unreachable from the no-arg body, so a suffix that lands on the
 *   `[DllImport]` EntryPoint but not on `CirMethod.nativeName` dispatches to the first overload
 *   *silently* and the returned value is the only tell.
 * - [Job.Running.resume] — `String` **in and out** on the suspend route: the UTF8 marshalling pair
 *   riding the async result protocol rather than the plan route.
 * - [Job.Idle.nap] — a `suspend fun` on a `data object` arm. It takes the same
 *   `asStableRef<Job.Idle>` handle receiver as a `data class` arm, and gives `Idle` the scope,
 *   `IAsyncDisposable` and `DisposeAsync` that [Job.Done] — no suspend member — must not gain.
 * - [Job.rest] — an `open suspend fun` **with a body on the base**, overridden by no arm. ADR-118's
 *   declared-only gate is required, not cosmetic: the suspend builder reads `getAllFunctions()`, so
 *   without it every arm exports `job_<arm>_rest_async` for a member it never declares, with a
 *   lenient `""` overload suffix; `ForwardAbiContract.kotlin` filters Kotlin exports down to the C#
 *   import set, so that extra export would vanish from the comparison rather than be flagged.
 * - [Job.describe] — a base `open fun` **with a body**. Under ADR-116's declared-only gate an arm
 *   exports only what it declares itself, so this renders on **no** arm. [Job.Idle.describe], which
 *   is a declared `override`, renders as a plain `public string Describe()`: not `override` (the C#
 *   base declares nothing to override, CS0115) and not `virtual` (a `virtual` member on a
 *   `public sealed class` is CS0549).
 * - [Job.Idle.poke] — a method on a `data object` arm. An object arm is a `KSClassDeclaration` in
 *   `getSealedSubclasses()` like any other and crosses as a handle, so it must take the same
 *   receiver as a `data class` arm rather than becoming a static.
 * - [Job.Watching.ticks], ADR-124's issue shape: a `StateFlow<Int>` **property getter**
 *   on an arm. It binds as `KotlinStateFlow<int> Ticks` off `job_watching_get_ticks_collect` /
 *   `job_watching_get_ticks_value`, where today the property half is dropped with no diagnostic at
 *   all (`recordDropped` returns early for a legacy-routed protocol, on the assumption a named
 *   legacy route re-emits it, which is false for an arm). The getter hands back a
 *   `MutableStateFlow` the arm holds, so `.Value` and a bounded collect read the same storage and
 *   have to agree.
 * - [Job.Watching.labels], a plain `Flow<String>` at a **method** return on an arm, the half that
 *   is named `SKIPPED_UNSUPPORTED_COMBINATION` today. `String` in and out, so the UTF8 pair rides
 *   the collect protocol on a parameter and on the element at once.
 * - [Job.Watching.labels] again, taking `times`: a **Flow overload pair on an arm**. `_2` on the
 *   entry point and on `CirMethod.nativeName` both. Its emissions (`times` of them, each numbered)
 *   are unreachable from the one-parameter body, so a suffix that lands on the `[DllImport]`
 *   EntryPoint but not on the extern stem dispatches to the first overload *silently* and the
 *   collected values are the only tell.
 * - [Job.Watching] as a whole, a **flow-only** arm: no `suspend` member, so the scope,
 *   `IAsyncDisposable` and `DisposeAsync` arrive from the flow route alone, exactly as they do for
 *   an ordinary class whose only async member is a flow.
 * - [Job.Running.beats], the **coexistence** cell: a flow member on an arm that already carries
 *   suspend members. One `_scopeHandle` and one `DisposeAsync`, not two, so the flow arm and the
 *   ADR-118 suspend arm cannot each emit the scope independently.
 * - [Job.Done] — the control: an arm that declares no functions at all must keep generating
 *   exactly as it does today, and with neither a suspend nor a flow member it stays the arm
 *   without a scope, without `IAsyncDisposable`.
 *
 * Deliberately absent on the ADR-124 half: a base-declared flow property on [Job] itself (the
 * all-properties rule comes from ADR-111 and is already fixture-covered for ordinary property
 * types), a `suspend fun` returning a `Flow` (still a named `SKIPPED_UNSUPPORTED_RETURN` since
 * ADR-119), a flow on a `sealed interface` arm, a sealed element type (`Flow<Job>`, issue #126 and
 * #127 territory), and a `MutableStateFlow` write on an arm.
 *
 * Deliberately absent: a lambda-parameter cell (`fun watch(onTick: (Int) -> Unit)`). It stays a
 * `SEALED_SUBCLASS_UNROUTED` row of the sealed post-process table — the half of ROADMAP line 39
 * that ADR-118 does not close, now that the `suspend` row is routed — and binding it would drag
 * stored-callback pair detection into a fixture whose subject is method routing.
 *
 * Oreo (black with the white middle) does all the running: he starts at a percentage of the hallway
 * and finishes it. Mylo (brown and creamy) is [Job.Idle], and pokes back exactly once when nudged.
 */
sealed class Job {
  /**
   * Base body. Under ADR-116's declared-only decision this renders on no arm at all, so
   * [Job.Running] has no `Describe()` in C# while [Job.Idle], which declares an `override`, does.
   */
  open fun describe(): String = "job"

  /**
   * ADR-118's declared-only cell on the **suspend** loop: an `open suspend fun` with a body that no
   * arm overrides. No arm may carry `RestAsync`, and no `job_*_rest_async` entry point may exist.
   */
  open suspend fun rest(): Int = 0

  /** Oreo, mid-sprint down the hallway, [progress] percent of the way to the food bowl. */
  data class Running(val progress: Int) : Job() {
    /** `Int` return, no conversion at the seam. */
    fun cancel(): Int = progress

    /** `String` in and out on one member. */
    fun label(prefix: String): String = "$prefix$progress"

    /** Overload pair, first arm. */
    fun step(by: Int): Int = progress + by

    /** Overload pair, second arm: same public C# name `Step`, `_2` native symbol. */
    fun step(by: Int, times: Int): Int = progress + by * times

    /** Sealed **base** return: `Job.FromHandle` discriminates it back onto [Done]. */
    fun next(): Job = Done(progress)

    /** Sibling nested arm return, spelled as its own concrete type. */
    fun finish(): Done = Done(progress)

    /**
     * Nullable **top-level** interface return (ADR-040). A real implementation above zero progress,
     * `null` at zero, and an anonymous object so C# can only reach it through `IJobListener`.
     */
    fun pick(): JobListener? =
      if (progress > 0) {
        object : JobListener {
          override fun onEvent(): String = "Oreo is $progress% of the way to the bowl"
        }
      } else {
        null
      }

    /** Nullable **nested** interface return: never declared in C#, so a named skip, not a binding. */
    fun pickNested(): NestedListenerOwner.Listener? = null

    /** `suspend` on an arm: binds as `Task<int> PauseAsync()` off `job_running_pause_async`. */
    suspend fun pause(): Int = progress

    /**
     * Second arm of a `suspend` **overload pair on a sealed arm**: same public name `PauseAsync`,
     * `_2` on both the native symbol and the private extern. `progress + millis` is unreachable
     * from [pause]'s body, so a mis-numbered extern shows up as a wrong *value*, not a missing
     * member.
     */
    suspend fun pause(millis: Int): Int = progress + millis

    /** `String` in and out across the async result protocol. */
    suspend fun resume(prefix: String): String = "$prefix$progress"

    // Private, so neither the sealed export loop nor the sealed translator sees it: both filter
    // the arm's properties to PUBLIC, and the arm's C# surface is the read-only `Beats` alone.
    private val _beats: MutableStateFlow<Int> = MutableStateFlow(progress)

    /**
     * ADR-124 coexistence: a flow member on an arm that already carries suspend members. The arm
     * owns exactly one `_scopeHandle` and one `DisposeAsync`, sourced from whichever route asks
     * first, so a second scope field would not compile and a second `DisposeAsync` would be CS0111.
     */
    val beats: StateFlow<Int> get() = _beats
  }

  /**
   * ADR-124: Mylo on the windowsill, watching. A **flow-only** arm, so the scope,
   * `IAsyncDisposable` and `DisposeAsync` all arrive from the flow route rather than from a
   * `suspend` member. [Job.Done] stays the arm with neither.
   */
  data class Watching(val id: String) : Job() {
    // The storage behind [ticks]. Private, so the arm's public surface is the read-only view.
    private val _ticks: MutableStateFlow<Int> = MutableStateFlow(id.length)

    /**
     * The issue's own shape: a `StateFlow<Int>` **property getter** on an arm, silently dropped
     * today. Backed by storage the arm holds rather than a fresh `MutableStateFlow` per get, so a
     * `.Value` read and a bounded collect observe the same value and a route that reads one
     * through a different export than the other disagrees.
     */
    val ticks: StateFlow<Int> get() = _ticks

    /**
     * A plain `Flow<String>` at a **method** return: `String` in and out on the collect protocol.
     */
    fun labels(prefix: String): Flow<String> = flow { emit("$prefix$id") }

    /**
     * Second arm of a **Flow overload pair on a sealed arm**: same public C# name `Labels`, `_2` on
     * the native symbol and on the private extern. Every emission here is unreachable from the
     * one-parameter body above, so a mis-numbered extern reads as wrong *values*, not as a missing
     * member.
     */
    fun labels(prefix: String, times: Int): Flow<String> =
      flow { repeat(times) { index -> emit("$prefix#$index") } }
  }

  /** Control arm: declares no functions of its own and must keep generating exactly as today. */
  data class Done(val code: Int) : Job()

  /** Mylo, folded into a loaf. A `data object` arm still carries declared methods. */
  data object Idle : Job() {
    /** A method on an object arm: it takes the handle receiver, not a static route. */
    fun poke(): String = "idle"

    /** Declared `override` of [Job.describe]: renders as a plain `public` method on the arm. */
    override fun describe(): String = "idle"

    /**
     * A `suspend fun` on a `data object` arm: it crosses on the arm's handle like any other arm,
     * and brings the scope, `IAsyncDisposable` and `DisposeAsync` with it.
     */
    suspend fun nap(): String = "napping"
  }
}

/**
 * Concrete-arm access, the `NestedShapeFactory`/`FlatShapeFactory` precedent: a nested arm and an
 * object arm at a class-method return position, each spelled as its own C# type.
 */
class JobFactory {
  /** Nested `data class` arm at a concrete return. */
  fun running(progress: Int): Job.Running = Job.Running(progress)

  /** Nested `data object` arm at a concrete return, the access path `Loaf` already has. */
  fun idle(): Job.Idle = Job.Idle

  /** ADR-124: the flow-only arm, reached the same way [running] reaches the suspending one. */
  fun watching(id: String): Job.Watching = Job.Watching(id)
}

/**
 * Sealed **base** at a top-level function return (ADR-007 puts it on the static class `JobSample`),
 * the sealed-return position this repository already exercises. Always answers with [Job.Running],
 * so C# can reach the arm without depending on the concrete-return spelling.
 */
fun anyJob(progress: Int): Job = Job.Running(progress)

/** The same base return, discriminating onto the `data object` arm instead. */
fun idleJob(): Job = Job.Idle
