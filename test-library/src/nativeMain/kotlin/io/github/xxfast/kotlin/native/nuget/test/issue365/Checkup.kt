package io.github.xxfast.kotlin.native.nuget.test.issue365

import io.github.xxfast.kotlin.native.nuget.test.cat.Cat
import io.github.xxfast.kotlin.native.nuget.test.cat.Observation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration.Companion.milliseconds

/**
 * Fixture for [#365](https://github.com/xxfast/kotlin-native-nuget/issues/365): a **nullable**
 * class or sealed handle parameter on a legacy route is skipped whole as
 * `SKIPPED_UNSUPPORTED_INPUT`. `legacyParameterShape` lands `ObjectHandle` on `Handle` (ADR-122)
 * and a nullable scalar on `NullableScalar` (#299), but `Nullable(ObjectHandle)` falls to
 * `Refused`.
 *
 * Expected after the fix: C# spells the parameter `Cat?` / `Observation?`, `null` crosses as
 * `IntPtr.Zero` (one pointer slot, no `HasValue` slot), and Kotlin rebuilds it as `null`.
 *
 * Two handle kinds on every route, because they reach the refusal by different paths: [Cat] is a
 * plain `ObjectHandle`, and [Observation] is a sealed base that `sealedAsHandle()` rewrites into
 * `Nullable(ObjectHandle)`. A fixture carrying only one would go green on a fix that special-cased
 * the other.
 *
 * | Route | Plain (`Cat?`) | Sealed (`Observation?`) |
 * | ----- | -------------- | ----------------------- |
 * | suspend member | [Checkup.examine] | [Checkup.triage] |
 * | sealed-arm suspend member (ADR-118) | [Stay.Overnight.admit] | [Stay.Overnight.observe] |
 * | top-level suspend | [weigh] | [recheck] |
 * | `Flow` / `StateFlow` member | [Checkup.rounds] | [Checkup.chart] |
 * | suspend returning `StateFlow` (ADR-068) | [Checkup.awaitChart] | |
 *
 * [Checkup.examine] carries a non-null `String` ahead of the nullable handle, the issue's
 * `water(id, dose?)` shape, so a fix that numbers slots off the first parameter shows up there.
 *
 * Every body spells what Kotlin received: `"none"` / `"unobserved"` for `null`, and a field read
 * *through* the handle otherwise (`cat.name`, the alive arm's own `cat.name`), so a pointer that
 * crossed as a number, or a null that crossed as a default instance, cannot answer.
 *
 * Oreo (black, white bib) comes in for a checkup. Mylo (brown and creamy) skipped his appointment,
 * so he is the `null`.
 */
class Checkup(private val vet: String) {
  /** Suspend member, plain handle, after a non-null `String`. */
  suspend fun examine(room: String, cat: Cat?): String {
    delay(10.milliseconds)
    return "$vet in $room: ${cat.spell()}"
  }

  /** Suspend member, sealed handle. */
  suspend fun triage(observation: Observation?): String {
    delay(10.milliseconds)
    return "$vet triage: ${observation.spell()}"
  }

  /** `Flow` member, plain handle. */
  fun rounds(cat: Cat?): Flow<String> = flow { emit("$vet rounds: ${cat.spell()}") }

  /** `StateFlow` member, sealed handle. */
  fun chart(observation: Observation?): StateFlow<String> =
    MutableStateFlow("$vet chart: ${observation.spell()}")

  /** Suspend member returning `StateFlow` (ADR-068), the third bucket sharing the classifier. */
  suspend fun awaitChart(cat: Cat?): StateFlow<String> {
    delay(10.milliseconds)
    return MutableStateFlow("$vet awaited chart: ${cat.spell()}")
  }
}

/** The sealed-arm route (ADR-118): suspend members declared on the arm itself. */
sealed class Stay {
  /** An overnight stay in kennel [night]. */
  class Overnight(val night: Int) : Stay() {
    /** Sealed-arm suspend member, plain handle. */
    suspend fun admit(cat: Cat?): String {
      delay(10.milliseconds)
      return "night $night: ${cat.spell()}"
    }

    /** Sealed-arm suspend member, sealed handle. */
    suspend fun observe(observation: Observation?): String {
      delay(10.milliseconds)
      return "night $night observed: ${observation.spell()}"
    }
  }
}

/** Top-level suspend, plain handle. */
suspend fun weigh(cat: Cat?): String {
  delay(10.milliseconds)
  return "weighed: ${cat.spell()}"
}

/** Top-level suspend, sealed handle. */
suspend fun recheck(observation: Observation?): String {
  delay(10.milliseconds)
  return "rechecked: ${observation.spell()}"
}

private fun Cat?.spell(): String = this?.name ?: "none"

private fun Observation?.spell(): String = when (this) {
  null -> "unobserved"
  Observation.Superposition -> "superposition"
  is Observation.Alive -> "alive:${cat.name}"
  is Observation.Dead -> "dead:$cause"
}
