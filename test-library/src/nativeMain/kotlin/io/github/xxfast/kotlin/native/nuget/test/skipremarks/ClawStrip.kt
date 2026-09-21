package io.github.xxfast.kotlin.native.nuget.test.skipremarks

/**
 * Issue #249 / ADR-064: every member-level skip must be named on its OWNER in the generated
 * `Interop.cs`, as a `<remarks>` paragraph, not only in the producer's build log.
 *
 * A dropped member reaches C# as nothing at all -- there is no `CirProperty`/`CirMethod` to hang
 * text on -- so the paragraph lives on the declaration that WOULD have declared it: the class,
 * the object's static class, the interface, the sealed ARM, the nested class, or (for a top-level
 * declaration) the ADR-007 file-named static holder. This package is new on purpose: its skip
 * warnings are isolated, so an assertion here cannot be held up or muddied by an unrelated
 * fixture's drop.
 *
 * Every owner seam once, and the two shapes that are NOT owner-level:
 * - [ClawStrip] -- dropped properties AND dropped methods beside surviving members (including the
 *   boundary-nullability cells: nullable map keys at a return, a property and a nested position, and
 *   nullable lambda payloads on the per-call and stored callback routes), plus the
 *   ADR-075 partial case ([ClawStrip.lastTumble]: the setter drops, the property survives, so the
 *   remark belongs on the C# PROPERTY rather than on the class),
 * - [KibbleBin] -- an `object` (static class) with a dropped method,
 * - [Pounceable] -- an interface with a dropped member,
 * - [Scamper] -- a sealed class whose dropped member is on ONE arm ([Scamper.Dash]); the base and
 *   the sibling arm ([Scamper.Skid]) must stay clean, which is the one silent-failure risk here
 *   (a remark landing on the wrong nested type or arm),
 * - [Gantry.Rung] -- a nested class with a dropped member; the OUTER type must stay clean,
 * - [sortStrips] -- a dropped top-level function beside the surviving [countStrips], so the
 *   remark lands on `ClawStripKt` (ADR-007 renames the file class because [ClawStrip] took the
 *   name),
 * - [Radiator] -- author KDoc with a second paragraph AND a dropped member, so the author's
 *   `<para>`s come first inside the single `<remarks>` and the generated one comes last
 *   (ADR-150 amendment 2026-09-20).
 *
 * The all-dropped FILE case is next door in `CatFlap.kt`.
 *
 * Only stably-unsupported shapes are used, so this fixture cannot quietly stop testing anything
 * the day a roadmap item lands:
 * - `Sequence<String>` at a property position -- a Kotlin stdlib type with no C# mapping
 *   (`SKIPPED_UNSUPPORTED_PROPERTY`), the same shape `cat.Cat.unsupported` has carried for ADRs,
 * - `Map<String?, Int>` at a parameter position -- ADR-083 excludes a nullable map key BY NAME (a
 *   C# `Dictionary` cannot hold one), pinned by `Tier1NamedSkipDiagnosticsTest`
 *   (`SKIPPED_UNSUPPORTED_INPUT`),
 * - `List<List<String>?>` at a parameter position -- ADR-099's nullable nested component, the same
 *   shape the `husk` fixtures use (`SKIPPED_UNSUPPORTED_INPUT`),
 * - `var t: Throwable?` -- ADR-107: C# cannot construct a Kotlin `Throwable`, so the setter alone
 *   is refused and the getter survives.
 *
 * Deliberately NOT used: `ByteArray` in collections, extension-property receivers, `object`
 * properties and nullable `Flow` elements, all of which sibling roadmap items are making
 * supported right now.
 *
 * Oreo shreds the sisal on the way past. Mylo supervises from the top of the gantry and takes
 * credit for it.
 */

/**
 * The ordinary-class owner: two whole-member drops plus one partial.
 *
 * @property name which cat the post belongs to
 */
class ClawStrip(val name: String) {

  /** The survivor beside the drops: nothing about its own marshalling is under test. */
  fun shred(): String = "$name shreds the sisal"

  /** The surviving property, so "named as dropped" is never confused with "named at all". */
  val height: Int = 40

  /**
   * Dropped whole (`SKIPPED_UNSUPPORTED_PROPERTY`): `kotlin.sequences.Sequence` has no C#
   * mapping, so nothing of this property reaches C#. `ClawStrip` must say so.
   */
  val weave: Sequence<String> get() = sequenceOf("sisal", "jute")

  /**
   * Dropped whole (`SKIPPED_UNSUPPORTED_INPUT`): ADR-083's nullable map key. `ClawStrip` must say
   * so, with the Kotlin spelling `rankPerches`, never the C# `RankPerches` that never existed.
   */
  fun rankPerches(scores: Map<String?, Int>): Int = scores.size

  /**
   * The ADR-075 PARTIAL case: the setter is refused (`SKIPPED_UNSUPPORTED_INPUT`, "C# cannot
   * construct a Kotlin Throwable"), the getter binds, so the member survives read-only and the
   * remark belongs on the C# property `LastTumble` itself -- the one per-member remark in the
   * feature.
   */
  var lastTumble: Throwable? = null

  /**
   * Part B, the RETURN position of a nullable map key (`SKIPPED_UNSUPPORTED_INPUT`'s missing twin).
   * ADR-083 declined `Map<String?, Int>` at an INPUT position by name -- that is [rankPerches]
   * above -- but left the result-position gates untouched, so this member binds today and renders
   * `IReadOnlyDictionary<string?, int>` whose body calls `NugetMarshal.ReadMap<string?, int>`. That
   * helper is declared `where TKey : notnull`, so the generated file does not compile: CS8714,
   * which the generated-bindings build turns into an error. A `Map<String?, Int>` return is
   * therefore a `packNuget` abort, not a consumer-side warning, and the fix is the same named skip
   * the input side already ships, attributed to the KEY and not to the slot.
   */
  fun perchScores(): Map<String?, Int> = mapOf(null to 1, "windowsill" to 2)

  /**
   * Part B, the PROPERTY-READ position of the same gap, through a different planner
   * (`ForwardPropertyPlanner.isReadable`, which has no key-nullability test either). The key is
   * `Int?` rather than `String?` so the cell also pins that the skip is about key NULLABILITY and
   * not about `String?` in particular: a value-typed nullable key raises the identical CS8714.
   */
  val tallies: Map<Int?, String> = emptyMap()

  /**
   * Part B, NESTED: the same key one level down. `isBridgeableComponent` recurses, so a fix that
   * consults the new predicate at the recursive site covers this for free and a fix that special
   * cases the outermost type does not. Red the same way today (`ReadMap<string?, int>` inside a
   * `ReadList`).
   */
  fun nestedScores(): List<Map<String?, Int>> = emptyList()

  /**
   * Part A2, per-call callback route, VALUE payload. The legacy lambda selector keys on the
   * expanded type's qualified name only, so the `?` on the payload is dropped: C# is handed
   * `Action<int>` and the generated Kotlin passes `it0: Int?` into a
   * `CFunction<(Int, COpaquePointer) -> Unit>`. That does not compile ("actual type is 'Int?', but
   * 'Int' was expected"), with no KSP diagnostic first, so today this shape aborts the author's
   * build with a Kotlin compiler error in generated code. The named skip replaces a broken build,
   * which is why it owes no removal note.
   */
  fun eachTumble(onTumble: (Int?) -> Unit) = onTumble(null)

  /**
   * Part A2, per-call route, REFERENCE payload. This one compiles and is worse for it: the
   * generated Kotlin is `NugetHandles.retain(it0 as Any)`, so a real null payload is an uncaught
   * `NullPointerException` inside a `@CName` export with no error slot, i.e. the host process dies
   * and no C# `catch` can see it. There is no xunit cell for that outcome on purpose: it would take
   * the test host down rather than fail.
   */
  fun eachStrip(onStrip: (String?) -> Unit) = onStrip(null)

  /**
   * Part A2, per-call route, HANDLE payload. `NugetHandles.retain` takes `Any`, so the generated
   * `retain(it0)` against a `ClawStrip?` does not compile either. Declared with a payload from this
   * same package so the cell does not drag an unrelated fixture type into the skip pool.
   */
  fun eachNeighbour(onNeighbour: (ClawStrip?) -> Unit) = onNeighbour(null)

  /**
   * Part A2, per-call route, nullable RETURN inside the lambda. The generated Kotlin ends in
   * `cbFn.invoke(it0, cbUserData)!!`, so a C# callback that legitimately returns null NPEs at the
   * `!!`. Nullability at the lambda's return is a separate seam from its argument and is dropped by
   * a separate line of the same selector, so it is its own cell.
   */
  fun askWeave(onAsk: (Int) -> String?): String? = onAsk(1)

  /**
   * Part A2, STORED callback route (ADR-037), the add half of the pair. Stricter than the per-call
   * route: the generated bridge lambda is declared with the nullability stripped
   * (`val bridge: (String) -> Unit = ...`) and then handed to a member expecting
   * `(String?) -> Unit`, so BOTH halves of the pair fail the generated-Kotlin compile even for the
   * reference payload that survives compilation on the per-call route. The pair must be named as a
   * pair: a skip on the add alone would leave a remove with nothing to remove.
   */
  fun addRinger(listener: (String?) -> Unit) {
    ringers += listener
  }

  /** Part A2, the remove half of the [addRinger] pair. */
  fun removeRinger(listener: (String?) -> Unit) {
    ringers -= listener
  }

  private var ringers: List<(String?) -> Unit> = emptyList()
}

/** An `object` owner: its static class carries the remark for the dropped [tallyShelves]. */
object KibbleBin {

  /** The survivor. */
  fun scoop(): String = "one scoop for Mylo"

  /** Dropped (`SKIPPED_UNSUPPORTED_INPUT`, nullable map key). */
  fun tallyShelves(counts: Map<String?, Int>): Int = counts.size
}

/**
 * An interface owner. Nothing implements it, exactly as `kdoc.IPerchable` -- the generated shape
 * is what is observable, and a dropped interface member is invisible in a different way from a
 * dropped class member (a consumer implementing it never learns the member was refused).
 */
interface Pounceable {

  /** The survivor. */
  fun pounce(): String

  /** Dropped (`SKIPPED_UNSUPPORTED_INPUT`, nullable map key). */
  fun rankTargets(scores: Map<String?, Int>): Int
}

/**
 * A sealed hierarchy: the drop is on ONE arm. The base and the sibling arm must carry nothing,
 * which is the assertion that catches a post-pass attaching a remark to the wrong owner.
 */
sealed class Scamper {

  /** The arm that loses a member. */
  data class Dash(val metres: Int) : Scamper() {

    /** The survivor on the arm. */
    fun speed(): String = "$metres metres flat out"

    /** Dropped (`SKIPPED_UNSUPPORTED_INPUT`, nullable map key). Remark belongs on `Dash`. */
    fun rankRoutes(scores: Map<String?, Int>): Int = scores.size
  }

  /** The clean sibling arm: no drop, therefore no remark anywhere on it. */
  data class Skid(val tiles: Int) : Scamper() {
    fun stop(): String = "$tiles tiles of skid"
  }
}

/** The OUTER type of the nesting cell: it declares nothing that was dropped, so it stays clean. */
class Gantry(val name: String) {

  /** The survivor on the outer type. */
  fun survey(): String = "$name surveys the room"

  /** The nested owner (ADR-133 declares it as `Gantry.Rung`), which loses a member of its own. */
  class Rung(val height: Int) {

    /** The survivor on the nested type. */
    fun perch(): String = "perched at $height"

    /** Dropped (`SKIPPED_UNSUPPORTED_INPUT`, nullable map key). Remark belongs on `Gantry.Rung`. */
    fun rankHeights(scores: Map<String?, Int>): Int = scores.size
  }
}

/**
 * Warms Oreo from below and Mylo from above.
 *
 * This second paragraph is author prose, so it becomes the FIRST `<para>` of the single
 * `<remarks>` this type gets (ADR-150). The generated skip paragraph for [thermalMap] must come
 * after it, never before and never in a second `<remarks>` element.
 */
class Radiator(val room: String) {

  /** The survivor. */
  fun warmth(): Int = 21

  /** Dropped whole (`SKIPPED_UNSUPPORTED_PROPERTY`): `Sequence` has no C# mapping. */
  val thermalMap: Sequence<String> get() = sequenceOf("warm", "warmer")
}

/**
 * The surviving top-level function. Its presence is what keeps `ClawStripKt` alive today, so the
 * remark for [sortStrips] has an owner without needing the husk rule the `CatFlap.kt` cell is
 * about.
 */
fun countStrips(): Int = 2

/**
 * The dropped top-level function (`SKIPPED_UNSUPPORTED_INPUT`, ADR-099's nullable nested
 * component). Issue #249's headline case: today it is absent from `Interop.cs` with no trace at
 * all, and the file's static holder is the only place a consumer could find out.
 */
fun sortStrips(litters: List<List<String>?>): Int = litters.size
