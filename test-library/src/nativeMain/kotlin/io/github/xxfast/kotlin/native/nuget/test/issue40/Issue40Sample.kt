package io.github.xxfast.kotlin.native.nuget.test.issue40

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Issue #40: a `StateFlow<Sealed>` / `Flow<Sealed>` whose element is a sealed BASE class.
 *
 * The generic read path (`KotlinStateFlow<T>.Value`, `KotlinFlowEnumerator<T>.onNext`) routes
 * every element through `NugetMarshal.Materialize<T>` with `T` = the abstract sealed base, and
 * the ADR-094 factory registry holds entries only for the sealed *subclasses*, never for the
 * base, so materialisation throws instead of reaching the base's generated
 * `LoadState.FromHandle(IntPtr)` discriminator.
 *
 * This fixture is deliberately minimal so it isolates that one seam:
 *  - non-null scalar payloads only (no nullable elements, no List members),
 *  - the sealed hierarchy lives entirely in this file and this namespace,
 *  - both surface shapes that hit the seam: a hot [state] `StateFlow<LoadState>` and a cold
 *    [history] `Flow<LoadState>`,
 *  - all three subclass kinds a real UI state uses: a `data object` (no payload), and two
 *    `data class`es carrying a value payload ([LoadState.Loading]) and a reference payload
 *    ([LoadState.Loaded]), so the discriminator has to pick between three arms rather than one.
 *
 * Mutation is driven exclusively by [Loader.advance] -- never a timer -- so the C# tests assert
 * deterministic transitions without racing a background emitter.
 */
sealed class LoadState {
  /** No payload -- the `data object` arm of the discriminator. */
  data object Idle : LoadState()

  /** Value payload -- proves the discriminator lands on the arm carrying an `Int`. */
  data class Loading(val progress: Int) : LoadState()

  /** Reference payload -- proves the discriminator lands on the arm carrying a `String`. */
  data class Loaded(val payload: String) : LoadState()
}

/**
 * Issue #40 fixture: exposes a sealed-base element through the two stream shapes that
 * currently cannot be materialised in C#.
 */
class Loader {
  private val _state: MutableStateFlow<LoadState> = MutableStateFlow(LoadState.Idle)

  /** `StateFlow<LoadState>` as a class property -- the `.Value` read path from issue #40. */
  val state: StateFlow<LoadState> = _state.asStateFlow()

  /** Deterministic transition: Idle -> Loading(50) -> Loaded("done") -> Idle. */
  fun advance() {
    _state.value = when (_state.value) {
      is LoadState.Idle -> LoadState.Loading(50)
      is LoadState.Loading -> LoadState.Loaded("done")
      is LoadState.Loaded -> LoadState.Idle
    }
  }

  /** `Flow<LoadState>` as a class property -- the cold `await foreach` path from issue #40. */
  val history: Flow<LoadState> = flowOf(
    LoadState.Idle,
    LoadState.Loading(50),
    LoadState.Loaded("done"),
  )
}
