rootProject.name = "kotlin-native-nuget"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
  includeBuild("nuget-plugin")
  repositories {
    mavenCentral()
    gradlePluginPortal()
  }
}

// ADR-195: `-PkotlinVersion=floor|tested|<literal>` (or ORG_GRADLE_PROJECT_kotlinVersion) builds
// this repo on another Kotlin without editing the catalog, so CI can run the real tests at both ends
// of the supported range. Absent, the catalog is imported by Gradle's own convention, untouched.
val kotlinVersion: String? = providers.gradleProperty("kotlinVersion").orNull?.takeIf(String::isNotBlank)?.let { requested ->
  when (requested) {
    "floor" -> requireNotNull(providers.gradleProperty("kotlinFloor").orNull) { "`kotlinFloor` missing from gradle.properties" }
    "tested" -> requireNotNull(providers.gradleProperty("kotlinTested").orNull) { "`kotlinTested` missing from gradle.properties" }
    else -> requested
  }
}

dependencyResolutionManagement {
  repositories {
    mavenCentral()
  }

  if (kotlinVersion != null) versionCatalogs.configureEach {
    if (name == "libs") version("kotlin", kotlinVersion)
  }
}

include(":nuget-processor")
include(":nuget-runtime")
include(":nuget-annotations")
include(":test-models")
include(":test-library")
include(":test-companion")
