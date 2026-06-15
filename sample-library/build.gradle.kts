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
    }
    nativeTest.dependencies {
      implementation(libs.kotlin.test)
    }
  }
}

nuget {
  packageId.set("SampleLibrary")
  version.set("1.0.0")
  authors.set("xxfast")
  description.set("A sample Kotlin/Native library packaged as NuGet")
  rootPackage.set("io.github.xxfast.kotlin.native.nuget.sample")
}
