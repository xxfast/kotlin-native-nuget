package io.github.xxfast.kotlin.native.nuget.test.issue127

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Fixture for issue [#127](https://github.com/xxfast/kotlin-native-nuget/issues/127) / ADR-123: a
 * `Flow` or `StateFlow` whose **element** is a Kotlin collection renders that element as
 * `global::TestLibrary.Kotlin.Collections.Set`, a namespace that does not exist, with the type
 * argument dropped. `packNuget` stays green and the consumer's compile fails with `CS0234`.
 *
 * The gap is visible on one declaration: in [NodeHub.visible] the `List<Kind>` **parameter** is
 * already right (`IReadOnlyList<global::TestLibrary.Issue127.Kind>`, ADR-114) while the
 * `StateFlow<List<NodeId>>` return spells its element as a user type run through the
 * `rootPackage` mapping. The property route and the ADR-119 suspend route both settled on
 * `IReadOnlyList<T>` / `IReadOnlySet<T>` / `IReadOnlyDictionary<K, V>` for the same Kotlin types,
 * so this route has to agree with them in the signature *and* at runtime, through the same
 * `nuget_list_*` / `nuget_set_*` / `nuget_map_*` helpers.
 *
 * ## Cells
 *
 * | # | Shape | What it pins |
 * | - | ----- | ------------ |
 * | 1 | [NodeHub.items] `StateFlow<Set<NodeId>>` | the issue's exact property shape, and a component that **projects** at the seam (value class over `Int`) |
 * | 2 | [NodeHub.plain] `StateFlow<List<String>>` | the same route with a component needing no projection, so cell 1 cannot pass by treating every element alike |
 * | 3 | [NodeHub.counts] `StateFlow<Map<String, Int>>` | the third collection kind, `IReadOnlyDictionary<K, V>`, two type arguments on one element |
 * | 4 | [NodeHub.ticks] `Flow<List<Kind>>` | a plain `Flow`, not a `StateFlow`, with a **handle** component, over several emissions |
 * | 5 | [NodeHub.visible] | the method route: a collection parameter (already right) and a collection element (wrong) on one signature |
 * | 6 | [NodeHub.paired] `StateFlow<Pair<String, Int>>` | refusal. Absent from C#, named `SKIPPED_UNSUPPORTED_PROPERTY`, never rendered as a bare `Pair` |
 * | 7 | [NodeHub.maybe] `StateFlow<List<String>?>` | refusal by the nullable-collection rule ADR-114 and ADR-119 already apply on their routes |
 *
 * Cells 1 and 4 together are the pair that matters at runtime: a projecting component read per
 * element, and a handle component read per element over more than one emission. A fix that only
 * corrects the spelling leaves both throwing.
 *
 * Nothing is added to the `cat`/`catcam` flow fixtures: those pin the ADR-065/067/071 element
 * shapes and a new element kind there would churn their assertions.
 *
 * The hub watches two nodes. Node 1 is Oreo (black, white bib), node 2 is Mylo (brown and
 * creamy), and node 3 is the empty spot on the windowsill they both want.
 */

/** A component that **projects** at the seam: crosses as its underlying `Int`, re-wrapped per element. */
value class NodeId(val value: Int)

/** A component that does **not** project: an ordinary handle, one box per element. */
data class Kind(val name: String)

class NodeHub {

  /** Cell 1: the issue's shape. `IReadOnlySet<NodeId>`, read through `nuget_set_*`. */
  val items: StateFlow<Set<NodeId>> = MutableStateFlow(setOf(NodeId(1), NodeId(2), NodeId(3)))

  /** Cell 2: the same route with a scalar component, so no per-element projection is involved. */
  val plain: StateFlow<List<String>> = MutableStateFlow(listOf("Oreo", "Mylo"))

  /** Cell 3: the map kind, `IReadOnlyDictionary<string, int>`. */
  val counts: StateFlow<Map<String, Int>> = MutableStateFlow(mapOf("Oreo" to 4, "Mylo" to 4))

  /**
   * Cell 4: a plain `Flow`, three emissions of different lengths, so a test reads *each* emission
   * rather than only that one arrived. The component is a handle, one box per element per
   * emission, which is what the leak row measures.
   */
  val ticks: Flow<List<Kind>> = flowOf(
    listOf(Kind("nap")),
    listOf(Kind("zoomies"), Kind("snack")),
    listOf(Kind("nap"), Kind("loaf"), Kind("window")),
  )

  /**
   * Cell 5: the method route. The parameter is already spelled right on this route, the element
   * is not, so both halves of one signature are crossed by a single call. Each `NodeId` is
   * derived from its kind's name length, so the result cannot be mistaken for an echo.
   */
  fun visible(kinds: List<Kind>): StateFlow<List<NodeId>> =
    MutableStateFlow(kinds.map { NodeId(it.name.length) })

  /** Cell 6: refused. A `Pair` element has no wire shape here, so the member must be absent. */
  val paired: StateFlow<Pair<String, Int>> = MutableStateFlow("Oreo" to 9)

  /** Cell 7: refused. A nullable collection element, by the same rule ADR-114 uses on parameters. */
  val maybe: StateFlow<List<String>?> = MutableStateFlow(null)
}
