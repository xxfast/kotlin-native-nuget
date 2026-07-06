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

// Pack the local SampleDependency fixture into build/nuget before dotnet restore runs.
// This ensures the .nupkg is present in the local NuGet feed (declared in nuget.config) even
// after :clean wipes build/. The task is declared before afterEvaluate so the task provider is
// available when nugetRestore is wired up below.
val packSampleDependency by tasks.registering(Exec::class) {
  group = "nuget"
  description = "Packs SampleDependency.nupkg into build/nuget so dotnet restore can find it"
  val csprojFile = rootProject.file("sample-dependency/SampleDependency.csproj")
  val outDir = layout.buildDirectory.dir("nuget")
  inputs.file(csprojFile)
  outputs.file(outDir.map { it.file("SampleDependency.1.0.0.nupkg") })
  commandLine(
    "dotnet", "pack",
    csprojFile.absolutePath,
    "-c", "Release",
    "-o", outDir.get().asFile.absolutePath,
  )
  doFirst { outDir.get().asFile.mkdirs() }
}

// Wire packSampleDependency before nugetRestore so the local feed is populated first.
// nugetRestore is registered in afterEvaluate by the nuget plugin, so the wiring must also be
// in afterEvaluate (registered second, runs after the plugin's afterEvaluate).
afterEvaluate {
  tasks.matching { it.name == "nugetRestore" }.configureEach { dependsOn(packSampleDependency) }
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
    dependency("SampleDependency", version = "1.0.0") {
      bind {
        include("Sample.Text")
        alias("Sample.Text", "sample.text")
      }
    }
  }
}
