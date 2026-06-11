plugins {
  alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
  mingwX64()

  sourceSets {
    commonMain.dependencies {
    }
    commonTest.dependencies {
      implementation(libs.kotlin.test)
    }
  }
}
