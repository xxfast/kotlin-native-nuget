package io.github.xxfast.kotlin.native.nuget

import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.time.Instant

/**
 * ADR-092: mints `<version>-snapshot.<epochMillis>` into a file at execution time, so every build
 * produces a new package identity and NuGet cannot serve the cached copy of the previous one.
 * Always out of date: the whole point is a fresh timestamp per build.
 */
@DisableCachingByDefault(because = "every snapshot build requires a new version")
public abstract class NugetSnapshotVersionTask : DefaultTask() {
  // ADR-180: optional, so a missing value fails in the action below with the DSL-level message
  // instead of Gradle's generic "doesn't have a configured value".
  @get:Input
  @get:Optional
  public abstract val baseVersion: Property<String>

  @get:Input
  @get:Optional
  public abstract val packageId: Property<String>

  @get:OutputFile
  public abstract val outputFile: RegularFileProperty

  init {
    outputs.upToDateWhen { false }
  }

  @TaskAction
  public fun write() {
    val base: String? = baseVersion.orNull
    require(!base.isNullOrBlank()) {
      "nuget { publish { snapshot = true } } requires a base version; set `version = \"1.0.0\"`"
    }

    val id: String? = packageId.orNull
    require(!id.isNullOrBlank()) {
      "nuget { publish { snapshot = true } } requires a packageId to name the version props file"
    }

    val file: File = outputFile.get().asFile
    file.parentFile.mkdirs()
    file.writeText("$base-snapshot.${Instant.now().toEpochMilli()}\n")
  }
}

/**
 * ADR-092: writes the MSBuild props file that pins the minted version, so a .NET consumer can
 * reference `Version="$(<SanitizedId>Version)"` without knowing the timestamp.
 */
@DisableCachingByDefault(because = "the props file is generated for the current snapshot version")
public abstract class NugetSnapshotVersionPropsTask : DefaultTask() {
  @get:Input
  public abstract val packageId: Property<String>

  @get:Input
  public abstract val packageVersion: Property<String>

  @get:OutputFile
  public abstract val outputFile: RegularFileProperty

  @TaskAction
  public fun write() {
    val property: String = msbuildVersionPropertyName(packageId.get())
    val file: File = outputFile.get().asFile
    file.parentFile.mkdirs()
    file.writeText(
      """
      |<Project>
      |  <PropertyGroup>
      |    <$property>${packageVersion.get()}</$property>
      |  </PropertyGroup>
      |</Project>
      """.trimMargin() + "\n"
    )
  }
}

/**
 * ADR-092: MSBuild property names cannot contain dots and cannot begin with a digit, so a package
 * id is not usable verbatim. Drop everything outside `[A-Za-z0-9_]`, prefix `_` if what remains
 * starts with a digit, append `Version`.
 */
internal fun msbuildVersionPropertyName(packageId: String): String {
  val sanitized: String = packageId.filter {
    it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_'
  }
  require(sanitized.isNotEmpty()) {
    "nuget { publish { packageId = \"$packageId\" } } has no character usable in an MSBuild " +
        "property name; expected at least one of [A-Za-z0-9_]"
  }
  val prefix: String = if (sanitized.first().isDigit()) "_" else ""
  return "$prefix${sanitized}Version"
}

/** The pair of tasks plus the execution-time version provider they share (ADR-092). */
internal class SnapshotVersioning(
  val version: Provider<String>,
  val versionTask: TaskProvider<NugetSnapshotVersionTask>,
  val propsTask: TaskProvider<NugetSnapshotVersionPropsTask>,
)

internal fun registerSnapshotVersioning(
  project: Project,
  pub: NugetPublishConfig,
): SnapshotVersioning {
  val versionFile: Provider<RegularFile> =
    project.layout.buildDirectory.file("nuget-snapshot-version.txt")

  // Execution-time resolution: the timestamp does not exist at configuration time, and a
  // file-backed provider is what keeps this configuration-cache safe.
  val version: Provider<String> = project.providers.fileContents(versionFile)
    .asText
    .map { it.trim() }

  // ADR-180: registered with `publish {}` whatever `snapshot` is; packNuget only depends on these
  // when it is true. A missing version or packageId fails in the mint task's action.
  val versionTask: TaskProvider<NugetSnapshotVersionTask> =
    project.tasks.register(NugetTaskNames.SNAPSHOT_VERSION, NugetSnapshotVersionTask::class.java) { task ->
      task.group = "nuget"
      task.description = "Mints a unique snapshot version for this build"
      task.baseVersion.set(pub.version)
      task.packageId.set(pub.packageId)
      task.outputFile.set(versionFile)
    }

  pub.versionPropsFile.convention(
    project.rootProject.layout.buildDirectory.file(pub.packageId.map { id -> "${id}Versions.props" })
  )

  val propsTask: TaskProvider<NugetSnapshotVersionPropsTask> =
    project.tasks.register(
      NugetTaskNames.SNAPSHOT_VERSION_PROPS,
      NugetSnapshotVersionPropsTask::class.java,
    ) { task ->
      task.group = "nuget"
      task.description = "Writes the MSBuild props file pinning the current snapshot version"
      task.packageId.set(pub.packageId)
      task.packageVersion.set(version)
      task.outputFile.set(pub.versionPropsFile)
      task.dependsOn(versionTask)
    }

  return SnapshotVersioning(version, versionTask, propsTask)
}
