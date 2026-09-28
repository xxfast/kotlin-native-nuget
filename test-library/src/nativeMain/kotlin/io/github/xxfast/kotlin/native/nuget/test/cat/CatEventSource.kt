package io.github.xxfast.kotlin.native.nuget.test.cat

class CatEventSource(val name: String) {
  private val listeners: MutableList<CatEventListener> = mutableListOf()

  fun addListener(listener: CatEventListener) { listeners.add(listener) }

  fun removeListener(listener: CatEventListener) { listeners.remove(listener) }

  fun trigger() {
    val msg = "$name says meow!"
    listeners.forEach { it.onMeow(msg) }
    listeners.forEach { it.onPurr() }
    maybeListeners.forEach { it.onMeow(msg) }
    maybeListeners.forEach { it.onPurr() }
  }

  // Null-guard cells (stored-callback-pair-null-guard). Every C# callback entry point on this
  // class takes a delegate or listener that C# could pass as `null`; each must reject it with
  // `ArgumentNullException` at the call site. The Kotlin nullability is deliberately mixed: the
  // pair route never forwards the C# argument (the export builds its own non-null bridge), so a
  // nullable Kotlin spelling must not be read as "null means no listener" on the C# side.

  // ADR-039 interface-bridge pair on a NULLABLE Kotlin type. [trigger] walks this list too, so a
  // null that slipped through would surface there.
  private val maybeListeners: MutableList<CatEventListener> = mutableListOf()

  fun addMaybeListener(listener: CatEventListener?) {
    if (listener != null) maybeListeners.add(listener)
  }

  fun removeMaybeListener(listener: CatEventListener?) {
    if (listener != null) maybeListeners.remove(listener)
  }

  // ADR-037 stored-callback lambda pair on a NULLABLE Kotlin type; [pounce] is its emission. Oreo
  // pounces, Mylo counts.
  private val pounceListeners: MutableList<(Int) -> Unit> = mutableListOf()

  fun addPounceListener(listener: ((Int) -> Unit)?) {
    if (listener != null) pounceListeners.add(listener)
  }

  fun removePounceListener(listener: ((Int) -> Unit)?) {
    if (listener != null) pounceListeners.remove(listener)
  }

  /** Fires every pounce listener [times] times with `1..times`. */
  fun pounce(times: Int) = repeat(times) { n -> pounceListeners.forEach { it(n + 1) } }

  /**
   * Per-call lambda, NON-null Kotlin type (ADR-062 plan route). The parameter is not called
   * `listener` on purpose, so a `ParamName` pin proves the guard names the real C# parameter.
   */
  fun countPounces(pouncer: (Int) -> Unit): Int {
    repeat(3) { pouncer(it + 1) }
    return 3
  }

  /** Per-call lambda, NULLABLE Kotlin type (legacy per-call route, the `onMaybeTick` shape). */
  fun onMaybePounce(pouncer: ((Int) -> Unit)?) = repeat(3) { pouncer?.invoke(it + 1) }
}
