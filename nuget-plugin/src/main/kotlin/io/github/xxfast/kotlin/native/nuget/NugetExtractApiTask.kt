package io.github.xxfast.kotlin.native.nuget

import io.github.xxfast.kotlin.native.nuget.rir.deriveDllPaths
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.process.ExecResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import javax.inject.Inject

abstract class NugetExtractApiTask : DefaultTask() {
  @get:Input abstract val boundPackageIds: ListProperty<String>
  @get:Input abstract val packageNameOverrides: MapProperty<String, String>
  @get:Input abstract val namespaceIncludes: MapProperty<String, List<String>>
  @get:Input abstract val namespaceExcludes: MapProperty<String, List<String>>
  @get:Input abstract val namespaceAliases: MapProperty<String, Map<String, String>>
  @get:InputFile abstract val assetsFile: RegularFileProperty
  @get:OutputFile abstract val reverseIrFile: RegularFileProperty

  @get:Inject abstract val execOps: ExecOperations

  @TaskAction
  fun extract() {
    val assetsJson: String = assetsFile.get().asFile.readText()
    val ids: Set<String> = boundPackageIds.get().toSet()

    val dllPaths: Map<String, List<String>> = deriveDllPaths(assetsJson, ids)

    dllPaths.forEach { (packageId, paths) ->
      paths.forEach { path ->
        if (!File(path).exists()) {
          throw GradleException(
            "[nuget] DLL not found at '$path' (package '$packageId'). " +
              "The global NuGet cache may have been cleared. Re-run nugetRestore to re-download."
          )
        }
      }
    }

    val dotnet: String = requireDotnet("extract the NuGet API surface")

    val toolDir: File = temporaryDir.resolve("metadata-reader")
    unpackMetadataReader(toolDir, javaClass.classLoader)

    val cmd: List<String> = metadataReaderCommand(
      dotnet, toolDir, dllPaths,
      namespaceIncludes.get(),
      namespaceExcludes.get(),
    )

    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()
    val result: ExecResult = execOps.exec { spec ->
      spec.commandLine(cmd)
      spec.standardOutput = stdout
      spec.errorOutput = stderr
      spec.isIgnoreExitValue = true
    }

    if (result.exitValue != 0) {
      throw GradleException(
        "[nuget] metadata reader failed (exit code ${result.exitValue}).\n" +
          stderr.toString().trimEnd() + "\n\n" +
          "Check that the NuGet DLLs are valid and re-run nugetRestore if paths are missing."
      )
    }

    val out: File = reverseIrFile.get().asFile
    out.parentFile.mkdirs()
    out.writeText(stdout.toString())
  }
}

/**
 * Extracts the bundled metadata reader project sources from the plugin JAR into [targetDir].
 * Uses [classLoader] to locate resources so it works both from the published plugin JAR
 * (jar:// protocol) and from the test classpath (file:// protocol).
 */
internal fun unpackMetadataReader(targetDir: File, classLoader: ClassLoader) {
  targetDir.mkdirs()
  listOf("NugetMetadataReader.csproj", "Program.cs").forEach { name ->
    val resource = "NugetMetadataReader/$name"
    val stream: InputStream = classLoader.getResourceAsStream(resource)
      ?: error("[nuget] missing bundled resource: $resource (plugin JAR may be corrupt)")
    stream.use { input ->
      File(targetDir, name).outputStream().use { output -> input.copyTo(output) }
    }
  }
}

/**
 * Assembles the `dotnet run --project [readerProjectDir]` command line for the metadata reader,
 * passing DLL paths and namespace filters as CLI arguments.
 *
 * A pure function so the launch mechanism is decoupled from argument assembly: the task action
 * runs it through Gradle's [ExecOperations], while integration tests run it via [ProcessBuilder].
 */
internal fun metadataReaderCommand(
  dotnet: String,
  readerProjectDir: File,
  dllPaths: Map<String, List<String>>,
  includes: Map<String, List<String>>,
  excludes: Map<String, List<String>>,
): List<String> = buildList {
  add(dotnet)
  add("run")
  add("--project")
  add(readerProjectDir.absolutePath)
  add("--")
  dllPaths.forEach { (id, paths) ->
    val packageIncludes: List<String> = includes[id] ?: emptyList()
    val packageExcludes: List<String> = excludes[id] ?: emptyList()
    paths.forEach { path ->
      add("--package")
      add(id)
      add(path)
      if (packageIncludes.isNotEmpty()) {
        add("--include")
        addAll(packageIncludes)
      }
      if (packageExcludes.isNotEmpty()) {
        add("--exclude")
        addAll(packageExcludes)
      }
    }
  }
}
