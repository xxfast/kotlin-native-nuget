package io.github.xxfast.kotlin.native.nuget

class NugetDependency(val id: String) {
  var version: String? = null
  var source: String? = null
  var bind: NugetBindConfig? = null
    private set

  fun bind(configure: NugetBindConfig.() -> Unit) {
    val config = NugetBindConfig()
    config.configure()
    bind = config
  }
}
