package io.github.xxfast.kotlin.native.nuget

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property

@NugetDsl
public abstract class NugetBindConfig {
  public abstract val packageName: Property<String>
  public abstract val includeNamespaces: ListProperty<String>
  public abstract val excludeNamespaces: ListProperty<String>
  public abstract val aliases: MapProperty<String, String>

  /** C# namespaces to bind. `publish { include(...) }` is the Kotlin-package filter (ADR-182). */
  public fun includeNamespaces(vararg namespace: String) {
    includeNamespaces.addAll(*namespace)
  }

  public fun excludeNamespaces(vararg namespace: String) {
    excludeNamespaces.addAll(*namespace)
  }

  public fun alias(csharpNamespace: String, kotlinPackage: String) {
    aliases.put(csharpNamespace, kotlinPackage)
  }
}
