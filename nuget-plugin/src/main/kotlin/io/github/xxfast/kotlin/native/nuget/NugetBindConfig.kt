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

  // ADR-182 section 4: kept in 0.9.x only so a stale script fails to compile with a quick-fix;
  // deleted in 0.10.0.
  @Deprecated(
    "Renamed in 0.9.0: bind {} filters C# namespaces, publish {} keeps include for Kotlin packages",
    replaceWith = ReplaceWith("includeNamespaces(*namespace)"),
    level = DeprecationLevel.ERROR,
  )
  public fun include(vararg namespace: String) {
    includeNamespaces(*namespace)
  }

  @Deprecated(
    "Renamed in 0.9.0: bind {} filters C# namespaces, publish {} keeps exclude for Kotlin packages",
    replaceWith = ReplaceWith("excludeNamespaces(*namespace)"),
    level = DeprecationLevel.ERROR,
  )
  public fun exclude(vararg namespace: String) {
    excludeNamespaces(*namespace)
  }

  public fun alias(csharpNamespace: String, kotlinPackage: String) {
    aliases.put(csharpNamespace, kotlinPackage)
  }
}
