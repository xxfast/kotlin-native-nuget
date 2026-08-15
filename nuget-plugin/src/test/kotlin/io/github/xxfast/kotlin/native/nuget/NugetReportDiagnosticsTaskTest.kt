package io.github.xxfast.kotlin.native.nuget

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ADR-100: the reader half of the forward diagnostic channel. The load-bearing test for the feature
 * is `scripts/verify-forward-diagnostics.sh` (a real build, run twice); this one only pins the file
 * format against the processor's writer, in particular the two things the `bound-types.json`
 * regex could not handle: braces inside a rendered hint, and the escaped newline of the source
 * location suffix.
 */
class NugetReportDiagnosticsTaskTest {
  @Test
  fun `parses a rendered diagnostic verbatim`() {
    val entries: List<ForwardDiagnosticEntry> = parseForwardDiagnostics(
      """
      [
        {
          "severity": "WARNING",
          "kind": "SKIPPED_INHERITED_MEMBER",
          "declaration": "com.example.Tag.length",
          "message": "[nuget:SKIPPED_INHERITED_MEMBER] Skipping com.example.Tag.length: reason. hint\n    at /src/Tag.kt:12"
        }
      ]
      """.trimIndent()
    )

    assertEquals(1, entries.size)
    assertEquals("WARNING", entries[0].severity)
    assertEquals("SKIPPED_INHERITED_MEMBER", entries[0].kind)
    assertEquals("com.example.Tag.length", entries[0].declaration)
    assertEquals(
      "[nuget:SKIPPED_INHERITED_MEMBER] Skipping com.example.Tag.length: reason. hint\n    at /src/Tag.kt:12",
      entries[0].message,
    )
  }

  @Test
  fun `keeps braces and quotes inside a message`() {
    val entries: List<ForwardDiagnosticEntry> = parseForwardDiagnostics(
      """
      [
        {
          "severity": "WARNING",
          "kind": "SKIPPED_UNEXPORTED_DEPENDENCY_TYPE",
          "declaration": "com.example.Api.use",
          "message": "add include(\"com.dep\") to nuget { publish { } }"
        },
        {
          "severity": "INFO",
          "kind": "INFO_DROPPED_VARIANCE",
          "declaration": "com.example.Box",
          "message": "Note com.example.Box: variance dropped"
        }
      ]
      """.trimIndent()
    )

    assertEquals(2, entries.size)
    assertEquals("""add include("com.dep") to nuget { publish { } }""", entries[0].message)
    assertEquals("INFO", entries[1].severity)
  }

  @Test
  fun `decodes every escape the writer can emit`() {
    val entries: List<ForwardDiagnosticEntry> = parseForwardDiagnostics(
      """[{"severity":"WARNING","kind":"K","declaration":"d","message":"a\tb\r\nA\\"}]"""
    )
    assertEquals("a\tb\r\nA\\", entries[0].message)
  }

  @Test
  fun `an unterminated string fails fast`() {
    val failure = assertFailsWith<IllegalStateException> {
      parseForwardDiagnostics("""[{"severity": "WARNING""")
    }
    assertTrue(failure.message!!.contains("unterminated string"))
  }

  @Test
  fun `an empty array reports nothing`() {
    assertTrue(parseForwardDiagnostics("[]\n").isEmpty())
    assertTrue(parseForwardDiagnostics("").isEmpty())
  }

  @Test
  fun `a non-array file fails fast`() {
    val failure = assertFailsWith<IllegalArgumentException> {
      parseForwardDiagnostics("{\"severity\":\"WARNING\"}", source = "NugetDiagnostics.json")
    }
    assertTrue(failure.message!!.contains("not a JSON array"))
  }

  @Test
  fun `a missing field fails fast naming the file`() {
    val failure = assertFailsWith<IllegalArgumentException> {
      parseForwardDiagnostics("""[{"severity": "WARNING", "kind": "SKIPPED_INHERITED_MEMBER"}]""")
    }
    assertTrue(failure.message!!.contains("`declaration`"))
  }
}
