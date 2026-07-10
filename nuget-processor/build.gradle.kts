plugins {
  alias(libs.plugins.kotlinJvm)
  alias(libs.plugins.mavenPublish)
}

// Matches `nuget-plugin`: the plugin resolves this processor onto a consumer's KSP classpath, so
// publishing it with a JVM 21 requirement would lock out every consumer on Java 17.
kotlin {
  jvmToolchain(17)
}

dependencies {
  implementation(libs.ksp.api)
  implementation(libs.kotlinpoet)
  implementation(libs.kotlinpoet.ksp)
}

// Publishes to the Sonatype Central Portal. Credentials and signing come from Gradle
// properties: mavenCentralUsername/mavenCentralPassword and signingInMemoryKey/
// signingInMemoryKeyPassword (see CI env).
mavenPublishing {
  publishToMavenCentral() // no automaticRelease: deployment waits for a manual "Publish" on the portal

  // Only sign when a key is actually configured, so the keyless local-repo smoke test
  // (see `localTest` below) can publish without credentials. CI always has the key.
  if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()

  pom {
    name.set("nuget-processor")
    description.set("KSP processor that generates C# bindings for Kotlin/Native libraries packaged as NuGet")
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

// Local file-backed repository, shared with the `nuget-plugin` included build, so the
// by-coordinate smoke test can resolve both artifacts without touching a real registry.
publishing {
  repositories {
    maven {
      name = "localTest"
      url = uri(rootProject.layout.buildDirectory.dir("local-repo"))
    }
  }
}
