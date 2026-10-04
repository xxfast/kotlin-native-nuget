package io.github.xxfast.kotlin.native.nuget.test.nested

/**
 * ADR-141 fixture: a public `inner class` is declared as a C# nested type whose constructor takes
 * the outer instance **first** (`new Hearth.Sunbather(hearth, 3)`, export
 * `hearth_sunbather_create(outer, minutes, error)`), which is the one thing ADR-133's nested class
 * is missing. Everything else about an inner class (its handle, its members, its dispose, its type
 * positions) is already what a plain nested class does, so the cells here are exactly the seams the
 * receiver-carrying constructor plan crosses:
 *
 * - [Hearth.Sunbather] is the **primitive-only** constructor: one `Int` parameter and nothing that
 *   needs conversion. It is the ADR-141 silent-loss hazard in fixture form. If the outer joins the
 *   public parameter list but not the projection's input list, the constructor keeps the trivial
 *   `hasErrorCheck` path and the generated C# passes a `Hearth` where an `IntPtr` slot is, which is
 *   CS1503 at the consumer's compile. A fixture whose only inner constructor took a `String` would
 *   never take that path and would go green over the bug.
 * - [Hearth.Cushion] is the **converted-parameter** constructor: a `String` parameter, so the
 *   projection has to keep the receiver at the head of the same list the prelude and the cleanup
 *   iterate, beside a parameter that genuinely needs custom marshalling.
 * - [Hearth.Sunbather.basking] and [Hearth.Cushion.label] read `this@Hearth.room`, the outer's
 *   own constructor argument. That is the reference an inner class carries and the reason the
 *   crossing needs no extra ABI: the inner's `StableRef` keeps the outer object alive on the Kotlin
 *   heap even after C# disposes the outer handle.
 * - [sunbatherAt] returns the inner and [minutesOf] takes it, so a member-returned instance and a
 *   constructed one have to be the same C# type, and the handle has to survive a round trip back
 *   into Kotlin. [cushionOf] is the same return seam for the converted-parameter inner.
 * - [Hearth.Sunbather]'s `require(minutes >= 0)` gives the receiver-carrying constructor a throwing
 *   path, which is what the `LeakTests` fault-injection row needs: the borrowed outer handle must
 *   not leak when the inner's constructor throws before it ever mints one.
 * - [label] is the control: a change that drops the owner instead of declaring its children reads
 *   differently from one that only fails the inner classes.
 *
 * Member names are `sunbatherAt`/`minutesOf`/`cushionOf`, never `sunbather`/`cushion`: C# forbids a
 * member and a nested type sharing a name in the same declaring type (CS0102), the same rule as
 * `Aviary.perchAt` and `Hamper.weightOf`. No constructor parameter is named `outer` here, but one
 * would be safe: `outer` is in `PLAN_OWNED_NAMES`, so a user parameter of that name renders
 * `outer_` the way `value` does (`Tier1ReservedParameterNamesTest`).
 *
 * - [Hearth.Sunbather.Paw] is the **inner-of-inner**: an inner class owning its own inner class.
 *   Its constructor's outer is the immediately enclosing [Hearth.Sunbather]
 *   (`new Hearth.Sunbather.Paw(sunbather, 2)`, `hearth_sunbather_paw_create(outer, toes, error)`),
 *   and [Hearth.Sunbather.Paw.trail] reads both `this@Hearth` and `this@Sunbather`, neither of
 *   which crosses the ABI: the chain above the receiver rides the Kotlin heap.
 *
 * An inner class under a sealed owner lives in `Deferred.kt` beside `Purr`. A generic inner class
 * and an inner class of a generic outer live in `GenericNested.kt` (ADR-196).
 *
 * Oreo (black with the white middle) holds the hearth for as long as it lasts; Mylo (brown and
 * creamy) waits for the velvet cushion beside it, which is also warm, but only second-best.
 */
class Hearth(val room: String) {

  /**
   * The primitive-only inner: `public Sunbather(Hearth outer, int minutes)`,
   * `hearth_sunbather_create(outer, minutes, error)`, Kotlin body
   * `outer.asStableRef<Hearth>().get().Sunbather(minutes)`.
   */
  inner class Sunbather(val minutes: Int) {

    init {
      require(minutes >= 0) { "A cat cannot bask for negative minutes" }
    }

    /** Reads the outer instance's constructor argument through `this@Hearth`. */
    val basking: String get() = "${this@Hearth.room} warms Oreo for $minutes min"

    fun describe(): String = "sunbather@$minutes"

    /**
     * The inner-of-inner: `public Paw(Sunbather outer, int toes)`, Kotlin body
     * `outer.asStableRef<Hearth.Sunbather>().get().Paw(toes)`.
     */
    inner class Paw(val toes: Int) {

      /** Reads both enclosing instances, two levels up and one level up. */
      fun trail(): String = "${this@Hearth.room}/${this@Sunbather.minutes}/$toes"
    }

    /** Return position for the inner-of-inner. */
    fun pawAt(toes: Int): Paw = Paw(toes)

    /** Parameter position for the inner-of-inner. */
    fun toesOf(paw: Paw): Int = paw.toes
  }

  /** The converted-parameter inner: `public Cushion(Hearth outer, string fabric)`. */
  inner class Cushion(val fabric: String) {

    /** The `this@Hearth` read on the converted-parameter side. */
    val label: String get() = "$fabric cushion in ${this@Hearth.room}"

    fun describe(): String = "cushion/$fabric"
  }

  /** Return position: a member-returned inner is the same C# type as a constructed one. */
  fun sunbatherAt(minutes: Int): Sunbather = Sunbather(minutes)

  /** Parameter position: the inner's handle has to unwrap back into Kotlin. */
  fun minutesOf(sunbather: Sunbather): Int = sunbather.minutes

  /** Return position for the converted-parameter inner. */
  fun cushionOf(fabric: String): Cushion = Cushion(fabric)

  /** Control. */
  fun label(): String = "hearth in $room"
}
