package io.github.xxfast.kotlin.native.nuget

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private val NATIVE_EXTENSIONS = setOf("dll", "dylib", "so")

// The .cs files nugetPack stages into contentFiles/cs/<tfm>/, in staging order. Deduped by file
// name - if the same name appears in more than one source dir, the last one wins (matches
// copyTo's overwrite = true applied in iteration order). ADR-138: nugetCompileInterop compiles
// exactly this set, so the check and the pack can never disagree about what ships.
internal fun generatedCsFiles(dirs: Iterable<File>): List<File> = dirs
  .flatMap { dir -> dir.listFiles()?.filter { it.extension == "cs" } ?: emptyList() }
  .distinctBy { it.name }

abstract class NugetPackTask : DefaultTask() {
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
  // nugetPack can merge .cs files from multiple producers — KSP's forward Interop.cs and
  // nugetGenerateShims's reverse registration shims — into one contentFiles/cs/<tfm>/ folder.
  @get:InputFiles
  abstract val generatedCsDirs: ConfigurableFileCollection

  // ADR-050 Alternative 5: per bound package, the exact version NuGet resolved (from
  // project.assets.json), driving the .nuspec <dependencies> block. Empty when there are no bound
  // dependencies (publish-only projects).
  @get:Input
  abstract val dependencyVersions: MapProperty<String, String>

  // ADR-093: native libraries built on another host, laid out as <dir>/<rid>/native/*.dll|dylib|so.
  // Staged after the locally linked RIDs into the same runtimes/ tree, so one pack produces one
  // package covering more RIDs than this host can link.
  @get:Optional
  @get:InputDirectory
  abstract val prebuiltRuntimesDir: DirectoryProperty

  // ADR-184: the package's floor TFM. Names the contentFiles folder, the empty lib/<tfm>/_._
  // placeholder and the nuspec dependency group, so NuGet rejects a lower consumer with NU1202.
  @get:Input
  abstract val targetFramework: Property<String>

  init {
    targetFramework.convention(DEFAULT_TARGET_FRAMEWORK)
  }

  @get:OutputDirectory
  abstract val outputDir: DirectoryProperty

  // ADR-180: whether any native target maps to a supported RID, linkable here or not. False means no
  // supported target at all, which used to leave nugetPack unregistered and now fails the task.
  @get:Internal
  abstract val hasSupportedTargets: Property<Boolean>

  // ADR-093 / ADR-180: RID to target name for each supported target whose link task is disabled
  // on this host. Logged when the task runs; the same RIDs are absent from nativeLibDirs.
  @get:Internal
  abstract val skippedRids: MapProperty<String, String>

  @TaskAction
  fun pack() {
    val id: String = packageId.get()
    require(id.isNotBlank()) {
      "[nuget] ${NugetTaskNames.PACK} needs a non-blank package id: " +
        "set nuget { publish { packageId = \"...\" } }"
    }
    val version: String = packageVersion.get()

    check(hasSupportedTargets.getOrElse(true)) {
      "[nuget] No supported native targets found (expected mingw or macOS) in $path, so " +
        "${NugetTaskNames.PACK} has nothing to pack. Add a supported Kotlin/Native target."
    }

    skippedRids.get().forEach { (rid, target) ->
      logger.lifecycle(
        "[nuget] Skipping RID '$rid': the link task for target '$target' is disabled " +
          "on this host. Supply it from another host via " +
          "nuget { publish { prebuiltRuntimes = ... } } to ship it in this package."
      )
    }

    // Only when the plugin wired the target facts (it always sets `hasSupportedTargets`): a task
    // configured by hand may legitimately stage C# only.
    val wired: Boolean = hasSupportedTargets.isPresent
    check(!wired || nativeLibDirs.get().isNotEmpty() || prebuiltRuntimesDir.isPresent) {
      "[nuget] No native library to pack in $path: every supported target's link task is " +
        "disabled on this host and no prebuiltRuntimes is set. Build on a host that can link " +
        "one, or set nuget { publish { prebuiltRuntimes = ... } }."
    }
    val outDir: File = outputDir.get().asFile

    val nupkgDir = File(outDir, "$id.$version")
    nupkgDir.deleteRecursively()
    nupkgDir.mkdirs()

    val localRids: Map<String, String> = nativeLibDirs.get()

    localRids.forEach { (rid, libPath) ->
      val sourceDir = File(libPath)
      val libs: List<File> = nativeLibsIn(sourceDir)

      // ADR-093: targets whose link task is disabled on this host never reach nativeLibDirs, so an
      // entry with nothing to copy means the link ran and produced nothing. Silently skipping it
      // shipped packages missing a platform.
      check(libs.isNotEmpty()) {
        "No native library (.dll, .dylib, .so) found for RID '$rid' in " +
            "${sourceDir.absolutePath}. The link task for this target produced nothing to pack."
      }

      validateNativeLibs(id, rid, sourceDir, libs)

      copyNativeLibs(libs, File(nupkgDir, "runtimes/$rid/native"))
    }

    stagePrebuiltRuntimes(nupkgDir, localRids)

    val tfm: String = targetFramework.get()
    val contentDir = File(nupkgDir, "contentFiles/cs/$tfm")
    contentDir.mkdirs()

    val csFiles: List<File> = generatedCsFiles(generatedCsDirs.files)

    for (csFile in csFiles) {
      csFile.copyTo(File(contentDir, csFile.name), overwrite = true)
    }

    // ADR-184: without a lib/<tfm>/ entry NuGet treats a contentFiles-only package as compatible
    // with every TFM and silently drops its dependency group for a lower consumer (memo spike, Q/R).
    val libDir = File(nupkgDir, "lib/$tfm")
    libDir.mkdirs()
    File(libDir, "_._").writeText("")

    // ADR-184: build/<tfm>/, not root build/. A root build/<id>.targets is an asset for every TFM,
    // so NuGet counts the package compatible with a lower consumer and restores it without NU1202,
    // compiling none of the source (verified: a net8.0 consumer of the packed TestLibrary).
    val buildDir = File(nupkgDir, "build/$tfm")
    buildDir.mkdirs()

    File(buildDir, "$id.targets").writeText(generateTargets(id))
    File(nupkgDir, "$id.nuspec").writeText(
      generateNuspec(id, version, tfm, csFiles, dependencyVersions.get())
    )

    logger.lifecycle("NuGet package staged at: ${nupkgDir.absolutePath}")

    val nupkgFile = File(outDir, "$id.$version.nupkg")
    writeNupkgZip(nupkgDir, nupkgFile, id)

    logger.lifecycle("NuGet package written at: ${nupkgFile.absolutePath}")
  }

  private fun nativeLibsIn(dir: File): List<File> =
    dir.listFiles()?.filter { it.isFile && it.extension.lowercase() in NATIVE_EXTENSIONS }.orEmpty()

  private fun copyNativeLibs(libs: List<File>, targetDir: File) {
    targetDir.mkdirs()
    libs.forEach { lib -> lib.copyTo(File(targetDir, lib.name), overwrite = true) }
  }

  private fun validateNativeLibs(id: String, rid: String, dir: File, libs: List<File>) {
    val expected: String = nativeLibraryFile(id, rid)
    require(libs.size == 1 && libs.single().name == expected) {
      "[nuget] RID '$rid' in ${dir.absolutePath} must contain exactly '$expected'; found " +
        libs.joinToString { it.name } + ". Rebuild the primary binary with this package identity " +
        "and migrate prebuilt assets. Auxiliary native libraries are not supported."
    }
  }

  // ADR-093: merges another host's runtimes/ tree into this pack. Every failure here is a CI
  // misconfiguration (an artifact download that fetched nothing, or two hosts producing the same
  // RID), so each one names the RID rather than shipping a package whose contents depend on
  // iteration order.
  private fun stagePrebuiltRuntimes(nupkgDir: File, localRids: Map<String, String>) {
    if (!prebuiltRuntimesDir.isPresent) return

    val root: File = prebuiltRuntimesDir.get().asFile
    val ridDirs: List<File> = root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }
      .orEmpty()

    require(ridDirs.isNotEmpty()) {
      "prebuiltRuntimes directory ${root.absolutePath} has no RID subdirectory. Expected layout: " +
          "<prebuiltRuntimes>/<rid>/native/ containing at least one .dll, .dylib or .so file."
    }

    ridDirs.forEach { ridDir ->
      val rid: String = ridDir.name
      val localPath: String? = localRids[rid]

      require(localPath == null) {
        "RID '$rid' is both linked locally (from $localPath) and supplied as a prebuilt runtime " +
            "(from ${ridDir.absolutePath}). Pick one producer per RID: disable the local " +
            "target, or drop the RID from prebuiltRuntimes."
      }

      val nativeDir = File(ridDir, "native")
      val libs: List<File> = nativeLibsIn(nativeDir)

      require(libs.isNotEmpty()) {
        "Prebuilt RID '$rid' contributes no native library. Expected " +
            "${nativeDir.absolutePath} to contain at least one .dll, .dylib or .so file " +
            "(layout: <prebuiltRuntimes>/<rid>/native/)."
      }

      validateNativeLibs(packageId.get(), rid, nativeDir, libs)

      // ADR-093: the RID set NuGet accepts is open, so an unknown name may be a legitimate
      // artifact from a newer plugin on the other host. Warn, do not block the pack.
      if (rid !in KONAN_TO_RID.values) {
        logger.warn(
          "w: [nuget] Prebuilt RID '$rid' is not one this plugin version can build " +
              "(${KONAN_TO_RID.values.sorted().joinToString(", ")}). Packing it as given."
        )
      }

      copyNativeLibs(libs, File(nupkgDir, "runtimes/$rid/native"))
    }
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
    tfm: String,
    csFiles: List<File>,
    dependencyVersions: Map<String, String>,
  ): String {
    val fileEntries: String = (
      csFiles.map { file ->
        "      <file src=\"contentFiles/cs/$tfm/${file.name}\" " +
            "target=\"contentFiles/cs/$tfm/${file.name}\" />"
      } + "      <file src=\"lib/$tfm/_._\" target=\"lib/$tfm/_._\" />"
    ).joinToString("\n")

    val ranges: Map<String, String> = dependencyRanges(dependencyVersions)
    val dependenciesBlock: String = if (ranges.isEmpty()) {
      ""
    } else {
      val entries: String = ranges.entries.joinToString("\n") { (depId, depVersion) ->
        "        <dependency id=\"$depId\" version=\"$depVersion\" />"
      }
      """
        |    <dependencies>
        |      <group targetFramework="$tfm">
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
      |      <files include="cs/$tfm/**/*.cs" buildAction="Compile" />
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
