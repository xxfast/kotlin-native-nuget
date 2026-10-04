package io.github.xxfast.kotlin.native.nuget.test.keywordmembers

import io.github.xxfast.kotlin.native.nuget.annotations.CSharpName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf

/**
 * ADR-179 rules 2 and 5: a C# keyword passed to `@CSharpName` renders `@`-escaped on every route
 * that renders a member, while the private externs keep the Kotlin-derived stem. Tier 1 only reads
 * the generated text; this fixture makes the consumer's compiler read it. One member per route,
 * one keyword per member (two members sharing a name on one type would be a CS0102 collision):
 *
 * - [updates]: the legacy class `Flow` property. Any `@CSharpName` here once declared its collect
 *   extern under the declared name and called it under the Kotlin one (CS0103).
 * - [brightness]: the legacy `MutableStateFlow` property, with its `_value` and `_set_value` pair.
 * - [flicker] / [glow]: the legacy `Flow` and `StateFlow` methods, which ignored the annotation.
 * - [addSpark]: the ADR-037 stored-callback add, which ignored the annotation. The remove half
 *   names no C# member, so it carries none.
 * - [addKeeper]: the ADR-039 interface-bridge add, whose thunk calls [LanternKeeper.onLit] by its
 *   declared name.
 * - [label]: an ordinary planned property.
 *
 * Oreo lights the lantern; Mylo watches it flicker.
 */
class Lantern(
  @CSharpName("checked")
  var label: String,
) {
  @CSharpName("event")
  val updates: Flow<Int> = flowOf(1, 2, 3)

  @CSharpName("object")
  val brightness: MutableStateFlow<Int> = MutableStateFlow(4)

  @CSharpName("lock")
  fun flicker(times: Int): Flow<Int> = (1..times).asFlow()

  @CSharpName("fixed")
  fun glow(level: Int): StateFlow<Int> = MutableStateFlow(level)

  private val sparks: MutableList<(Int) -> Unit> = mutableListOf()

  @CSharpName("namespace")
  fun addSpark(listener: (Int) -> Unit) {
    sparks.add(listener)
  }

  fun removeSpark(listener: (Int) -> Unit) {
    sparks.remove(listener)
  }

  private val keepers: MutableList<LanternKeeper> = mutableListOf()

  @CSharpName("operator")
  fun addKeeper(listener: LanternKeeper) {
    keepers.add(listener)
  }

  fun removeKeeper(listener: LanternKeeper) {
    keepers.remove(listener)
  }

  /** Fires every spark listener and every keeper with [level]. */
  fun light(level: Int) {
    sparks.forEach { it(level) }
    keepers.forEach { it.onLit(level) }
  }
}

/** The interface-bridge listener; its one member is keyword-named. */
interface LanternKeeper {
  @CSharpName("event")
  fun onLit(level: Int)
}
