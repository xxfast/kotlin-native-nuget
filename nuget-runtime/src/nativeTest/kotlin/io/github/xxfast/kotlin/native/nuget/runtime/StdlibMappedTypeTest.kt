@file:OptIn(NugetRuntimeApi::class)
@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package io.github.xxfast.kotlin.native.nuget.runtime

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * ADR-177: the stdlib rows are matched with `is`, most specific first. Two Kotlin/Native
 * hierarchy facts make the order load-bearing: `NumberFormatException : IllegalArgumentException`
 * and `CancellationException : IllegalStateException`.
 */
class StdlibMappedTypeTest {

  private class GrumbleException : IllegalStateException()

  private class Unmapped(message: String) : Exception(message)

  @Test
  fun `number format wins over illegal argument`() {
    assertEquals("kotlin.NumberFormatException", nugetStdlibMappedType(NumberFormatException("x")))
  }

  @Test
  fun `cancellation wins over illegal state`() {
    assertEquals(
      "kotlin.coroutines.cancellation.CancellationException",
      nugetStdlibMappedType(CancellationException("c")),
    )
  }

  @Test
  fun `a subclass of a row maps to that row`() {
    assertEquals("kotlin.IllegalStateException", nugetStdlibMappedType(GrumbleException()))
  }

  @Test
  fun `every remaining stdlib row matches its own type`() {
    assertEquals(
      "kotlin.IllegalArgumentException",
      nugetStdlibMappedType(IllegalArgumentException()),
    )
    assertEquals("kotlin.NoSuchElementException", nugetStdlibMappedType(NoSuchElementException()))
    assertEquals(
      "kotlin.ConcurrentModificationException",
      nugetStdlibMappedType(ConcurrentModificationException()),
    )
    assertEquals(
      "kotlin.UnsupportedOperationException",
      nugetStdlibMappedType(UnsupportedOperationException()),
    )
    assertEquals("kotlin.ClassCastException", nugetStdlibMappedType(ClassCastException()))
    assertEquals("kotlin.ArithmeticException", nugetStdlibMappedType(ArithmeticException()))
    assertEquals("kotlin.NullPointerException", nugetStdlibMappedType(NullPointerException()))
  }

  @Test
  fun `no when branch matched is matched by name`() {
    assertEquals(
      "kotlin.NoWhenBranchMatchedException",
      nugetStdlibMappedType(NoWhenBranchMatchedException()),
    )
  }

  @Test
  fun `an unmapped exception has no row`() {
    assertNull(nugetStdlibMappedType(Unmapped("x")))
  }

  @Test
  fun `buildError classifies every node and names the type when the message is null`() {
    val error: NugetError = buildError(
      IllegalStateException("outer", NullPointerException()),
      ::nugetStdlibMappedType,
    )
    assertEquals("kotlin.IllegalStateException", error.mappedType)
    assertEquals("outer", error.message)
    val cause: NugetError = error.cause!!
    assertEquals("kotlin.NullPointerException", cause.mappedType)
    assertEquals("kotlin.NullPointerException", cause.message)
  }
}
