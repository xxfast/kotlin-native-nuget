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
 * - [Headcount.maybe], [Headcount.maybeTags], [Headcount.maybeIds], [Headcount.maybeAges],
 *   [Headcount.maybeTempers] and top-level [nobody]: a **nullable** collection return (ADR-119
 *   amendment), all three kinds, an enum element that needs a projection, and the top-level owner.
 *   Binds as `Task<IReadOnlyList<T>?>` (`IReadOnlySet`/`IReadOnlyDictionary`) and completes with
 *   `null` for a Kotlin `null`.
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

  /**
   * A nullable collection return that is always `null`: binds as `Task<IReadOnlyList<string>?>`
   * and completes with `null`, the null result pointer never reaching `NugetMarshal.ReadList`.
   * Refused with `SKIPPED_UNSUPPORTED_RETURN` before ADR-119's 2026-09-29 amendment.
   */
  suspend fun maybe(): List<String>? = null

  /** Nullable `List<String>`, present or absent on demand: Oreo's roll call, or nobody home. */
  suspend fun maybeTags(present: Boolean): List<String>? {
    delay(1.milliseconds)
    return if (present) names else null
  }

  /** Nullable `Set<Int>`: the `ReadSet` twin of [maybeTags]. */
  suspend fun maybeIds(present: Boolean): Set<Int>? {
    delay(1.milliseconds)
    return if (present) names.indices.toSet() else null
  }

  /** Nullable `Map<String, Int>`: the `ReadMap` twin of [maybeTags]. */
  suspend fun maybeAges(present: Boolean): Map<String, Int>? {
    delay(1.milliseconds)
    return if (present) names.associateWith { it.length } else null
  }

  /**
   * Nullable `List<Temper>`: the element needs a conversion at the seam (enum leaves as its
   * ordinal, ADR-097), so the per-element projection runs on the smart-cast non-null `result`
   * inside the Kotlin export's null test. Mylo is Hungry; so, it turns out, is everyone.
   */
  suspend fun maybeTempers(present: Boolean): List<Temper>? {
    delay(1.milliseconds)
    return if (present) names.map { Temper.entries[it.length % Temper.entries.size] } else null
  }
}

/**
 * The top-level owner of a nullable collection return: `AssignmentSample.NobodyAsync(bool)` is
 * `Task<IReadOnlyList<string>?>`. Absent, nobody is home; present, Oreo and Mylo both are.
 */
suspend fun nobody(present: Boolean): List<String>? {
  delay(1.milliseconds)
  return if (present) listOf("Oreo", "Mylo") else null
}

/**
 * The top-level suspend route. Takes a `String` rather than a [Headcount]: an object-typed
 * parameter on this route still renders `IntPtr` (ROADMAP Phase 6), a separate gap.
 */
suspend fun everyone(prefix: String): List<String> {
  delay(1.milliseconds)
  return listOf("$prefix Oreo", "$prefix Mylo")
}
