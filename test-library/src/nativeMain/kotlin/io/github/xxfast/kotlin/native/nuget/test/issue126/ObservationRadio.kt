package io.github.xxfast.kotlin.native.nuget.test.issue126

import io.github.xxfast.kotlin.native.nuget.test.cat.Cat
import io.github.xxfast.kotlin.native.nuget.test.cat.Observation
import io.github.xxfast.kotlin.native.nuget.test.issue54.Label
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Fixture for [#126](https://github.com/xxfast/kotlin-native-nuget/issues/126): a handle-typed
 * parameter on the legacy `Flow`/`StateFlow` and `suspend` routes renders as raw `IntPtr`.
 *
 * ADR-114 classified *generic* parameters on these routes and left every non-generic one as
 * `Plain`, which means `mapParamType(simpleName)` hands back `IntPtr` for anything outside the
 * primitive/`String` table. So a sealed arm, a sealed base and an ordinary exported class all
 * arrive in C# as `Watch(IntPtr observation)`: uncallable, because the only source of that
 * `IntPtr` is an `internal` handle constructor. The overload collision (`CS0111`) is the symptom
 * that makes it fail the build; `IntPtr` is the defect, and a single non-overloaded member would
 * be just as broken.
 *
 * The Kotlin half is separately wrong: the `@CName` export declares the parameter with its real
 * Kotlin type, so Kotlin says `observation: Observation.Alive` while C# says `IntPtr`. The fix
 * puts `COpaquePointer` on the export and dereferences it into a local **before** `scope.launch`,
 * so the coroutine holds a strong Kotlin reference and a consumer disposing its wrapper mid-flow
 * cannot invalidate what the coroutine is still reading. Cell 8 is what observes that.
 *
 * The precedent is the ordinary ADR-062 plan route on the same types: it already spells the
 * parameter as the mapped C# type and passes `x._handle`. Only the legacy routes fall back.
 *
 * ## Cells
 *
 * | # | Shape | What it pins |
 * | - | ----- | ------------ |
 * | 1 | [ObservationRadio.watch] `(Observation.Alive)` | the issue's exact shape, `_collect` + `_value`, and the **nested** arm spelling `Observation.Alive` |
 * | 2 | [ObservationRadio.watch] `(Observation.Dead)` | the overload collision (`CS0111`) that motivated the issue |
 * | 3 | [ObservationRadio.watch] `(Label)` | the **sibling** arm spelling, namespace-level `Label`, not `FlatShape.Label` |
 * | 4 | [ObservationRadio.watch] `(Observation)` | the sealed **base**, `sealedAsHandle()` on the legacy route, discriminated Kotlin-side |
 * | 5 | [ObservationRadio.watch] `(Cat)` | an **ordinary class** handle, proving the fix is not sealed-specific |
 * | 6 | [ObservationRadio.log] | the `_async` builder, the second hand-written copy of the same mistake |
 * | 7 | [ObservationRadio.tally] | a `Marshalled` and a `Handle` parameter on one member: prelude ordering, and a native argument spelled per parameter |
 * | 8 | [ObservationRadio.watch] `(Observation.Alive)`, ticking | the ownership window: the flow keeps emitting after C# disposes the argument wrapper |
 *
 * Cell 7 is the "one type needing conversion, one not" pair: a handle parameter crosses as a
 * borrowed `_handle` with no conversion at the seam, right beside a collection parameter that is
 * built and disposed around the call. Neither alone says anything about the other.
 *
 * Every body echoes the argument it received, so a test reads *which* argument arrived rather than
 * only that the call compiled. A raw pointer that survived the crossing as a number cannot answer
 * any of them.
 *
 * The radio is on the windowsill. Oreo (black, white bib) is alive and reported as such; Mylo
 * (brown and creamy) is only ever observed as a rumour, so he arrives as whatever the box says.
 */
class ObservationRadio {
  /**
   * Drives cell 8. Private, so it is not part of the exported surface: it only exists to give
   * [watch] a `StateFlow` that keeps moving after the subscription is live, which is the only way
   * a consumer can observe the flow surviving the disposal of its own argument wrapper.
   */
  private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)

  /**
   * Cells 1 and 8: the issue's shape verbatim, with a **nested** sealed arm at the parameter
   * position. Reaches `_collect` and `_value`.
   *
   * The initial value is available synchronously, so `.Value` reads it. The two follow-up
   * emissions read [observation] again 20ms apart, *after* the export has returned, so a C#
   * consumer that disposes its wrapper between emissions keeps collecting them.
   */
  fun watch(observation: Observation.Alive): StateFlow<String> {
    val state = MutableStateFlow("alive:${observation.cat.name}")
    scope.launch {
      repeat(2) { tick ->
        delay(20)
        state.value = "alive:${observation.cat.name}:${tick + 1}"
      }
    }
    return state
  }

  /** Cell 2: the second arm of the same base, the overload that collapses onto cell 1 today. */
  fun watch(observation: Observation.Dead): StateFlow<String> =
    MutableStateFlow("dead:${observation.cause}")

  /**
   * Cell 3: a **sibling** sealed arm, declared beside its base rather than inside it, so C# must
   * spell it `global::TestLibrary.Issue54.Label` and not `FlatShape.Label`.
   */
  fun watch(flat: Label): StateFlow<String> = MutableStateFlow("label:${flat.text}")

  /**
   * Cell 4: the sealed **base** itself. The discriminator is irrelevant at a parameter position
   * (C# only ever writes `_handle`), so this must bind with the same treatment as an arm, and
   * Kotlin is the one that discriminates.
   */
  fun watch(observation: Observation): StateFlow<String> = MutableStateFlow(
    when (observation) {
      Observation.Superposition -> "base:superposition"
      is Observation.Alive -> "base:alive:${observation.cat.name}"
      is Observation.Dead -> "base:dead:${observation.cause}"
    },
  )

  /** Cell 5: an ordinary exported class, which is the same `ObjectHandle` with nothing sealed. */
  fun watch(cat: Cat): StateFlow<String> = MutableStateFlow("cat:${cat.name}")

  /**
   * Cell 6: the `_async` route. The delay is long enough for a consumer to dispose its argument
   * wrapper after the P/Invoke returned but before the body reads [observation], which is where
   * the eager dereference earns its place.
   */
  suspend fun log(observation: Observation.Alive): String {
    delay(50)
    return "logged:${observation.cat.name}"
  }

  /**
   * Cell 7: a collection parameter and a handle parameter on one member. The collection is built
   * and disposed around the call, the handle is borrowed, and the two contributions are separable
   * in the result: ten per kind, one per letter of the cat's name.
   */
  fun tally(kinds: List<String>, observation: Observation.Alive): StateFlow<Int> =
    MutableStateFlow(kinds.size * 10 + observation.cat.name.length)
}
