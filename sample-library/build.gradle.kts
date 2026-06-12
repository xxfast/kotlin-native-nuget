plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.ksp)
  id("io.github.xxfast.nuget")
}

kotlin {
  mingwX64 {
    binaries {
      sharedLib {
        baseName = "sample"
        linkerOpts("-lmsvcrt", "-static-libgcc", "-static-libstdc++")
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

dependencies {
  add("kspMingwX64", project(":processor"))
  add("kspMacosArm64", project(":processor"))
}

ksp {
  arg("nuget.libraryName", "sample")
  arg("nuget.namespace", "SampleLibrary.Interop")
  arg("nuget.className", "SampleLibraryNative")
}

nuget {
  packageId.set("SampleLibrary")
  version.set("1.0.0")
  authors.set("xxfast")
  description.set("A sample Kotlin/Native library packaged as NuGet")
}
