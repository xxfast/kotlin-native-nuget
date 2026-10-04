package io.github.xxfast.kotlin.native.nuget.test.torpor

/**
 * Fixture for the abstract backing wrapper: C# never constructs an abstract class, so a handle that
 * materialises as one constructs the class's internal `Backing` wrapper instead, which overrides
 * every abstract member over a call-through export that dispatches virtually in Kotlin.
 *
 * - [Torpor.Dormant], an `abstract` sealed arm. It renders `public abstract class Dormant : Torpor`
 *   (it used to be `sealed`, so [DeepTorpor] could not derive from it, CS0509), declares
 *   [Torpor.Dormant.depth] and [Torpor.Dormant.mood] `abstract` (they used to be dropped, so
 *   [DeepTorpor]'s overrides were CS0115), and its `open` [Torpor.Dormant.label] `virtual`.
 * - [DeepTorpor], an exported concrete subclass of the arm, handed back typed as the sealed base
 *   ([Den.deepest]) and as the arm ([Den.deep]). Both come back as the arm's backing wrapper:
 *   `is Torpor.Dormant` holds, `is DeepTorpor` does not, and every member answers as [DeepTorpor].
 * - [Hibernator], an ordinary abstract class at a return position ([Den.hibernator]) and as a list
 *   element ([Den.hibernators]). Its return used to render `new Hibernator(...)`, CS0144.
 *
 * Oreo hibernates on the radiator from November to March. Mylo only claims to.
 */
sealed class Torpor {
  abstract class Dormant : Torpor() {
    /** How deep, overridden by every concrete subclass. */
    abstract fun depth(): Int

    /** An abstract property, overridden by every concrete subclass. */
    abstract val mood: String

    /** An open member a subclass overrides. */
    open fun label(): String = "deep"

    /** A final member that calls the abstract one, so it answers through Kotlin dispatch. */
    fun minutes(): Int = depth() * 10

    /** A `Result` member: its `TryWeigh` twin is abstract here and implemented by the wrapper. */
    abstract fun weigh(): Result<Int>
  }

  /** A final arm beside the abstract one, so the discriminator has two kinds to tell apart. */
  class Brief(val minutes: Int) : Torpor()
}

/** Oreo's torpor, [level] deep. */
class DeepTorpor(private val level: Int) : Torpor.Dormant() {
  override fun depth(): Int = level

  override val mood: String = "dreaming"

  override fun label(): String = "deeper"

  override fun weigh(): Result<Int> =
    if (level > 0) Result.success(level) else Result.failure(IllegalStateException("awake"))
}

/** An ordinary abstract class, with abstract and open members. */
abstract class Hibernator {
  abstract val name: String

  abstract fun snores(): Int

  /** A `Result` member: its `TryWeigh` twin is abstract here and implemented by the wrapper. */
  abstract fun weigh(): Result<Int>

  open fun describe(): String = "$name snores ${snores()} times"
}

/** Oreo, who snores three times and then pretends she did not. */
class OreoHibernator : Hibernator() {
  override val name: String = "Oreo"

  override fun snores(): Int = 3

  override fun weigh(): Result<Int> = Result.success(4)
}

/** Where everyone sleeps; hands each shape back through a base-typed position. */
class Den {
  fun deepest(level: Int): Torpor = DeepTorpor(level)

  fun deep(level: Int): Torpor.Dormant = DeepTorpor(level)

  fun brief(minutes: Int): Torpor = Torpor.Brief(minutes)

  fun hibernator(): Hibernator = OreoHibernator()

  val hibernators: List<Hibernator> get() = listOf(OreoHibernator(), OreoHibernator())
}
