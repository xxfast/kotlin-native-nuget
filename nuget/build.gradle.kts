import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.androidMultiplatformLibrary)
}

kotlin {

  androidLibrary {
    namespace = "io.github.xxfast.kotlin.nuget"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    minSdk = libs.versions.android.minSdk.get().toInt()

    compilerOptions {
      jvmTarget = JvmTarget.JVM_11
    }
    androidResources {
      enable = true
    }
    withHostTest {
      isIncludeAndroidResources = true
    }
  }

  sourceSets {
    androidMain.dependencies {
      implementation(libs.compose.uiToolingPreview)
    }
    commonMain.dependencies {
    }
    commonTest.dependencies {
      implementation(libs.kotlin.test)
    }
  }
}

dependencies {
  androidRuntimeClasspath(libs.compose.uiTooling)
}
