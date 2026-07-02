package io.github.xxfast.kotlin.native.nuget

open class NugetExtension {
  var publish: NugetPublishConfig? = null
    private set

  private val _dependencies = mutableListOf<NugetDependency>()
  val dependencies: List<NugetDependency> get() = _dependencies.toList()

  fun publish(configure: NugetPublishConfig.() -> Unit) {
    val config = NugetPublishConfig()
    config.configure()
    publish = config
  }

  fun dependencies(configure: NugetDependencyScope.() -> Unit) {
    NugetDependencyScope(_dependencies).configure()
  }
}
