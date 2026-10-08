package io.github.xxfast.kotlin.native.nuget.test.cat

import kotlin.concurrent.AtomicInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * ADR-207: a `Flow` collected from C# is credit-gated, so a slow C# reader stalls the Kotlin
 * producer instead of letting it run ahead into an unbounded C# buffer.
 *
 * [emitted] counts emits that RETURNED: the increment follows `emit`, and the body parks inside
 * the next `emit`, before its increment. So after the reader has taken one item and gone quiet the
 * count reads 2 (item 0 handed out, item 1 waiting on the C# side), not 100.
 *
 * One element that needs no conversion ([belt]) and one that needs conversion plus a release path
 * ([crates], row 16l's `_release`), and a flow that fails after a stall ([spoiled]).
 *
 * Oreo works the treat conveyor, and the next treat waits until he has eaten the last one.
 */
class TreatConveyor {
  private val count: AtomicInt = AtomicInt(0)

  val emitted: Int get() = count.value

  val belt: Flow<Int> = flow {
    repeat(100) {
      emit(it)
      count.incrementAndGet()
    }
  }

  val crates: Flow<List<String>> = flow {
    repeat(100) {
      emit(listOf("treat $it"))
      count.incrementAndGet()
    }
  }

  val spoiled: Flow<Int> = flow {
    emit(1)
    emit(2)
    emit(3)
    throw IllegalStateException("conveyor jammed")
  }
}
