package io.github.xxfast.kotlin.native.nuget.test.cat

import kotlinx.coroutines.delay

/**
 * Issue #109 / ADR-114, the *top-level* `suspend` route. It is broken differently from the class
 * method in [TreatBoard]: it goes through `addParameters` / `toBridgeTypeName`, which preserves
 * type arguments (the BUG-005 fix), so it emits `ids: Set<String>` and the Kotlin compiles. It
 * still hands C# `ForgetAllTreatsAsync(IntPtr ids)`, a public parameter no caller can produce, so
 * it needs the same handle treatment as every other cell.
 *
 * A separate file because ADR-007 names the generated static class after the source file, and a
 * top-level function in `TreatBoard.kt` would collide with the `TreatBoard` class itself.
 *
 * Oreo has never forgotten a single treat. Mylo forgets them all.
 */
suspend fun forgetAllTreats(ids: Set<String>): Int {
  delay(1)
  return ids.size
}
