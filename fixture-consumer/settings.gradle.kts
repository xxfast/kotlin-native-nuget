// A second root over the same fixture projects, not a copy of them. The repo's own root builds
// `test-library` and `test-companion` beside `:nuget-processor`, `:nuget-runtime` and
// `:nuget-annotations`, so there `NugetPlugin` wires all three as projects and they are compiled by
// whatever Kotlin the build runs on. Here none of them is a project: the plugin takes its
// maven-coordinate fallback and the fixtures link the artifacts as published, exactly as a real
// consumer does, while this build's own Kotlin is the consumer's (ADR-195).
//
// `pluginManagement` is hoisted above the script body, so it sees neither script-level vals nor
// the file's imports. Everything it needs is derived from `settingsDir` (a Settings member) and
// fully-qualified type names.

pluginManagement {
  val rootProperties = java.util.Properties()
  settingsDir.parentFile.resolve("gradle.properties").inputStream().use(rootProperties::load)
  val pluginVersion: String = requireNotNull(rootProperties.getProperty("version")) {
    "`version` missing from the root gradle.properties"
  }

  repositories {
    maven { url = uri(settingsDir.parentFile.resolve("build/local-repo")) }
    gradlePluginPortal()
    mavenCentral()
  }

  plugins {
    id("io.github.xxfast.kotlin.native.nuget") version pluginVersion
    id("io.github.xxfast.kotlin.native.nuget.annotations") version pluginVersion
  }
}

val rootProperties = java.util.Properties()
settingsDir.parentFile.resolve("gradle.properties").inputStream().use(rootProperties::load)

// `-Pconsumer.kotlin=floor|tested|<literal>` picks the consumer's Kotlin, so CI can build the
// fixtures at both ends of the supported range. Absent means `tested`.
val kotlinVersion: String = when (val requested: String = providers.gradleProperty("consumer.kotlin").getOrElse("tested")) {
  "floor" -> requireNotNull(rootProperties.getProperty("kotlinFloor")) { "`kotlinFloor` missing from the root gradle.properties" }
  "tested" -> requireNotNull(rootProperties.getProperty("kotlinTested")) { "`kotlinTested` missing from the root gradle.properties" }
  else -> requested
}

dependencyResolutionManagement {
  repositories {
    maven { url = uri(settingsDir.parentFile.resolve("build/local-repo")) }
    mavenCentral()
  }

  // The fixtures' scripts read `libs`, so this root hands them the repo's catalog with only the
  // Kotlin version replaced. There is no `gradle/libs.versions.toml` under this directory, so
  // nothing is imported by convention and `from` is called exactly once.
  versionCatalogs {
    create("libs") {
      from(files("../gradle/libs.versions.toml"))
      version("kotlin", kotlinVersion)
    }
  }
}

rootProject.name = "fixture-consumer"

include(":test-models")
include(":test-library")
include(":test-companion")
project(":test-models").projectDir = settingsDir.parentFile.resolve("test-models")
project(":test-library").projectDir = settingsDir.parentFile.resolve("test-library")
project(":test-companion").projectDir = settingsDir.parentFile.resolve("test-companion")
