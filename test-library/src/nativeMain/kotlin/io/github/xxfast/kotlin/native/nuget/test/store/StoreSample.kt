package io.github.xxfast.kotlin.native.nuget.test.store

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.WeakReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.yield

/**
 * Fixture for ADR-241: an exported Kotlin exception class binds as a C# exception type. The handle
 * class IS the exception (unified), so a thrown [StoreException.NotFound] still reads [key].
 *
 * It crosses every mechanism the ADR adds:
 * - a sealed exported hierarchy with nested arms, one with a member ([StoreException.NotFound]);
 * - an unexported subclass ([TokenExpired]) caught as its nearest exported ancestor, the abstract
 *   arm [StoreException.Locked] rather than the root, which needs the private concrete backing of
 *   an abstract class;
 * - an exported subclass of a mapped stdlib row ([StaleCache]), whose C# base is
 *   `KotlinInvalidOperationException`;
 * - the sync member, static, suspend and Flow error routes, the cause loop
 *   ([TreatStore.collapse]) and a value position ([TreatStore.lastMiss]).
 *
 * Oreo raids the treat cupboard; Mylo is the one who left it open.
 */
sealed class StoreException(message: String) : Exception(message) {
  /** Oreo tried the cupboard without signing in. */
  class Unauthorized : StoreException("Oreo is not signed in to the treat cupboard")

  /** The cupboard has no [key]. */
  data class NotFound(val key: String) : StoreException("no $key in the cupboard")

  /** An abstract arm: Kotlin only ever throws an unexported subclass of it. */
  abstract class Locked(message: String) : StoreException(message)
}

/** An exported subclass of a mapped row: still an `InvalidOperationException` in C#. */
class StaleCache(message: String) : IllegalStateException(message)

/**
 * Not exported: C# sees it only as its nearest exported ancestor, [StoreException.Locked].
 *
 * Not a direct arm of the sealed base on purpose: a `private` direct sealed arm is enumerated by
 * the sealed discriminator today regardless of visibility, and the generated `CNameExports.kt`
 * then fails with "Cannot access 'class TokenExpired : StoreException': it is private in file".
 */
private class TokenExpired : StoreException.Locked("Mylo's paw token expired")

/** The cupboard. Every member but [lastMiss] and [isLastThrownAlive] throws. */
@OptIn(ExperimentalNativeApi::class)
class TreatStore {
  private var lastThrown: WeakReference<Throwable>? = null

  private fun <T : Throwable> remember(t: T): T {
    lastThrown = WeakReference(t)
    return t
  }

  /** Sync member route, most-derived arm with a member. */
  fun fetch(key: String): String = throw remember(StoreException.NotFound(key))

  /** Sync member route, an unexported subclass of the sealed base. */
  fun signIn(): Unit = throw remember(TokenExpired())

  /** Sync member route, an exported subclass of `IllegalStateException`. */
  fun stale(): Unit = throw remember(StaleCache("the kibble cache is stale"))

  /** The cause loop: a stdlib row whose cause is an exported arm. */
  fun collapse(key: String): Unit =
    throw IllegalStateException("the shelf collapsed", remember(StoreException.NotFound(key)))

  /** Suspend route, after a real suspension point. */
  suspend fun fetchAsync(key: String): String {
    yield()
    throw remember(StoreException.Unauthorized())
  }

  /** Suspend route, thrown before any suspension, with a member to read back. */
  suspend fun findAsync(key: String): String = throw remember(StoreException.NotFound(key))

  /** Flow route: one emission, then the most-derived arm. */
  fun watch(): Flow<String> = flow {
    emit("salmon")
    throw remember(StoreException.NotFound("feed"))
  }

  /** A value position: an exception returned, not thrown. */
  fun lastMiss(): StoreException = StoreException.NotFound("tuna")

  /** True while the last thrown exception is still reachable from Kotlin's side. */
  fun isLastThrownAlive(): Boolean = lastThrown?.get() != null
}

/** The static route: a top-level function in this file. */
fun openCupboard(): Unit = throw StoreException.Unauthorized()
