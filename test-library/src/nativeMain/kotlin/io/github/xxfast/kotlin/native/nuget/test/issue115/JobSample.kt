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
 * - [Job.kind], item 35's property half: an `open val` **with a body on the base**, overridden by
 *   [Job.Running] and inherited unchanged by every other arm. A consumer holding a [Job] must be
 *   able to read it without discriminating, and the two arms must answer differently, so a base
 *   read that never dispatches into Kotlin is visible as a wrong value rather than a missing
 *   member.
 * - [Job.describe], a base `open fun` **with a body**. Since item 35 the sealed base carries it as
 *   `public virtual string Describe()`, so [Job.Running], which declares no override, inherits it
 *   in C# exactly as it does in Kotlin, and [Job.Idle], which declares one, renders
 *   `public override string Describe()`. The declared-only gate still holds on the arms: `Running`
 *   declares no `Describe` of its own.
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
 * - [JobFactory.runningLater], a `suspend fun` on an **ordinary class** returning a nested arm.
 *   The completion has to construct `new Job.Running(resultPtr)`: at namespace scope the bare
 *   `Running` the suspend route spells today is CS0246, since ADR-009 nests the arm inside `Job`.
 * - [JobFactory.idleLater], the same on a `data object` arm, so the fix covers both arm kinds
 *   rather than only the `data class` one.
 * - [Job.Running.finishLater], the arm-declared half: a sibling arm at a suspend return. It
 *   resolves from inside the enclosing base even unqualified, so it is the cell that separates
 *   "the speller is wrong everywhere" from "the speller is wrong only outside the base".
 * - [anyRunningLater], the **top-level** suspend route (ADR-007 static class `JobSample`), the
 *   second spelling site. A fix applied to the class route alone still leaves this one broken.
 *
 * The sealed **base** (not an arm) at a `suspend` return is the row ADR-118's amendment left
 * behind: `Task<Job>` is spelled correctly, but the completion renders
 * `t.SetResult(new Job(resultPtr))` against `public abstract class Job`, which is CS0144 in the
 * consumer. It must read `Job.FromHandle(resultPtr)`, the discriminator the synchronous
 * [Job.Running.next] already goes through. Every owner kind, once:
 * - [Job.Running.nextLater] — the base at a suspend return **on an arm**, answering with a
 *   *different* arm than the receiver, so a completion that constructs the receiver's type by
 *   name (or reads the discriminator wrong) fails on the payload rather than passing by luck.
 * - [Job.Running.nextOrNullLater] — the **nullable** twin, the branch one step up in the same
 *   renderer (`resultPtr == IntPtr.Zero ? null : new Job(resultPtr)`). Both the null input and an
 *   arm-returning one are reachable, so the guard and the read are crossed separately.
 * - [Job.Running.nextOrThrowLater] — the **throw** path: no result is minted, so the new read is
 *   never reached and only the ADR-128/130 error envelope crosses. It is the leak row's fixture.
 * - [JobFactory.nextLater] — an **ordinary class** owner dispatching across all three arm shapes
 *   by input (`data object`, payload `data class`, second `data class`), so a completion pinned to
 *   one constructor cannot pass all three.
 * - [anyNextLater] — the **top-level** route's own speller, the second site.
 * All of them are named `...Later`: [Job.Running.next] already owns the synchronous half of this
 * cell, and `suspend fun next()` beside it is `CONFLICTING_OVERLOADS`. The sealed-*interface* twin
 * lives on `Monitor.nextPulseLater` (issue54), and [Job.Done] stays suspend-free.
 *
 * Deliberately absent on the ADR-124 half: a base-declared flow property on [Job] itself (the
 * all-properties rule comes from ADR-111 and is already fixture-covered for ordinary property
 * types), a `suspend fun` returning a `Flow` (still a named `SKIPPED_UNSUPPORTED_RETURN` since
 * ADR-119), a flow on a `sealed interface` arm, a sealed element type (`Flow<Job>`, issue #126 and
 * #127 territory), and a `MutableStateFlow` write on an arm.
 *
 * The lambda-parameter half (ADR-036) is the row ADR-118 and ADR-124 left behind:
 * - [Job.Running.relabel], the `Func` cell. A `(String) -> String` parameter with a `String` outer
 *   return, so the arm has to reach the same thunk + `GCHandle` protocol `Cat.describeWith` uses,
 *   under the arm's own export prefix (`job_running_relabel`), with the UTF8 pair crossing on the
 *   callback argument and on the outer return at once.
 * - [Job.Idle.pokeWith], the `Action` cell. A `(String) -> Unit` parameter and a `Unit` outer
 *   return on a `data object` arm: the void branch of the renderer and the object arm's handle
 *   receiver in one member, so a route that only ever binds the value-returning shape, or only
 *   ever binds a `data class` receiver, cannot go green on this pair.
 *
 * Deliberately absent on the lambda half: a stored-callback or interface-bridge **pair** on an arm
 * (`addX`/`removeX`), a `suspend` lambda parameter, and a generic method. All three keep the
 * `SEALED_SUBCLASS_UNROUTED` row of the sealed post-process table, and a pair in particular must
 * stay *named* rather than fall silent, which is a diagnostic assertion rather than a cell.
 *
 * Oreo (black with the white middle) does all the running: he starts at a percentage of the hallway
 * and finishes it. Mylo (brown and creamy) is [Job.Idle], and pokes back exactly once when nudged.
 */
sealed class Job {
  /**
   * Item 35's property half: an `open val` with a body on the base. [Job.Running] overrides it and
   * every other arm inherits it unchanged, so a base-typed read has to dispatch to tell them apart.
   */
  open val kind: String = "job"

  /**
   * Base body. Item 35 made the sealed base the carrier: this renders `virtual` on the C# base,
   * [Job.Running] declares nothing and inherits it, and [Job.Idle], which declares an `override`,
   * spells one in C# too.
   */
  open fun describe(): String = "job"

  /**
   * ADR-118's declared-only cell on the **suspend** loop: an `open suspend fun` with a body that no
   * arm overrides. No arm may carry `RestAsync`, and no `job_*_rest_async` entry point may exist.
   */
  open suspend fun rest(): Int = 0

  /** Oreo, mid-sprint down the hallway, [progress] percent of the way to the food bowl. */
  data class Running(val progress: Int) : Job() {
    /** The one arm that overrides [Job.kind]; the rest inherit the base's `"job"`. */
    override val kind: String = "running"

    /** `Int` return, no conversion at the seam. */
    fun cancel(): Int = progress

    /** `String` in and out on one member. */
    fun label(prefix: String): String = "$prefix$progress"

    /**
     * The ADR-036 **lambda parameter** on a sealed arm, `Func` half: `String` in and out across
     * the callback protocol, so the UTF8 pair rides the thunk on the argument and on the outer
     * return at once. Oreo answers with his own progress and lets C# rename it.
     */
    fun relabel(transform: (String) -> String): String = transform("running-$progress")

    /** Overload pair, first arm. */
    fun step(by: Int): Int = progress + by

    /** Overload pair, second arm: same public C# name `Step`, `_2` native symbol. */
    fun step(by: Int, times: Int): Int = progress + by * times

    /** Sealed **base** return: `Job.FromHandle` discriminates it back onto [Done]. */
    fun next(): Job = Done(progress)

    /**
     * The sealed **base** at a *suspend* return, on an arm: `Task<Job>` completed through
     * `Job.FromHandle(resultPtr)`. Answers with a *different* arm than the receiver, so a
     * completion that constructs the receiver's type by name, or reads the discriminator wrong,
     * fails on `Code` rather than passing by luck. Named `nextLater` because [next] already owns
     * the synchronous half of this cell and `suspend fun next()` beside it is
     * `CONFLICTING_OVERLOADS`.
     */
    suspend fun nextLater(): Job = Done(progress + 1)

    /**
     * The nullable twin, `Task<Job?>`: the branch one step up in the completion renderer, which
     * spells `resultPtr == IntPtr.Zero ? null : new Job(resultPtr)` today and is the same defect.
     * Oreo at 100% is already at the bowl, so there is no next job.
     */
    suspend fun nextOrNullLater(): Job? = if (progress >= 100) null else Done(progress + 1)

    /**
     * The throw path of the same route: when the body throws, no result is minted, so the new
     * `FromHandle` read is never reached and the error envelope (ADR-128/130) is the only thing
     * crossing. A leak row drives this thousands of times, so the sentinel is cheap and the body
     * has no suspension point at all.
     */
    suspend fun nextOrThrowLater(step: Int): Job =
      if (step < 0) error("Oreo refuses to run backwards down the hallway")
      else Done(progress + step)

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

    /**
     * A **sibling nested arm** at a `suspend` return: `Task<Job.Done> FinishLaterAsync()`, whose
     * completion constructs the arm. Declared inside [Job], so C#'s enclosing-type lookup resolves
     * a bare `Done` here; [JobFactory.runningLater] is the same shape where it cannot.
     */
    suspend fun finishLater(): Done = Done(progress)

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

    /**
     * The `Action` half of the lambda-parameter route, on a `data object` arm: a `Unit` outer
     * return, so `renderCallbackMethod`'s void branch is the one crossed, and the object arm's
     * handle receiver rather than a `data class` one. Mylo says exactly one thing when nudged.
     */
    fun pokeWith(action: (String) -> Unit) = action("idle")

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

  /**
   * The same nested `data class` arm return as [running], on the **suspend** route: it binds as
   * `Task<Job.Running>` and its completion must construct `new Job.Running(resultPtr)`.
   */
  suspend fun runningLater(progress: Int): Job.Running = Job.Running(progress)

  /** The `data object` arm on the suspend route: `Task<Job.Idle>`, `new Job.Idle(resultPtr)`. */
  suspend fun idleLater(): Job.Idle = Job.Idle

  /**
   * The sealed **base** at a suspend return on an *ordinary class* owner, dispatching across all
   * three payload shapes by input: a `data object` arm, a `data class` arm with a payload, and a
   * second `data class` arm. One `Task<Job>` completion has to land on whichever arm the
   * discriminator names, so a completion pinned to a single constructor cannot pass all three.
   */
  suspend fun nextLater(progress: Int): Job = when {
    progress < 0 -> Job.Idle
    progress < 100 -> Job.Running(progress)
    else -> Job.Done(progress)
  }
}

/**
 * Sealed **base** at a top-level function return (ADR-007 puts it on the static class `JobSample`),
 * the sealed-return position this repository already exercises. Always answers with [Job.Running],
 * so C# can reach the arm without depending on the concrete-return spelling.
 */
fun anyJob(progress: Int): Job = Job.Running(progress)

/** The same base return, discriminating onto the `data object` arm instead. */
fun idleJob(): Job = Job.Idle

/**
 * The **top-level** suspend spelling site: a `suspend fun` on the ADR-007 static class `JobSample`
 * returning a nested arm. Oreo always ends up 33% down the hallway, so the value pins the crossing
 * rather than the argument.
 */
suspend fun anyRunningLater(): Job.Running = Job.Running(33)

/**
 * The **top-level** spelling site for the sealed *base* at a suspend return (ADR-007 static class
 * `JobSample`). The top-level route has its own return speller, so a fix applied to the class
 * route alone still leaves this one completing with `new Job(resultPtr)`. Always [Job.Done], so
 * the assertion reads a payload the base cannot answer.
 */
suspend fun anyNextLater(progress: Int): Job = Job.Done(progress)
