package io.github.xxfast.kotlin.native.nuget.test.cat

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow

/**
 * Issue #109 / ADR-114: a collection-typed parameter on a `Flow`/`StateFlow`-returning or
 * `suspend` member. Both legacy routes spell the parameter by pasting the declaration's own type
 * name through `ClassName.bestGuess`, which drops the type arguments, so the generated
 * `CNameExports.kt` does not compile:
 *
 * ```
 * e: CNameExports.kt:97:10 One type argument expected for 'interface List<out E> : Collection<E>'.
 * e: CNameExports.kt:342:8 One type argument expected for 'interface Set<out E> : Collection<E>'.
 * ```
 *
 * `packNuget` dies at that compile, so the whole package fails to build, not just the member.
 *
 * Three routes, because they are three separate copies of the same mistake and have drifted
 * before:
 *  - [served] / [rations] reach `_collect` **and** `_value` (StateFlow return)
 *  - [servings] / [feeding] reach `_collect` only (plain Flow return)
 *  - [forget] / [tally] / [audit] reach `_async` (suspend class method)
 *
 * Component variety, because the wire container boxes each element through its own projection:
 * [served] carries `List<String>` (needs conversion at the seam), [rations] carries `List<Int>`
 * (does not), and [feeding] carries `List<Toy>` (an exported class, so the component crosses as a
 * handle). A fixture with only one of the three would go green while proving nothing about the
 * other two.
 *
 * [paired] is the refusal arm ADR-114 keeps: a generic parameter that is *not* a supported
 * collection must skip named (`SKIPPED_UNSUPPORTED_INPUT`) rather than emit `entry: Pair`, which
 * is the same build break. [servedAll] is the control: a flow member with no collection parameter
 * must be untouched by any of it.
 *
 * Oreo (black with the white bib) and Mylo (brown and creamy) audit the treat board every night.
 * It has never once balanced.
 */
class TreatBoard {
  private val _servedAll: MutableStateFlow<String> = MutableStateFlow("biscuit")

  /** `_collect` + `_value`, `List<String>`: the component needs conversion at the seam. */
  fun served(kinds: List<String>): StateFlow<String> =
    MutableStateFlow(kinds.joinToString(", ") { "$it x2" })

  /** `_collect` + `_value`, `List<Int>`: the component needs no conversion at the seam. */
  fun rations(portions: List<Int>): StateFlow<Int> = MutableStateFlow(portions.sum())

  /** `_collect` only: a plain `Flow` return with a `List<String>` parameter. */
  fun servings(kinds: List<String>): Flow<String> = kinds.asFlow()

  /** `_collect` with an exported-class element, so the component crosses as a handle. */
  fun feeding(toys: List<Toy>): Flow<String> = toys.map { "${it.name} (${it.color})" }.asFlow()

  /** `_async`: issue #109's second shape verbatim, a `Set` parameter on a suspend method. */
  suspend fun forget(ids: Set<String>): Int {
    delay(1)
    return ids.size
  }

  /** `_async` with a `List<Int>`, so the suspend route crosses both collection kinds. */
  suspend fun tally(portions: List<Int>): Int {
    delay(1)
    return portions.sum()
  }

  /**
   * `_async` that throws *after* C# has built the wire container: the ownership model's real
   * risk is a leaked or double-freed handle on the throwing path, and it is invisible from C#
   * (there is no live-handle export). Hammering this is the behavioural net, exactly as
   * `CollectionParameterCleanupTests` is for the synchronous route.
   */
  suspend fun audit(entries: List<String>): Int {
    delay(1)
    error("audit failed: ${entries.size} entries do not balance")
  }

  /** The `_collect` twin of [audit]: the flow route's throwing path, same reason. */
  fun review(entries: List<String>): Flow<String> = flow {
    error("review failed: ${entries.size} entries do not balance")
  }

  /**
   * Refusal arm: a generic parameter that is not a supported collection. Must be absent from C#
   * with a `SKIPPED_UNSUPPORTED_INPUT` naming it, never emitted as `entry: Pair`.
   */
  fun paired(entry: Pair<String, Int>): Flow<String> = listOf(entry.first).asFlow()

  /** Control: no collection parameter, so nothing here may change. */
  fun servedAll(): StateFlow<String> = _servedAll
}
