package io.github.xxfast.kotlin.native.nuget.rir

import io.github.xxfast.kotlin.native.nuget.formatDiagnostic
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ADR-182 section 3: one casing and one prefix set for diagnostic codes across both directions,
 * and a `schemaVersion` on `reverse-ir.json`.
 */
class RirDiagnosticKindTest {
  private val scheme = Regex("^(SKIPPED|WARNING|INFO|ERROR)_[A-Z0-9_]+$")

  @Test
  fun `every reverse code is SCREAMING_SNAKE with a severity prefix`() {
    RirDiagnosticKind.entries.forEach { kind ->
      assertTrue(scheme.matches(kind.name), kind.name)
      // No lowercase @SerialName left: the wire code IS the enum name.
      assertEquals("\"${kind.name}\"", Json.encodeToString(kind))
    }
  }

  @Test
  fun `severity and verb derive from the prefix`() {
    RirDiagnosticKind.entries.forEach { kind ->
      val (severity: RirDiagnosticSeverity, verb: String?) = when {
        kind.name.startsWith("SKIPPED_") -> RirDiagnosticSeverity.WARNING to "Skipping"
        kind.name.startsWith("INFO_") -> RirDiagnosticSeverity.INFO to "Note"
        kind.name.startsWith("ERROR_") -> RirDiagnosticSeverity.ERROR to "Error"
        else -> RirDiagnosticSeverity.WARNING to null
      }
      assertEquals(severity, kind.severity, kind.name)
      if (verb != null) assertEquals(verb, kind.verb, kind.name)
    }
  }

  @Test
  fun `the reverse console line has forward's shape at every scope`() {
    fun diagnostic(kind: RirDiagnosticKind, type: String, member: String, signature: String) =
      RirDiagnostic(kind, type, member, signature, reason = "why", hint = "Do this.")

    assertEquals(
      "[nuget:SKIPPED_REF_STRUCT] Skipping Acme.Api/Widget.Span(Span<byte>): why. Do this.",
      formatDiagnostic(
        "Acme.Api", diagnostic(RirDiagnosticKind.SKIPPED_REF_STRUCT, "Widget", "Span", "Span<byte>"),
      ),
    )
    assertEquals(
      "[nuget:SKIPPED_EMPTY_INTERFACE] Skipping Acme.Api/IEmpty: why. Do this.",
      formatDiagnostic(
        "Acme.Api", diagnostic(RirDiagnosticKind.SKIPPED_EMPTY_INTERFACE, "IEmpty", "", ""),
      ),
    )
    assertEquals(
      "[nuget:INFO_OBLIVIOUS_NULLABILITY] Note Acme.Api: why. Do this.",
      formatDiagnostic(
        "Acme.Api", diagnostic(RirDiagnosticKind.INFO_OBLIVIOUS_NULLABILITY, "", "", ""),
      ),
    )
    assertEquals(
      "[nuget:ERROR_KOTLIN_SIGNATURE_COLLISION] Error Acme.Api/Widget.Run(): why. Do this.",
      formatDiagnostic(
        "Acme.Api", diagnostic(RirDiagnosticKind.ERROR_KOTLIN_SIGNATURE_COLLISION, "Widget", "Run", ""),
      ),
    )
  }

  /** The reader's real `SKIPPED_ARRAY` reason already ends its sentence; no `buffer.. Expose`. */
  @Test
  fun `the console line does not double a reason's own closing period`() {
    val reason =
      "`string[]`: arrays are deferred (ADR-155). The Kotlin type for an array is its own " +
          "decision, and `byte[]` wants the ADR-151 blit rather than a slot buffer."
    val hint = "Expose IReadOnlyList<T> (or another mapped BCL collection) instead of an array."
    val array = RirDiagnostic(
      RirDiagnosticKind.SKIPPED_ARRAY, "MimeUtility", "GetExtensions", "GetExtensions(string)",
      reason = reason, hint = hint,
    )

    assertEquals(
      "[nuget:SKIPPED_ARRAY] Skipping MimeMapping/MimeUtility.GetExtensions(GetExtensions(string)): " +
          "$reason $hint",
      formatDiagnostic("MimeMapping", array),
    )
    assertEquals(
      "[nuget:SKIPPED_ARRAY] Skipping MimeMapping/MimeUtility.GetExtensions(GetExtensions(string)): " +
          "no wire. $hint",
      formatDiagnostic("MimeMapping", array.copy(reason = "no wire")),
    )
  }

  @Test
  fun `the reader's uppercase code deserializes`() {
    val parsed: RirFile = parseReverseIr(
      """
      { "schemaVersion": 1, "assemblies": [ { "packageId": "P", "assemblyName": "P",
        "namespaces": [], "diagnostics": [ { "kind": "SKIPPED_REF_STRUCT", "typeName": "T",
        "memberName": "", "memberSignature": "", "reason": "r", "hint": "h" } ] } ] }
      """.trimIndent()
    )
    assertEquals(1, parsed.schemaVersion)
    assertEquals(RirDiagnosticKind.SKIPPED_REF_STRUCT, parsed.assemblies.single().diagnostics.single().kind)
  }

  @Test
  fun `a lowercase pre-0_9 code no longer deserializes`() {
    assertFailsWith<IllegalArgumentException> {
      parseReverseIr(
        """
        { "assemblies": [ { "packageId": "P", "assemblyName": "P", "namespaces": [],
          "diagnostics": [ { "kind": "skipped_ref_struct", "typeName": "T", "memberName": "",
          "memberSignature": "", "reason": "r", "hint": "h" } ] } ] }
        """.trimIndent()
      )
    }
  }

  @Test
  fun `a reverse-ir json at the current schemaVersion passes the task check`() {
    val file = RirFile(assemblies = emptyList(), schemaVersion = REVERSE_IR_SCHEMA_VERSION)
    assertEquals(file, file.requireCurrentSchema("build/nuget-interop/reverse-ir.json"))
  }

  @Test
  fun `a reverse-ir json with no schemaVersion fails naming the file and the fix`() {
    val error = assertFailsWith<IllegalArgumentException> {
      RirFile(assemblies = emptyList()).requireCurrentSchema("build/nuget-interop/reverse-ir.json")
    }
    assertContains(error.message!!, "build/nuget-interop/reverse-ir.json")
    assertContains(error.message!!, "schemaVersion")
    assertContains(error.message!!, "nugetExtractApi")
  }

  @Test
  fun `a reverse-ir json from a newer reader fails with the version it found`() {
    val error = assertFailsWith<IllegalArgumentException> {
      RirFile(assemblies = emptyList(), schemaVersion = 2).requireCurrentSchema("reverse-ir.json")
    }
    assertContains(error.message!!, "schemaVersion 2")
    assertContains(error.message!!, "nugetExtractApi")
  }

  /**
   * Codes stay unique across directions and carry no direction marker, so a code alone says which
   * side produced it. No module sees both enums, so the processor's source is read as text.
   */
  @Test
  fun `no diagnostic code is shared between the forward and reverse directions`() {
    val source = File(
      requireNotNull(System.getProperty("nuget.forwardDiagnosticSource")) {
        "nuget.forwardDiagnosticSource is not set; run through Gradle's test task"
      },
    )
    assertTrue(source.isFile, "forward diagnostic source not found at $source")
    val enumBody: String = source.readText()
      .substringAfter("internal enum class ForwardDiagnosticKind(")
      .substringAfter(") {")
    val forward: Set<String> = Regex("""^\s{2}((?:SKIPPED|WARNING|INFO|ERROR)_[A-Z0-9_]+)\s*[(,;]""", RegexOption.MULTILINE)
      .findAll(enumBody)
      .map { it.groupValues[1] }
      .toSet()

    // Guard against a vacuous pass: a regex that matches nothing would make every set disjoint.
    listOf("SKIPPED_", "WARNING_", "INFO_", "ERROR_").forEach { prefix ->
      assertTrue(forward.any { it.startsWith(prefix) }, "no forward $prefix code extracted: $forward")
    }
    assertTrue(forward.size >= 30, "only ${forward.size} forward codes extracted: $forward")

    val shared: Set<String> = forward intersect RirDiagnosticKind.entries.map { it.name }.toSet()
    assertTrue(shared.isEmpty(), "codes shared across directions: $shared")
  }
}
