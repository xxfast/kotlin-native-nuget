package io.github.xxfast.kotlin.native.nuget

import io.github.xxfast.kotlin.native.nuget.rir.derivePackageFolders
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
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
public abstract class NugetRestoreTask : DefaultTask() {
  @get:InputFile public abstract val csprojFile: RegularFileProperty

  // ADR-184: the restore TFM, named in the failure hint.
  @get:Input public abstract val targetFramework: Property<String>
  @get:OutputFile public abstract val assetsFile: RegularFileProperty

  // ADR-190: the staged feed and every directory source. A same-version rebuild changes the bytes
  // here and nothing else, so these are what re-run restore.
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.ABSOLUTE)
  public abstract val localFeeds: ConfigurableFileCollection

  // ADR-190: dependency id to its resolved local source (a directory or a `.nupkg` file).
  @get:Input public abstract val localSources: MapProperty<String, String>

  // ADR-190: the project-local packages folder, present only when a local source is declared.
  @get:OutputDirectory
  @get:Optional
  public abstract val packagesDir: DirectoryProperty

  @get:Inject public abstract val execOps: ExecOperations

  init {
    localSources.convention(emptyMap())
  }

  @TaskAction
  public fun restore() {
    val dotnet: String = requireDotnet("restore NuGet packages")
    val packages: File? = packagesDir.orNull?.asFile
    if (packages != null) evictLocalPackages(localFeeds.files, packages)

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
          "If a package requires a higher .NET version than ${targetFramework.get()}, " +
          "raise nuget { targetFramework } or " +
          "use an older compatible version."
      )
    }

    if (localSources.get().isEmpty()) return
    val folders: Map<String, File> = derivePackageFolders(
      assetsFile.get().asFile.readText(),
      localSources.get().keys,
    )
    verifyLocalPackages(localSources.get(), folders)
  }
}
