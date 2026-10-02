package io.github.xxfast.kotlin.native.nuget.processor.forward

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-109 follow-up: the verb a diagnostic renders with is derived from the kind's name prefix,
 * never from its severity. Before this, a WARNING that forgot to declare a verb rendered as
 * "Skipping" even when nothing was skipped. `ForwardDiagnosticKind`'s own `init` block enforces
 * the rule at class-init time; these tests pin the rule itself, so that relaxing the enforcement
 * fails here rather than quietly going back to lying in a build log.
 */
class ForwardDiagnosticKindTest {
  @Test
  fun `a prefixed kind derives its verb and never overrides it`() {
    val verbs: Map<String, String> = mapOf(
      "SKIPPED_" to "Skipping",
      "INFO_" to "Note",
      "ERROR_" to "Error",
    )

    ForwardDiagnosticKind.entries.forEach { kind ->
      val prefix: String? = verbs.keys.firstOrNull { kind.name.startsWith(it) }
      if (prefix != null) assertEquals(verbs.getValue(prefix), kind.verb, kind.name)
    }
  }

  @Test
  fun `a prefixed kind carries the severity its prefix promises`() {
    val severities: Map<String, ForwardDiagnosticSeverity> = mapOf(
      "SKIPPED_" to ForwardDiagnosticSeverity.WARNING,
      "INFO_" to ForwardDiagnosticSeverity.INFO,
      "ERROR_" to ForwardDiagnosticSeverity.ERROR,
    )

    ForwardDiagnosticKind.entries.forEach { kind ->
      val prefix: String? = severities.keys.firstOrNull { kind.name.startsWith(it) }
      if (prefix != null) assertEquals(severities.getValue(prefix), kind.severity, kind.name)
    }
  }

  @Test
  fun `an unprefixed kind declares a verb that does not claim a skip`() {
    val unprefixed: List<ForwardDiagnosticKind> = ForwardDiagnosticKind.entries
      .filterNot { it.name.startsWith("SKIPPED_") }
      .filterNot { it.name.startsWith("INFO_") }
      .filterNot { it.name.startsWith("ERROR_") }

    assertTrue(
      unprefixed.isNotEmpty(),
      "expected at least ADR-109's WARNING_DUPLICATED_DEPENDENCY_TYPE",
    )
    unprefixed.forEach { kind ->
      assertTrue(kind.verb.isNotBlank(), kind.name)
      assertTrue(
        kind.verb !in listOf("Skipping", "Note", "Error"),
        "${kind.name} reads ${kind.verb}",
      )
    }
  }

  @Test
  fun `format opens with the kind's own verb, not one keyed on severity`() {
    val skipped = ForwardDiagnostic(
      kind = ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_TYPE,
      symbol = null,
      declaration = "Sample.thing",
      reason = "reason",
      hint = "hint.",
      owner = null,
    )
    val warned = skipped.copy(kind = ForwardDiagnosticKind.WARNING_DUPLICATED_DEPENDENCY_TYPE)

    assertTrue(
      skipped.format().startsWith("[nuget:SKIPPED_UNSUPPORTED_TYPE] Skipping Sample.thing"),
    )
    assertTrue(
      warned.format()
        .startsWith("[nuget:WARNING_DUPLICATED_DEPENDENCY_TYPE] Duplicating Sample.thing"),
      warned.format(),
    )
  }

  /**
   * A reason that already ends its sentence (NugetMetadataReader's real `SKIPPED_ARRAY` text, here
   * as a forward reason) must not print `buffer.. Expose`; a bare reason still gets its period.
   */
  @Test
  fun `format does not double a reason's own closing period`() {
    val reason =
      "`string[]`: arrays are deferred (ADR-155). The Kotlin type for an array is its own " +
          "decision, and `byte[]` wants the ADR-151 blit rather than a slot buffer."
    val hint = "Expose IReadOnlyList<T> (or another mapped BCL collection) instead of an array."
    val diagnostic = ForwardDiagnostic(
      kind = ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_TYPE,
      symbol = null,
      declaration = "Sample.thing",
      reason = reason,
      hint = hint,
      owner = null,
    )

    assertEquals(
      "[nuget:SKIPPED_UNSUPPORTED_TYPE] Skipping Sample.thing: $reason $hint",
      diagnostic.format(),
    )
    assertEquals(
      "[nuget:SKIPPED_UNSUPPORTED_TYPE] Skipping Sample.thing: no wire. $hint",
      diagnostic.copy(reason = "no wire").format(),
    )
  }
}
