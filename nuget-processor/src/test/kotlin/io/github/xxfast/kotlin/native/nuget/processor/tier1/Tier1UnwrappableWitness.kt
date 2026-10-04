package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.BridgeType
import io.github.xxfast.kotlin.native.nuget.processor.forward.CollectionKind
import io.github.xxfast.kotlin.native.nuget.processor.forward.diagnosticTypeName
import io.github.xxfast.kotlin.native.nuget.processor.forward.isBridgeableComponent
import io.github.xxfast.kotlin.native.nuget.processor.forward.isWrappableComponent

/**
 * One Kotlin collection component spelling, the [BridgeType] the classifier gives it, and the
 * import its fixture needs.
 *
 * [readable] records whether a collection property over it has a C# getter
 * (`ForwardPropertyPlanner.isReadableComponent`, private). It is hand-kept, but never trusted
 * silently: every setter cell that selects on it also asserts the getter export is generated.
 */
internal class Tier1UnwrappableCandidate(
  val kotlin: String,
  val type: BridgeType,
  val readable: Boolean,
  private val import: String? = null,
) {
  /** The `import` line a fixture needs for [kotlin], or an empty line. */
  val importLine: String get() = import?.let { "import $it" } ?: ""

  /** The component's name as every diagnostic spells it ("element type X", "value type X"). */
  val diagnosticName: String get() = type.diagnosticTypeName()
}

/**
 * The cells that pin "a collection component that can be read but cannot be written" (a property
 * keeps its getter and loses its setter, an input skips as `SKIPPED_UNSUPPORTED_INPUT`, and the
 * diagnostic names the component) used to hard-code whichever type was unwrappable that week.
 * They moved once per ADR: `Mood` (ADR-097), `Char`/`Short` (ADR-098), a plain nested collection
 * (ADR-099), then a nullable nested collection. The mechanism never moved: it is one predicate,
 * [isWrappableComponent]. So the cells ask this witness for a component instead, and it selects
 * the first [candidates] entry that still fails the predicate. When an ADR makes a candidate
 * wrappable, the cells move to the next one with no edit, and that ADR's own test pins the newly
 * bound shape.
 *
 * When no candidate is left the witness fails loudly rather than letting the cells pass vacuously:
 * the category has emptied, and the cells (and the setter-drop / COLLECTION-skip arms they guard)
 * need a human decision, either deletion as dead code or a deliberate permanent refusal.
 *
 * A cell that only needs "some member that skips" should not use this at all: a nullable map key
 * (`Map<String?, Int>`, ADR-083) is refused by rule, not by gap, and never moves.
 */
internal object Tier1UnwrappableWitness {

  /** Ordered: the first entry still failing [isWrappableComponent] wins. */
  private val candidates: List<Tier1UnwrappableCandidate> = listOf(
    // ADR-099 deliberately refuses a nullable nested collection: its write projection has no null
    // arm. Readable (the getter hands out the inner handle or null).
    Tier1UnwrappableCandidate(
      kotlin = "List<String>?",
      type = BridgeType.Nullable(
        BridgeType.Collection(CollectionKind.LIST, element = BridgeType.String),
      ),
      readable = true,
    ),
    // ADR-076 / ADR-103: readable as a property component, but neither is a bridgeable component
    // at an input position, so they only ever witness the setter cells.
    Tier1UnwrappableCandidate(
      kotlin = "Instant",
      type = BridgeType.Instant,
      readable = true,
      import = "kotlin.time.Instant",
    ),
    Tier1UnwrappableCandidate(
      kotlin = "Duration",
      type = BridgeType.Duration,
      readable = true,
      import = "kotlin.time.Duration",
    ),
    // Bridgeable as a component but not readable at a property position, so it only ever
    // witnesses the input cells.
    Tier1UnwrappableCandidate(kotlin = "Unit", type = BridgeType.Unit, readable = false),
  )

  /**
   * A component a collection PROPERTY can be read over but not written: the getter binds, the
   * setter is dropped with a `SKIPPED_UNSUPPORTED_INPUT` naming the component.
   */
  val setterComponent: Tier1UnwrappableCandidate
    get() = select("a readable collection property component (setter-drop cells)") {
      readable && !type.isWrappableComponent()
    }

  /**
   * [setterComponent], further restricted to a legal map KEY. A nullable key is refused at the
   * READ position too (ADR-083 amendment), which would drop the getter the setter cells assert.
   */
  val setterMapKey: Tier1UnwrappableCandidate
    get() = select("a readable, non-null map key (the both-sides setter-drop cell)") {
      readable && type !is BridgeType.Nullable && !type.isWrappableComponent()
    }

  /**
   * A component a collection INPUT accepts as bridgeable but cannot write, so the callable skips
   * on `COLLECTION` with "the element type X cannot be written". A non-bridgeable component would
   * skip earlier under its own reason and wording, which is not this mechanism.
   */
  val inputComponent: Tier1UnwrappableCandidate
    get() = select("a bridgeable collection input component (COLLECTION-skip cells)") {
      type.isBridgeableComponent() && !type.isWrappableComponent()
    }

  private fun select(
    role: String,
    admits: Tier1UnwrappableCandidate.() -> Boolean,
  ): Tier1UnwrappableCandidate = candidates.firstOrNull { candidate -> candidate.admits() }
    ?: error(
      "Tier1UnwrappableWitness: no candidate is left for $role; none of " +
        "${candidates.map { candidate -> candidate.kotlin }} still fails isWrappableComponent() " +
        "there. The unwrappable-component category has emptied: decide whether the cells using " +
        "this witness (and the planner arm they guard) are now dead code to delete, or whether " +
        "a deliberate permanent refusal should be added to the candidate list.",
    )
}
