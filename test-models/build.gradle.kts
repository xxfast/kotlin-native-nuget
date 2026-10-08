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
      // Issue #487: `dev/other/bysuspend/Spotter.kt` returns a `Flow`. `implementation`, not `api`,
      // on purpose: an `api` edge would put coroutines on :test-library's compile classpath through
      // `implementation(project(":test-models"))`, which is exactly the declaration ADR-156 keeps
      // out of :test-library so that a plugin which stops adding it still fails there.
      implementation(libs.kotlinx.coroutines.core)
    }
    nativeTest.dependencies {
      implementation(libs.kotlin.test)
    }
  }
}
