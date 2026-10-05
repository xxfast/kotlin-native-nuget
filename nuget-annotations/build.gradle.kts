plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.mavenPublish)
}

// ADR-179: one annotation class, no code. Pure common and published for the standard KMP target
// set, because the plugin wires it to `commonMainApi` and every target a consumer declares must
// resolve a variant. No AGP: an `androidTarget()` consumer resolves the `jvm` variant.
kotlin {
  explicitApi()

  // JVM 1.8 bytecode: the class is read by any JVM consumer (and by the processor's own Tier 1
  // tests on JVM 17), so it must not inherit the build JDK's class-file version.
  jvm {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8) }
  }
  js { nodejs() }
  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
  wasmJs { nodejs() }

  iosArm64()
  iosX64()
  iosSimulatorArm64()
  macosArm64()
  macosX64()
  tvosArm64()
  tvosX64()
  tvosSimulatorArm64()
  watchosArm32()
  watchosArm64()
  watchosDeviceArm64()
  watchosX64()
  watchosSimulatorArm64()
  linuxX64()
  linuxArm64()
  mingwX64()
  androidNativeArm32()
  androidNativeArm64()
  androidNativeX86()
  androidNativeX64()
}

mavenPublishing {
  publishToMavenCentral()

  if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()

  pom {
    name.set("nuget-annotations")
    description.set("Source annotations for Kotlin libraries packaged as NuGet")
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

    // Mirrors the Maven Central artifacts. The credentials exist only in the release workflow.
    maven {
      name = "GitHubPackages"
      url = uri("https://maven.pkg.github.com/xxfast/kotlin-native-nuget")
      credentials {
        username = providers.environmentVariable("GITHUB_ACTOR").orNull
        password = providers.environmentVariable("GITHUB_TOKEN").orNull
      }
    }
  }
}
