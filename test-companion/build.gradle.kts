import io.github.xxfast.kotlin.native.nuget.NugetGenerateRestoreProjectTask
import io.github.xxfast.kotlin.native.nuget.NugetCompileInteropTask
import io.github.xxfast.kotlin.native.nuget.PackNugetTask

plugins {
  alias(libs.plugins.kotlinMultiplatform)
  id("io.github.xxfast.kotlin.native.nuget")
}

// ADR-109 fixture: configure this publisher only after the reader has evaluated.
// One-way ordering; the reader sentinel never resolves KSP arguments.
evaluationDependsOn(":test-library")

kotlin {
  // ADR-181: this fixture calls `bind {}`-generated bindings, which are experimental.
  compilerOptions {
    optIn.add("io.github.xxfast.kotlin.native.nuget.annotations.ExperimentalNugetBindingApi")
  }
  mingwX64 {
    binaries.sharedLib { baseName = "shared" }
  }
  macosArm64 {
    binaries.sharedLib { baseName = "shared" }
  }
  // Never name `nativeMain` here: this fixture is the real build proving the reverse bindings
  // reach a `nativeMain` that only the default hierarchy creates (its sources import them).
  sourceSets {
    commonMain.dependencies {
      implementation(project(":test-models"))
    }
  }
}

// The repo directory, taken from this project's own location and not from `rootProject`: these
// scripts are built from the repo root and from `fixture-consumer/`, a second root over the same
// project directories, and the files below live in the repo either way.
val repoDir: File = projectDir.parentFile

val fixtureVersion = providers.fileContents(
  project(":test-library").layout.buildDirectory.file("fixture-version.txt"),
).asText.map { it.trim() }

tasks.withType<NugetCompileInteropTask>().configureEach {
  dependencySources.add(repoDir.resolve("build/nuget").absolutePath)
}

afterEvaluate {
  tasks.matching { it.name == "nugetRestore" }.configureEach {
    dependsOn(":test-library:packTestDependency")
  }
  tasks.named("nugetGenerateRestoreProject", NugetGenerateRestoreProjectTask::class.java) {
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
    include(
      "io.github.xxfast.kotlin.native.nuget.companion",
      "io.github.xxfast.kotlin.native.nuget.test.models",
    )
  }
  dependencies {
    dependency("TestDependency", version = "1.0.0") {
      bind {
        includeNamespaces("Test.Text")
        alias("Test.Text", "test.text")
      }
    }
  }
}
