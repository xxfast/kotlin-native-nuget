import java.util.Properties

// Loaded once here, `apply false`, so every fixture project sees the same plugin classes. The
// plugin looks up its extension on sibling projects by type, and two classloaders would make that
// lookup return nothing.
plugins {
  alias(libs.plugins.kotlinMultiplatform) apply false
  id("io.github.xxfast.kotlin.native.nuget") apply false
  id("io.github.xxfast.kotlin.native.nuget.annotations") apply false
}

// The repo root's `gradle.properties` gives every project its coordinates. This root does not
// inherit that file, and `test-library` stamps `rootProject.version` into the fixture props as
// the runtime version, so both are read from it here.
val rootProperties = Properties()
rootDir.parentFile.resolve("gradle.properties").inputStream().use(rootProperties::load)
val repoGroup: String = requireNotNull(rootProperties.getProperty("group")) {
  "`group` missing from the root gradle.properties"
}
val repoVersion: String = requireNotNull(rootProperties.getProperty("version")) {
  "`version` missing from the root gradle.properties"
}

allprojects {
  group = repoGroup
  version = repoVersion
}
