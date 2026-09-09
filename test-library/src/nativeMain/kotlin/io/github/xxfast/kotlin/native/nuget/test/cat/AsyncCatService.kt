package io.github.xxfast.kotlin.native.nuget.test.cat

import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class AsyncCatService(private val prefix: String) {
  suspend fun fetch(): String {
    delay(5.seconds)
    return "$prefix result"
  }

  suspend fun fetchCat(name: String): Cat {
    delay(5.seconds)
    return Cat(name)
  }

  /**
   * ADR-118 / ROADMAP line 54: the **arity-distinct** half of the suspend overload pair. Both
   * overloads keep the C# name `FetchCatAsync`; the second must take `asynccatservice_fetchCat_2_async`
   * and `Native_FetchCat_2Async`, or the two `[DllImport]`s collide on one C symbol and the round
   * fails with `ERROR_C_ENTRY_POINT_COLLISION` before anything is generated.
   *
   * The cat comes back with [lives] set explicitly, which the one-parameter overload can never
   * produce (it takes the default 9), so the C# assertion is a *value* and not a presence.
   */
  suspend fun fetchCat(name: String, lives: Int): Cat {
    delay(100.milliseconds)
    return Cat(name, lives)
  }

  // Issue #108: the class-method twin of `AsyncFunctions`'s nullable top-level suspend functions.
  // `buildSuspendMethodBody` carries the identical `isUnit`-only branch, so the same unguarded
  // `StableRef.create(result)` lands here and both builders have to be fixed for this to go green.

  /** Nullable STRING return: only Oreo's toy has a name tag on it. */
  suspend fun findToyName(catName: String): String? {
    delay(100.milliseconds)
    return if (catName == "Oreo") "$prefix mouse" else null
  }

  /** Nullable OBJECT return: Mylo, brown and creamy, is the only one on the adoption list. */
  suspend fun findAdoptedCat(catName: String): Cat? {
    delay(100.milliseconds)
    return if (catName == "Mylo") Cat(catName) else null
  }

  /** Nullable PRIMITIVE return: nobody has ever finished counting Mylo's whiskers. */
  suspend fun countWhiskers(catName: String): Int? {
    delay(100.milliseconds)
    return if (catName == "Oreo") 24 else null
  }
}
