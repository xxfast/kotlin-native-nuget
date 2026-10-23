package io.github.xxfast.kotlin.native.nuget.test.purrclock

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * A `MutableStateFlow` or `MutableSharedFlow` of a nullable collection or `ByteArray` element, on
 * a class, an `object` and the top level. Null crosses as a null handle both ways, so C# reads,
 * writes and emits null as well as a collection, and Kotlin sees each write.
 *
 * Oreo hoards socks; Mylo only counts them.
 */

/** A drawer that may hold no socks at all, as opposed to an empty pile. */
class SockDrawer {
  /** The sock sizes, or null before anyone looked. */
  val socks: MutableStateFlow<List<Int>?> = MutableStateFlow(null)

  /** A photo of the drawer, or null when nobody took one. */
  val photo: MutableStateFlow<ByteArray?> = MutableStateFlow(null)

  /** Name tags sewn in, replayed once; null clears them. */
  val labels: MutableSharedFlow<Set<String>?> = MutableSharedFlow(replay = 1)

  /** Who sleeps in the drawer, in order, or null when it is empty. */
  val sleepers: MutableStateFlow<List<Posture>?> = MutableStateFlow(null)

  /** A cold flow of tallies, the first of which is missing. */
  val tallies: Flow<Map<String, Int>?> = flowOf(null, mapOf("Oreo" to 3))

  /** The same storage as [socks], held by its own handle. */
  fun sockJar(): MutableStateFlow<List<Int>?> = socks

  /** [socks], awaited. */
  suspend fun awaitSocks(): MutableStateFlow<List<Int>?> = socks

  /** [photo], awaited. */
  suspend fun awaitPhoto(): MutableStateFlow<ByteArray?> = photo

  /** What Kotlin sees in [socks]: the sum, or -1 for null. */
  fun sockTotal(): Int = socks.value?.sum() ?: -1

  /** What Kotlin sees in [photo]: its size, or -1 for null. */
  fun photoSize(): Int = photo.value?.size ?: -1

  /** What Kotlin last saw on [labels]: the sorted tags joined, or "none" for null. */
  fun lastLabels(): String = labels.replayCache.lastOrNull()?.sorted()?.joinToString(",") ?: "none"

  /** What Kotlin sees in [sleepers]: their names joined, or "none" for null. */
  fun sleeperNames(): String = sleepers.value?.joinToString(",") { it.name } ?: "none"
}

/** The shared laundry basket, an `object` owner. */
object Laundry {
  /** Sock sizes in the basket, or null when it was never filled. */
  val socks: MutableStateFlow<List<Int>?> = MutableStateFlow(null)

  /** Tags waiting to be sewn, replayed once. */
  val labels: MutableSharedFlow<Set<String>?> = MutableSharedFlow(replay = 1)

  /** What Kotlin sees in [socks]: the sum, or -1 for null. */
  fun sockTotal(): Int = socks.value?.sum() ?: -1
}

/** Socks Mylo dragged under the sofa, or null when he has not been at it. */
val looseSocks: MutableStateFlow<List<Int>?> = MutableStateFlow(null)

/** Tags found under the sofa, replayed once. */
val looseLabels: MutableSharedFlow<Set<String>?> = MutableSharedFlow(replay = 1)

/** What Kotlin sees in [looseSocks]: the sum, or -1 for null. */
fun looseSockTotal(): Int = looseSocks.value?.sum() ?: -1
