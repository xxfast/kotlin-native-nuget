package io.github.xxfast.kotlin.native.nuget.test.kennel

import io.github.xxfast.kotlin.native.nuget.internal.NugetManagedException
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
