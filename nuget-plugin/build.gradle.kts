import java.util.Properties

plugins {
  `java-gradle-plugin`
  kotlin("jvm") version "2.4.0"
  kotlin("plugin.serialization") version "2.4.0"
  id("com.gradle.plugin-publish") version "1.3.1"
  id("com.vanniktech.maven.publish") version "0.37.0"
}

// Gradle does not propagate the root build's gradle.properties across a composite
// boundary, so this included build reads the single source of truth directly.
val rootProperties = Properties()
rootDir.parentFile.resolve("gradle.properties").inputStream().use(rootProperties::load)

group = requireNotNull(rootProperties.getProperty("group")) { "`group` missing from the root gradle.properties" }
version = requireNotNull(rootProperties.getProperty("version")) { "`version` missing from the root gradle.properties" }

repositories {
  mavenCentral()
}

dependencies {
  implementation(kotlin("gradle-plugin-api"))
  implementation(kotlin("gradle-plugin"))
  implementation("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.3.9")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

  testImplementation(kotlin("test"))
}

// `PLUGIN_VERSION` is generated, never hand-written: `NugetPlugin` uses it to resolve
// `io.github.xxfast:nuget-processor` at the same version, and a drifting copy would hand
// every consumer an unresolvable processor coordinate.
val generateVersionConstant: TaskProvider<Task> = tasks.register("generateVersionConstant") {
  val outputDir: Provider<Directory> = layout.buildDirectory.dir("generated/source/version/main")
  val pluginVersion: String = version.toString()
  inputs.property("pluginVersion", pluginVersion)
  outputs.dir(outputDir)

  doLast {
    val packageDir: File = outputDir.get().asFile.resolve("io/github/xxfast/kotlin/native/nuget")
    packageDir.mkdirs()
    packageDir.resolve("NugetVersion.kt").writeText(
      """
      package io.github.xxfast.kotlin.native.nuget

      internal const val PLUGIN_VERSION: String = "$pluginVersion"
      """.trimIndent() + "\n",
    )
  }
}

kotlin.sourceSets.named("main") { kotlin.srcDir(generateVersionConstant) }

tasks.processResources {
  from(project.file("../nuget-metadata-reader")) {
    into("nuget-metadata-reader")
    exclude("bin/**", "obj/**")
  }
}

tasks.test {
  useJUnitPlatform()
}

gradlePlugin {
  website.set("https://github.com/xxfast/kotlin-native-nuget")
  vcsUrl.set("https://github.com/xxfast/kotlin-native-nuget")

  plugins {
    create("nuget") {
      id = "io.github.xxfast.kotlin.native.nuget"
      implementationClass = "io.github.xxfast.kotlin.native.nuget.NugetPlugin"
      displayName = "Kotlin/Native NuGet"
      description = "Packages a Kotlin/Native library as a NuGet package with generated C# bindings, and consumes C# NuGet packages from Kotlin"
      tags.set(listOf("kotlin", "kotlin-native", "nuget", "csharp", "dotnet", "interop"))
    }
  }
}

// Publishes the plugin and its marker to the Sonatype Central Portal. `publishPlugins`
// (from `com.gradle.plugin-publish`) targets the Gradle Plugin Portal separately.
mavenPublishing {
  publishToMavenCentral()

  // Only sign when a key is configured, so the keyless local-repo smoke test can publish.
  if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()

  pom {
    name.set("kotlin-native-nuget")
    description.set("Gradle plugin bridging Kotlin/Native and C# via NuGet, in both directions")
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

// Writes into the same `build/local-repo` as `:nuget-processor`, so a fixture project can
// resolve the plugin marker and the processor together, by coordinate, with no registry.
publishing {
  repositories {
    maven {
      name = "localTest"
      url = uri(rootDir.parentFile.resolve("build/local-repo"))
    }
  }
}
