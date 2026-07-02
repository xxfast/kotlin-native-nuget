package io.github.xxfast.kotlin.native.nuget

class NugetBindConfig {
  var packageName: String? = null

  private val _include = mutableListOf<String>()
  private val _exclude = mutableListOf<String>()
  private val _aliases = mutableMapOf<String, String>()

  val include: List<String> get() = _include.toList()
  val exclude: List<String> get() = _exclude.toList()
  val aliases: Map<String, String> get() = _aliases.toMap()

  fun include(vararg namespace: String) { _include.addAll(namespace) }
  fun exclude(vararg namespace: String) { _exclude.addAll(namespace) }

  fun alias(csharpNamespace: String, kotlinPackage: String) {
    _aliases[csharpNamespace] = kotlinPackage
  }
}
