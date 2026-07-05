plugins {
  alias(libs.plugins.kotlinMultiplatform)
  id("io.github.xxfast.kotlin.native.nuget")
}

kotlin {
  mingwX64 {
    binaries {
      sharedLib {
        baseName = "sample"
      }
    }
  }

  macosArm64 {
    binaries {
      sharedLib {
        baseName = "sample"
      }
    }
  }

  sourceSets {
    nativeMain.dependencies {
      implementation(libs.kotlinx.coroutines.core)
    }
    nativeTest.dependencies {
      implementation(libs.kotlin.test)
    }
  }
}

nuget {
  publish {
    packageId = "SampleLibrary"
    version = "1.0.0"
    authors = "xxfast"
    description = "A sample Kotlin/Native library packaged as NuGet"
    rootPackage = "io.github.xxfast.kotlin.native.nuget.sample"
  }

  dependencies {
    dependency("MimeMapping", version = "4.0.0") {
      bind {
        packageName = "mimemapping"
        include("MimeMapping")
      }
    }
  }
}
