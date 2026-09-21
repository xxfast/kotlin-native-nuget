package io.github.xxfast.kotlin.native.nuget.test.roster

import io.github.xxfast.kotlin.native.nuget.internal.NugetManagedException
import test.enums.CatMood
import test.roster.ILabelled
import test.roster.Roster

// ADR-155: a C# member returning or taking a BCL collection, consumed from Kotlin as an eagerly
// copied Kotlin collection over a single `[count][slots]` buffer.
//
//   C# IntegrationTests
//     -> (forward bridge)               RosterSample.*   (top-level funs, ADR-007)
//       -> Kotlin test-library          RosterSample.kt (this file)
//         -> (reverse bridge, ADR-155)  test.roster.{Roster, Tag, ILabelled}
//           -> real C# TestDependency   Test.Roster.{Roster, Tag, ILabelled}
//
// EXPECTED TO FAIL TODAY: every member named below is currently dropped by the reader with
// `skipped_unbound_generic_instantiation`, so `test.roster.Roster` is generated without any of
// them and this file does not compile. That failure is the point of this file at this stage.
//
// Organised by the SEAM each row crosses, because the conversion is per ELEMENT and per
// CONTAINER and a fixture built only from `IReadOnlyList<Int>` needs no conversion anywhere:
//
//   int element (no conversion) | string element (one CoTaskMem slot each) | nullable element |
//   nullable collection | map (interleaved) | set of a bound enum from another namespace |
//   handle elements from a MUTABLE declared type | collection property (get and set) |
//   collection constructor parameter | IEnumerable parameter | the overload pair that pins the
//   shim's cast to the declared type | a lazy sequence that throws mid-enumeration.

private const val NULL_NICKNAME = "<null>"

private const val NO_THROW = "no throw"

/** `managedType|message` for a caught reverse throw: ADR-104's channel, reused verbatim. */
private fun describe(e: NugetManagedException): String = "${e.managedType}|${e.message}"

/** `string` elements: one CoTaskMem slot each, read and freed by the Kotlin side. */
fun joinedNames(): String = Roster().use { it.names().joinToString(",") }

/** `int` elements: the control row, the one element kind whose slot needs no conversion. */
fun summedAges(): Int = Roster().use { it.ages().sum() }

/**
 * A NULLABLE element. Both halves in one collection, because a binding that lost the second
 * `NullableAttribute` byte binds `String` and then reads the null slot as something else.
 */
fun joinedNicknames(): String =
  Roster().use { roster -> roster.nicknames().joinToString(",") { it ?: NULL_NICKNAME } }

/** A NULLABLE COLLECTION: `IntPtr.Zero`, which is null, not an empty list. */
fun maybeNamesIsNull(): Boolean = Roster().use { it.maybeNames() == null }

/**
 * A MAP: interleaved key and value slots, a key that needs a string conversion and a `Double`
 * value that is BIT-CAST into its slot. Non-integral, so a slot that widened instead reads 9.0.
 */
fun scoreOf(name: String): Double = Roster().use { it.scores().getValue(name) }

/**
 * Bound-INTERFACE elements: the Kotlin value of each slot is the interface's own handle wrapper
 * dispatching through its slot table, never the concrete `Tag`.
 */
fun joinedLabels(): String =
  Roster().use { roster -> roster.labels().joinToString(",") { it.label } }

/**
 * A SET whose element is a bound enum declared in ANOTHER bound namespace and used nowhere else
 * on `Roster`. Sorted by name, because a `HashSet` has no order to promise.
 */
fun sortedMoods(): String =
  Roster().use { roster -> roster.moods().map(CatMood::name).sorted().joinToString(",") }

/**
 * HANDLE elements: each slot is a fresh strong `GCHandle` owned by the Kotlin wrapper built from
 * it, so every wrapper is closed here. `IList<Tag>` in C#, a read-only `List<Tag>` in Kotlin.
 */
fun firstTagLabel(): String = Roster().use { roster -> roster.tags().first().use { it.label } }

/** An `IEnumerable<String>` PARAMETER, built in the stub's `memScoped` and read by the thunk. */
fun enrollTwo(): Int = Roster().use { it.enroll(listOf("Oreo", "Mylo")) }

/**
 * A SET PARAMETER: the Kotlin side writes the slot buffer and the thunk rebuilds a `HashSet`.
 * NOT `rollCall`: KennelSample already exports that name, and every top-level fun shares one
 * forward export namespace (an ambiguity in the generated `CNameExports.kt`, not a compile error
 * here).
 */
fun rosterRoll(): String = Roster().use { it.roll(setOf("Oreo", "Mylo")) }

/**
 * A MAP PARAMETER with a bit-cast value: the interleaved `[count][k][v]...` buffer written from
 * Kotlin. Summed on the C# side, so keys and values swapped read as a wrong number.
 */
fun weighBoth(): Double = Roster().use { it.weigh(mapOf("Oreo" to 4.5, "Mylo" to 3.25)) }

/**
 * The collection CONSTRUCTOR parameter and the collection PROPERTY, get and set, in one row:
 * what the constructor received is read back through the getter, then replaced through the
 * setter and read again. A constructor handed an empty buffer is a wrong string here.
 */
fun rankingsRoundTrip(): String = Roster(listOf("Oreo", "Mylo")).use { roster ->
  val seeded: String = roster.rankings.joinToString(",")
  roster.rankings = listOf("Mylo")
  "$seeded|${roster.rankings.joinToString(",")}"
}

/**
 * The overload pair. Both C# overloads are applicable to the same argument list, so the thunk
 * that does not cast the `List<int>` it built to its DECLARED parameter type either fails to
 * compile (`CS0121`) or dispatches to the other overload silently. The two bodies answer
 * differently, which is what makes the silent case visible.
 */
fun pickedBoth(): String = Roster().use { roster ->
  roster.tags().first().use { tag ->
    "${roster.pick(listOf(1), tag)},${roster.pick(listOf(1), tag as ILabelled)}"
  }
}

/**
 * A LAZY sequence that throws after one element. User code runs inside the shim's `ToArray()`,
 * before any buffer or string slot exists, so this must arrive as an ordinary managed exception.
 */
fun brokenDescribed(): String = Roster().use { roster ->
  try {
    roster.broken().joinToString(",")
    NO_THROW
  } catch (e: NugetManagedException) {
    describe(e)
  }
}

/**
 * Leak driver for the ADR-121 collectability shape, mirrored for collection ELEMENTS: issue
 * [times] tags and close every wrapper. Returns the total label length so a run where nothing
 * was actually issued cannot pass as a clean one. The alive count is read separately, by
 * [aliveTags], AFTER this has returned and its wrappers are gone.
 */
fun issueTags(times: Int): Int = Roster().use { roster ->
  var length = 0
  repeat(times) { roster.tags().forEach { tag -> tag.use { length += it.label.length } } }
  length
}

/** How many issued tags C# can still reach. A leaked element slot is a strong root. */
fun aliveTags(): Int = Roster.aliveTags()
