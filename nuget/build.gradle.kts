plugins {
  `java-gradle-plugin`
  kotlin("jvm") version "2.4.0"
}

group = "io.github.xxfast"
version = "0.1.0"

repositories {
  mavenCentral()
}

dependencies {
  implementation(kotlin("gradle-plugin-api"))
  implementation(kotlin("gradle-plugin"))
}

gradlePlugin {
  plugins {
    create("nuget") {
      id = "io.github.xxfast.nuget"
      implementationClass = "io.github.xxfast.nuget.NugetPlugin"
    }
  }
}
