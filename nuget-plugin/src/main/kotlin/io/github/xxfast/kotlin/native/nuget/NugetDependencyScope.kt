package io.github.xxfast.kotlin.native.nuget

class NugetDependencyScope(private val dependencies: MutableList<NugetDependency>) {
  fun dependency(id: String, version: String? = null, configure: NugetDependency.() -> Unit = {}) {
    val dep = NugetDependency(id)

    if (version != null) dep.version = version
    dep.configure()
    dependencies.add(dep)
  }
}
