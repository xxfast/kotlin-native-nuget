package io.github.xxfast.kotlin.native.nuget.test.boxshelf

import io.github.xxfast.kotlin.native.nuget.test.cat.Box
import io.github.xxfast.kotlin.native.nuget.test.cat.Cat
import io.github.xxfast.kotlin.native.nuget.test.cat.Mood
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
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
