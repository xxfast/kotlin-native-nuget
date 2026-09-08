package io.github.xxfast.kotlin.native.nuget.test.models

/**
 * Issue #110's second route, and the only one that is *not* fatal: a **nested** `object` subclass of
 * a sealed base, reached across the klib boundary.
 *
 * A module-local nested one never reproduces the bug. `rootObjects` filters `parentDeclaration ==
 * null`, and the ADR-066 reachability closure only ever *admits* a declaration whose
 * `containingFile == null`, so a module-local `Issue54Shape.Empty` cannot enter either object
 * bucket. A cross-module one can: the closure's nested-declaration refusal carves out
 * `isSealedSubclass()` on purpose (ADR-009 declares a sealed subclass nested under its base, which
 * is how `nestedCsName` spells it), and `reachabilityBucket()` then tests `classKind == OBJECT`
 * *before* `isSealedSubclass()`, so [Zoomies] lands in `ForwardReachabilityBucket.OBJECT` and gets a
 * bogus empty `public static class Zoomies { }` at namespace level alongside the real nested
 * `Nap.Zoomies` the sealed route emits.
 *
 * That does not collide, because the real one is nested inside `Nap`'s braces, so it compiles. It is
 * still a public C# type that is not an API and that no member ever references. This is why the
 * cell exists in `:test-models` rather than in `test-library`: the module boundary is the mechanism.
 *
 * [Deep] is the control arm. It is a nested `CLASS`, so it takes the `SEALED_SUBCLASS` bucket and
 * has always been declared exactly once. If a fix ever moves the object test in front of the wrong
 * predicate, this arm is what notices.
 *
 * Oreo sleeps like a rock, twelve hours at a stretch. Mylo does not sleep, he just stops moving at
 * high speed, and then starts again.
 */
sealed class Nap {

  /** Control arm: a nested sealed subclass of kind `CLASS`, correct today and must stay correct. */
  data class Deep(val minutes: Int) : Nap()

  /** The cell under test: a nested sealed subclass of kind `OBJECT`, one klib boundary away. */
  data object Zoomies : Nap()
}
