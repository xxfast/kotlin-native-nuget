plugins {
  alias(libs.plugins.kotlinMultiplatform)
  // id("io.github.xxfast.nuget") // TODO: apply once the nuget plugin is implemented
}

kotlin {
  mingwX64 {
    binaries {
      sharedLib {
        baseName = "sample"
      }
    }
  }

  sourceSets {
    nativeMain.dependencies {
    }
    nativeTest.dependencies {
      implementation(libs.kotlin.test)
    }
  }
}
