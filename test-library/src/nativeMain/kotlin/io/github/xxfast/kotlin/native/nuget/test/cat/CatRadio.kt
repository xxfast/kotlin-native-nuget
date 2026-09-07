package io.github.xxfast.kotlin.native.nuget.test.cat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

/**
 * Issue #97: overloads returning `Flow<T>` / `StateFlow<T>` / `MutableStateFlow<T>` /
 * `StateFlow<T>?`. Every pair used to collide on its `_collect` (and `_value`, `_has_value`,
 * `_set_value`) entry point because the flow route ignored overload numbering.
 */
class CatRadio {
  private val volumes: MutableMap<String, MutableStateFlow<Int>> = mutableMapOf()

  fun nowPlaying(station: String): StateFlow<String> = MutableStateFlow("$station: purring hour")

  fun nowPlaying(channel: Int): StateFlow<String> = MutableStateFlow("channel $channel: nap time")

  fun schedule(station: String): Flow<String> = flow {
    emit("$station: morning purrs")
    emit("$station: evening naps")
  }

  fun schedule(channel: Int): Flow<String> = flow {
    emit("channel $channel: morning zoomies")
  }

  fun volume(station: String): MutableStateFlow<Int> =
    volumes.getOrPut(station) { MutableStateFlow(3) }

  fun volume(channel: Int): MutableStateFlow<Int> =
    volumes.getOrPut("channel $channel") { MutableStateFlow(5) }

  fun maybeNowPlaying(station: String): StateFlow<String>? =
    if (station == "static") null else nowPlaying(station)

  fun maybeNowPlaying(channel: Int): StateFlow<String>? =
    if (channel == 0) null else nowPlaying(channel)
}
