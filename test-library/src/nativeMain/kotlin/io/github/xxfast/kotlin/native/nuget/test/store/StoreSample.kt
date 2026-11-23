package io.github.xxfast.kotlin.native.nuget.test.store

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.WeakReference
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
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
 *   ([TreatStore.collapse]) and a value position ([TreatStore.lastMiss]);
 * - one member per remaining error route (property, constructor, object, companion, extension,
 *   generic owner, sealed arm, interface, `Result`, the runtime-owned suspend-lambda
 *   and `StateFlow` routes), a `data object` arm, an exported cause, an open root, and the erased
 *   and typed `Throwable` value positions.
 *
 * Oreo raids the treat cupboard; Mylo is the one who left it open.
 */
sealed class StoreException(message: String) : Exception(message) {
  /** Oreo tried the cupboard without signing in. */
  class Unauthorized : StoreException("Oreo is not signed in to the treat cupboard")

  /** The cupboard has no [key]. */
  data class NotFound(val key: String) : StoreException("no $key in the cupboard") {
    /** The sealed-arm member route: a member of an exported exception arm that throws. */
    fun retry(): String = throw NotFound(key)
  }

  /** An abstract arm: Kotlin only ever throws an unexported subclass of it. */
  abstract class Locked(message: String) : StoreException(message)

  /**
   * A `data object` arm: an `OBJECT`-kind exported class, so the classifier key and the C# arm
   * cannot assume `ClassKind.CLASS`. Thrown by [TreatStore.rummage].
   */
  data object Empty : StoreException("the cupboard is bare")
}

/** An exported subclass of a mapped row: still an `InvalidOperationException` in C#. */
class StaleCache(message: String) : IllegalStateException(message)

/**
 * An exported exception whose cause is another exported exception: the cause loop with an exported
 * node on both ends. Thrown by [TreatStore.spill].
 */
class Spillage(message: String, cause: Throwable) : Exception(message, cause)

/**
 * An open (non-sealed, non-abstract) exported root. Kotlin throws only the unexported
 * [MouldyKibble], so C# catches it as this concrete class, not a private backing.
 */
open class Spoiled(message: String) : Exception(message)

/** Not exported: reaches C# as its nearest exported ancestor, [Spoiled]. */
private class MouldyKibble : Spoiled("the kibble went mouldy")

/**
 * A generic owner. [item] is read through C#'s erased `FromHandle<T>`, so a `Jar<StoreException>`
 * reaches the exported arm of `BuildException` as a value; [unscrew] is the generic-owner member
 * error route.
 */
class Jar<T>(val item: T) {
  /** Oreo cannot get the lid off. */
  fun unscrew(): T = throw StoreException.NotFound("jar lid")
}

/** The constructor error route: the init block throws before C# gets a handle. */
class TreatBin(key: String) {
  init {
    throw StoreException.NotFound(key)
  }
}

/** The object member error route. */
object Gatekeeper {
  /** Mylo asks to be let in; the treat is never there. */
  fun letIn(key: String): String = throw StoreException.NotFound(key)
}

/** The interface member route: a Kotlin-implemented interface held by C#. */
interface Hidey {
  /** Looks for [key] in the hidey hole. */
  fun peek(key: String): String
}

/**
 * A library-authored `StateFlow` whose `collect` throws, the only way a `StateFlow` collection can
 * fail (the same shape `RuntimeMappedTypeTest.JammedBowlFlow` drives the runtime with).
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class JammedBowl(private val key: String) : StateFlow<String> {
  override val value: String = "jammed"
  override val replayCache: List<String> get() = listOf(value)
  override suspend fun collect(collector: FlowCollector<String>): Nothing =
    throw StoreException.NotFound(key)
}

/**
 * Not exported: C# sees it only as its nearest exported ancestor, [StoreException.Locked].
 *
 * Not a direct arm of the sealed base on purpose: a `private` direct sealed arm is enumerated by
 * the sealed discriminator today regardless of visibility, and the generated `CNameExports.kt`
 * then fails with "Cannot access 'class TokenExpired : StoreException': it is private in file".
 */
private class TokenExpired : StoreException.Locked("Mylo's paw token expired")

/**
 * The cupboard. The error-route members all throw; [lastMiss], [isLastThrownAlive], [isTheMiss],
 * the `Jar` members, [missAsThrowable], [sniff] and [echo] are the value and erased positions.
 */
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
  suspend fun fetchLater(key: String): String {
    yield()
    throw remember(StoreException.Unauthorized())
  }

  /** Suspend route, thrown before any suspension, with a member to read back. */
  suspend fun find(key: String): String = throw remember(StoreException.NotFound(key))

  /** Flow route: one emission, then the most-derived arm. */
  fun watch(): Flow<String> = flow {
    emit("salmon")
    throw remember(StoreException.NotFound("feed"))
  }

  /** A value position: an exception returned, not thrown. */
  fun lastMiss(): StoreException = StoreException.NotFound("tuna")

  /** True while the last thrown exception is still reachable from Kotlin's side. */
  fun isLastThrownAlive(): Boolean = lastThrown?.get() != null

  // --- more error routes (one member per route) ---

  /** The property getter error route. */
  val topShelf: String get() = throw remember(StoreException.NotFound("top-shelf"))

  /** The property setter error route: the getter is fine, the setter throws. */
  var label: String
    get() = "treats"
    set(value) {
      throw remember(StoreException.NotFound(value))
    }

  /** The `Result<T>` failure route: a modelled failure, not a throw. */
  fun fetchOrFail(key: String): Result<String> =
    Result.failure(remember(StoreException.NotFound(key)))

  /** A `Spillage` whose cause is the exported [StoreException.NotFound]. */
  fun spill(key: String): Unit =
    throw Spillage("Oreo knocked the bag over", remember(StoreException.NotFound(key)))

  /** The `data object` arm, thrown. */
  fun rummage(): Unit = throw StoreException.Empty

  /** An unexported subclass of an open exported root. */
  fun sniffBowl(): Unit = throw remember(MouldyKibble())

  /** The interface member route: C# calls [Hidey.peek] on a Kotlin object it holds. */
  fun backOfTheCupboard(): Hidey = object : Hidey {
    override fun peek(key: String): String = throw remember(StoreException.NotFound(key))
  }

  /**
   * A runtime-owned route: a suspend-lambda property. Its getter is an ADR-202 mint site, and the
   * throw is classified inside the runtime by `nuget_suspend_func1_invoke`.
   */
  val fetcher: suspend (String) -> String = { key ->
    yield()
    throw remember(StoreException.NotFound(key))
  }

  /**
   * A runtime-owned route: a returned suspend lambda, invoked through `nuget_suspend_func1_invoke`.
   * Its export installs the module classifier (ADR-241 added this mint site to ADR-202's).
   */
  fun fetcherLater(): suspend (String) -> String = { key ->
    throw remember(StoreException.NotFound(key))
  }

  /**
   * A runtime-owned route: a `suspend fun` returning `StateFlow`, collected through
   * `nuget_stateflow_collect`, whose `collect` throws.
   */
  suspend fun bowlLater(): StateFlow<String> {
    yield()
    return JammedBowl("bowl-later")
  }

  /** A `StateFlow` property whose `collect` throws: the generated hot-flow collect route. */
  val bowl: StateFlow<String> get() = JammedBowl("bowl")

  /** A `Flow` property: the property flavour of the [watch] collect route. */
  val feed: Flow<String> get() = flow {
    emit("salmon")
    throw remember(StoreException.NotFound("feed"))
  }

  // --- value and erased positions ---

  private val theMiss: StoreException.NotFound = StoreException.NotFound("tuna")

  /** True when [miss] is the very Kotlin object [missJar] and [missAsThrowable] hand out. */
  fun isTheMiss(miss: StoreException): Boolean = miss === theMiss

  /** An exported arm at an erased `T` (`Jar<StoreException>.item`, C#'s `FromHandle<T>`). */
  fun missJar(): Jar<StoreException> = Jar(theMiss)

  /**
   * A stdlib row at an erased `T` (the lambda's result, C#'s `FromHandle<Exception>`): still mapped
   * by row. Not a `Jar<Throwable>`: a generic class's `Throwable` type argument has no erased read.
   */
  fun staleLater(): suspend (String) -> Throwable = { IllegalStateException("the kibble went stale") }

  /** A non-exception at the same erased `T`: unaffected. */
  fun kibbleJar(): Jar<String> = Jar("salmon")

  /** An exported arm at a typed `Throwable` return (an unthrown `BuildException`). */
  fun missAsThrowable(): Throwable = theMiss

  /** An exported arm as a callback's `Throwable` argument. */
  fun sniff(onMiss: (Throwable) -> String): String = onMiss(theMiss)

  /** A member's own `T`: C# writes the value through `Wrap<T>` and reads it back. */
  fun <T> echo(item: T): T = item

  companion object {
    /** The companion member error route. */
    fun restock(key: String): TreatStore = throw StoreException.NotFound(key)
  }
}

/** The static route: a top-level function in this file. */
fun openCupboard(): Unit = throw StoreException.Unauthorized()

/**
 * A runtime-owned route: a C#-implemented interface whose suspend slot receives a Kotlin suspend
 * lambda (the `whenHungry` shape in `catsitter`). C# invokes the lambda through
 * `nuget_suspend_func1_invoke`; the bridge factory installs the module classifier (ADR-241).
 */
interface Taster {
  /** Tastes [sample]; the C# side decides what to do when it throws. */
  suspend fun taste(sample: suspend (String) -> String): String
}

/** Hands [taster] a Kotlin suspend lambda that always throws an exported arm. */
suspend fun tasting(taster: Taster): String =
  taster.taste { key -> throw StoreException.NotFound(key) }

/** The extension function error route. */
fun TreatStore.raid(key: String): String = throw StoreException.NotFound(key)

/** The top-level suspend route, after a real suspension point. */
suspend fun openCupboardLater(key: String): String {
  yield()
  throw StoreException.NotFound(key)
}
