package io.github.xxfast.kotlin.native.nuget

import java.util.Locale

internal const val INTEROP_CONTRACT_ID: String = "Xxfast.Kotlin.Native.Interop"

// ADR-178: the contract releases in lockstep with the plugin. The floor is this plugin's version and
// the ceiling its next major, so packages built by different plugin versions coalesce on one contract.
internal val INTEROP_CONTRACT_RANGE: String = contractRange(PLUGIN_VERSION)

internal fun contractRange(version: String): String {
  val major: Int = requireNotNull(version.substringBefore('.').toIntOrNull()) {
    "[nuget] Cannot derive the contract range from version '$version'."
  }
  return "[$version,${major + 1}.0.0)"
}

internal fun nativeLibraryStem(id: String): String {
  require(id.length in 1..100 && id.all { it in 'a'..'z' || it in 'A'..'Z' ||
    it in '0'..'9' || it == '.' || it == '-' || it == '_' }) {
    "[nuget] Invalid package id '$id': use 1-100 ASCII letters, digits, '.', '-' or '_'."
  }
  // #469: readable, not injective. Ids differing only by separators share a stem (accepted).
  return "kn_" + id.lowercase(Locale.ROOT).replace('.', '_').replace('-', '_')
}

internal fun nativeLibraryFile(id: String, rid: String): String {
  val stem = nativeLibraryStem(id)
  return if (rid.startsWith("win-")) "$stem.dll"
  else if (rid.startsWith("osx-")) "lib$stem.dylib"
  else if (rid.startsWith("linux-")) "lib$stem.so"
  else error("[nuget] Cannot validate primary native filename for unknown RID '$rid'.")
}

internal fun dependencyRanges(versions: Map<String, String>): Map<String, String> =
  versions.filterKeys { !it.equals(INTEROP_CONTRACT_ID, ignoreCase = true) }
    .mapValues { (_, version) -> "[$version]" } +
    (INTEROP_CONTRACT_ID to INTEROP_CONTRACT_RANGE)
