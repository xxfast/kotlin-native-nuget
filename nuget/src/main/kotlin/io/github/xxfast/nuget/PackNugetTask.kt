package io.github.xxfast.nuget

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
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

  @get:Input
  abstract val generatedCsDir: Property<String>

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

    val contentDir = File(nupkgDir, "contentFiles/cs/any")
    contentDir.mkdirs()

    val csDir = File(generatedCsDir.get())
    val csFiles: List<File> = csDir.listFiles()
      ?.filter { it.extension == "cs" }
      ?: emptyList()

    for (csFile in csFiles) {
      csFile.copyTo(File(contentDir, csFile.name), overwrite = true)
    }

    val buildDir = File(nupkgDir, "build")
    buildDir.mkdirs()

    File(buildDir, "$id.targets").writeText(generateTargets(id))
    File(nupkgDir, "$id.nuspec").writeText(generateNuspec(id, version, csFiles))

    logger.lifecycle("NuGet package staged at: ${nupkgDir.absolutePath}")
  }

  @Suppress("HttpUrlsUsage")
  private fun generateTargets(id: String): String = """
    |<Project xmlns="http://schemas.microsoft.com/developer/msbuild/2003">
    |  <PropertyGroup>
    |    <AllowUnsafeBlocks>true</AllowUnsafeBlocks>
    |  </PropertyGroup>
    |</Project>
  """.trimMargin()

  private fun generateNuspec(
    id: String,
    version: String,
    csFiles: List<File>,
  ): String {
    val fileEntries: String = csFiles.joinToString("\n") { file ->
      "      <file src=\"contentFiles/cs/any/${file.name}\" target=\"contentFiles/cs/any/${file.name}\" />"
    }

    return """
      |<?xml version="1.0" encoding="utf-8"?>
      |<package xmlns="http://schemas.microsoft.com/packaging/2013/05/nuspec.xsd">
      |  <metadata>
      |    <id>$id</id>
      |    <version>$version</version>
      |    <authors>${authors.get()}</authors>
      |    <description>${packageDescription.get()}</description>
      |    <contentFiles>
      |      <files include="cs/any/**/*.cs" buildAction="Compile" />
      |    </contentFiles>
      |  </metadata>
      |  <files>
      |$fileEntries
      |  </files>
      |</package>
    """.trimMargin()
  }
}
