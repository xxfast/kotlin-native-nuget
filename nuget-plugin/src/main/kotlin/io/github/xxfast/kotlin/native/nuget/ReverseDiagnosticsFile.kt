package io.github.xxfast.kotlin.native.nuget

import io.github.xxfast.kotlin.native.nuget.rir.RirDiagnostic
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * ADR-182 amendment: one entry of the reverse `NugetDiagnostics.json`. The four leading fields are
 * the forward entry's, so `parseForwardDiagnostics` reads this file unchanged; the rest are the
 * `RirDiagnostic` fields under their `reverse-ir.json` names, omitted when empty. Experimental with
 * the reverse direction (ADR-181).
 */
@Serializable
internal data class ReverseDiagnosticEntry(
  val severity: String,
  val kind: String,
  val declaration: String,
  val message: String,
  val packageId: String? = null,
  val typeName: String? = null,
  val memberName: String? = null,
  val memberSignature: String? = null,
)

@Serializable
private data class ReverseDiagnosticsFile(
  val schemaVersion: Int,
  val diagnostics: List<ReverseDiagnosticEntry>,
)

// Defaults are not encoded, so an empty optional field (a null here) never reaches the file.
private val reverseDiagnosticsJson: Json = Json { prettyPrint = true }

/** The report for [diagnostics] (package id to diagnostic), in the order given. */
internal fun reverseDiagnosticsJson(diagnostics: List<Pair<String, RirDiagnostic>>): String {
  val entries: List<ReverseDiagnosticEntry> = diagnostics.map { (packageId, diagnostic) ->
    ReverseDiagnosticEntry(
      severity = diagnostic.kind.severity.name,
      kind = diagnostic.kind.name,
      declaration = diagnosticLocation(packageId, diagnostic),
      message = formatDiagnostic(packageId, diagnostic),
      packageId = packageId.ifEmpty { null },
      typeName = diagnostic.typeName.ifEmpty { null },
      memberName = diagnostic.memberName.ifEmpty { null },
      memberSignature = diagnostic.memberSignature.ifEmpty { null },
    )
  }
  val file = ReverseDiagnosticsFile(FORWARD_DIAGNOSTICS_SCHEMA_VERSION, entries)
  return reverseDiagnosticsJson.encodeToString(ReverseDiagnosticsFile.serializer(), file) + "\n"
}
