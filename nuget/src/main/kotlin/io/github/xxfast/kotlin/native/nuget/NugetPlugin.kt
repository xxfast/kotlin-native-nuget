package io.github.xxfast.kotlin.native.nuget

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.SharedLibrary

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
      project.pluginManager.apply("com.google.devtools.ksp")

      val kotlin: KotlinMultiplatformExtension =
        project.extensions.getByType(KotlinMultiplatformExtension::class.java)

      project.pluginManager.withPlugin("com.google.devtools.ksp") {
        project.afterEvaluate {
          val ksp = project.extensions.getByType(
            Class.forName("com.google.devtools.ksp.gradle.KspExtension")
          )

          val baseName: String? = kotlin.targets
            .filterIsInstance<KotlinNativeTarget>()
            .flatMap { it.binaries.filterIsInstance<SharedLibrary>() }
            .firstOrNull()?.baseName

          val kspClass = ksp.javaClass
          val argMethod = kspClass.getMethod("arg", String::class.java, String::class.java)
          argMethod.invoke(ksp, "nuget.libraryName", baseName ?: "library")
          argMethod.invoke(ksp, "nuget.namespace", extension.packageId.get())
          argMethod.invoke(ksp, "nuget.rootPackage", extension.rootPackage.getOrElse(""))
          argMethod.invoke(ksp, "nuget.className", "${extension.packageId.get()}Native")
        }
      }

      project.afterEvaluate {
        val nativeTargets: List<KotlinNativeTarget> =
          kotlin.targets.filterIsInstance<KotlinNativeTarget>()

        val libDirs: MutableMap<String, String> = mutableMapOf()
        val linkTasks: MutableList<Any> = mutableListOf()
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

          if (sharedLib.linkTaskProvider.get().enabled) {
            linkTasks.add(sharedLib.linkTaskProvider)
          }

          if (baseName == null) {
            baseName = sharedLib.baseName
          }
        }

        if (libDirs.isEmpty()) return@afterEvaluate

        // KSP generates Interop.cs at:
        // build/generated/ksp/<target>/<target>Main/resources/Interop.cs
        // Pick the first available target's output
        val firstTarget: String = nativeTargets
          .first { KONAN_TO_RID.containsKey(it.konanTarget.name) }
          .name

        val kspOutputDir = project.layout.buildDirectory
          .dir("generated/ksp/$firstTarget/${firstTarget}Main/resources")

        project.tasks.register("packNuget", PackNugetTask::class.java)
          .configure { task ->
            task.group = "nuget"
            task.description = "Packages the Kotlin/Native shared library as a NuGet package"
            task.packageId.convention(extension.packageId)
            task.packageVersion.convention(extension.version)
            task.authors.convention(extension.authors)
            task.packageDescription.convention(extension.description)
            task.nativeLibDirs.set(libDirs)
            task.nativeLibFiles.from(libDirs.values.map { project.fileTree(it) })
            task.generatedCsDir.set(kspOutputDir)
            task.outputDir.set(project.layout.buildDirectory.dir("nuget"))

            for (linkTask in linkTasks) {
              task.dependsOn(linkTask)
            }

            task.dependsOn("kspKotlin${firstTarget.replaceFirstChar { it.uppercase() }}")
          }
      }
    }
  }
}
