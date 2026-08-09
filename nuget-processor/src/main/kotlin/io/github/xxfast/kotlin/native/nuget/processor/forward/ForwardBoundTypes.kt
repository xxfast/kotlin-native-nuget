package io.github.xxfast.kotlin.native.nuget.processor.forward

/**
 * ADR-088: one bound C# interface as the plugin's `bound-types.json` manifest describes it.
 *
 * @param kotlinName the ADR-070 pure stub's Kotlin qualified name, which is what a `KSType`
 *   resolves to at a forward position.
 * @param csharpName the ORIGINAL C# full name (`Test.Menagerie.IFeedable`), unqualified by
 *   `global::` (the classifier adds that).
 * @param implementable ADR-085 admissibility: a `mint{Iface}Bridge` exists.
 */
data class ForwardBoundInterface(
  val kotlinName: String,
  val csharpName: String,
  val implementable: Boolean,
)

/**
 * ADR-088: reads the plugin-written manifest.
 *
 * Hand-rolled rather than kotlinx.serialization: the KSP processor has no JSON dependency (see
 * `nuget-processor/build.gradle.kts`), and adding one to every consumer's KSP classpath to read a
 * three-field flat array is a poor trade. The writer is
 * `NugetGenerateBindingsTask.boundTypesManifest`, in this same repository, so the shape is fixed:
 * one flat `interfaces` array of objects with exactly `kotlinName`, `csharpName`, `implementable`.
 * Anything else fails fast with the offending text rather than silently classifying nothing (a
 * silently empty manifest would degrade into `SKIPPED_UNSUPPORTED_TYPE` for every bound interface,
 * which is precisely the pre-ADR-088 bug this feature removes).
 */
fun parseBoundTypesManifest(json: String): List<ForwardBoundInterface> {
  if (json.isBlank()) return emptyList()
  val open: Int = json.indexOf('[', startIndex = json.indexOf("\"interfaces\""))
  val close: Int = json.lastIndexOf(']')
  require(open > 0 && close > open) {
    "[nuget] bound-types.json has no `interfaces` array: $json"
  }
  return OBJECT.findAll(json.substring(open + 1, close)).map { match ->
    val body: String = match.groupValues[1]
    ForwardBoundInterface(
      kotlinName = body.stringField("kotlinName", match.value),
      csharpName = body.stringField("csharpName", match.value),
      implementable = body.booleanField("implementable", match.value),
    )
  }.toList()
}

private val OBJECT = Regex("""\{([^{}]*)}""")

private fun String.stringField(name: String, context: String): String = requireNotNull(
  Regex(""""$name"\s*:\s*"([^"]*)"""").find(this)?.groupValues?.get(1)
) {
  "[nuget] bound-types.json entry is missing a string `$name` field: $context"
}

private fun String.booleanField(name: String, context: String): Boolean = requireNotNull(
  Regex(""""$name"\s*:\s*(true|false)""").find(this)?.groupValues?.get(1)?.toBooleanStrictOrNull()
) {
  "[nuget] bound-types.json entry is missing a boolean `$name` field: $context"
}
