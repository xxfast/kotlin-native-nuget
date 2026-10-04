package io.github.xxfast.kotlin.native.nuget.test.outcome

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * ADR-199: a generic sealed hierarchy binds as `Outcome<T>` with its arms on the non-generic
 * `Outcome` holder. Every arm shape the ADR's arm rule decides appears here once:
 *
 *  - [Ok] forwards `T` (rule 1), [Err] and [Loading] fix the variant `T` to `Nothing` (rule 2,
 *    phantom), [Both] has an own parameter `U` C# cannot recover (rule 3).
 *  - [Pending] is an abstract generic arm with a non-arm subclass [Later] declared outside the
 *    hierarchy, so a `Later` handle reconstructs through `Outcome.Pending.Backing<T>`.
 *  - [Partial] is an open arm, [Fault] an intermediate sealed arm, [Stray] a top-level sibling arm,
 *    [Detail] a nested declaration that is not an arm at all.
 *
 * Oreo's dinner requests mostly come back [Ok]. Mylo's mostly come back [Err].
 */
sealed class Outcome<out T> {
  /** Rule 1: forwards `T`. A `data` arm, so `Equals`/`ToString` cross too. */
  data class Ok<T>(val value: T) : Outcome<T>()

  /** Rule 2 under `out T`: a phantom `Outcome.Err<T>` in C#. */
  data class Err(val message: String) : Outcome<Nothing>()

  /** Rule 2 for a `data object`: received, never constructed. Overrides the base's open member. */
  data object Loading : Outcome<Nothing>() {
    override fun label(): String = "still loading"
  }

  /** An abstract generic arm. Only [Later], declared outside the hierarchy, implements it. */
  abstract class Pending<T> : Outcome<T>() {
    abstract fun eta(): Int
  }

  /** An open arm: `public class` in C#, its open member `virtual`. */
  open class Partial<T>(val sofar: T) : Outcome<T>() {
    open fun progress(): String = "partial $sofar"
  }

  /** An intermediate sealed arm with a forwarding arm and a phantom arm of its own. */
  sealed class Fault<out T> : Outcome<T>() {
    class Timeout(val seconds: Int) : Fault<Nothing>()
    class Refused<T>(val offer: T) : Fault<T>()
  }

  /**
   * An intermediate sealed arm that fixes the variant `T` to `Nothing`. The phantom carries down:
   * C# declares `Outcome.Lapse<T>`, with arms `Outcome.Lapse.Stall<T>` and `Outcome.Lapse.Gone<T>`.
   */
  sealed class Lapse : Outcome<Nothing>() {
    class Stall(val minutes: Int) : Lapse()
    data object Gone : Lapse()
  }

  /**
   * Rule 3: `U` never reaches the base, so C# declares `Outcome.Both<T>`; the constructor and
   * [extra] name `U` and are named skips, [value] still binds.
   */
  class Both<T, U>(val value: T, val extra: U) : Outcome<T>()

  /** Not an arm: a plain nested declaration on the holder, `Outcome.Detail`. */
  class Detail(val note: String)

  open fun label(): String = "outcome"
}

/** A top-level sibling arm: declared at namespace level in C# as `Stray<T> : Outcome<T>`. */
class Stray<T>(val found: T) : Outcome<T>()

/** A non-arm subclass of the abstract arm. Takes the ordinary class route. */
class Later<T>(val item: T) : Outcome.Pending<T>() {
  override fun eta(): Int = 3
}

/** Invariant base: the fixed arm keeps Kotlin's exact shape, `Cell.IntCell : Cell<int>`. */
sealed class Cell<T> {
  class Full<T>(val item: T) : Cell<T>()
  class IntCell(val number: Int) : Cell<Int>()

  /** An intermediate sealed arm closing the invariant `T`: plain `Cell.Spare : Cell<int>` in C#. */
  sealed class Spare : Cell<Int>() {
    class Last(val count: Int) : Spare()
  }
}

/**
 * Two variant parameters. [Flip] permutes them, so C#'s `Duel.Flip<Y, X>` lists `Y` first, and
 * [Draw] fixes both, so it carries two phantoms.
 */
sealed class Duel<out A, out B> {
  class Flip<X, Y>(val first: X, val second: Y) : Duel<Y, X>()
  data object Draw : Duel<Nothing, Nothing>()
}

/** The ribbons on offer at the cat show. */
enum class Ribbon { BLUE, RED, GOLD }

/**
 * ADR-198 bound: `T : Enum<T>` has no closed C# spelling, so members go through the trampoline.
 * [Placed.beats] compares on the Kotlin side, so a crossing that handed Kotlin the wrong entry
 * answers wrongly. The bound forces an invariant `T` (`out T : Enum<T>` does not compile), so the
 * fixed arm closes over [Ribbon] by rule 2: `Showcase.Scratched : Showcase<Ribbon>`.
 */
sealed class Showcase<T : Enum<T>> {
  class Placed<T : Enum<T>>(val prize: T) : Showcase<T>() {
    fun beats(other: T): Boolean = prize > other
  }

  data object Scratched : Showcase<Ribbon>()
}

/** An exported interface used as a bound. */
interface Dozer {
  val name: String
}

/** An exported class: the [Outcome] argument that is neither primitive nor string. */
class Lodger(override val name: String) : Dozer

/** Interface bound: every arm restates `where T : IDozer`. */
sealed class Blanket<out T : Dozer> {
  class Claimed<T : Dozer>(val dozer: T) : Blanket<T>()
  data object Folded : Blanket<Nothing>()
}

/** ADR-112: an eligible generic sealed interface takes the same route. */
sealed interface Reply<out T> {
  data class Trill<T>(val pitch: T) : Reply<T>
  data object Hiss : Reply<Nothing>
}

/**
 * A generic class with `T` in scope at a sealed position, and an erased `T` slot the consumer
 * can fill with an instantiation no Kotlin position names (`Hamper<Outcome<long>>`).
 */
class Hamper<T>(val item: T) {
  fun wrap(): Outcome<T> = Outcome.Ok(item)

  fun unwrapOr(outcome: Outcome<T>, fallback: T): T =
    if (outcome is Outcome.Ok) outcome.value else fallback
}

/** A constructor parameter and a mutable property typed with a closed instantiation. */
class Diary(var entry: Outcome<String>) {
  fun read(): String = when (val current = entry) {
    is Outcome.Ok -> "dear diary: ${current.value}"
    is Outcome.Err -> "dear diary: ${current.message}"
    else -> "dear diary: ${current.label()}"
  }

  /**
   * ADR-199 inferred claim 4: a closed instantiation as a `Flow` element. A `Flow` return binds
   * only on an ordinary class method, hence here and not on [OutcomeDesk].
   */
  fun dinnerBell(): Flow<Outcome<Int>> =
    flowOf(Outcome.Ok(1), Outcome.Err("late"), Outcome.Loading)
}

/** Top-level function return, on the file's static class. Oreo always asks for tuna first. */
fun firstMeal(): Outcome<String> = Outcome.Ok("tuna")

/**
 * ADR-199 inferred claim 4: a returned lambda taking and returning a closed instantiation. A lambda
 * return binds only at a top-level function, hence here and not on [OutcomeDesk].
 */
fun retry(): (Outcome<Int>) -> Outcome<Int> = { outcome ->
  if (outcome is Outcome.Ok) Outcome.Ok(outcome.value + 1) else Outcome.Err("retry")
}

object OutcomeDesk {
  fun fetch(id: Int): Outcome<Int> = if (id > 0) Outcome.Ok(id) else Outcome.Err("no $id")

  fun name(id: Int): Outcome<String>? = if (id > 0) Outcome.Ok("Oreo") else null

  fun all(): List<Outcome<Int>> = listOf(Outcome.Ok(1), Outcome.Err("two"), Outcome.Loading)

  fun okCount(outcomes: List<Outcome<Int>>): Int = outcomes.count { it is Outcome.Ok }

  fun describe(outcome: Outcome<Int>): String = when (outcome) {
    is Outcome.Ok -> "ok ${outcome.value}"
    is Outcome.Err -> "err ${outcome.message}"
    is Outcome.Pending -> "pending ${outcome.eta()}"
    else -> outcome.label()
  }

  fun describeOrNone(outcome: Outcome<Int>?): String =
    if (outcome == null) "none" else describe(outcome)

  fun eventually(): Outcome<Int> = Later(7)

  fun fail(): Outcome.Err = Outcome.Err("boom")

  fun unwrap(ok: Outcome.Ok<Int>): Int = ok.value

  fun adopt(): Outcome<Lodger> = Outcome.Ok(Lodger("Mylo"))

  fun both(): Outcome<Int> = Outcome.Both(5, "Mylo")

  fun partial(): Outcome<String> = Outcome.Partial("half a treat")

  fun wander(): Outcome<String> = Stray("Mylo")

  fun timeout(): Outcome<Int> = Outcome.Fault.Timeout(30)

  fun refused(): Outcome<String> = Outcome.Fault.Refused("kibble")

  fun numberCell(): Cell<Int> = Cell.IntCell(4)

  fun fullCell(): Cell<String> = Cell.Full("Oreo")

  fun peek(cell: Cell<Int>): Int = when (cell) {
    is Cell.Full -> cell.item
    is Cell.IntCell -> cell.number
    is Cell.Spare.Last -> cell.count
  }

  fun stall(): Outcome<Int> = Outcome.Lapse.Stall(15)

  fun gone(): Outcome<String> = Outcome.Lapse.Gone

  fun spareCell(): Cell<Int> = Cell.Spare.Last(3)

  fun rematch(): Duel<String, Int> = Duel.Flip(7, "Oreo")

  fun referee(duel: Duel<String, Int>): String = when (duel) {
    is Duel.Flip<*, *> -> "${duel.second} beat ${duel.first}"
    Duel.Draw -> "draw"
  }

  fun showOff(win: Boolean): Showcase<Ribbon> =
    if (win) Showcase.Placed(Ribbon.GOLD) else Showcase.Scratched

  fun judge(entry: Showcase<Ribbon>): String = when (entry) {
    is Showcase.Placed -> "${entry.prize.name}#${entry.prize.ordinal}"
    Showcase.Scratched -> "scratched"
  }

  fun bedtime(): Blanket<Lodger> = Blanket.Claimed(Lodger("Oreo"))

  fun call(loud: Boolean): Reply<Int> = if (loud) Reply.Trill(11) else Reply.Hiss

  fun hear(reply: Reply<Int>): String = when (reply) {
    is Reply.Trill -> "trill ${reply.pitch}"
    Reply.Hiss -> "hiss"
  }

  /** Named skip: a use-site projection has no C# spelling. */
  fun anyLabel(outcome: Outcome<*>): String = outcome.label()

  /** Named skip: the erased wire cannot read a `List<Int>` argument. */
  fun batch(): Outcome<List<Int>> = Outcome.Ok(listOf(1, 2))

  /** Named skip: `KotlinNothing` does not satisfy `where T : IDozer` (C6). */
  fun folded(): Blanket.Folded = Blanket.Folded
}
