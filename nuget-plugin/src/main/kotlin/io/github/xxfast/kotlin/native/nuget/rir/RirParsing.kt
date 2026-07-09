package io.github.xxfast.kotlin.native.nuget.rir

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private val json = Json { ignoreUnknownKeys = true }

fun parseReverseIr(jsonString: String): RirFile = json.decodeFromString(jsonString)

fun deriveDllPaths(assetsJson: String, packageIds: Set<String>): Map<String, List<String>> {
  if (packageIds.isEmpty()) return emptyMap()

  val assets: AssetsFile = json.decodeFromString(assetsJson)
  val packagesPath: String = assets.project.restore.packagesPath
  val targets: Map<String, AssetsTarget> = assets.targets["net8.0"] ?: return emptyMap()

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
fun deriveResolvedVersions(assetsJson: String, packageIds: Set<String>): Map<String, String> {
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
