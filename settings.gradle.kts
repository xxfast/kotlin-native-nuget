rootProject.name = "kotlin-native-nuget"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
  includeBuild("nuget-plugin")
  repositories {
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositories {
    mavenCentral()
  }
}

include(":nuget-processor")
include(":sample-library")
include(":sample-app")
