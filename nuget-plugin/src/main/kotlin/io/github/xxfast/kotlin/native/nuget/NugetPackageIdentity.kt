package io.github.xxfast.kotlin.native.nuget

import java.util.Locale

internal const val INTEROP_CONTRACT_ID: String = "Kotlin.Native.Interop"
internal const val INTEROP_CONTRACT_RANGE: String = "[1.0.0,2.0.0)"

internal fun nativeLibraryStem(id: String): String {
  require(id.length in 1..100 && id.all { it in 'a'..'z' || it in 'A'..'Z' ||
    it in '0'..'9' || it == '.' || it == '-' || it == '_' }) {
    "[nuget] Invalid package id '$id': use 1-100 ASCII letters, digits, '.', '-' or '_'."
  }
  val bytes = id.lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8)
  return "kn_" + bytes.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
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
