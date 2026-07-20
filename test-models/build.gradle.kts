// ADR-066: a real second Gradle module, consumed by :test-library via
// `implementation(project(":test-models"))`. The nuget plugin is deliberately NOT applied here:
// this module exists only to put a klib boundary between its declarations and the KSP processor
// that runs in :test-library, which is the whole point of the fixture (module isolation is what
// today's `getAllFiles()` choke point relies on, and what ADR-066's reachability closure has to
// cross correctly).
plugins {
  alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
  mingwX64()
  macosArm64()

  sourceSets {
    nativeTest.dependencies {
      implementation(libs.kotlin.test)
    }
  }
}
