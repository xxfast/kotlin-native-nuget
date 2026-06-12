package io.github.xxfast.nuget

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.SharedLibrary
import java.io.File

private val KONAN_TO_RID = mapOf(
  "mingw_x64" to "win-x64",
  "macos_arm64" to "osx-arm64",
  "macos_x64" to "osx-x64",
  "linux_x64" to "linux-x64",
  "linux_arm64" to "linux-arm64",
)

class NugetPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val extension: NugetExtension =
      project.extensions.create("nuget", NugetExtension::class.java)

    project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
      val kotlin: KotlinMultiplatformExtension =
        project.extensions.getByType(KotlinMultiplatformExtension::class.java)

      project.afterEvaluate {
        val nativeTargets: List<KotlinNativeTarget> =
          kotlin.targets.filterIsInstance<KotlinNativeTarget>()

        val libDirs: MutableMap<String, String> = mutableMapOf()
        val linkTasks: MutableList<Any> = mutableListOf()
        var headerFile: File? = null
        var baseName: String? = null

        for (target in nativeTargets) {
          val rid: String = KONAN_TO_RID[target.konanTarget.name] ?: continue

          val sharedLib: SharedLibrary = target.binaries
            .filterIsInstance<SharedLibrary>()
            .firstOrNull { it.buildType.name == "RELEASE" }
            ?: target.binaries
              .filterIsInstance<SharedLibrary>()
              .firstOrNull()
            ?: continue

          libDirs[rid] = sharedLib.outputDirectory.absolutePath
          linkTasks.add(sharedLib.linkTaskProvider)

          if (baseName == null) {
            baseName = sharedLib.baseName
          }

          // Prefer mingw header (no 'lib' prefix) for cross-platform compatibility
          if (headerFile == null || target.konanTarget.name == "mingw_x64") {
            val headerName: String = "${sharedLib.baseName}_api.h"
            headerFile = File(sharedLib.outputDirectory, headerName)
          }
        }

        if (libDirs.isEmpty()) return@afterEvaluate

        project.tasks.register("packNuget", PackNugetTask::class.java)
          .configure { task ->
            task.group = "nuget"
            task.description = "Packages the Kotlin/Native shared library as a NuGet package"
            task.packageId.convention(extension.packageId)
            task.packageVersion.convention(extension.version)
            task.authors.convention(extension.authors)
            task.packageDescription.convention(extension.description)
            task.libraryName.convention(baseName ?: "library")
            task.nativeLibDirs.set(libDirs)
            task.headerFilePath.set(headerFile!!.absolutePath)
            task.outputDir.set(project.layout.buildDirectory.dir("nuget"))

            // Ensure the pack task runs after the native libraries are built
            linkTasks.forEach { task.dependsOn(it) }
          }
      }
    }
  }
}
