package io.github.xxfast.kotlin.native.nuget.test.models

/**
 * ADR-066 closure-termination guard: a cyclic pair across the module boundary (`Whisker.purr:
 * Purr?`, `Purr.whisker: Whisker?`). The closure's visited set is keyed on qualified name, so
 * walking from [Whisker] into [Purr] and back must terminate on the second visit rather than
 * recursing forever. Object handles (not a data class), so the cycle is safe to construct: each
 * property crossing the bridge is a lazily-fetched handle, never an eagerly-unwrapped value.
 */
class Whisker(val label: String) {
  var purr: Purr? = null
}

class Purr(val sound: String) {
  var whisker: Whisker? = null
}

/** Oreo purrs, and the purr echoes back to Oreo -- a closed loop, one hop each way. */
fun catnip(): Whisker {
  val whisker = Whisker("Oreo")
  val purr = Purr("prrrrr")
  whisker.purr = purr
  purr.whisker = whisker
  return whisker
}
