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

  private val _include = mutableListOf<String>()
  private val _exclude = mutableListOf<String>()

  val include: List<String> get() = _include.toList()
  val exclude: List<String> get() = _exclude.toList()

  fun include(vararg packages: String) {
    _include.addAll(packages)
  }

  fun exclude(vararg packages: String) {
    _exclude.addAll(packages)
  }
}
