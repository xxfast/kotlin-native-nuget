package io.github.xxfast.kotlin.native.nuget

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property

@NugetDsl
abstract class NugetBindConfig {
  abstract val packageName: Property<String>
  abstract val include: ListProperty<String>
  abstract val exclude: ListProperty<String>
  abstract val aliases: MapProperty<String, String>

  fun include(vararg namespace: String) {
    include.addAll(*namespace)
  }

  fun exclude(vararg namespace: String) {
    exclude.addAll(*namespace)
  }

  fun alias(csharpNamespace: String, kotlinPackage: String) {
    aliases.put(csharpNamespace, kotlinPackage)
  }
}
