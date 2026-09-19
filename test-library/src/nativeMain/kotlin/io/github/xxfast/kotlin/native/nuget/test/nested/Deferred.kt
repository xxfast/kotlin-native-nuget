package io.github.xxfast.kotlin.native.nuget.test.nested

/**
 * ADR-134 fixture: one declaration per owner kind ADR-133 deferred and ADR-134 **admits**, each
 * with a member on the owner (or on one of its arms) that returns it, so the declaration seam and
 * the marshalling seam are both crossed.
 *
 * ADR-133 declares a nested type only under a `class` or `object` owner. Everything here is an
 * owner kind it skipped by name, and the cells are disjoint on purpose - each one travels a
 * different translator/renderer path, so an implementation that teaches one of them to carry
 * children still loses the others:
 *
 * - [Cage] is the **interface owner**: [Cage.Bar] is declared inside `public interface ICage`, and
 *   ADR-134's one reversal of ADR-133 is that the `I` prefix attaches to *every* interface segment
 *   of a chain, not only the last, so the C# name is `ICage.Bar` (not `Cage.Bar`, not `IBar`).
 *   [WireCage] exists because an interface cannot be constructed from C#: the consumer needs some
 *   exported class implementing [Cage] to get a [Cage.Bar] out of [Cage.barAt].
 * - [Purr] is the **sealed base owner**: [Purr.Detail] is declared beside the arms, in the block
 *   ADR-009 owns, and [Purr.detailOf] is a concrete base method so the arms inherit the crossing.
 * - [Purr.On.Trace] is the **sealed arm owner**: the child of an arm, two prefix segments deep
 *   (`purr_on_trace_create`), reachable through [Purr.On.traceOf].
 * - [Beam] is the **eligible sealed interface** owner (ADR-112), which is the gate-order cell: the
 *   owner walk tests `INTERFACE` before it tests sealed, and an eligible sealed interface is
 *   rendered by the *sealed* route as `public abstract class Beam`. If the relaxed interface arm
 *   claims it, [Beam.Lens] is partitioned into a `CirInterface` slot the sealed renderer never
 *   reads and disappears with no diagnostic. So the C# name is `Beam.Lens`, with no `I` anywhere,
 *   and `IBeam` must not exist (issue #54's rule).
 * - [Hamper.Weight] is the nested **`value class`** candidate: declared as a nested
 *   `readonly record struct`, crossing as its underlying `Int` (ADR-014/077) at both a return
 *   ([Hamper.weightOf]) and a parameter ([Hamper.gramsOf]) position.
 *
 * Member names are `barAt`/`detailOf`/`traceOf`/`weightOf`, never `bar`/`detail`/`trace`/`weight`:
 * C# forbids a member and a nested type sharing a name in the same declaring type (CS0102), which
 * `nestedOwnerScopeCollision()` diagnoses as `ERROR_CSHARP_SIGNATURE_COLLISION` and which would
 * skip the nested declaration this file exists to pin. Same rule as `Aviary.perchAt`.
 *
 * Deferred by ADR-134 and therefore absent here on purpose: an `enum class` owner
 * (`Season.Almanac`), a generic owner (`Box<T>.Lid`) and, per ADR-141, an `inner class` **owner**
 * (an inner class nested inside another one). Those stay a named `SKIPPED_NESTED_DECLARATION`
 * permanently and live in `Tier1NestedTypesTest`'s `deferredSource` only - a skip needs no consumer
 * test. The inner class itself is declared as of ADR-141 and lives in `Inner.kt` beside this file.
 *
 * Type names dodge the process-global C entry-point space (ADR-117): `Signal` would collide with
 * `platform`'s `expect sealed class Signal` (`signal_get_type`), `Crate` with `parcel`'s generic
 * `Crate<T>`, and `Beacon` with `platform`'s `expect class Beacon`. Hence `Purr`, `Hamper`, `Beam`.
 *
 * Oreo (black with the white middle) sleeps in the cage he is not supposed to be in and purrs at
 * two levels of nesting; Mylo (brown and creamy) is a hamper of exactly one weight, in grams.
 */
interface Cage {

  /** Nested class under an **interface** owner: `public class ICage.Bar`, `cage_bar_create`. */
  class Bar(val n: Int) {
    fun describe(): String = "bar#$n"
  }

  /** Return position on the interface itself, so the nested class crosses through dispatch. */
  fun barAt(): Bar

  /** Control: must keep binding whatever happens to the nested declaration. */
  fun label(): String
}

/**
 * The exported class a C# consumer actually constructs to reach [Cage.barAt]; an interface has no
 * constructor, so without this the interface cell could only be checked by reflection.
 */
class WireCage(val gauge: Int) : Cage {
  override fun barAt(): Cage.Bar = Cage.Bar(gauge)
  override fun label(): String = "wire/$gauge"
}

/**
 * The **sealed base** owner ([Purr.Detail]) and the **sealed arm** owner ([Purr.On.Trace]) in one
 * hierarchy, because the arm's block is rendered by a different function from the base's.
 *
 * Oreo purrs [On] at a level; Mylo, asleep, is [Off].
 */
sealed class Purr {

  /** Nested class beside the arms, in ADR-009's block: `purr_detail_create`. */
  class Detail(val text: String) {
    fun describe(): String = "detail:$text"
  }

  /** The payload arm, and itself an owner. */
  data class On(val level: Int) : Purr() {

    /** Nested class under a sealed **arm**: `purr_on_trace_create`, three prefix segments. */
    class Trace(val at: Int) {
      fun describe(): String = "trace@$at"
    }

    /** Return position on the arm. */
    fun traceOf(): Trace = Trace(level)
  }

  /** The payload-free arm; no children, so the base's crossing has to reach it by inheritance. */
  data object Off : Purr()

  /** Return position on the sealed base itself (a concrete base method, rendered `virtual`). */
  fun detailOf(): Detail = Detail("purr")
}

/**
 * The **eligible sealed interface** owner (ADR-112): no type parameters, every arm a class or
 * object with this as its only sealed supertype, so it renders `public abstract class Beam` and
 * [Beam.Lens] must be declared under *that*, spelled without an `I`. `IBeam` must not exist.
 *
 * The crossing member sits on the [Lit] arm rather than on the interface: an eligible sealed
 * interface is rendered as the abstract base, and nothing in the repo pins a Kotlin interface
 * default method surviving that render, so the cell would be red for an unrelated reason.
 */
sealed interface Beam {

  /** Nested class under an eligible sealed interface: `beam_lens_create`, declared on the base. */
  class Lens(val strength: Int) {
    fun describe(): String = "lens x$strength"
  }

  /** The payload arm, carrying the crossing. */
  data class Lit(val lumens: Int) : Beam {
    fun lensOf(): Lens = Lens(lumens)
  }

  /** The payload-free arm. */
  data object Dark : Beam
}

/**
 * The nested **`value class`** cell: [Hamper.Weight] is a nested `readonly record struct` whose
 * members export under the whole chain (`hamper_weight_isHeavy`), crossing the wire as its
 * underlying `Int`.
 */
class Hamper(val id: String) {

  /** Nested `value class`: `public readonly record struct Hamper.Weight`. */
  value class Weight(val grams: Int) {
    val kilos: Double get() = grams / 1000.0
    fun isHeavy(): Boolean = grams > 1000
  }

  /** Return position, nested value class. */
  fun weightOf(): Weight = Weight(id.length * 100)

  /** Parameter position, nested value class: the underlying has to re-wrap inside Kotlin. */
  fun gramsOf(weight: Weight): Int = weight.grams

  /** Control. */
  fun label(): String = "hamper $id"
}

/**
 * The payload-free arm at a top-level return (ADR-007 puts it on the static class `Deferred`): a
 * `data object` arm has no C# constructor, so this is how the consumer gets one to check that the
 * sealed base's nested-type crossing reaches every arm, not just the payload one.
 *
 * Mylo, asleep, purring at nobody.
 */
fun sleepingPurr(): Purr = Purr.Off

/**
 * A payload arm at a top-level return, for the same reason [sleepingPurr] exists: ADR-009 gives a
 * sealed arm an `internal On(IntPtr handle)` constructor and no public one, so a C# consumer
 * reaches an arm through a factory and a type test, never through `new`.
 *
 * Worth stating because the wrong spelling *compiles*: `new Purr.On(9)` binds to that internal
 * handle constructor (in .NET 7+ `IntPtr` is `nint`, which takes an `int` implicitly) and hands
 * the bridge 9 as a stable-ref address, which is an access violation on the first call through it.
 *
 * Oreo, purring at a level.
 */
fun purringPurr(level: Int): Purr = Purr.On(level)

/** The [Beam] half of [purringPurr]: an eligible sealed interface's arm is an arm too. */
fun litBeam(lumens: Int): Beam = Beam.Lit(lumens)
