package io.github.xxfast.kotlin.native.nuget.test.issue115

import io.github.xxfast.kotlin.native.nuget.test.issue54.NestedListenerOwner

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
 * - [Job.Running.pause] — `suspend`, the ADR-116 deferral: **absent** from C# and named with
 *   `SKIPPED_UNSUPPORTED_COMBINATION`, where today it is absent and silent.
 * - [Job.describe] — a base `open fun` **with a body**. Under ADR-116's declared-only gate an arm
 *   exports only what it declares itself, so this renders on **no** arm. [Job.Idle.describe], which
 *   is a declared `override`, renders as a plain `public string Describe()`: not `override` (the C#
 *   base declares nothing to override, CS0115) and not `virtual` (a `virtual` member on a
 *   `public sealed class` is CS0549).
 * - [Job.Idle.poke] — a method on a `data object` arm. An object arm is a `KSClassDeclaration` in
 *   `getSealedSubclasses()` like any other and crosses as a handle, so it must take the same
 *   receiver as a `data class` arm rather than becoming a static.
 * - [Job.Done] — the control: an arm that declares no functions at all must keep generating
 *   exactly as it does today.
 *
 * Deliberately absent: a lambda-parameter cell (`fun watch(onTick: (Int) -> Unit)`). Its skip is
 * one more row of the same post-process table as [Job.Running.pause], and it would drag the
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

    /** `suspend`: absent from C# in v1, but named. */
    suspend fun pause(): Int = progress
  }

  /** Control arm: declares no functions of its own and must keep generating exactly as today. */
  data class Done(val code: Int) : Job()

  /** Mylo, folded into a loaf. A `data object` arm still carries declared methods. */
  data object Idle : Job() {
    /** A method on an object arm: it takes the handle receiver, not a static route. */
    fun poke(): String = "idle"

    /** Declared `override` of [Job.describe]: renders as a plain `public` method on the arm. */
    override fun describe(): String = "idle"
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
}

/**
 * Sealed **base** at a top-level function return (ADR-007 puts it on the static class `JobSample`),
 * the sealed-return position this repository already exercises. Always answers with [Job.Running],
 * so C# can reach the arm without depending on the concrete-return spelling.
 */
fun anyJob(progress: Int): Job = Job.Running(progress)

/** The same base return, discriminating onto the `data object` arm instead. */
fun idleJob(): Job = Job.Idle
