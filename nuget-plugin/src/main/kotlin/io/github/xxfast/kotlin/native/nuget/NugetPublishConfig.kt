package io.github.xxfast.kotlin.native.nuget

import java.io.File

class NugetPublishConfig {
  var packageId: String? = null
  var version: String? = null
  var authors: String? = null
  var description: String? = null
  var rootPackage: String? = null

  // ADR-092: derive `<version>-snapshot.<epochMillis>` at execution time, so successive local
  // builds produce distinct package identities instead of hitting NuGet's immutable-version cache.
  var snapshot: Boolean = false

  // ADR-092: where to write the MSBuild props file pinning the minted snapshot version.
  // Default: <rootProject>/build/<packageId>Versions.props. Only consulted when snapshot is true.
  var versionPropsFile: File? = null

  // ADR-093: a directory of native libraries built on another host, laid out exactly like the
  // runtimes/ tree packNuget stages: <dir>/<rid>/native/ holding dll, dylib or so files. Merged
  // into this host's own linked output so one pack produces one multi-RID package.
  var prebuiltRuntimes: File? = null

  private val _include = mutableListOf<String>()
  private val _exclude = mutableListOf<String>()

  // ADR-115 amendment: fully-qualified `@RequiresOptIn` marker names whose declarations keep
  // exporting despite carrying them. The inverse of binary-compatibility-validator's
  // `nonPublicMarkers`: that lists what to hide, this lists what to keep. Entries are trusted
  // unvalidated; a misspelt one simply waives nothing, and the SKIPPED_OPT_IN_MARKER warning next
  // to it prints the correct FQN.
  private val _exportMarkers = mutableListOf<String>()

  val include: List<String> get() = _include.toList()
  val exclude: List<String> get() = _exclude.toList()
  val exportMarkers: List<String> get() = _exportMarkers.toList()

  fun include(vararg packages: String) {
    _include.addAll(packages)
  }

  fun exclude(vararg packages: String) {
    _exclude.addAll(packages)
  }

  fun exportMarkers(vararg markers: String) {
    _exportMarkers.addAll(markers)
  }
}
