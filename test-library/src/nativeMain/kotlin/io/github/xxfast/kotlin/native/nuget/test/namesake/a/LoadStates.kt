package io.github.xxfast.kotlin.native.nuget.test.namesake.a

/**
 * Namesake fixture, half A: the sealed shape (backlog shape 3). Two `LoadState` sealed classes in
 * two packages collide on `loadstate_get_type` and every other `loadstate_*` export, which is the
 * shape ADR-107's fixture worked around by renaming to `Issue56LoadState`.
 *
 * File name is deliberately `LoadStates.kt`, not `LoadState.kt`, so the ADR-007 file class is
 * `LoadStates` and the ADR-110 `Kt` rename is not also in play in this cell.
 *
 * Oreo's food bowl: either full or empty, never ambiguous.
 */
sealed class LoadState {
  /** The loaded arm, carrying the name it loaded. */
  class Ready(val name: String) : LoadState()

  /** The failed arm, carrying a distinguishable cause. */
  class Failed(val cause: String) : LoadState()
}

/** Returns [LoadState.Ready] for Oreo, who is always on time for dinner, and [LoadState.Failed] otherwise. */
fun loadFor(name: String): LoadState =
  if (name == "Oreo") LoadState.Ready("a:$name") else LoadState.Failed("a: $name is not in house A")
