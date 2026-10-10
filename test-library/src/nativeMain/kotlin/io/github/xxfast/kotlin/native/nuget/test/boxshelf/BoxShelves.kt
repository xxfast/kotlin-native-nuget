package io.github.xxfast.kotlin.native.nuget.test.boxshelf

import io.github.xxfast.kotlin.native.nuget.test.cat.Box
import io.github.xxfast.kotlin.native.nuget.test.cat.Cat
import io.github.xxfast.kotlin.native.nuget.test.cat.Mood
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Duration.Companion.milliseconds

/**
 * ADR-208 part D: a closed instantiation of the exported generic class [Box] at every member
 * position. `Box<T>` lives in `test.cat` (`TestLibrary.Cat`) and every use here is in
 * `test.boxshelf` (`TestLibrary.Boxshelf`), so each spelling has to be `global::`-qualified.
 *
 * | Position | Member |
 * | -------- | ------ |
 * | class `val` get | [BoxShelf.label] |
 * | class `var` get and set | [BoxShelf.favourite] |
 * | member return | [BoxShelf.nested], [BoxShelf.moodBox], [BoxShelf.maybe], [BoxShelf.stack] |
 * | member parameter | [BoxShelf.peek], [BoxShelf.peekMaybe] |
 * | constructor parameter | [BoxPlinth] |
 * | companion | [BoxShelf.spare] |
 * | object | [BoxDepot.fresh], [BoxDepot.weigh] |
 * | top-level property, parameter | [topBox], [unbox] |
 * | extension receiver | [doubled] |
 * | suspend result and parameter | [BoxCourier.later], [BoxCourier.laterPeek], [laterBox] |
 * | Flow / StateFlow element | [BoxCourier.stream], [BoxCourier.latest] |
 *
 * Every body reads *through* the box it was handed (`box.value`, `box.value.name`), so a pointer
 * that crossed as the wrong instantiation, or as a fresh default, cannot answer.
 *
 * Oreo's shelf holds one labelled box and one box with Mylo in it. Mylo does not mind.
 */
class BoxShelf {
  val label: Box<String> = Box("Oreo")

  var favourite: Box<Cat> = Box(Cat("Mylo"))

  fun peek(box: Box<String>): String = box.value

  fun peekMaybe(box: Box<String>?): String = box?.value ?: "none"

  fun maybe(present: Boolean): Box<String>? = if (present) Box("here") else null

  fun nested(): Box<Box<Int>> = Box(Box(7))

  fun moodBox(): Box<Mood> = Box(Mood.GRUMPY)

  fun stack(): List<Box<String>> = listOf(Box("top"), Box("bottom"))

  companion object {
    fun spare(): Box<Int> = Box(9)
  }
}

/** A constructor parameter: the plinth reads the box it was built on. */
class BoxPlinth(box: Box<String>) {
  val engraving: String = "plinth of ${box.value}"
}

object BoxDepot {
  fun fresh(): Box<String> = Box("fresh")

  fun weigh(box: Box<Cat>): Int = box.value.name.length
}

val topBox: Box<String> = Box("top shelf")

fun unbox(box: Box<Int>): Int = box.value

val Box<Int>.doubled: Int get() = value * 2

/**
 * The suspend and Flow routes: a closed instantiation is an awaited result, a borrowed parameter
 * and a collected element there too.
 */
class BoxCourier {
  private val shown: MutableStateFlow<Box<Int>> = MutableStateFlow(Box(1))

  suspend fun later(): Box<String> {
    delay(1.milliseconds)
    return Box("later")
  }

  suspend fun laterPeek(box: Box<String>): String {
    delay(1.milliseconds)
    return "peeked ${box.value}"
  }

  val stream: Flow<Box<String>> = flow {
    emit(Box("first"))
    emit(Box("second"))
  }

  val latest: StateFlow<Box<Int>> get() = shown
}

suspend fun laterBox(label: String): Box<String> {
  delay(1.milliseconds)
  return Box(label)
}

/**
 * ADR-208 part E: a `Flow` / `StateFlow` as the type argument. `box.Value` is a `KotlinFlow<E>` /
 * `KotlinStateFlow<E>` built from the flow's own handle, collected through one generated export
 * per closed flow instantiation, on a scope the holder owns.
 *
 * | Argument | No conversion | Converted element |
 * | -------- | ------------- | ----------------- |
 * | `Flow` | [BoxRadio.ticks] (`Int`), [boxedTicks] | [BoxRadio.moods] (`Mood`, by ordinal) |
 * | `StateFlow` | [BoxRadio.volume] (`Int`) | [BoxRadio.mood] (`Mood`) |
 *
 * [BoxRadio.endless] never completes, for the cells that dispose things mid-collection, and
 * [BoxRadio.warmUp] gives the radio a scope of its own, so its `DisposeAsync` has something to
 * drain. [BoxRadio.dial] is a declared `MutableStateFlow`, bound read-only, and
 * [BoxRadio.jingles] a `SharedFlow`, bound as the plain `KotlinFlow`.
 *
 * Oreo's radio plays three ticks and stops. Mylo's never stops, so somebody has to switch it off.
 */
class BoxRadio {
  private val level: MutableStateFlow<Int> = MutableStateFlow(3)
  private val temper: MutableStateFlow<Mood> = MutableStateFlow(Mood.SLEEPY)
  private val announcements: MutableSharedFlow<String> =
    MutableSharedFlow<String>(replay = 1).also { flow -> flow.tryEmit("dinner") }

  fun ticks(): Box<Flow<Int>> = Box(flowOf(1, 2, 3))

  fun moods(): Box<Flow<Mood>> = Box(flowOf(Mood.SLEEPY, Mood.GRUMPY))

  fun whispers(): Box<Flow<String?>> = Box(flowOf("psst", null))

  val volume: Box<StateFlow<Int>> get() = Box(level)

  fun mood(): Box<StateFlow<Mood>> = Box(temper)

  fun dial(): Box<MutableStateFlow<Int>> = Box(level)

  fun jingles(): Box<SharedFlow<String>> = Box(announcements)

  fun silence(): Box<Flow<Int>?> = Box(null)

  fun nestedTicks(): Box<Box<Flow<Int>>> = Box(Box(flowOf(4)))

  fun endless(): Box<Flow<Int>> = Box(
    flow {
      emit(1)
      awaitCancellation()
    },
  )

  fun turnUp() {
    level.value += 1
  }

  fun sulk() {
    temper.value = Mood.GRUMPY
  }

  suspend fun warmUp(): Int {
    delay(1.milliseconds)
    return level.value
  }
}

fun boxedTicks(): Box<Flow<Int>> = Box(flowOf(7, 8))
