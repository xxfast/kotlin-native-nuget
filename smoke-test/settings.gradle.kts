// A deliberately separate Gradle build, not a subproject of the root build. That is the whole
// point: in the root build `NugetPlugin` finds `:nuget-processor` as a project and never takes
// the maven-coordinate branch. Here `findProject(":nuget-processor")` is null, so the plugin
// resolves `io.github.xxfast:nuget-processor:$PLUGIN_VERSION` exactly as a real consumer does.
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

  // ADR-195: `-Psmoke.kotlin=floor|tested|<literal>` picks the consumer's Kotlin, so CI can link this
  // consumer at both ends of the supported range. Absent means `tested`.
  val kotlinVersion: String = when (val requested: String = providers.gradleProperty("smoke.kotlin").getOrElse("tested")) {
    "floor" -> requireNotNull(rootProperties.getProperty("kotlinFloor")) { "`kotlinFloor` missing from the root gradle.properties" }
    "tested" -> requireNotNull(rootProperties.getProperty("kotlinTested")) { "`kotlinTested` missing from the root gradle.properties" }
    else -> requested
  }

  repositories {
    maven { url = uri(settingsDir.parentFile.resolve("build/local-repo")) }
    gradlePluginPortal()
    mavenCentral()
  }

  plugins {
    id("io.github.xxfast.kotlin.native.nuget") version pluginVersion
    id("io.github.xxfast.kotlin.native.nuget.annotations") version pluginVersion
    kotlin("multiplatform") version kotlinVersion
  }
}

dependencyResolutionManagement {
  repositories {
    maven { url = uri(settingsDir.parentFile.resolve("build/local-repo")) }
    mavenCentral()
  }
}

rootProject.name = "smoke-test"

include(":annotations")
