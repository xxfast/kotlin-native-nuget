package io.github.xxfast.kotlin.native.nuget.test.kennel

import io.github.xxfast.kotlin.native.nuget.internal.NugetManagedException
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import test.kennel.Kennel
import test.menagerie.IFeedable

// ADR-152: a C# `Task` / `Task<T>` member consumed from Kotlin as a `suspend fun`.
//
//   C# IntegrationTests
//     -> (forward bridge, ADR-019)      KennelSample.*Async  (a Kotlin suspend fun is a C# Task)
//       -> Kotlin test-library          KennelSample.kt (this file)
//         -> (reverse bridge, ADR-152)  test.kennel.{Kennel, Kitten}
//           -> real C# TestDependency   Test.Kennel.{Kennel, Kitten}
//
// EXPECTED TO FAIL TODAY: the reader now emits `asyncKind: "task"`, but neither generator knows
// what that means, so `test.kennel.Kennel` is generated without a single one of the members named
// below and this file does not compile. That failure is the point of this file at this stage.
//
// Organised by the SEAM each row crosses, because ADR-152's `End` thunk is `buildThunkMethod`'s
// return half and a fixture built only from `Task<Int>` would go green while the string, handle,
// nullable, fault and already-completed paths were all still wrong:
//
//   Task (no result) | Task<Int> (no conversion) | Task<String> (conversion) | Task<Kitten>
//   (handle) | Task<String?> (nullable) | fault after a real await | synchronous throw before any
//   Task exists | already completed (Task.FromResult / Task.CompletedTask) | genuinely async
//   (Task.Delay) | static | ReadAsync beside a sync Read | a Kotlin-implemented bound interface
//   used AFTER the await.

private const val NO_THROW = "no throw"

private const val NULL_WHISPER = "<null>"

/** `managedType|message` for a caught reverse throw: ADR-104's channel, reused verbatim. */
private fun describe(e: NugetManagedException): String = "${e.managedType}|${e.message}"

/** Non-generic `Task`: nothing comes back but the resumption itself. */
suspend fun oreoNaps(): String = Kennel().use {
  it.nap()
  "rested"
}

/** `Task<Int>`: a pass-through scalar, no conversion in `End`. */
suspend fun kennelCount(): Int = Kennel().use { it.count() }

/** `Task<String>`: the CoTaskMem string conversion Kotlin then frees. */
suspend fun kennelName(): String = Kennel().use { it.name() }

/** `Task<Kitten>`: a bound-class handle return, with a string argument `Begin` must copy. */
suspend fun adoptMylo(): String = Kennel().use { kennel -> kennel.adopt("Mylo").use { it.name } }

/**
 * `Task<String?>`: the NULLABLE reference result. Both halves, because a binding that read the
 * wrong `NullableAttribute` byte would be visibly wrong on exactly one of them.
 */
suspend fun whisperTo(name: String): String = Kennel().use { it.whisper(name) ?: NULL_WHISPER }

/** Faults AFTER a real suspension: the managed exception rides the continuation, not `Begin`. */
suspend fun oreoEscapes(): String = Kennel().use { kennel ->
  try {
    kennel.escape("Oreo")
    NO_THROW
  } catch (e: NugetManagedException) {
    describe(e)
  }
}

/**
 * The synchronous throw, before any `Task` exists: it leaves through `Begin`'s `errOut` on the
 * calling thread and the completion callback never fires, so this is the one row where releasing
 * the pending-continuation `ctx` is the Kotlin side's job.
 */
suspend fun rejectNull(): String = Kennel().use { kennel ->
  try {
    kennel.reject(null).toString()
  } catch (e: NugetManagedException) {
    e.managedType
  }
}

/**
 * ALREADY COMPLETED before `Begin` returns (`Task.FromResult`), driven [times] times on ONE
 * receiver. The ADR-019 race class: the callback can land before `suspendCancellableCoroutine`'s
 * block returns, and that race reads roughly +1 per thousand, so the loop lives here rather than
 * in the C# test, where every iteration would also pay a forward crossing.
 */
suspend fun seatedRepeatedly(times: Int): Int = Kennel().use { kennel ->
  var total = 0
  repeat(times) { total += kennel.seated() }
  total
}

/** Already completed with NO result (`Task.CompletedTask`), same race, `void` return half. */
suspend fun settledRepeatedly(times: Int): String = Kennel().use { kennel ->
  repeat(times) { kennel.settle() }
  "settled $times times"
}

/**
 * The SINGLE-BYTE `NullableAttribute` encoding: three nullable parameters put this method in a
 * `NullableContext(2)`, so its all-non-null `Task<String>` return tree is written as one byte, not
 * a two-element array. A reader that only compares lengths skips this member outright (ADR-152's
 * inferred claim D, which turned out to be false).
 */
suspend fun kennelLedger(): String = Kennel().use { it.ledger("kibble", null, "tuna") }

/** A STATIC async member: no `selfHandle` in the generated `Begin`. */
suspend fun rollCall(): String = Kennel.rollCall()

/**
 * The `Async` suffix RULE, both halves on one receiver: `Read` has no suffix to strip, and
 * `ReadAsync` KEEPS its suffix because `Read` already owns the stripped name.
 */
suspend fun readBothWays(): String = Kennel().use { "${it.read()}~${it.readAsync()}" }

/**
 * A Kotlin-implemented [IFeedable] (ADR-085) handed to an async C# member that calls back into it
 * AFTER the await, long after `Begin` returned and its transfer scope released the bridge handle.
 * ADR-152's inferred claim C, which is SILENT if wrong, so the assertion reads the Kotlin object's
 * own state back afterwards rather than trusting the returned string alone.
 */
suspend fun boardNibbles(): String = Kennel().use { kennel ->
  val goat = Nibbles()
  val boarded = kennel.board(goat)
  "$boarded~${goat.meals}"
}

// ADR-153: the cancellation half. Two directions meet on these rows and they are NOT the same
// mechanism:
//
//   Kotlin cancels  -> the bridge cancels the CancellationToken it supplied -> C# is told to stop.
//                      `End` is never called on this path, so nothing is mapped: the coroutine
//                      ends on its OWN cancellation and the only observable is C#-side state.
//   C# cancels      -> the task ends Canceled, `End` rethrows, and the mapping turns that into a
//                      CancellationException whose `cause` is the NugetManagedException.
//
// So each row below names which of the two it stands on, and the Kotlin-cancels rows read state
// back off the C# object rather than trusting that the wait ended.

/** How long to wait for the queued C#-side `Cancel()` to land before reading its effect. */
private val SETTLE = 200.milliseconds

/** The shortest wait that reliably reaches the suspension inside the C# method. */
private val IMPATIENT = 50.milliseconds

/** Longer than `DawdleAsync`'s own 300ms, so the row can see it finish after being cancelled. */
private val PATIENT = 500.milliseconds

/**
 * `managedType|message` for a cancellation mapped out of C#: the ADR-104 envelope rides as the
 * `cause`, so nothing is lost by the mapping. A missing or wrong cause is reported rather than
 * thrown, because a cast failure here would surface as an unrelated Kotlin exception in the test.
 */
private fun describeCancellation(e: CancellationException): String {
  val cause = e.cause
  if (cause !is NugetManagedException) return "cause=${cause?.toString() ?: "<null>"}"
  return "${cause.managedType}|${e.message}"
}

/**
 * KOTLIN CANCELS, through `withTimeout`. `stay` waits forever on its token, so the only way this
 * returns is the bridge cancelling the token it supplied. `stayCancelled` is read off the SAME C#
 * object afterwards: without it, a bridge that cancels nothing looks identical from here, because
 * the coroutine ends on its own timeout either way.
 */
suspend fun stayTimesOut(): String = Kennel().use { kennel ->
  val result: Int? = withTimeoutOrNull(IMPATIENT) { kennel.stay("Oreo") }
  delay(SETTLE)
  "${result == null}|${kennel.stayCancelled}|${kennel.stayCancellations}"
}

/**
 * KOTLIN CANCELS, through `job.cancel()` from another coroutine, so the thunk that cancels the
 * token is entered from a thread the CLR did not create and did not previously call (ADR-153's
 * inferred claim C). Same C#-side assertion as [stayTimesOut]; different cancelling thread.
 */
suspend fun stayJobCancelled(): String = Kennel().use { kennel ->
  coroutineScope {
    val job = launch { kennel.stay("Mylo") }
    delay(IMPATIENT)
    job.cancelAndJoin()
  }
  delay(SETTLE)
  "${kennel.stayCancelled}|${kennel.stayCancellations}"
}

/**
 * KOTLIN CANCELS a method that IGNORES its token. The wait must still end promptly (the coroutine
 * resumes with its own exception; C# is never obliged to stop), and the C# work runs on to
 * completion afterwards. Both halves in one row, because "ends promptly" alone would also be true
 * of a bridge that dropped the call on the floor.
 */
suspend fun dawdleIgnoresTheToken(): String = Kennel().use { kennel ->
  val result: Int? = withTimeoutOrNull(IMPATIENT) { kennel.dawdle() }
  delay(PATIENT)
  "${result == null}|${kennel.dawdleCompleted}"
}

/**
 * C# CANCELS ITSELF and Kotlin does not: the one path on which `End` is reached with a cancelled
 * task, and therefore the only path where the mapping runs at all. `TaskCanceledException`, the
 * shape a name match would catch.
 */
suspend fun boltSurfacesAsCancellation(): String = Kennel().use { kennel ->
  try {
    kennel.bolt()
    NO_THROW
  } catch (e: CancellationException) {
    describeCancellation(e)
  }
}

/**
 * The same self-cancel ending in a USER SUBCLASS of `OperationCanceledException`. A Kotlin-side
 * `when (managedType)` over the two well-known names leaves this one an ordinary
 * [NugetManagedException], so it is caught here as one and reported, rather than escaping.
 */
suspend fun scarperSurfacesAsCancellation(): String = Kennel().use { kennel ->
  try {
    kennel.scarper("Oreo")
    NO_THROW
  } catch (e: CancellationException) {
    describeCancellation(e)
  } catch (e: NugetManagedException) {
    "unmapped|${describe(e)}"
  }
}

/**
 * The SYNC route: one managed-throw site serves every thunk, so an `OperationCanceledException`
 * out of an ordinary call maps too. Pins that as a decision rather than an accident.
 */
suspend fun startleSurfacesAsCancellation(): String = Kennel().use { kennel ->
  try {
    kennel.startle().toString()
  } catch (e: CancellationException) {
    describeCancellation(e)
  }
}

/**
 * The token in a MID position, between a `String` that needs conversion and an `Int` that does
 * not. An implementation that assumes the token is last shifts one of them into the other's slot.
 */
suspend fun fetchWithAMidToken(): String = Kennel().use { it.fetch("Mouse", 3) }

/**
 * The `CallAsync()` / `CallAsync(CancellationToken)` fold. The two C# bodies answer differently,
 * so this is the row that says WHICH sibling survived; a fold that kept the token-less one still
 * compiles and still binds.
 */
suspend fun callKeepsTheTokenOverload(): String = Kennel().use { it.call() }

/** A `= default` token: still elided, still bound, the default never consulted. */
suspend fun dozeWithADefaultToken(): Int = Kennel().use { it.doze(3) }

/**
 * Leak driver, CANCELLED path: [times] cancelled calls on ONE receiver, so the CTS handle minted
 * per call is released by the `invokeOnCancellation` handler [times] times over. Returns the C#
 * count so a run where nothing was actually cancelled cannot pass as a clean one.
 */
suspend fun stayCancelledRepeatedly(times: Int): Int = Kennel().use { kennel ->
  repeat(times) { withTimeoutOrNull(IMPATIENT) { kennel.stay("Oreo") } }
  delay(SETTLE)
  kennel.stayCancellations
}

/**
 * Leak driver, COMPLETED path, already-completed task: the ADR-019 race with a CTS handle riding
 * on it. The completion can land before `suspendCancellableCoroutine`'s block returns, which is
 * the window in which the handle is minted but not yet stored (ADR-153's inferred claim B), so a
 * miss there leaks one .NET handle per call and one Kotlin `ctx` per call.
 */
suspend fun pounceRepeatedly(times: Int): Int = Kennel().use { kennel ->
  var total = 0
  repeat(times) { total += kennel.pounce(2) }
  total
}

// ADR-155: a C# `IAsyncEnumerable<T>` member consumed from Kotlin as a COLD `Flow<T>`, pulled one
// `MoveNextAsync` at a time over ADR-152's begin/end pair.
//
// EXPECTED TO FAIL TODAY: neither the reader nor the generator knows `asyncKind:
// "async_enumerable"`, so `test.kennel.Kennel` has none of `barks`, `litter`, `ticks`, `howls` or
// the counter properties below and this file does not compile. That is the point at this stage.
//
// Each row names the seam it stands on, and the rows that are ABOUT stopping read C#-side counters
// back off the same object afterwards: "the collector saw one element" is equally true of a bridge
// that cancelled the C# enumeration, of one that abandoned it running forever, and of one that
// never started it. Only the difference between what C# YIELDED and what the collector RECEIVED,
// plus whether the iterator's `finally` ran, tells those three apart.

/**
 * A full second, in 10ms steps, against a 300ms uninterruptible C# step: disposal is
 * fire-and-forget (ADR-155 open question 2), so cleanup is polled rather than assumed.
 */
private suspend fun pollFor(predicate: () -> Boolean): Boolean {
  repeat(100) {
    if (predicate()) return true
    delay(10.milliseconds)
  }
  return predicate()
}

/**
 * The converting element (`String`) in order, over a source that ignores its token. `count`
 * elements means `count - 1` dawdles, so keep it at 1 anywhere the wall clock matters.
 */
suspend fun kennelBarks(count: Int): String =
  Kennel().use { it.barks(count).toList().joinToString(",") }

/**
 * COLDNESS, the whole of it: ONE `Flow` value, collected TWICE. Collecting
 * `kennel.barks(1).toList()` twice would move `barkCalls` to 2 whether the C# method ran at the
 * Kotlin call or at each collect, so it could not discriminate ADR-155's open question 1. This
 * shape can: a flow that captured one C# enumeration reads `1|1`, a cold one reads `2|2`.
 */
suspend fun barksCollectedTwice(): String = Kennel().use { kennel ->
  val barks: Flow<String> = kennel.barks(1)
  val first: List<String> = barks.toList()
  val second: List<String> = barks.toList()
  "${first.joinToString(",")}~${second.joinToString(",")}" +
      "|${kennel.barkCalls}|${kennel.barkEnumerations}"
}

/**
 * CANCEL MID-STEP against the TOKEN-IGNORING source. The collector gives up while C# is inside an
 * uninterruptible wait, so C# finishes that step and yields once more (two yields) while the
 * collector receives exactly one (nothing is delivered after cancellation, ADR-155's ownership
 * note: `Current` is never read on the cancelled step). The `finally` is POLLED, not assumed:
 * disposal is fire-and-forget and may land after `collect` has already returned.
 */
suspend fun barksCancelledMidStep(): String = Kennel().use { kennel ->
  var delivered = 0
  withTimeoutOrNull(IMPATIENT) { kennel.barks(3).collect { delivered++ } }
  val cleaned: Boolean = pollFor { kennel.barkFinallyRuns > 0 }
  "$delivered|${kennel.barkYields}|$cleaned|${kennel.barkFinallyRuns}"
}

/**
 * The other end of the same mechanism: `take(1)` aborts the collector while C# is SUSPENDED at a
 * yield with no step in flight, so the enumeration is disposed there and C# never produces a
 * second element at all. Distinct from [barksCancelledMidStep], where it produces one more.
 */
suspend fun firstBarkOnly(): String = Kennel().use { kennel ->
  val first: String = kennel.barks(3).take(1).toList().single()
  val cleaned: Boolean = pollFor { kennel.barkFinallyRuns > 0 }
  "$first|${kennel.barkYields}|$cleaned"
}

/** The bound-class HANDLE element: each `Kitten` is a handle the collector owns and closes. */
suspend fun kennelLitter(): String = Kennel().use { kennel ->
  kennel.litter().toList().joinToString(",") { kitten -> kitten.use { it.name } }
}

/**
 * CANCEL MID-STEP against the TOKEN-HONOURING source. Same collector cancellation as
 * [barksCancelledMidStep], different C# outcome: the pending step is aborted, so `litterCancelled`
 * is the proof that the per-collect token the bridge owns actually reached the C# method rather
 * than the enumeration merely being dropped on the floor.
 */
suspend fun litterCancelledMidStep(): String = Kennel().use { kennel ->
  var delivered = 0
  withTimeoutOrNull(IMPATIENT) { kennel.litter().collect { kitten -> delivered++; kitten.close() } }
  val cleaned: Boolean = pollFor { kennel.litterFinallyRuns > 0 }
  "$delivered|${kennel.litterCancelled}|$cleaned"
}

/**
 * STATIC: `Kennel.ticks()` is called off the companion, so the generated `Enumerate` thunk carries
 * no receiver handle. (The nullable VALUE element this row originally carried is a split-out item;
 * `Kennel.NullableTicks` stays an unbound, named skip.)
 */
suspend fun kennelTicks(): String = Kennel.ticks().toList().joinToString(",")

/**
 * The MID-STREAM THROW, on the element shape that needs no conversion. Two elements arrive first,
 * so "received nothing" and "received two and no exception" are different failures here, and the
 * iterator's `finally` is asserted to have run on the fault path too.
 */
suspend fun howlsFault(): String = Kennel().use { kennel ->
  val heard: MutableList<Int> = mutableListOf()
  val outcome: String = try {
    kennel.howls().collect { heard += it }
    NO_THROW
  } catch (e: NugetManagedException) {
    describe(e)
  }
  val cleaned: Boolean = pollFor { kennel.howlFinallyRuns > 0 }
  "${heard.joinToString(",")}|$outcome|$cleaned"
}

/**
 * Leak driver: [times] full enumerations, one `ctx` per element step plus one per end-of-stream.
 */
suspend fun barksRepeatedly(times: Int): Int = Kennel().use { kennel ->
  var total = 0
  repeat(times) { total += kennel.barks(1).toList().size }
  total
}

/**
 * Leak driver, CANCELLED path: the step's `ctx` is released by a completion landing after cancel.
 */
suspend fun barksCancelledRepeatedly(times: Int): Int = Kennel().use { kennel ->
  repeat(times) { withTimeoutOrNull(IMPATIENT) { kennel.barks(3).collect { } } }
  pollFor { kennel.barkFinallyRuns >= times }
  kennel.barkFinallyRuns
}

/** Leak driver, FAULTED path: the throwing step reaches `End`, which must free both handles. */
suspend fun howlsFaultRepeatedly(times: Int): Int = Kennel().use { kennel ->
  repeat(times) {
    try {
      kennel.howls().collect { }
    } catch (e: NugetManagedException) {
      // expected, every round
    }
  }
  pollFor { kennel.howlFinallyRuns >= times }
  kennel.howlFinallyRuns
}

/**
 * Two male cats, Oreo and Mylo, own the rest of this repository's fixtures, so the boarder here is
 * a goat: a plain Kotlin class implementing the bound C# [IFeedable], with observable state so the
 * post-await dispatch can be proved to have reached Kotlin and not merely returned a string.
 */
private class Nibbles : IFeedable {
  var meals: Int = 0
    private set

  override fun describe(): String = "Nibbles the goat"

  override val legs: Int get() = 4

  override fun feed(food: String) {
    meals++
  }

  override var nickname: String? = null
}
