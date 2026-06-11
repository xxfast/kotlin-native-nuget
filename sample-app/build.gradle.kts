plugins {
  alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
  mingwX64 {
    binaries {
      executable {
        entryPoint = "io.github.xxfast.nuget.app.main"
      }
    }
  }

  sourceSets {
    nativeMain.dependencies {
      implementation(project(":sample-library"))
    }
    nativeTest.dependencies {
      implementation(libs.kotlin.test)
    }
  }
}
