package io.github.xxfast.kotlin.native.nuget.test.curlup

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Fixture for ADR-175: a sealed base's own `suspend` / `Flow` / `StateFlow` members reachable
 * through the **base-typed** reference. [Shape] is an eligible `sealed interface`, so ADR-112
 * renders it `public abstract class Shape`, and every factory below returns that base, never an
 * arm.
 *
 * Today the base declares none of its async members: [area], [ticks] and [fallback] are named
 * `SKIPPED_UNSUPPORTED_COMBINATION` (`SEALED_BASE_UNROUTED`), [level] is dropped in silence, and
 * each arm re-projects its own override with a scope of its own. After ADR-175 the base projects
 * them through its own exports (`shape_area_async`, `shape_ticks_collect`,
 * `shape_get_level_value`), receiver `asStableRef<Shape>()`, and owns the one scope.
 *
 * Every arm kind is crossed once, in one hierarchy, because the base's own dispatch is what has to
 * reach each of them, and values differ per arm so a wrong dispatch is a wrong number:
 * - [Shape.Loaf], a nested `data class` arm, with [Shape.Loaf.knead], the **arm-declared** suspend
 *   member the base does not declare: it stays on the arm and has to use the base-owned scope.
 *   [Shape.Loaf.area] has no suspension point, so it is also the leak harness's tight-loop body.
 * - [Shape.Donut], a nested `data object` arm.
 * - [Sprawl], a **sibling** arm declared beside the interface (ADR-125).
 * - [Curl], an **enum** arm (ADR-157, boxed as `CurlArm`). The arm the rejected "abstract member,
 *   arms override" design cannot serve, because the enum's own members never reach the box; only
 *   base dispatch on the entry's `StableRef` answers here.
 * - [fallback], a default body **no arm overrides**: bound nowhere today.
 * - [doze], a default body that never completes, so cancellation has to cross the base's route.
 * - [roll], a default body returning the sealed base itself, so the base route's completion goes
 *   through `Shape.FromHandle` (ADR-131's discriminated return).
 *
 * The sealed-*class* half of ADR-175 is `issue115`'s `Job.rest`, not repeated here.
 *
 * The shapes are the cats' sleeping shapes. Oreo (black with the white middle) folds into a loaf or
 * a tight curl; Mylo (brown and creamy) sprawls the length of the sofa or rings into a donut.
 */
sealed interface Shape {
  /** Overridden by every arm, each with its own answer. */
  suspend fun area(): Int

  /** A cold `Flow` every arm overrides. */
  fun ticks(): Flow<Int>

  /** A `StateFlow` property every arm overrides; silently dropped on the base today. */
  val level: StateFlow<Int>

  /** A default body no arm overrides: Oreo and Mylo both nap for nine minutes, any shape. */
  suspend fun fallback(): Int = 9

  /** A default body that never completes; only cancellation ends it. */
  suspend fun doze(): Int = awaitCancellation()

  /** A default body returning the sealed base, so the completion discriminates. Always a donut. */
  suspend fun roll(): Shape = Donut

  /** Oreo, folded into a loaf [width] paws wide. */
  data class Loaf(val width: Int) : Shape {
    private val _level: MutableStateFlow<Int> = MutableStateFlow(width)

    override suspend fun area(): Int = width * 3

    override fun ticks(): Flow<Int> = flowOf(width, width + 1)

    override val level: StateFlow<Int> get() = _level

    /** Arm-declared, not on the base: stays on `Shape.Loaf`, on the base-owned scope. */
    suspend fun knead(times: Int): Int = width * times + 1
  }

  /** Mylo, ringed into a donut. */
  data object Donut : Shape {
    private val _level: MutableStateFlow<Int> = MutableStateFlow(11)

    override suspend fun area(): Int = 11

    override fun ticks(): Flow<Int> = flowOf(11)

    override val level: StateFlow<Int> get() = _level
  }
}

/** Mylo, sprawled [length] cushions long. A sibling arm declared beside the interface (ADR-125). */
data class Sprawl(val length: Int) : Shape {
  private val _level: MutableStateFlow<Int> = MutableStateFlow(length)

  override suspend fun area(): Int = length * 10

  override fun ticks(): Flow<Int> = flowOf(length, length * 2)

  override val level: StateFlow<Int> get() = _level
}

/** Oreo, curled nose to tail. The enum arm (ADR-157), boxed in C# as `CurlArm`. */
enum class Curl : Shape {
  TIGHT,
  LOOSE;

  override suspend fun area(): Int = 100 + ordinal

  override fun ticks(): Flow<Int> = flowOf(100 + ordinal)

  override val level: StateFlow<Int> get() = MutableStateFlow(100 + ordinal)
}

/** A [Shape.Loaf] behind the base. */
fun loafShape(width: Int): Shape = Shape.Loaf(width)

/** [Shape.Donut] behind the base. */
fun donutShape(): Shape = Shape.Donut

/** A [Sprawl] behind the base. */
fun sprawlShape(length: Int): Shape = Sprawl(length)

/** A [Curl] entry behind the base. */
fun curlShape(loose: Boolean): Shape = if (loose) Curl.LOOSE else Curl.TIGHT
