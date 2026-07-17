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

  testImplementation(libs.kotlin.test)

  // ADR-060 Tier 1 harness: drives the real NugetProcessor through KSP2's own programmatic
  // entry point, then compiles the generated CNameExports.kt for JVM in-process. No
  // kotlin-compile-testing / kctfork — see the ADR's "Tier 1 compiles; it does not
  // substring-match" section for why these three are enough on their own.
  testImplementation(libs.ksp.symbolProcessingAaEmbeddable)
  testImplementation(libs.ksp.symbolProcessingCommonDeps)
  testImplementation(libs.kotlin.compiler.embeddable)
  // Real kotlinx-coroutines-core is JVM-usable as-is (unlike kotlinx.cinterop, which is
  // Kotlin/Native-only), so it is a genuine dependency here, not part of the harness's
  // cinterop stub file: it supplies the real `ExperimentalCoroutinesApi` marker that every
  // generated file's unconditional `@OptIn` references.
  testImplementation(libs.kotlinx.coroutines.core)

  // ADR-060's strict `@XFail`: a JUnit 5 `InvocationInterceptor` extension (Tier1XFail.kt).
  // `junit-jupiter-engine` is `testRuntimeOnly` because nothing in this module's test sources
  // calls into it directly — only the platform launcher needs it, to discover and run
  // Jupiter-annotated tests once `useJUnitPlatform()` is enabled below.
  testImplementation(libs.junit.jupiter.api)
  testRuntimeOnly(libs.junit.jupiter.engine)
}

// Needed for `@XFail` (a JUnit 5 extension) and it also flips the Kotlin Gradle plugin's
// automatic `kotlin-test` variant selection from `kotlin-test-junit` to `kotlin-test-junit5`
// (verified: every pre-existing `kotlin.test.Test` in this module — `ForwardAbiContractTest`,
// `CirFunctionTranslatorNullableTest` — still runs, now on the Jupiter engine, with zero
// changes to those files).
//
// Deliberately does *not* set `forkEvery`. Tier 1 (ADR-060) drives KSP2's standalone Analysis
// API session, which leaves non-daemon threads running after `execute()` returns — verified in
// the ADR; it is what made the spike's first run look like a hang when it had actually finished
// in seconds. That is harmless inside a Gradle test worker: Gradle reclaims the worker process
// itself once the task completes rather than waiting on natural JVM exit (verified — a Tier 1
// run here returns control to `gradlew` normally), and KSP2 is re-entrant within one JVM
// (ADR-060 Verification, "KSP2 is re-entrant in one JVM"), which is exactly what lets every
// Tier 1 test share one warm worker and hit the ADR's measured per-cell cost instead of paying
// the ~5-6s cold-JVM cost per cell.
tasks.test {
  useJUnitPlatform()
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
