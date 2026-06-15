plugins {
  alias(libs.plugins.kotlinJvm)
}

dependencies {
  implementation(libs.ksp.api)
  implementation(libs.kotlinpoet)
  implementation(libs.kotlinpoet.ksp)
}
