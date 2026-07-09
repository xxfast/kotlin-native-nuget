plugins {
  alias(libs.plugins.kotlinMultiplatform) apply false
  alias(libs.plugins.kotlinJvm) apply false
  alias(libs.plugins.mavenPublish) apply false
  id("io.github.xxfast.kotlin.native.nuget") apply false
}
