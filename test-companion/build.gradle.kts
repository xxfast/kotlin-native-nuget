import io.github.xxfast.kotlin.native.nuget.NugetGenTask
import io.github.xxfast.kotlin.native.nuget.NugetCompileInteropTask
import io.github.xxfast.kotlin.native.nuget.PackNugetTask

plugins {
  alias(libs.plugins.kotlinMultiplatform)
  id("io.github.xxfast.kotlin.native.nuget")
}

kotlin {
  mingwX64 {
    binaries.sharedLib { baseName = "shared" }
  }
  macosArm64 {
    binaries.sharedLib { baseName = "shared" }
  }
  sourceSets {
    nativeMain.dependencies {
      // Materialize the shared source set before reverse binding tasks attach generated sources.
    }
  }
}

val fixtureVersion = providers.fileContents(
  project(":test-library").layout.buildDirectory.file("fixture-version.txt"),
).asText.map { it.trim() }

tasks.withType<NugetCompileInteropTask>().configureEach {
  dependencySources.add(rootProject.layout.buildDirectory.dir("nuget").get().asFile.absolutePath)
}

afterEvaluate {
  tasks.matching { it.name == "nugetRestore" }.configureEach {
    dependsOn(":test-library:packTestDependency")
  }
  tasks.named("nugetGen", NugetGenTask::class.java) {
    dependsOn(":test-library:writeFixtureVersion")
    dependencyVersions.set(fixtureVersion.map { mapOf("TestDependency" to it) })
  }
  tasks.named("packNuget", PackNugetTask::class.java) {
    packageVersion.set(fixtureVersion)
    dependsOn(":test-library:writeFixtureVersions")
  }
}

nuget {
  publish {
    packageId = "TestCompanion"
    version = "1.0.0"
    authors = "xxfast"
    description = "Independent second Kotlin publisher for coexistence verification"
    rootPackage = "io.github.xxfast.kotlin.native.nuget.companion"
  }
  dependencies {
    dependency("TestDependency", version = "1.0.0") {
      bind {
        include("Test.Text")
        alias("Test.Text", "test.text")
      }
    }
  }
}
