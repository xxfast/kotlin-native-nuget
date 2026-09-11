package io.github.xxfast.kotlin.native.nuget.test.dispenser

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * ADR-071 · a `MutableStateFlow<T>` returned from a function whose body is **not** field-backed.
 *
 * Every shipped `MutableStateFlow`-returning function hands back a stable instance
 * (`CatMoodTracker.treatJar()` returns the `treatCount` property, `CatRadio.volume(...)` and
 * `KeywordRoutes.state(...)` memoise via `getOrPut`), so nothing exercised the case the generated
 * wrapper actually has to survive: a body that builds a **fresh flow on every call**. The C#
 * wrapper re-invokes the function behind each `.Value` read and each `.Value` write, so a write
 * lands in one throwaway flow and the following read builds another. Kotlin's own semantics are
 * that the caller keeps the object the call returned, and that is what this fixture pins.
 *
 * Its own package, so the fresh-per-call route does not share a first-registration-wins name pool
 * with the memoised fixtures it is contrasted against.
 *
 * Oreo has learned that the snack dispenser refills itself if you stare at it long enough. He is
 * wrong, but the bridge should not be the thing that proves him right.
 */
class CatSnackDispenser {
  /** The most recently handed-out flow, so Kotlin can be asked what the C# write actually did. */
  private var latest: MutableStateFlow<Int>? = null

  /**
   * A **fresh** flow per call, deliberately not memoised: the C# wrapper must hold on to the one
   * flow this call returned rather than calling back in for a new one on every access.
   */
  fun level(): MutableStateFlow<Int> = MutableStateFlow(3).also { latest = it }

  /**
   * Kotlin-side read-back of the flow [level] most recently handed out, proving a C# write landed
   * in Kotlin rather than in a C# cache. `-1` when nothing has been handed out yet.
   */
  fun lastLevel(): Int = latest?.value ?: -1
}
