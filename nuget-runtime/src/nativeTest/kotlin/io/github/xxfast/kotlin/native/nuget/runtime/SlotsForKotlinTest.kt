@file:OptIn(ExperimentalForeignApi::class, NugetRuntimeApi::class)

package io.github.xxfast.kotlin.native.nuget.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set

/**
 * ADR-155: the buffer shape both directions share. Written against the runtime's own helpers
 * rather than a generated stub, because what these pin is the LAYOUT (`[count][slots]`, a null
 * pointer being a null collection, the buffer being released exactly once) and the layout is what
 * the C# side independently agrees to.
 */
class SlotsForKotlinTest {

  @Test
  fun `a written buffer reads back as the same slots`() = memScoped {
    val buffer: COpaquePointer = writeSlotsForKotlin(listOf(9L, -7L, 0L))
    var released = 0

    assertEquals(
      listOf(9L, -7L, 0L),
      readSlotsForKotlin(buffer) { released++ }?.toList(),
    )
    assertEquals(1, released, "the buffer is released exactly once, by the reader")
  }

  @Test
  fun `a null pointer is a null collection and an empty buffer is an empty one`() = memScoped {
    assertNull(
      readSlotsForKotlin(null) { error("[nuget] a null collection has no buffer to release") },
      "IntPtr.Zero is a null collection, never an empty list",
    )

    val empty: COpaquePointer = writeSlotsForKotlin(emptyList())
    assertEquals(0, readSlotsForKotlin(empty) { }?.size)
  }

  // Every slot is 8 bytes whatever the element is, so a double crosses bit-cast rather than
  // widened: 9.5 widened reads as 9, which is also what a correct integral value looks like.
  @Test
  fun `a double survives the slot bit-for-bit`() = memScoped {
    val buffer: COpaquePointer = writeSlotsForKotlin(listOf((9.5).toRawBits(), (-2.25).toRawBits()))
    val slots: LongArray = readSlotsForKotlin(buffer) { }!!

    assertEquals(listOf(9.5, -2.25), slots.map { Double.fromBits(it) })
  }

  // A C# shim that disagreed with this runtime about the layout writes a count that is not a
  // count. Reading n slots off it walks wild memory, so it fails fast instead.
  @Test
  fun `an unreadable count fails fast rather than reading wild memory`() = memScoped {
    val corrupt: CPointer<LongVar> = allocArray(2)
    corrupt[0] = -5L
    corrupt[1] = 1L

    val failure: Throwable = assertFails { readSlotsForKotlin(corrupt) { } }
    assertTrue(
      failure.message?.contains("-5") == true,
      "the failure must name the count it refused, got: ${failure.message}",
    )
  }
}
