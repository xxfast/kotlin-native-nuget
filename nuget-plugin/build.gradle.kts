import java.util.Properties

plugins {
  `java-gradle-plugin`
  kotlin("jvm") version "2.4.10"
  kotlin("plugin.serialization") version "2.4.10"
  id("com.gradle.plugin-publish") version "1.3.1"
  id("com.vanniktech.maven.publish") version "0.37.0"
  id("org.jetbrains.kotlinx.kover") version "0.9.9"
}

// Gradle does not propagate the root build's gradle.properties across a composite
// boundary, so this included build reads the single source of truth directly.
val rootProperties = Properties()
rootDir.parentFile.resolve("gradle.properties").inputStream().use(rootProperties::load)

group = requireNotNull(rootProperties.getProperty("group")) { "`group` missing from the root gradle.properties" }
version = requireNotNull(rootProperties.getProperty("version")) { "`version` missing from the root gradle.properties" }

// This included build cannot read the version catalog. Keep aligned with kotlin("jvm") above.
val kotlinNativeVersion = "2.4.10"

// ADR-156. NugetCompileGeneratedKotlinTest resolves the host klib at this same pin.
val coroutinesVersion = "1.10.2"

// Java 17 is Gradle 9's own floor, so it is the lowest a consumer can be on. This build's daemon
// runs on 21 (gradle/gradle-daemon-jvm.properties); without pinning the toolchain, the published
// Gradle module metadata records `org.gradle.jvm.version: 21` and every consumer on 17 fails to
// resolve the plugin at all.
kotlin {
  explicitApi()
  jvmToolchain(17)
}

repositories {
  mavenCentral()
}

val kotlinNativeHost: HostKotlinNative? = hostKotlinNative()

// ADR-193: the plugin tests compile generated Kotlin with the host kotlinc-native. Resolved
// here so a clean test run does not depend on ~/.konan.
val kotlinNativeCompiler: Configuration by configurations.creating {
  isCanBeConsumed = false
  isCanBeResolved = true
  isTransitive = false
  description = "Host kotlin-native-prebuilt archive for NugetCompileGeneratedKotlinTest."
}

val hostCoroutinesKlib: Configuration by configurations.creating {
  isCanBeConsumed = false
  isCanBeResolved = true
  isTransitive = false
  description = "Host kotlinx-coroutines-core klib for NugetCompileGeneratedKotlinTest."
  attributes {
    attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, "kotlin-api"))
  }
}

dependencies {
  implementation(kotlin("gradle-plugin-api"))
  implementation(kotlin("gradle-plugin"))
  implementation("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.3.10")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

  testImplementation(kotlin("test"))

  if (kotlinNativeHost != null) {
    add(
      "kotlinNativeCompiler",
      "org.jetbrains.kotlin:kotlin-native-prebuilt:$kotlinNativeVersion:" +
        "${kotlinNativeHost.compilerClassifier}@tar.gz",
    )
    add(
      "hostCoroutinesKlib",
      "org.jetbrains.kotlinx:kotlinx-coroutines-core-${kotlinNativeHost.coroutinesModule}:" +
        "$coroutinesVersion@klib",
    )
  }
}

// `PLUGIN_VERSION` is generated, never hand-written: `NugetPlugin` uses it to resolve
// `io.github.xxfast:nuget-processor` at the same version, and a drifting copy would hand
// every consumer an unresolvable processor coordinate.
val generateVersionConstant: TaskProvider<Task> = tasks.register("generateVersionConstant") {
  val outputDir: Provider<Directory> = layout.buildDirectory.dir("generated/source/version/main")
  val pluginVersion: String = version.toString()
  inputs.property("pluginVersion", pluginVersion)
  inputs.property("coroutinesVersion", coroutinesVersion)
  outputs.dir(outputDir)

  doLast {
    val packageDir: File = outputDir.get().asFile.resolve("io/github/xxfast/kotlin/native/nuget")
    packageDir.mkdirs()
    packageDir.resolve("NugetVersion.kt").writeText(
      """
      package io.github.xxfast.kotlin.native.nuget

      internal const val PLUGIN_VERSION: String = "$pluginVersion"

      // ADR-156: the plugin puts kotlinx-coroutines-core on the consumer's `nativeMain` so a
      // generated `Flow<T>` signature resolves there. Pinned inline for the same reason Kover is
      // (this included build does not consume the root version catalog): if you bump
      // `coroutines` in gradle/libs.versions.toml, bump it here too.
      internal const val COROUTINES_VERSION: String = "$coroutinesVersion"
      """.trimIndent() + "\n",
    )
  }
}

kotlin.sourceSets.named("main") { kotlin.srcDir(generateVersionConstant) }

tasks.processResources {
  from(project.file("../NugetMetadataReader")) {
    into("NugetMetadataReader")
    exclude("bin/**", "obj/**")
  }
}

tasks.test {
  // The reverse dogfooding census restores nine real packages from nuget.org. A feed outage
  // must never turn the ordinary gate (and every PR's matrix) red, so it is tagged out of
  // `test` and lives in `dogfoodCensus` below. The PURE half (RirCensusTest) is untagged and
  // runs here.
  useJUnitPlatform { excludeTags("dogfood") }
  // ADR-182: RirDiagnosticKindTest keeps the forward and reverse diagnostic codes disjoint. No
  // module sees both enums, so it reads the processor's enum source as text.
  val forwardDiagnosticSource: File = rootDir.resolve(
    "../nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/" +
      "forward/ForwardDiagnostic.kt",
  )
  inputs.file(forwardDiagnosticSource).withPropertyName("forwardDiagnosticSource")
  systemProperty("nuget.forwardDiagnosticSource", forwardDiagnosticSource.absolutePath)

  // ADR-193: paths the compile-generated-kotlin test needs. The included build cannot take a
  // project dependency on :nuget-runtime (the dependency runs the other way).
  val runtimeSources: File = rootDir.parentFile.resolve("nuget-runtime/src/nativeMain")
  inputs.dir(runtimeSources).withPropertyName("runtimeSources")
  systemProperty("nuget.runtimeSources", runtimeSources.absolutePath)

  val annotationSource: File = rootDir.parentFile.resolve(
    "nuget-annotations/src/commonMain/kotlin/io/github/xxfast/kotlin/native/nuget/" +
      "annotations/ExperimentalNugetBindingApi.kt",
  )
  inputs.file(annotationSource).withPropertyName("annotationSource")
  systemProperty("nuget.annotationSource", annotationSource.absolutePath)
  systemProperty(
    "nuget.compileWorkDir",
    layout.buildDirectory.dir("compile-generated-kotlin").get().asFile.absolutePath,
  )

  if (kotlinNativeHost == null) {
    systemProperty("nuget.hostActualSet", "unsupported")
  } else {
    // Providers, not the configurations themselves: Gradle 9 will not resolve a configuration
    // while the project is being configured, and configuration cache cannot store one.
    val archivePath: Provider<String> = kotlinNativeCompiler.elements.map { locations ->
      locations.single().asFile.absolutePath
    }
    val coroutinesPath: Provider<String> = hostCoroutinesKlib.elements.map { locations ->
      locations.single().asFile.absolutePath
    }
    inputs.files(kotlinNativeCompiler).withPropertyName("kotlinNativeArchive")
    inputs.files(hostCoroutinesKlib).withPropertyName("coroutinesKlib")
    val actualSet: String = kotlinNativeHost.actualSet
    doFirst {
      systemProperty("nuget.kotlinNativeArchive", archivePath.get())
      systemProperty("nuget.coroutinesKlib", coroutinesPath.get())
      systemProperty("nuget.hostActualSet", actualSet)
    }
  }
}

// scripts/verify-dogfood.sh runs this. `--update` there maps to -Pdogfood.update=true, which
// rewrites every golden in place: the goldens live in the SOURCE tree, not on the test
// classpath, because a classpath copy under build/ would let an update write into the void.
tasks.register<Test>("dogfoodCensus") {
  group = "verification"
  description =
    "Runs the real reverse pipeline over nine pinned published NuGet packages and compares " +
        "the per-package census against its committed golden."
  testClassesDirs = sourceSets.test.get().output.classesDirs
  classpath = sourceSets.test.get().runtimeClasspath
  useJUnitPlatform { includeTags("dogfood") }
  systemProperty(
    "dogfood.goldenDir",
    project.file("src/test/resources/dogfood").absolutePath,
  )
  systemProperty(
    "dogfood.update",
    project.findProperty("dogfood.update")?.toString() ?: "false",
  )
  // Every run reaches the network on a cold NuGet cache and the goldens are exact, so caching a
  // pass would hide upstream drift the weekly schedule exists to catch.
  outputs.upToDateWhen { false }
  testLogging { showStandardStreams = true }
}

gradlePlugin {
  website.set("https://github.com/xxfast/kotlin-native-nuget")
  vcsUrl.set("https://github.com/xxfast/kotlin-native-nuget")

  plugins {
    create("nuget") {
      id = "io.github.xxfast.kotlin.native.nuget"
      implementationClass = "io.github.xxfast.kotlin.native.nuget.NugetPlugin"
      displayName = "Kotlin/Native NuGet"
      description =
        "Packages a Kotlin/Native library as a NuGet package with generated C# bindings, and consumes C# NuGet packages from Kotlin"
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

data class HostKotlinNative(
  val compilerClassifier: String,
  val coroutinesModule: String,
  val actualSet: String,
)

fun hostKotlinNative(): HostKotlinNative? {
  val os: String = System.getProperty("os.name").lowercase()
  val arch: String = System.getProperty("os.arch").lowercase()
  val x64: Boolean = arch == "x86_64" || arch == "amd64"
  val arm64: Boolean = arch == "aarch64" || arch == "arm64"
  if (os.startsWith("mac") && arm64) {
    return HostKotlinNative("macos-aarch64", "macosarm64", "posixMain")
  }
  if (os.startsWith("mac") && x64) {
    return HostKotlinNative("macos-x64", "macosx64", "posixMain")
  }
  if (os.startsWith("linux") && x64) {
    return HostKotlinNative("linux-x64", "linuxx64", "posixMain")
  }
  if (os.startsWith("windows") && x64) {
    return HostKotlinNative("mingw-x64", "mingwx64", "mingwMain")
  }
  return null
}
