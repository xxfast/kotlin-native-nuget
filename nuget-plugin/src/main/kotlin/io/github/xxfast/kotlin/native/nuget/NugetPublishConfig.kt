package io.github.xxfast.kotlin.native.nuget

class NugetPublishConfig {
  var packageId: String? = null
  var version: String? = null
  var authors: String? = null
  var description: String? = null
  var rootPackage: String? = null

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
