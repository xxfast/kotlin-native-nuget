package io.github.xxfast.kotlin.native.nuget.test.issue122

import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

/**
 * Fixture for issue [#122](https://github.com/xxfast/kotlin-native-nuget/issues/122) / ADR-119: a
 * `suspend` member returning `List<T>` rendered the type argument away, as bare `List`, so the
 * generated `Interop.cs` failed the consumer's compile (`CS0305`) while `packNuget` stayed green.
 *
 * The precedent is on the same class: the **property** route already spells `List<Member>` as
 * `IReadOnlyList<global::TestLibrary.Issue122.Member>` and reads it through `nuget_list_*`. ADR-119
 * makes the suspend route's return agree with it, on both halves.
 *
 * ### Cells
 * - [Assignment.Existing.fetch]: the issue's shape verbatim, a sealed arm with both spellings of
 *   the same element type. [Assignment.Existing.members] is the property-route control on the same
 *   class, so the two can be compared element for element.
 * - [Headcount.tags] / [Headcount.ids] / [Headcount.ages]: the three kinds on an ordinary
 *   class, with components that need no conversion at the seam.
 * - [Headcount.tempers]: a bare-enum element, which leaves as its int ordinal (ADR-097) and is
 *   cast back per element, so the per-element projection is crossed too, not only the identity
 *   box.
 * - [Headcount.paired]: the refusal arm. A `Pair` return has no wire shape here, so the member
 *   must be absent and named `SKIPPED_UNSUPPORTED_RETURN`, never rendered as `Task<Pair>`.
 * - [Headcount.maybe]: a nullable collection return is refused by the same rule ADR-114 applies
 *   to a nullable collection parameter.
 * - [everyone]: the top-level suspend route, the third copy of the same composition.
 * - [AssignmentFactory.existing]: how the test reaches the arm, since a sealed arm has no public
 *   C# constructor.
 *
 * Oreo runs the headcount; Mylo is on it, and would like that reflected in the total.
 */
sealed class Assignment

data class Member(val id: Int, val name: String)

enum class Temper { SLEEPY, HUNGRY, PLAYFUL }

/** The issue's arm: `Members` via the property route, `FetchAsync` via the suspend route. */
data class Existing(val members: List<Member>) : Assignment() {
  suspend fun fetch(limit: Int, offset: Int): List<Member> {
    delay(1.milliseconds)
    return members.drop(offset).take(limit)
  }
}

/** Control arm: declares no functions and must keep generating exactly as today. */
data class Vacant(val reason: String) : Assignment()

/**
 * Concrete-arm access, the `JobFactory` precedent: a sealed arm has no public C# constructor, so
 * the test reaches [Existing] through an ordinary class method that takes the `List<Member>` on
 * the plan route.
 */
class AssignmentFactory {
  fun existing(members: List<Member>): Existing = Existing(members)
}

class Headcount(private val names: List<String>) {
  suspend fun tags(): List<String> {
    delay(1.milliseconds)
    return names
  }

  suspend fun ids(): Set<Int> {
    delay(1.milliseconds)
    return names.indices.toSet()
  }

  suspend fun ages(): Map<String, Int> {
    delay(1.milliseconds)
    return names.associateWith { it.length }
  }

  suspend fun tempers(): List<Temper> {
    delay(1.milliseconds)
    return names.map { Temper.entries[it.length % Temper.entries.size] }
  }

  /** Refused: absent from C#, named `SKIPPED_UNSUPPORTED_RETURN`. */
  suspend fun paired(): Pair<String, Int> = names.first() to names.size

  /** Refused: a nullable collection return, mirroring ADR-114's nullable-parameter rule. */
  suspend fun maybe(): List<String>? = null
}

/**
 * The top-level suspend route. Takes a `String` rather than a [Headcount]: an object-typed
 * parameter on this route still renders `IntPtr` (ROADMAP Phase 6), a separate gap.
 */
suspend fun everyone(prefix: String): List<String> {
  delay(1.milliseconds)
  return listOf("$prefix Oreo", "$prefix Mylo")
}
