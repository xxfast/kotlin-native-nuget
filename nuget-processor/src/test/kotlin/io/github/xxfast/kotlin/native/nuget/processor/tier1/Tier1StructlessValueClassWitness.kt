package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.BridgeType
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardValueClassUnderlying
import io.github.xxfast.kotlin.native.nuget.processor.forward.valueClassUnderlyingRole

/**
 * One value-class underlying spelling: the [BridgeType] the classifier gives it, a [sample] value
 * of it and the qualified name the value class's refusal quotes. [kotlin] and [sample] are fully
 * qualified, so a fixture needs no import (one it already has would conflict).
 */
internal class Tier1StructlessUnderlying(
  val kotlin: String,
  val type: BridgeType,
  val sample: String,
  val qualifiedName: String,
)

/**
 * The cells that pin "a value class with no C# record struct" (its own refusal, and a named skip
 * at every position typed with it) used to hard-code `Char` as the underlying. ADR-234 made `Char`
 * a value underlying and every one of those cells moved at once. The mechanism never moved: it is
 * one rule, [valueClassUnderlyingRole]. So the cells ask this witness for an underlying instead,
 * and it selects the first [candidates] entry that rule still refuses. When an ADR makes one a
 * value-class underlying, the cells move to the next one with no edit, and that ADR's own test
 * pins the newly bound shape.
 *
 * When no candidate is left the witness fails loudly rather than letting the cells pass vacuously.
 */
internal object Tier1StructlessValueClassWitness {

  /**
   * Ordered: the first entry [valueClassUnderlyingRole] still refuses wins. `Instant` and
   * `Duration` led this list until ADR-235 made both WIRE underlyings.
   */
  private val candidates: List<Tier1StructlessUnderlying> = listOf(
    Tier1StructlessUnderlying(
      kotlin = "() -> Unit",
      type = BridgeType.Callback(emptyList(), BridgeType.Unit),
      sample = "{}",
      qualifiedName = "kotlin.Function0",
    ),
    Tier1StructlessUnderlying(
      kotlin = "kotlin.Unit",
      type = BridgeType.Unit,
      sample = "kotlin.Unit",
      qualifiedName = "kotlin.Unit",
    ),
  )

  /** An underlying no value-class wire carries, so the value class has no record struct. */
  val underlying: Tier1StructlessUnderlying
    get() = candidates.firstOrNull { candidate ->
      candidate.type.valueClassUnderlyingRole() == ForwardValueClassUnderlying.REFUSED
    } ?: error(
      "Tier1StructlessValueClassWitness: every candidate in " +
        "${candidates.map { candidate -> candidate.kotlin }} is a value-class underlying now. " +
        "Decide whether the struct-less value class cells are dead code to delete, or add a " +
        "deliberate permanent refusal to the candidate list.",
    )
}
