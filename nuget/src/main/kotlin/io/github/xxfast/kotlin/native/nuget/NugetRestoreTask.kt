package io.github.xxfast.kotlin.native.nuget

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.process.ExecResult
import org.gradle.work.DisableCachingByDefault
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

@DisableCachingByDefault(
  because = "dotnet restore manages its own package cache; " +
    "the global NuGet cache is outside project scope and not tracked by Gradle's build cache"
)
abstract class NugetRestoreTask : DefaultTask() {
  @get:InputFile abstract val csprojFile: RegularFileProperty
  @get:OutputFile abstract val assetsFile: RegularFileProperty

  @get:Inject abstract val execOps: ExecOperations

  @TaskAction
  fun restore() {
    fun findExecutable(name: String): String? {
      val paths: String = System.getenv("PATH") ?: return null
      val extensions: List<String> = listOf("", ".exe", ".cmd", ".bat")
      return paths.split(File.pathSeparator)
        .flatMap { dir -> extensions.map { ext -> File(dir, "$name$ext") } }
        .firstOrNull { it.canExecute() }
        ?.absolutePath
    }

    val dotnet: String = requireNotNull(findExecutable("dotnet")) {
      "[nuget] dotnet is required to restore NuGet packages but was not found on PATH. " +
        "Install the .NET SDK 8 or later from https://dot.net/download, then re-run Gradle sync. " +
        "(Full tooling detection with local.properties override is coming in " +
        "the next pipeline item.)"
    }

    val stderr = ByteArrayOutputStream()
    val result: ExecResult = execOps.exec { spec ->
      spec.commandLine(dotnet, "restore", csprojFile.get().asFile.absolutePath)
      spec.errorOutput = stderr
      spec.isIgnoreExitValue = true
    }

    val exitCode: Int = result.exitValue
    if (exitCode != 0) {
      throw GradleException(
        "[nuget] dotnet restore failed (exit code $exitCode).\n" +
          stderr.toString().trimEnd() + "\n\n" +
          "If this is a transient network error, re-run with --rerun-tasks. " +
          "If a package requires a higher .NET version than net8.0, " +
          "use an older compatible version."
      )
    }
  }
}
