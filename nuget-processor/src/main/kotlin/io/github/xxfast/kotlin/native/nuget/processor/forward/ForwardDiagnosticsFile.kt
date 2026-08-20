package io.github.xxfast.kotlin.native.nuget.processor.forward

/**
 * ADR-100: one recorded diagnostic as it lands in `NugetDiagnostics.json`.
 *
 * [message] is the exact string [ForwardDiagnostic.format] already produced, source-location suffix
 * included. The Gradle re-emitter prints it verbatim and renders nothing itself: two renderers for
 * one message is a drift bug waiting to happen, and ADR-064's format contract stays in one place.
 * [severity], [kind] and [declaration] are carried for future consumers (an opt-in
 * fail-on-skip, tooling), not because the re-emitter needs them.
 */
internal data class ForwardDiagnosticRecord(
  val severity: ForwardDiagnosticSeverity,
  val kind: ForwardDiagnosticKind,
  val declaration: String,
  val message: String,
)

/**
 * ADR-100: the JSON array the KSP round writes and `NugetReportDiagnosticsTask` reads back.
 *
 * Hand-rolled rather than kotlinx.serialization, following the `bound-types.json` precedent
 * ([parseBoundTypesManifest]): the processor has no JSON dependency and adding one to every
 * consumer's KSP classpath to write a four-field flat array is a poor trade.
 */
internal fun renderForwardDiagnosticsJson(records: List<ForwardDiagnosticRecord>): String {
  val entries: String = records.joinToString(",\n") { record ->
    """
    |  {
    |    "severity": "${record.severity.name}",
    |    "kind": "${record.kind.name}",
    |    "declaration": ${record.declaration.jsonString()},
    |    "message": ${record.message.jsonString()}
    |  }
    """.trimMargin()
  }
  return if (records.isEmpty()) "[]\n" else "[\n$entries\n]\n"
}

private fun String.jsonString(): String {
  val body: String = buildString {
    this@jsonString.forEach { c ->
      when {
        c == '"' -> append("\\\"")
        c == '\\' -> append("\\\\")
        c == '\n' -> append("\\n")
        c == '\r' -> append("\\r")
        c == '\t' -> append("\\t")
        c < ' ' -> append("\\u%04x".format(c.code))
        else -> append(c)
      }
    }
  }
  return "\"$body\""
}
