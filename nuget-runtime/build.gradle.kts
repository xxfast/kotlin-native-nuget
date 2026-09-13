plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.mavenPublish)
}

// ADR-127: the fixed `nuget_*` ABI, shipped once as a klib the plugin adds as `api` and
// `export()`s from every shared library, instead of being regenerated into every consumer's
// `CNameExports.kt`. The C names here ARE the versioned ABI; the Kotlin names are for the
// generator of the same version and nobody else, which is what `@NugetRuntimeApi` says.
kotlin {
  macosArm64()
  macosX64()
  linuxX64()
  mingwX64()

  sourceSets {
    val nativeMain by creating {
      dependsOn(commonMain.get())
      dependencies {
        // `api`, not `implementation`: the generated code's suspend and Flow exports name
        // coroutines types in their own signatures.
        api(libs.kotlinx.coroutines.core)
      }
    }
    macosArm64Main.get().dependsOn(nativeMain)
    macosX64Main.get().dependsOn(nativeMain)
    linuxX64Main.get().dependsOn(nativeMain)
    mingwX64Main.get().dependsOn(nativeMain)

    // ADR-128: `launchForCSharp` / `collectForCSharp` are ordinary Kotlin/Native code with no
    // `@CName`, so they can be driven in-process. KGP host-gates the `<target>Test` tasks, so
    // `allTests` runs `mingwX64Test` on Windows and `macosArm64Test` on an Apple Silicon Mac.
    val nativeTest by creating {
      dependsOn(commonTest.get())
      dependencies {
        implementation(libs.kotlin.test)
      }
    }
    macosArm64Test.get().dependsOn(nativeTest)
    macosX64Test.get().dependsOn(nativeTest)
    linuxX64Test.get().dependsOn(nativeTest)
    mingwX64Test.get().dependsOn(nativeTest)
  }
}

// ADR-129: `NUGET_RUNTIME_VERSION` is generated, never hand-written, so the `nuget_runtime_version`
// export reports the version this klib was actually built at rather than a copy that can drift.
// Mirrors `nuget-plugin/build.gradle.kts`'s `generateVersionConstant`; `internal` because only the
// export reads it, so ADR-127's "every public declaration is `@NugetRuntimeApi`" rule is untouched.
val generateRuntimeVersionConstant: TaskProvider<Task> =
  tasks.register("generateRuntimeVersionConstant") {
    val outputDir: Provider<Directory> =
      layout.buildDirectory.dir("generated/source/version/nativeMain")
    val runtimeVersion: String = version.toString()
    inputs.property("runtimeVersion", runtimeVersion)
    outputs.dir(outputDir)

    doLast {
      val packageDir: File =
        outputDir.get().asFile.resolve("io/github/xxfast/kotlin/native/nuget/runtime")
      packageDir.mkdirs()
      packageDir.resolve("NugetRuntimeVersion.kt").writeText(
        """
      package io.github.xxfast.kotlin.native.nuget.runtime

      internal const val NUGET_RUNTIME_VERSION: String = "$runtimeVersion"
      """.trimIndent() + "\n",
      )
    }
  }

kotlin.sourceSets.named("nativeMain") { kotlin.srcDir(generateRuntimeVersionConstant) }

mavenPublishing {
  publishToMavenCentral()

  if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()

  pom {
    name.set("nuget-runtime")
    description.set("Fixed nuget_* ABI for Kotlin/Native libraries packaged as NuGet")
    url.set("https://github.com/xxfast/kotlin-native-nuget")
    licenses {
      license {
        name.set("Apache-2.0")
        url.set("https://opensource.org/licenses/Apache-2.0")
      }
    }
    issueManagement {
      system.set("Github")
      url.set("https://github.com/xxfast/kotlin-native-nuget/issues")
    }
    scm {
      url.set("https://github.com/xxfast/kotlin-native-nuget")
      connection.set("scm:git:git://github.com/xxfast/kotlin-native-nuget.git")
      developerConnection.set("scm:git:ssh://git@github.com/xxfast/kotlin-native-nuget.git")
    }
    developers {
      developer {
        id.set("xxfast")
        name.set("Isuru Rajapakse")
        email.set("isurukusumal36@gmail.com")
      }
    }
  }
}

// Shared with `nuget-processor` and the `nuget-plugin` included build, so the by-coordinate
// smoke test can resolve all three artifacts without touching a real registry.
publishing {
  repositories {
    maven {
      name = "localTest"
      url = uri(rootProject.layout.buildDirectory.dir("local-repo"))
    }
  }
}
