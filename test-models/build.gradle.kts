// ADR-066: a real second Gradle module, consumed by :test-library via
// `implementation(project(":test-models"))`. The nuget plugin is deliberately NOT applied here:
// this module exists only to put a klib boundary between its declarations and the KSP processor
// that runs in :test-library, which is the whole point of the fixture (module isolation is what
// today's `getAllFiles()` choke point relies on, and what ADR-066's reachability closure has to
// cross correctly).
//
// Issue #464: only the annotations plugin is applied, the shape of a dependency module that needs
// `@CSharpName` (ADR-179) without the main plugin. It adds `nuget-annotations` and nothing else:
// no `nuget` extension, no KSP, so the klib boundary above is unchanged.
plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.kotlinSerialization)
  id("io.github.xxfast.kotlin.native.nuget.annotations")
}

kotlin {
  mingwX64()
  macosArm64()
  // Linux hosts only, matching the fixtures that link against this klib.
  if (org.jetbrains.kotlin.konan.target.HostManager.hostIsLinux) linuxX64()

  sourceSets {
    nativeMain.dependencies {
      api(libs.kotlinx.serialization.core)
    }
    nativeTest.dependencies {
      implementation(libs.kotlin.test)
    }
  }
}
