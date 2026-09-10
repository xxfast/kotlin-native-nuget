package io.github.xxfast.kotlin.native.nuget.test.cat

import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * An exported top-level interface, so an implementing class carries a non-empty `interfaces` list
 * and the renderer's `implements` `when` takes the interface arm. Deliberately has no `suspend`
 * member: ADR-040's dispatch skips those, and the point of this fixture is the *class*.
 */
interface Napper {
  fun nap(): String
}

/**
 * The one shape the base list used to drop both disposables on: implements an exported interface
 * *and* owns a suspending member. `renderDispose` emits `Dispose()` and `DisposeAsync()` for it,
 * but the interface arm of the `when` short-circuits the disposable arms, so the generated class
 * reads `: INapper, INugetHandle` and `await using var pod = new NapPod();` is CS8410.
 *
 * The pod itself is a cardboard box by the heater. Oreo claims it first, Mylo dozes on top.
 */
class NapPod : Napper {
  override fun nap(): String = "Oreo curls up in the pod"

  /** Minutes Mylo dozed for. Makes the class a reachable-scope owner (ADR-021/025). */
  suspend fun doze(): Int {
    delay(10.milliseconds)
    return 20
  }
}
