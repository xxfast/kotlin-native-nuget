package io.github.xxfast.kotlin.native.nuget.processor.forward

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-084 / ADR-064: the named skip for an interface with no bridge for C# implementations, built
 * from the planner's refusals without KSP, so the rendered line is pinned on its own.
 */
class ForwardBridgeRefusalDiagnosticTest {

  @Test
  fun `the line names the interface, every refused member, and each remedy`() {
    val diagnostic: ForwardDiagnostic = unimplementableInterfaceDiagnostic(
      symbol = null,
      qualifiedName = "tier1.x.Pet",
      csName = "IPet",
      refusals = listOf(
        ForwardBridgeRefusal("var mood: String", "is a `var`", "declare it `val`"),
        ForwardBridgeRefusal("suspend fun fetch", "is a suspend member", "move it to a class"),
      ),
    )

    assertEquals(ForwardDiagnosticKind.SKIPPED_UNIMPLEMENTABLE_INTERFACE, diagnostic.kind)
    val line: String = diagnostic.format()
    assertTrue(
      line.startsWith("[nuget:SKIPPED_UNIMPLEMENTABLE_INTERFACE] Skipping tier1.x.Pet: "),
      line,
    )
    assertTrue("a C# class implementing `IPet` cannot be passed to Kotlin" in line, line)
    assertTrue("`var mood: String` is a `var`" in line, line)
    assertTrue("`suspend fun fetch` is a suspend member" in line, line)
    assertTrue("declare it `val`" in line && "move it to a class" in line, line)
    assertTrue("Kotlin-backed `IPet` values are unaffected" in line, line)
    // Owner-less: no generated `<remarks>` claims a member of `IPet` is absent, because none is.
    assertEquals(null, diagnostic.owner)
  }
}
