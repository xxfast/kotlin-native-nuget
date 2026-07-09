plugins {
  `java-gradle-plugin`
  kotlin("jvm") version "2.4.0"
  kotlin("plugin.serialization") version "2.4.0"
}

group = "io.github.xxfast"
version = "0.1.0"

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
  plugins {
    create("nuget") {
      id = "io.github.xxfast.kotlin.native.nuget"
      implementationClass = "io.github.xxfast.kotlin.native.nuget.NugetPlugin"
    }
  }
}
