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
  /**
   * ADR-064 amendment (issue #249): the skip's owner, member name and raw reason sentence, kept
   * unformatted so `CirFile.withSkipRemarks` can render the consumer-facing `<remarks>` paragraph
   * from the same list this file is written from. None of the three is serialized below:
   * `NugetReportDiagnosticsTask` parses four fields, and a fifth is additive later if a consumer
   * ever needs it.
   */
  val owner: ForwardDiagnosticOwner? = null,
  val member: String? = null,
  val reason: String = "",
  /**
   * ADR-162 (ROADMAP line 58): the `KSNode`'s own source location, split into its two parts.
   *
   * [message] already ends with `\n    at <path>:<line>`, so this is not new information; it is the
   * same information in a shape a consumer can use. `NugetReportDiagnosticsTask` composes its
   * console line as `<file>:<line>: <message>`, the kotlinc/KSP shape an IDE build window
   * linkifies, which the trailing suffix inside [message] is not. Serialized only when present: a
   * scope-level diagnostic (`SKIPPED_ALL_DECLARATIONS`) has no declaration to point at.
   */
  val file: String? = null,
  val line: Int? = null,
)

/**
 * ADR-100: the JSON array the KSP round writes and `NugetReportDiagnosticsTask` reads back.
 *
 * Hand-rolled rather than kotlinx.serialization, following the `bound-types.json` precedent
 * ([parseBoundTypesManifest]): the processor has no JSON dependency and adding one to every
 * consumer's KSP classpath to write a flat array of at most six string fields is a poor trade.
 *
 * Every value is a JSON **string**, including ADR-162's `line`. That is a contract with the reader
 * (`NugetReportDiagnosticsTask.parseForwardDiagnostics`), which finds fields by walking quoted
 * tokens and pairing them key, value: one bare number would shift every following key onto the
 * wrong value. A new field must be a string, or the reader has to learn about types first.
 */
internal fun renderForwardDiagnosticsJson(records: List<ForwardDiagnosticRecord>): String {
  val entries: String = records.joinToString(",\n") { record ->
    // ADR-162: `file`/`line` are additive and OMITTED when absent rather than written as `null`.
    // The reader requires every field it names, so an absent-means-absent encoding keeps a
    // location-less diagnostic (a scope-level one) readable by both the old and the new parser.
    val location: String =
      if (record.file != null && record.line != null) {
        // `line` is written as a JSON *string*, deliberately. The reader on the other side
        // (`parseForwardDiagnostics`) finds fields by walking quoted tokens and pairing them
        // key, value, which is sound only while every value this writer emits is a string; a bare
        // number would shift every following key onto the wrong value.
        "\n    \"file\": ${record.file.jsonString()}," +
            "\n    \"line\": ${record.line.toString().jsonString()},"
      } else {
        ""
      }
    """
    |  {
    |    "severity": "${record.severity.name}",
    |    "kind": "${record.kind.name}",
    |    "declaration": ${record.declaration.jsonString()},$location
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
