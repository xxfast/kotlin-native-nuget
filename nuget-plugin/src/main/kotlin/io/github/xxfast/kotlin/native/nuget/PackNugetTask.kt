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

// #618: the directory under outputDir where packNuget writes every producer of the pack in the
// layout prebuiltRuntimes reads (<rid>/native/<lib>, <rid>/ForwardAbi.json, <rid>/Interop.cs).
// It is what one host uploads for the next; the package itself carries only <rid>/native/.
internal const val PREBUILT_RUNTIMES_DIR = "prebuilt-runtimes"

private val CONTRACT_FILES = listOf("ForwardAbi.json", "Interop.cs")

// The .cs files packNuget stages into contentFiles/cs/<tfm>/, in staging order. Deduped by file
// name - if the same name appears in more than one source dir, the last one wins (matches
// copyTo's overwrite = true applied in iteration order). ADR-138: nugetCompileInterop compiles
// exactly this set, so the check and the pack can never disagree about what ships.
internal fun generatedCsFiles(dirs: Iterable<File>): List<File> = dirs
  .flatMap { dir -> dir.listFiles()?.filter { it.extension == "cs" } ?: emptyList() }
  .distinctBy { it.name }

public abstract class PackNugetTask : DefaultTask() {
  @get:Input
  public abstract val packageId: Property<String>

  @get:Input
  public abstract val packageVersion: Property<String>

  @get:Input
  public abstract val authors: Property<String>

  @get:Input
  public abstract val packageDescription: Property<String>

  @get:Input
  public abstract val nativeLibDirs: MapProperty<String, String>

  @get:Input
  public abstract val localContractDirs: MapProperty<String, String>

  @get:InputFiles
  public abstract val contractFiles: ConfigurableFileCollection

  @get:InputFiles
  public abstract val nativeLibFiles: ConfigurableFileCollection

  // ADR-050 Alternative 3: a ConfigurableFileCollection (not a single DirectoryProperty) so
  // packNuget can merge .cs files from multiple producers — KSP's forward Interop.cs and
  // nugetGenerateShims's reverse registration shims — into one contentFiles/cs/<tfm>/ folder.
  @get:InputFiles
  public abstract val generatedCsDirs: ConfigurableFileCollection

  // ADR-050 Alternative 5: per bound package, the exact version NuGet resolved (from
  // project.assets.json), driving the .nuspec <dependencies> block. Empty when there are no bound
  // dependencies (publish-only projects).
  @get:Input
  public abstract val dependencyVersions: MapProperty<String, String>

  // ADR-093 / ADR-189: another host's prebuiltRuntimesOutputDir, laid out as
  // <dir>/<rid>/native/<lib> plus <dir>/<rid>/ForwardAbi.json and <dir>/<rid>/Interop.cs. Only the
  // native libraries are staged into the package's runtimes/ tree, after the locally linked RIDs,
  // so one pack produces one package covering more RIDs than this host can link.
  @get:Optional
  @get:InputDirectory
  public abstract val prebuiltRuntimesDir: DirectoryProperty

  // ADR-184: the package's floor TFM. Names the contentFiles folder, the empty lib/<tfm>/_._
  // placeholder and the nuspec dependency group, so NuGet rejects a lower consumer with NU1202.
  @get:Input
  public abstract val targetFramework: Property<String>

  @get:OutputDirectory
  public abstract val outputDir: DirectoryProperty

  // #618: every producer of this pack, local and prebuilt, in the layout prebuiltRuntimesDir reads,
  // so the next host can chain it. Kept out of the package staging dir: no consumer reads the
  // ADR-189 sidecars, they exist only for the cross-target contract check.
  @get:OutputDirectory
  public abstract val prebuiltRuntimesOutputDir: DirectoryProperty

  init {
    targetFramework.convention(DEFAULT_TARGET_FRAMEWORK)
    localContractDirs.convention(emptyMap())
    prebuiltRuntimesOutputDir.convention(outputDir.dir(PREBUILT_RUNTIMES_DIR))
  }

  // ADR-180: whether any native target maps to a supported RID, linkable here or not. False means no
  // supported target at all, which used to leave packNuget unregistered and now fails the task.
  @get:Internal
  public abstract val hasSupportedTargets: Property<Boolean>

  // ADR-093 / ADR-180: RID to target name for each supported target whose link task is disabled
  // on this host. Logged when the task runs; the same RIDs are absent from nativeLibDirs.
  @get:Internal
  public abstract val skippedRids: MapProperty<String, String>

  @TaskAction
  public fun pack() {
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
    val transferDir: File = prebuiltRuntimesOutputDir.get().asFile
    requireDistinctPrebuiltInput(transferDir)
    nativeLibDirs.get().forEach { (rid, path) -> linkedNativeLib(id, rid, File(path)) }
    val producers: List<ForwardPackageProducer> = forwardPackageProducers(
      nativeLibDirs.get(), localContractDirs.get(), prebuiltRuntimesDir.orNull?.asFile,
    )
    producers.filter { it.rid !in nativeLibDirs.get() }.forEach { producer ->
      val dir = File(producer.directory, "native")
      val libs = nativeLibsIn(dir)
      require(libs.isNotEmpty()) { "Prebuilt RID '${producer.rid}' contributes no native library in ${dir.absolutePath}. Expected <prebuiltRuntimes>/<rid>/native/." }
      validateNativeLibs(id, producer.rid, dir, libs)
    }
    validateForwardPackageContracts(producers)
    val outDir: File = outputDir.get().asFile

    val nupkgDir = File(outDir, "$id.$version")
    nupkgDir.deleteRecursively()
    nupkgDir.mkdirs()

    val localRids: Map<String, String> = nativeLibDirs.get()

    localRids.forEach { (rid, libPath) ->
      val lib: File = linkedNativeLib(id, rid, File(libPath))
      copyNativeLibs(listOf(lib), File(nupkgDir, "runtimes/$rid/native"))
    }

    stagePrebuiltRuntimes(nupkgDir, localRids)
    writePrebuiltRuntimesOutput(id, transferDir, producers, localRids)

    val tfm: String = targetFramework.get()
    val contentDir = File(nupkgDir, "contentFiles/cs/$tfm")
    contentDir.mkdirs()

    generatedCsDirs.files.forEach(::requireGeneratedCsDir)
    val csFiles: List<File> = generatedCsFiles(generatedCsDirs.files).filter {
      producers.isEmpty() || it.name != "Interop.cs"
    } + producers.take(1).map { File(it.directory, "Interop.cs") }

    for (csFile in csFiles) {
      csFile.copyTo(File(contentDir, csFile.name), overwrite = true)
    }

    // ADR-184: without a lib/<tfm>/ entry NuGet treats a contentFiles-only package as compatible
    // with every TFM and silently drops its dependency group for a lower consumer (memo
    // spike, Q/R).
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

  // The ADR-093 fail-fast, applied to generatedCsDirs: every entry comes from a task-managed
  // output directory, so a missing one means its producer was mis-wired or never ran, and
  // skipping it would ship a package without those bindings. An existing directory with no .cs
  // files stays legal.
  private fun requireGeneratedCsDir(dir: File) {
    check(dir.isDirectory) {
      val state: String = if (dir.exists()) "is not a directory" else "does not exist"
      "[nuget] Generated C# directory ${dir.absolutePath} $state. It should have been created " +
        "by ${generatedCsProducer(dir)}; check that task ran and still writes there."
    }
  }

  // Names the task expected to produce a generatedCsDirs entry, from the layouts the plugin wires:
  // KSP's `generated/ksp/<target>/<target>Main/resources` and the shims' `nuget-interop/csharp`.
  private fun generatedCsProducer(dir: File): String {
    val segments: List<String> = dir.invariantSeparatorsPath.split('/')
    val ksp: Int = segments.indexOf("ksp")
    if (ksp > 0 && segments[ksp - 1] == "generated" && ksp + 1 < segments.size) {
      val target: String = segments[ksp + 1]
      return "kspKotlin${target.replaceFirstChar { it.uppercase() }} (the forward Interop.cs)"
    }
    if (segments.takeLast(2) == listOf("nuget-interop", "csharp")) {
      return "${NugetTaskNames.GENERATE_SHIMS} (the reverse registration shims)"
    }
    return "the task configured to produce it"
  }

  private fun nativeLibsIn(dir: File): List<File> =
    dir.listFiles()?.filter { it.isFile && it.extension.lowercase() in NATIVE_EXTENSIONS }.orEmpty()

  private fun copyNativeLibs(libs: List<File>, targetDir: File) {
    targetDir.mkdirs()
    libs.forEach { lib -> lib.copyTo(File(targetDir, lib.name), overwrite = true) }
  }

  // #469: link output is taken by name. The link directory keeps whatever an earlier build left
  // there (a library under an old `baseName`), so only the expected file is required and shipped.
  private fun linkedNativeLib(id: String, rid: String, dir: File): File {
    val libs: List<File> = nativeLibsIn(dir)

    // ADR-093: targets whose link task is disabled on this host never reach nativeLibDirs, so an
    // entry with nothing to copy means the link ran and produced nothing. Silently skipping it
    // shipped packages missing a platform.
    check(libs.isNotEmpty()) {
      "No native library (.dll, .dylib, .so) found for RID '$rid' in " +
        "${dir.absolutePath}. The link task for this target produced nothing to pack."
    }

    val expected: String = nativeLibraryFile(id, rid)
    val lib: File? = libs.firstOrNull { it.name == expected }
    require(lib != null) {
      "[nuget] RID '$rid' in ${dir.absolutePath} has no '$expected'; found " +
        libs.joinToString { it.name } + ". The native library is now named from packageId " +
        "(ADR-178), so a file under an old baseName is a leftover. Remove the baseName setting " +
        "and relink."
    }
    return lib
  }

  private fun validateNativeLibs(id: String, rid: String, dir: File, libs: List<File>) {
    val expected: String = nativeLibraryFile(id, rid)
    require(libs.size == 1 && libs.single().name == expected) {
      "[nuget] RID '$rid' in ${dir.absolutePath} must contain exactly '$expected'; found " +
        libs.joinToString { it.name } + ". Rebuild the primary binary with this package identity " +
        "and migrate prebuilt assets. Auxiliary native libraries are not supported."
    }
  }

  // #618: the transferable tree is deleted and rewritten on every pack, so a prebuilt input inside
  // it would be destroyed before it is copied. Typically an artifact downloaded back into the
  // directory this host uploads from.
  private fun requireDistinctPrebuiltInput(transferDir: File) {
    val input: File = prebuiltRuntimesDir.orNull?.asFile?.canonicalFile ?: return
    val output: File = transferDir.canonicalFile
    require(!input.startsWith(output)) {
      "[nuget] prebuiltRuntimes ${input.path} is inside ${output.path}, the " +
        "$PREBUILT_RUNTIMES_DIR tree ${NugetTaskNames.PACK} rewrites on every run. Download " +
        "the other host's $PREBUILT_RUNTIMES_DIR artifact to a separate directory and point " +
        "prebuiltRuntimes there."
    }
  }

  // #618: writes every producer of this pack, already validated, in the layout prebuiltRuntimesDir
  // reads. A local RID contributes only its linked library: the next host's validateNativeLibs
  // accepts exactly one. Rewritten from scratch so a RID dropped from this pack does not linger
  // and get chained into the next host's package.
  private fun writePrebuiltRuntimesOutput(
    id: String,
    transferDir: File,
    producers: List<ForwardPackageProducer>,
    localRids: Map<String, String>,
  ) {
    transferDir.deleteRecursively()
    transferDir.mkdirs()

    producers.forEach { producer ->
      val rid: String = producer.rid
      val ridDir = File(transferDir, rid)
      val localPath: String? = localRids[rid]
      val libs: List<File> =
        if (localPath != null) listOf(linkedNativeLib(id, rid, File(localPath)))
        else nativeLibsIn(File(producer.directory, "native"))

      copyNativeLibs(libs, File(ridDir, "native"))
      CONTRACT_FILES.forEach { name ->
        File(producer.directory, name).copyTo(File(ridDir, name), overwrite = true)
      }
    }
  }

  // ADR-093: merges the native libraries of another host's prebuilt-runtimes tree into this
  // pack's runtimes/ tree. Only <rid>/native/ is staged; the ADR-189 sidecars beside it are read
  // by validateForwardPackageContracts and never shipped (#618). Every failure here is a CI
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
          "<prebuiltRuntimes>/<rid>/native/ containing at least one .dll, .dylib or .so file, " +
          "as written to the $PREBUILT_RUNTIMES_DIR directory by another host's " +
          "${NugetTaskNames.PACK}."
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

  // Zips the already-staged folder into a real .nupkg: a valid OPC (Open Packaging Conventions)
  // ZIP package, per the "known-good minimal .nupkg layout": `[Content_Types].xml` at the root,
  // `_rels/.rels` declaring the manifest + core-properties relationships, and a
  // `package/services/metadata/core-properties/{guid}.psmdcp` part, alongside the staged payload
  // (the .nuspec, contentFiles/, runtimes/<rid>/native/, lib/, build/). The staging dir holds
  // exactly the package contents, so everything in it is zipped; the ADR-189 sidecars live in
  // prebuiltRuntimesOutputDir instead (#618). Pure java.util.zip, no external dependency.
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
