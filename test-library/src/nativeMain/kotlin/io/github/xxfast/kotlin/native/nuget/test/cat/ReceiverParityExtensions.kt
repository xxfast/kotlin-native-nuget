package io.github.xxfast.kotlin.native.nuget.test.cat

import io.github.xxfast.kotlin.native.nuget.test.clinic.ChartRef
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid
import test.menagerie.IFeedable

/**
 * ROADMAP Phase 4 (ADR-132 parity): the receiver shapes an extension **function** already binds
 * over, declared here at the extension **property** position, where they are still a named
 * `SKIPPED_UNSUPPORTED_PROPERTY`.
 *
 * Every declaration below crosses a *different* receiver mechanism once, because a fixture trimmed
 * to the easiest receiver would pick the one that needs no work at the seam this item is about:
 *
 *  - [emoji] is the **enum ordinal** receiver (INT32 wire, `Mood.entries[receiver]` on the Kotlin
 *    side). [Mood] is deliberately an enum that has its own property (`description`), because that
 *    is what makes the generator emit a non-`partial` `MoodExtensions` class for the enum's own
 *    property extensions, which any extension merged into a class of the same name collides with.
 *  - [rallyCry] is the same collision on the **shipped extension-function route**, which has no
 *    fixture over an enum at all today. It is not part of the gated set: it is the control that
 *    says whether the enum defect is this item's or older than it.
 *  - [shortForm] and [nickname] are the **converting text receiver** (`Uuid` <-> `Guid`, hex-dash
 *    string on the wire), and [nickname] is a `var`, so the setter export carries the receiver too.
 *  - [epochDay] is the **converting 64-bit receiver** whose C# argument must be `UtcTicks`, not
 *    `Ticks`: the test hands it a non-UTC `DateTimeOffset` whose local day differs from its UTC
 *    day, so a wall-clock read is off by one whole day rather than invisible.
 *  - [wholeHours] is the **non-converting 64-bit receiver** (`TimeSpan.Ticks`, one tick domain).
 *  - [orPlaceholder], [isMissing], [display] and [patientName] are the **nullable receivers that
 *    ride the null in-band**: a null string reference for the first three, `IntPtr.Zero` for the
 *    handle-underlying value class. They are the shapes whose DllImport receiver parameter has to
 *    be spelled `string?` rather than `string`.
 *  - [napQuota] is the folded-in `var` of **nullable-primitive type over an interface receiver**:
 *    the setter takes the has-value fan-out (`NullableDispatch`) path, which is the one arm that
 *    never learned about the receiver's own handle local.
 *  - [longestName] and [feedingNote] are the two receivers that **mint a handle** for the crossing
 *    (a Kotlin list StableRef, and a GCHandle over the C#-side object), so both get a leak row.
 *
 * Refused on purpose, and deliberately NOT declared here: `Int?`, `Mood?`, `Instant?`, `Duration?`
 * and a nullable value class over a primitive or enum underlying. Each fans out to an adjacent
 * `HasValue` slot, the receiver slot is exactly one, and admitting them would silently lose null.
 *
 * Oreo files his paperwork; Mylo naps through his.
 */

// ---- enum receiver -------------------------------------------------------------------------

/** The enum receiver: Oreo's face, as the cat himself would render it. */
val Mood.emoji: String
  get() = when (this) {
    Mood.HAPPY -> "=^.^="
    Mood.SLEEPY -> "(-.-)zzZ"
    Mood.GRUMPY -> ">:("
  }

/**
 * The extension-FUNCTION control over the same enum. Already admitted by the function route, so it
 * is what tells apart "this item broke the enum class header" from "the enum class header has been
 * broken for every extension over an enum since ADR-132 shipped, and nothing declared one".
 */
fun Mood.rallyCry(): String = "${name.lowercase()} cats of the world, unite"

// ---- Uuid receivers ------------------------------------------------------------------------

/** The converting text receiver: the first block of Oreo's microchip id, for the collar tag. */
val Uuid.shortForm: String get() = toString().substringBefore('-')

private val chipNicknames: MutableMap<Uuid, String> = mutableMapOf()

/**
 * The `var` over a converting receiver: the setter export carries the receiver slot in front of
 * the value slot. Backed by a package-level map, the same way `var ChartId.symptomTags` is.
 */
var Uuid.nickname: String
  get() = chipNicknames[this] ?: "unnamed chip"
  set(value) {
    chipNicknames[this] = value
  }

/** The nullable twin, null in-band on the string wire: no chip, no cat on file. */
val Uuid?.isMissing: Boolean get() = this == null

// ---- Instant / Duration receivers ----------------------------------------------------------

/**
 * Days since the epoch. The C# argument must be `UtcTicks`: a consumer in Melbourne holding a
 * `DateTimeOffset` at `+10:00` on the 2nd of January 1970 is still on the 1st in UTC, so a
 * wall-clock read answers 1 where this answers 0.
 */
val Instant.epochDay: Long get() = epochSeconds.floorDiv(86_400L)

/** The non-converting 64-bit receiver: how many whole hours of nap that was. */
val Duration.wholeHours: Long get() = inWholeHours

// ---- nullable-by-reference receivers -------------------------------------------------------

/** Nullable String receiver: the cat flap logs a name, or nobody came through. */
val String?.orPlaceholder: String get() = this ?: "(no cat)"

/** Nullable value class over a String underlying: the stray at the back door has no id. */
val CatId?.display: String get() = this?.id ?: "anonymous"

/**
 * Nullable value class over an OBJECT HANDLE underlying: the same null-pointer ride one underlying
 * over, where the receiver is reconstructed from a StableRef rather than from text.
 */
val ChartRef?.patientName: String get() = this?.patient?.name ?: "(unfiled)"

// ---- interface receiver, nullable-primitive value ------------------------------------------

private val napQuotas: MutableMap<String, Int?> = mutableMapOf()

/**
 * The folded-in case: a `var` of nullable-primitive type over an INTERFACE receiver. The getter is
 * the shape that already ships; the setter is the has-value fan-out arm, which renders the
 * receiver's handle local nowhere and then reads it in the call. Keyed by name rather than by the
 * receiver object, because a C#-implemented `Pet` arrives as a fresh bridge object per crossing.
 */
var Pet.napQuota: Int?
  get() = napQuotas[name]
  set(value) {
    napQuotas[name] = value
  }

// ---- handle-minting receivers --------------------------------------------------------------

/**
 * A COLLECTION receiver: the C# side builds a Kotlin list StableRef for the crossing and has to
 * dispose it afterwards, so this shape owns a leak row.
 */
val List<String>.longestName: String get() = maxByOrNull { it.length } ?: "(empty basket)"

/**
 * A BOUND-INTERFACE receiver (ADR-088: a C# interface from the TestDependency package, reached
 * from Kotlin). The body dispatches BACK into two of the receiver's own members rather than
 * echoing it, so a C#-implemented goat can only produce this string if the reverse slots really
 * fired. The receiver crosses as a fresh GCHandle the Kotlin side takes ownership of.
 */
val IFeedable.feedingNote: String get() = "${describe()} needs ${legs} bowls"
