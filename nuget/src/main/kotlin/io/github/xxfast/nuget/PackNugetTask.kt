package io.github.xxfast.nuget

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File

abstract class PackNugetTask : DefaultTask() {
  @get:Input
  abstract val packageId: Property<String>

  @get:Input
  abstract val packageVersion: Property<String>

  @get:Input
  abstract val authors: Property<String>

  @get:Input
  abstract val packageDescription: Property<String>

  @get:Input
  abstract val nativeLibDirs: MapProperty<String, String>

  @get:OutputDirectory
  abstract val outputDir: DirectoryProperty

  @TaskAction
  fun pack() {
    val id: String = packageId.get()
    val version: String = packageVersion.get()
    val outDir: File = outputDir.get().asFile

    val nupkgDir = File(outDir, "$id.$version")
    nupkgDir.deleteRecursively()
    nupkgDir.mkdirs()

    for ((rid, libPath) in nativeLibDirs.get()) {
      val nativeDir = File(nupkgDir, "runtimes/$rid/native")
      nativeDir.mkdirs()

      val libs: List<File> = File(libPath).listFiles()
        ?.filter { it.extension in listOf("dll", "dylib", "so") }
        ?: continue

      for (lib in libs) {
        lib.copyTo(File(nativeDir, lib.name), overwrite = true)
      }
    }

    val nuspec = """
      |<?xml version="1.0" encoding="utf-8"?>
      |<package xmlns="http://schemas.microsoft.com/packaging/2013/05/nuspec.xsd">
      |  <metadata>
      |    <id>$id</id>
      |    <version>$version</version>
      |    <authors>${authors.get()}</authors>
      |    <description>${packageDescription.get()}</description>
      |  </metadata>
      |</package>
    """.trimMargin()

    File(nupkgDir, "$id.nuspec").writeText(nuspec)
    logger.lifecycle("NuGet package staged at: ${nupkgDir.absolutePath}")
  }
}
