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
