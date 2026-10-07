package io.github.xxfast.kotlin.native.nuget

import org.opentest4j.TestAbortedException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The `dotnet` gate every SDK-dependent plugin test goes through. A missing SDK used to `return`
 * from the test, which JUnit reports as passed, so a CI leg without `dotnet` looked green while
 * running none of them.
 */
class DotnetForTestTest {
  @Test
  fun `returns the dotnet command when the SDK is found`() {
    assertEquals("dotnet", dotnetForTest(dotnet = "dotnet", ci = "true"))
  }

  @Test
  fun `fails under CI when the SDK is missing`() {
    val error: AssertionError =
      assertFailsWith<AssertionError> { dotnetForTest(dotnet = null, ci = "true") }

    assertContains(error.message.orEmpty(), "dotnet")
    assertContains(error.message.orEmpty(), "CI")
  }

  @Test
  fun `is reported as skipped locally when the SDK is missing`() {
    assertFailsWith<TestAbortedException> { dotnetForTest(dotnet = null, ci = null) }
  }
}
