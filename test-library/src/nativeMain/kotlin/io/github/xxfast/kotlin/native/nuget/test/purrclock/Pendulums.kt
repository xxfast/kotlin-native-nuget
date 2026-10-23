package io.github.xxfast.kotlin.native.nuget.test.purrclock

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Duration.Companion.milliseconds

/**
 * ADR-230: the owners with no C# instance scope that carry a receiver slot. An enum body's flow
 * members take the entry as its ordinal (a C# `this` extension on `ChimeExtensions`), an enum
 * companion's land on the same class, and a value class's are instance methods of the record
 * struct that pass the underlying first.
 *
 * Oreo (black with the white middle) and Mylo (brown and creamy) keep their own time.
 */

/** Which chime a clock plays. */
enum class Chime(val notes: Int) {
  WESTMINSTER(4),
  WHITTINGTON(2),
  CUCKOO(1);

  /** [notes] notes, numbered from one. */
  fun play(): Flow<Int> = flow { repeat(notes) { emit(it + 1) } }

  /** The entry's own `T`, once per note. */
  fun <T> hum(value: T): Flow<T> = flow { repeat(notes) { emit(value) } }

  /** The chime played as a `StateFlow` of its name. */
  fun current(): StateFlow<String> = MutableStateFlow(name)

  /** Rings until cancelled. */
  fun ring(): Flow<Int> = flow {
    var n = 0
    while (true) {
      emit(n++)
      delay(1.milliseconds)
    }
  }

  /** Mylo jammed the chime after one note. */
  fun jam(): Flow<Int> = flow {
    emit(1)
    throw IllegalStateException("Mylo jammed the $name chime")
  }

  /** A flow property on the enum body, re-read per collection. */
  val echo: Flow<String> get() = flowOf("$name echoes")

  /** A `suspend` member with a flow result on an enum body. */
  suspend fun prelude(): Flow<Int> {
    delay(1.milliseconds)
    return flowOf(notes * 10)
  }

  companion object {
    private val loudestState: MutableStateFlow<Chime> = MutableStateFlow(WESTMINSTER)

    /** Every chime in order, from the enum companion. */
    fun all(): Flow<Chime> = flowOf(*entries.toTypedArray())

    /** A held `MutableStateFlow` return on an enum companion. */
    fun loudest(): MutableStateFlow<Chime> = loudestState

    /** A cold flow property on an enum companion. */
    val sampler: Flow<Int> = flowOf(4, 2, 1)
  }
}

/** A pendulum swing, in milliseconds. */
value class Pendulum(val millis: Int) {
  /** [count] swings, each [millis] apart on paper. */
  fun beats(count: Int): Flow<Int> = flow { repeat(count) { emit(millis * (it + 1)) } }

  /** The value class's own `T`, once. */
  fun <T> carry(value: T): Flow<T> = flowOf(value)

  /** A flow property on a value class, re-read per collection. */
  val pulse: Flow<Int> get() = flowOf(millis, millis * 2)

  /** The swing as a `StateFlow`. */
  fun reading(): StateFlow<Int> = MutableStateFlow(millis)

  /** Oreo stopped the pendulum. */
  fun stall(): Flow<Int> = flow {
    emit(millis)
    throw IllegalStateException("Oreo stopped a $millis ms swing")
  }

  /** A `suspend` member with a flow result on a value class. */
  suspend fun warm(): Flow<Int> {
    delay(1.milliseconds)
    return flowOf(millis, millis)
  }
}
