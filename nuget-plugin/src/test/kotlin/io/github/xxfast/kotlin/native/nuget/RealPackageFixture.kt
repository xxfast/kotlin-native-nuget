package io.github.xxfast.kotlin.native.nuget

import io.github.xxfast.kotlin.native.nuget.rir.deriveDllPaths
import java.io.File
import java.nio.file.Files

/**
 * The shared "real published package to reverse-ir.json" pipeline: `dotnet restore`, the
 * plugin's own `deriveDllPaths`, then the bundled `NugetMetadataReader` as a subprocess.
 * Extracted from `NugetExtractApiIntegrationTest` so the dogfood census runs the SAME path a
 * consumer's build does rather than a second copy of it that could drift.
 *
 * Unlike the integration tests, nothing here asserts: [readerOutcome] returns the exit code and
 * stderr so a caller can record a reader crash as data. One package per reader invocation,
 * because the reader aborts the whole run on one bad type and a shared invocation would lose
 * every other package's row.
 */
object RealPackageFixture {
  data class ReaderOutcome(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    /** The DLL's path relative to the package root, `/`-separated: `lib/net8.0/Serilog.dll`. */
    val asset: String,
  )

  fun findDotnet(): String? = runCatching {
    ProcessBuilder("dotnet", "--version")
      .redirectOutput(ProcessBuilder.Redirect.DISCARD)
      .redirectError(ProcessBuilder.Redirect.DISCARD)
      .start()
      .waitFor()
    "dotnet"
  }.getOrNull()

  /**
   * Unpacks the bundled reader once. The reader's first build is the expensive part, so callers
   * share one directory across every package.
   */
  fun unpackReader(classLoader: ClassLoader, name: String = "NugetMetadataReader-dogfood"): File {
    val dir: File = Files.createTempDirectory(name).toFile()
    unpackMetadataReader(dir, classLoader)
    return dir
  }

  /** Restores one pinned package into a fresh temp dir and returns its resolved DLL paths. */
  fun restore(dotnet: String, id: String, version: String): Map<String, List<String>> {
    val restoreDir: File = Files.createTempDirectory("dogfood-restore").toFile()
    val csprojFile = File(restoreDir, "interop.csproj")
    csprojFile.writeText(
      generateCsproj(
        ids = listOf(id),
        versions = mapOf(id to version),
        sources = emptyMap(),
        targetFramework = "net8.0",
        rids = listOf("win-x64"),
      ),
    )

    val process: Process = ProcessBuilder(dotnet, "restore", csprojFile.absolutePath)
      .directory(restoreDir)
      .redirectErrorStream(true)
      .start()
    val output: String = process.inputStream.bufferedReader().readText()
    val exit: Int = process.waitFor()
    require(exit == 0) { "dotnet restore failed for $id $version\n$output" }

    val assets = File(restoreDir, "obj/project.assets.json")
    require(assets.exists()) { "no project.assets.json after restoring $id $version" }
    return deriveDllPaths(assetsJson = assets.readText(), packageIds = setOf(id))
  }

  fun readerOutcome(
    dotnet: String,
    readerProjectDir: File,
    id: String,
    version: String,
    dllPaths: Map<String, List<String>>,
    includes: List<String>,
  ): ReaderOutcome {
    val cmd: List<String> = metadataReaderCommand(
      dotnet = dotnet,
      readerProjectDir = readerProjectDir,
      dllPaths = dllPaths,
      includes = if (includes.isEmpty()) emptyMap() else mapOf(id to includes),
      excludes = emptyMap(),
    )
    val process: Process = ProcessBuilder(cmd).redirectErrorStream(false).start()
    val stdout: String = process.inputStream.bufferedReader().readText()
    val stderr: String = process.errorStream.bufferedReader().readText()
    val exitCode: Int = process.waitFor()
    return ReaderOutcome(
      exitCode = exitCode,
      stdout = stdout,
      stderr = stderr,
      asset = relativeAsset(dllPaths.getValue(id).single(), id, version),
    )
  }

  /**
   * Cuts an absolute NuGet-cache path down to the part that is the same on every machine. The
   * cache path is `<home>/.nuget/packages/<id lowercased>/<version>/lib/<tfm>/<name>.dll`, and
   * both the home directory and the separator differ between this developer's Windows box and
   * the Linux CI runner. The asset IS the multi-TFM assertion, so it must survive; the prefix
   * must not.
   */
  fun relativeAsset(absolute: String, id: String, version: String): String {
    val normalized: String = absolute.replace('\\', '/')
    val marker = "/${id.lowercase()}/$version/"
    val index: Int = normalized.indexOf(marker)
    return if (index < 0) normalized.substringAfterLast('/')
    else normalized.substring(index + marker.length)
  }

  /**
   * The reader's stderr names the absolute DLL path it failed on; that prefix is
   * machine-specific and would make a committed failure row red for the wrong reason on
   * another host.
   */
  fun sanitizeReaderError(stderr: String, id: String, version: String): String {
    val firstLine: String = stderr.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
    val normalized: String = firstLine.replace('\\', '/')
    val marker = "/${id.lowercase()}/$version/"
    val index: Int = normalized.indexOf(marker)
    if (index < 0) return normalized
    val prefixEnd: Int = normalized.lastIndexOf('\'', index).let { if (it < 0) 0 else it + 1 }
    return normalized.substring(0, prefixEnd) + normalized.substring(index + marker.length)
  }
}
