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

  /**
   * ADR-162 (ROADMAP line 58): the re-emitted console line LEADS with `<path>:<line>: `, the
   * kotlinc/KSP shape a build window linkifies. Composed here and not inside the processor's
   * `ForwardDiagnostic.format()`: KSP's own Gradle logger already prefixes that location to the
   * `w: [ksp] ...` line, so a leading location in `format()` would print it twice there.
   */
  @Test
  fun `a located entry composes a leading path and line`() {
    val entry: ForwardDiagnosticEntry = parseForwardDiagnostics(
      """
      [
        {
          "severity": "WARNING",
          "kind": "SKIPPED_INHERITED_MEMBER",
          "declaration": "com.example.Tag.length",
          "file": "C:/src/Tag.kt",
          "line": "12",
          "message": "[nuget:SKIPPED_INHERITED_MEMBER] Skipping com.example.Tag.length: reason. hint\n    at C:/src/Tag.kt:12"
        }
      ]
      """.trimIndent()
    ).single()

    assertEquals("C:/src/Tag.kt", entry.file)
    assertEquals("12", entry.line)
    assertTrue(
      entry.consoleLine().startsWith("C:/src/Tag.kt:12: [nuget:SKIPPED_INHERITED_MEMBER]"),
      "consoleLine=${entry.consoleLine()}",
    )
    // The message body is still the processor's verbatim string: one renderer, no drift.
    assertTrue(entry.consoleLine().endsWith(entry.message))
  }

  /**
   * The two fields are additive, so a `NugetDiagnostics.json` written by an older processor (and a
   * scope-level diagnostic, which has no declaration to point at) must still parse and print.
   */
  @Test
  fun `an entry with no location prints the message alone`() {
    val entry: ForwardDiagnosticEntry = parseForwardDiagnostics(
      """
      [
        {
          "severity": "WARNING",
          "kind": "SKIPPED_ALL_DECLARATIONS",
          "declaration": "com.example",
          "message": "[nuget:SKIPPED_ALL_DECLARATIONS] Skipping com.example: reason. hint"
        }
      ]
      """.trimIndent()
    ).single()

    assertEquals(null, entry.file)
    assertEquals(null, entry.line)
    assertEquals(entry.message, entry.consoleLine())
  }
}
