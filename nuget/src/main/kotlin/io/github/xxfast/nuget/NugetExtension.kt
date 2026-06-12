package io.github.xxfast.nuget

import org.gradle.api.provider.Property

abstract class NugetExtension {
  abstract val packageId: Property<String>
  abstract val version: Property<String>
  abstract val authors: Property<String>
  abstract val description: Property<String>
  abstract val rootPackage: Property<String>
}
