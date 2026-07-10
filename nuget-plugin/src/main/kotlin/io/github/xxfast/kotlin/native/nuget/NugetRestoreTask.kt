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
    val dotnet: String = requireDotnet("restore NuGet packages")

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
