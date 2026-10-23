package io.github.xxfast.kotlin.native.nuget.test.purrclock

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Flow family properties declared on an enum: in the constructor, in the class body, and as an
 * abstract member each entry overrides in its own body. Each binds as a C# extension property on
 * `TailExtensions`, the entry crossing as its ordinal; a held `MutableStateFlow` or
 * `MutableSharedFlow` returns the holder of that entry's own flow.
 *
 * Oreo flicks the tip of hers; Mylo thumps his on the floor.
 */
enum class Tail(val swishes: MutableStateFlow<Int>) {
  OREO(MutableStateFlow(1)) {
    override val mood: Flow<String> get() = flowOf("Oreo flicks")
  },
  MYLO(MutableStateFlow(2)) {
    override val mood: Flow<String> get() = flowOf("Mylo thumps", "Mylo thumps again")
  };

  /** Declared abstract, overridden in each entry's own body. */
  abstract val mood: Flow<String>

  /** Each entry's own height, held: a write through one entry leaves the other alone. */
  val height: MutableStateFlow<Int> = MutableStateFlow(ordinal * 10)

  /** Each entry's own twitches, replayed once. */
  val twitches: MutableSharedFlow<String> = MutableSharedFlow(replay = 1)

  /** [height], read-only. */
  val level: StateFlow<Int> get() = height

  /** [twitches], read-only. */
  val echoes: SharedFlow<String> get() = twitches

  /** A cold flow, re-read per collection. */
  val wags: Flow<Int> get() = flowOf(ordinal, ordinal + 1)
}

/** What Kotlin sees in an entry's [Tail.height]. */
fun tailHeight(tail: Tail): Int = tail.height.value
