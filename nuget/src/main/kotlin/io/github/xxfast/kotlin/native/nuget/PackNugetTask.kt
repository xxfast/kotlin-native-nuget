package io.github.xxfast.kotlin.native.nuget

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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

  @get:InputFiles
  abstract val nativeLibFiles: ConfigurableFileCollection

  // ADR-050 Alternative 3: a ConfigurableFileCollection (not a single DirectoryProperty) so
  // packNuget can merge .cs files from multiple producers — KSP's forward Interop.cs and
  // nugetGenerateShims's reverse registration shims — into one contentFiles/cs/any/ folder.
  @get:InputFiles
  abstract val generatedCsDirs: ConfigurableFileCollection

  // ADR-050 Alternative 5: per bound package, the exact version NuGet resolved (from
  // project.assets.json), driving the .nuspec <dependencies> block. Empty when there are no bound
  // dependencies (publish-only projects).
  @get:Input
  abstract val dependencyVersions: MapProperty<String, String>

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

    // Merge .cs files from every generatedCsDirs entry. Dedupe by file name — if the same name
    // appears in more than one source dir, the last one wins (matches copyTo's overwrite = true
    // applied in iteration order).
    val csFiles: List<File> = generatedCsDirs.files
      .flatMap { dir -> dir.listFiles()?.filter { it.extension == "cs" } ?: emptyList() }
      .distinctBy { it.name }

    for (csFile in csFiles) {
      csFile.copyTo(File(contentDir, csFile.name), overwrite = true)
    }

    val buildDir = File(nupkgDir, "build")
    buildDir.mkdirs()

    File(buildDir, "$id.targets").writeText(generateTargets(id))
    File(nupkgDir, "$id.nuspec").writeText(
      generateNuspec(id, version, csFiles, dependencyVersions.get())
    )

    logger.lifecycle("NuGet package staged at: ${nupkgDir.absolutePath}")

    val nupkgFile = File(outDir, "$id.$version.nupkg")
    writeNupkgZip(nupkgDir, nupkgFile, id)

    logger.lifecycle("NuGet package written at: ${nupkgFile.absolutePath}")
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
    dependencyVersions: Map<String, String>,
  ): String {
    val fileEntries: String = csFiles.joinToString("\n") { file ->
      "      <file src=\"contentFiles/cs/any/${file.name}\" " +
        "target=\"contentFiles/cs/any/${file.name}\" />"
    }

    val dependenciesBlock: String = if (dependencyVersions.isEmpty()) {
      ""
    } else {
      val entries: String = dependencyVersions.entries.joinToString("\n") { (depId, depVersion) ->
        "        <dependency id=\"$depId\" version=\"[$depVersion]\" />"
      }
      """
        |    <dependencies>
        |      <group targetFramework="net8.0">
        |$entries
        |      </group>
        |    </dependencies>
      """.trimMargin()
    }

    return """
      |<?xml version="1.0" encoding="utf-8"?>
      |<package xmlns="http://schemas.microsoft.com/packaging/2013/05/nuspec.xsd">
      |  <metadata>
      |    <id>$id</id>
      |    <version>$version</version>
      |    <authors>${authors.get()}</authors>
      |    <description>${packageDescription.get()}</description>
      |$dependenciesBlock
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

  // Zips the already-staged folder into a real .nupkg — a valid OPC (Open Packaging Conventions)
  // ZIP package, per the "known-good minimal .nupkg layout": `[Content_Types].xml` at the root,
  // `_rels/.rels` declaring the manifest + core-properties relationships, and a
  // `package/services/metadata/core-properties/{guid}.psmdcp` part, alongside the staged payload
  // (the .nuspec, contentFiles/, runtimes/, build/). Pure java.util.zip — no external dependency.
  private fun writeNupkgZip(stagedDir: File, nupkgFile: File, id: String) {
    nupkgFile.delete()

    val stagedFiles: List<File> = stagedDir.walkTopDown().filter { it.isFile }.toList()

    val psmdcpFileName = "${UUID.randomUUID().toString().replace("-", "")}.psmdcp"
    val psmdcpRelPath = "package/services/metadata/core-properties/$psmdcpFileName"

    val payloadExtensions: Set<String> = stagedFiles
      .map { it.extension.lowercase() }
      .filter { it.isNotEmpty() }
      .toSet()
    val allExtensions: Set<String> = payloadExtensions + setOf("rels", "psmdcp")

    ZipOutputStream(nupkgFile.outputStream()).use { zip ->
      writeZipEntry(zip, "[Content_Types].xml", buildContentTypesXml(allExtensions))
      writeZipEntry(zip, "_rels/.rels", buildRelsXml(id, psmdcpRelPath))
      writeZipEntry(zip, psmdcpRelPath, buildCorePropertiesXml(id))

      stagedFiles.forEach { file ->
        val relPath: String = file.relativeTo(stagedDir).invariantSeparatorsPath
        zip.putNextEntry(ZipEntry(relPath))
        file.inputStream().use { input -> input.copyTo(zip) }
        zip.closeEntry()
      }
    }
  }

  private fun writeZipEntry(zip: ZipOutputStream, path: String, content: String) {
    zip.putNextEntry(ZipEntry(path))
    zip.write(content.toByteArray(Charsets.UTF_8))
    zip.closeEntry()
  }

  @Suppress("HttpUrlsUsage")
  private fun buildContentTypesXml(extensions: Set<String>): String {
    val defaults: String = extensions.sorted().joinToString("\n") { ext ->
      val contentType: String = when (ext) {
        "rels" -> "application/vnd.openxmlformats-package.relationships+xml"
        "psmdcp" -> "application/vnd.openxmlformats-package.core-properties+xml"
        else -> "application/octet"
      }
      "  <Default Extension=\"$ext\" ContentType=\"$contentType\" />"
    }

    return """
      |<?xml version="1.0" encoding="utf-8"?>
      |<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
      |$defaults
      |</Types>
    """.trimMargin()
  }

  @Suppress("HttpUrlsUsage")
  private fun buildRelsXml(id: String, psmdcpRelPath: String): String = """
    |<?xml version="1.0" encoding="utf-8"?>
    |<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
    |  <Relationship Type="http://schemas.microsoft.com/packaging/2010/07/manifest" Target="/$id.nuspec" Id="R0" />
    |  <Relationship Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="/$psmdcpRelPath" Id="R1" />
    |</Relationships>
  """.trimMargin()

  @Suppress("HttpUrlsUsage")
  private fun buildCorePropertiesXml(id: String): String = """
    |<?xml version="1.0" encoding="utf-8"?>
    |<coreProperties xmlns="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
    |  <dc:creator>${authors.get()}</dc:creator>
    |  <dc:identifier>$id</dc:identifier>
    |  <version>${packageVersion.get()}</version>
    |  <keywords></keywords>
    |  <lastModifiedBy>nuget-gradle-plugin</lastModifiedBy>
    |</coreProperties>
  """.trimMargin()
}
