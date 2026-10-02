package io.github.xxfast.kotlin.native.nuget.rir

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private val json = Json { ignoreUnknownKeys = true }

internal fun parseReverseIr(jsonString: String): RirFile = json.decodeFromString(jsonString)

/**
 * ADR-182: the `reverse-ir.json` schema this plugin reads; NugetMetadataReader's
 * `RirFile.CurrentSchemaVersion`.
 */
internal const val REVERSE_IR_SCHEMA_VERSION: Int = 1

/**
 * ADR-182: the task-action half of the schema check, kept out of [parseReverseIr] so hand-built
 * test fixtures (which carry no version) still parse. A file with no version predates 0.9.0; a
 * different one came from another plugin release. Either way it was written by a reader this
 * plugin does not ship, so the only fix is to extract it again.
 */
internal fun RirFile.requireCurrentSchema(source: String): RirFile {
  require(schemaVersion == REVERSE_IR_SCHEMA_VERSION) {
    val found: String = schemaVersion?.let { "schemaVersion $it" } ?: "no schemaVersion"
    "[nuget] $source has $found, but this plugin reads schemaVersion $REVERSE_IR_SCHEMA_VERSION. " +
        "It was written by a different plugin release; re-run nugetExtractApi " +
        "(`./gradlew nugetExtractApi --rerun-tasks`) to regenerate it."
  }
  return this
}

// ADR-184: [targetFramework] is the restore TFM, the assets file's `targets` key. No default: a key
// that differs from the restore TFM finds no entry and binds nothing, silently.
internal fun deriveDllPaths(
  assetsJson: String,
  packageIds: Set<String>,
  targetFramework: String,
): Map<String, List<String>> {
  if (packageIds.isEmpty()) return emptyMap()

  val assets: AssetsFile = json.decodeFromString(assetsJson)
  val packagesPath: String = assets.project.restore.packagesPath
  val targets: Map<String, AssetsTarget> = assets.targets[targetFramework] ?: return emptyMap()

  // Case-insensitive lookup from lowercased id → caller-specified id (NuGet IDs are case-insensitive)
  val idLookup: Map<String, String> = packageIds.associateBy { it.lowercase() }

  val result = mutableMapOf<String, MutableList<String>>()

  targets.forEach { (key, target) ->
    val id: String = key.substringBefore("/")
    val matchedId: String = idLookup[id.lowercase()] ?: return@forEach

    val library: AssetsLibrary = requireNotNull(assets.libraries[key]) {
      "[nuget] deriveDllPaths: no libraries entry for '$key' in project.assets.json"
    }
    val libPath: String = library.path
    val paths: MutableList<String> = result.getOrPut(matchedId) { mutableListOf() }

    target.runtime.keys.forEach { dllRelPath ->
      paths.add("$packagesPath/$libPath/$dllRelPath")
    }
  }

  return result
}

// ADR-050 Alternative 5: the .nuspec's <dependencies> entries must pin each bound package at the
// version NuGet actually resolved (read from project.assets.json), not the DSL-declared/floating
// version — the shim's method signatures are frozen against one specific assembly's metadata.
// Mirrors deriveDllPaths(): parses the same `libraries` map, whose keys are "{id}/{version}".
internal fun deriveResolvedVersions(
  assetsJson: String,
  packageIds: Set<String>,
): Map<String, String> {
  if (packageIds.isEmpty()) return emptyMap()

  val assets: AssetsFile = json.decodeFromString(assetsJson)

  // Case-insensitive lookup from lowercased id → caller-specified id (NuGet IDs are
  // case-insensitive)
  val idLookup: Map<String, String> = packageIds.associateBy { it.lowercase() }

  val result = mutableMapOf<String, String>()

  assets.libraries.keys.forEach { key ->
    val id: String = key.substringBefore("/")
    val version: String = key.substringAfter("/", missingDelimiterValue = "")
    val matchedId: String = idLookup[id.lowercase()] ?: return@forEach
    if (version.isEmpty()) return@forEach

    result[matchedId] = version
  }

  return result
}

@Serializable
private data class AssetsFile(
  val targets: Map<String, Map<String, AssetsTarget>> = emptyMap(),
  val libraries: Map<String, AssetsLibrary> = emptyMap(),
  val project: AssetsProject,
)

@Serializable
private data class AssetsTarget(
  val runtime: Map<String, JsonObject> = emptyMap(),
)

@Serializable
private data class AssetsLibrary(
  val path: String,
)

@Serializable
private data class AssetsProject(
  val restore: AssetsRestore,
)

@Serializable
private data class AssetsRestore(
  val packagesPath: String,
)
