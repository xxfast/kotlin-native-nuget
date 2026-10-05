package io.github.xxfast.kotlin.native.nuget.test.mishaps

/**
 * Fixture for the ADR-201 amendment: `Throwable` at the positions ADR-201 deferred. Out of Kotlin
 * every value is the ADR-107 envelope (an unthrown `System.Exception`); into Kotlin every
 * `System.Exception` arrives as the ADR-161 `NugetManagedException`, type name and message only.
 *
 * Group A, a `List`/`MutableList` element and a `Map` value at an input:
 * [reportAll], [reportSome], [reportByCat], [pending], and the throw path [reportAllOrThrow].
 * Group B, a `suspend`/`Flow` parameter: [reportLater], [maybeLater], [watch].
 * Group D, a lambda payload and result and a listener parameter: [onMishap], [recover],
 * [MishapListener] with [addMishapListener]/[removeMishapListener] and [announce].
 * Group C, a bare `suspend` result, `Flow` element and `StateFlow` element: [worstLater],
 * [latestLater], [worstOrThrowLater], [each], [maybeEach], [eachThenFail], [current],
 * [streamLater].
 * Group E, a C#-implemented interface slot: [MishapSink] and [MishapStream.drainTo].
 *
 * Mochi coughs up a hairball at 3am; Oreo reports it, in bulk.
 */
class MishapStream {
  /** Every mishap Kotlin received, as "`SimpleName`: message", in order. */
  fun reportAll(mishaps: List<Exception>): String =
    mishaps.joinToString(" | ") { "${it::class.simpleName}: ${it.message}" }

  /** How many of [mishaps] were present. */
  fun reportSome(mishaps: List<Throwable?>): Int = mishaps.count { it != null }

  /** The messages per cat, sorted by cat. */
  fun reportByCat(mishaps: Map<String, Throwable>): String =
    mishaps.entries.sortedBy { it.key }.joinToString(" | ") { "${it.key}=${it.value.message}" }

  /** A list property the C# side can replace. */
  var pending: MutableList<Throwable> = mutableListOf()

  /** The size of [pending], read back in Kotlin. */
  fun pendingCount(): Int = pending.size

  /** The list input's throw path: throws after receiving the list when [fail] is set. */
  fun reportAllOrThrow(mishaps: List<Throwable>, fail: Boolean): Int =
    if (fail) throw IllegalStateException("the vet is closed") else mishaps.size

  /** Group B: a suspend parameter, with a real suspension point before it is read. */
  suspend fun reportLater(mishap: Throwable): String {
    kotlinx.coroutines.yield()
    return "${mishap::class.simpleName}: ${mishap.message}"
  }

  /** Group B: a nullable suspend parameter that completes without suspending. */
  suspend fun maybeLater(mishap: Exception?): Boolean = mishap != null

  /** Group B: a Flow-returning member's parameter. */
  fun watch(mishap: RuntimeException): kotlinx.coroutines.flow.Flow<String> =
    kotlinx.coroutines.flow.flowOf(mishap.message ?: "", "again")

  /** Group D: a lambda payload, invoked synchronously with a module-local hairball. */
  fun onMishap(handler: (Throwable) -> Unit) {
    handler(io.github.xxfast.kotlin.native.nuget.hidden.HairballError("Mochi, 3am"))
  }

  /** Group D: a lambda result, a C# exception handed back as a value. */
  fun recover(fallback: () -> Exception): String =
    fallback().let { "${it::class.simpleName}: ${it.message}" }

  private val listeners: MutableList<MishapListener> = mutableListOf()

  /** Group D: the ADR-039 subscription pair. */
  fun addMishapListener(listener: MishapListener) {
    listeners.add(listener)
  }

  fun removeMishapListener(listener: MishapListener) {
    listeners.remove(listener)
  }

  /** Hands every listener the same unthrown mishap; returns how many heard it. */
  fun announce(): Int {
    listeners.forEach { it.onMishap(IllegalArgumentException("Oreo ate the plant")) }
    return listeners.size
  }

  private val seen: kotlinx.coroutines.flow.MutableStateFlow<Throwable?> =
    kotlinx.coroutines.flow.MutableStateFlow(null)

  /** Group C: a bare suspend result, after a real suspension point. */
  suspend fun worstLater(): Throwable {
    kotlinx.coroutines.yield()
    return IllegalArgumentException("Oreo ate the plant", RuntimeException("the door was open"))
  }

  /** Group C: a nullable suspend result that completes without suspending. */
  suspend fun latestLater(): Throwable? = seen.value

  /** Group C: the suspend result's throw path. */
  suspend fun worstOrThrowLater(fail: Boolean): Throwable =
    if (fail) throw IllegalStateException("the vet is closed") else worstLater()

  /** Group C: a bare `Flow` element, the second a module-local subclass. */
  fun each(): kotlinx.coroutines.flow.Flow<Throwable> = kotlinx.coroutines.flow.flowOf(
    IllegalStateException("the bowl is empty"),
    io.github.xxfast.kotlin.native.nuget.hidden.HairballError("Mochi, 3am"),
  )

  /** Group C: a nullable `Flow` element. */
  fun maybeEach(): kotlinx.coroutines.flow.Flow<Throwable?> =
    kotlinx.coroutines.flow.flowOf(null, IllegalStateException("x"))

  /** Group C: the `Flow` route's throw path, after one element. */
  fun eachThenFail(): kotlinx.coroutines.flow.Flow<Throwable> = kotlinx.coroutines.flow.flow {
    emit(IllegalStateException("one"))
    throw IllegalStateException("the vet is closed")
  }

  /** Group C: a `StateFlow` element on a property, set from C# through [record]. */
  val current: kotlinx.coroutines.flow.StateFlow<Throwable?> get() = seen

  /** Group C: an acquired `Flow` from a suspend member. */
  suspend fun streamLater(): kotlinx.coroutines.flow.Flow<Exception> =
    kotlinx.coroutines.flow.flowOf(RuntimeException("again"))

  /** Records [mishap] (or clears it) into [current]. */
  fun record(mishap: Throwable?) {
    seen.value = mishap
  }

  /** Hands [sink] a module-local hairball, then reads the sink's own latest mishap back. */
  fun drainTo(sink: MishapSink): String? {
    sink.accept(io.github.xxfast.kotlin.native.nuget.hidden.HairballError("Mochi, 3am"))
    return sink.last()?.message
  }
}

/**
 * Group E, a C#-implemented interface slot (ADR-084): [accept]'s parameter crosses out as the
 * ADR-107 envelope, [last]'s result comes back in as the managed-exception text.
 */
interface MishapSink {
  /** Receives one Kotlin mishap. */
  fun accept(mishap: Throwable)

  /** The sink's own latest mishap, or null. */
  fun last(): Exception?
}

/** Group D, an ADR-039 listener whose parameter crosses out as the ADR-107 envelope. */
interface MishapListener {
  /** Hears one Kotlin mishap. */
  fun onMishap(mishap: Throwable)
}
