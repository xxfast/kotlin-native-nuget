package io.github.xxfast.kotlin.native.nuget

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

internal fun generateCsproj(
  ids: List<String>,
  versions: Map<String, String>,
  sources: Map<String, String>,
  targetFramework: String,
  rids: List<String>,
  packagesPath: String? = null,
  shared: List<String> = emptyList(),
): String {
  val ridsJoined: String = rids.joinToString(";")
  // ADR-191: nuget.org (added by restoreLines), the shared list, then per-dependency feeds.
  val restoreLines: String = restoreLines(shared + sources.values, packagesPath)

  val packageReferences: String = ids.joinToString("\n") { id ->
    val version: String? = versions[id]
    if (version != null) {
      """    <PackageReference Include="$id" Version="$version" />"""
    } else {
      """    <PackageReference Include="$id" />"""
    }
  }

  return """
    |<Project Sdk="Microsoft.NET.Sdk">
    |  <PropertyGroup>
    |    <TargetFramework>$targetFramework</TargetFramework>
    |    <RuntimeIdentifiers>$ridsJoined</RuntimeIdentifiers>
    |    <OutputType>Library</OutputType>
    |    <GenerateAssemblyInfo>false</GenerateAssemblyInfo>$restoreLines
    |  </PropertyGroup>
    |  <ItemGroup>
    |$packageReferences
    |  </ItemGroup>
    |</Project>
  """.trimMargin().trim()
}

public abstract class NugetGenerateRestoreProjectTask : DefaultTask() {
  @get:Input public abstract val dependencyIds: ListProperty<String>
  @get:Input public abstract val dependencyVersions: MapProperty<String, String>

  // ADR-190: URLs as declared; local paths already resolved against the project directory.
  @get:Input public abstract val dependencySources: MapProperty<String, String>

  // ADR-191: `nuget { sources }`, URLs as declared and directories resolved.
  @get:Input public abstract val sharedSources: ListProperty<String>
  @get:Input public abstract val targetFramework: Property<String>
  @get:Input public abstract val runtimeIdentifiers: ListProperty<String>

  // ADR-190: the local `.nupkg` files and directories, so a same-version rebuild re-runs this.
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.ABSOLUTE)
  public abstract val localSources: ConfigurableFileCollection

  // ADR-190: present only when a local source is declared. Follows dependencySources, so it needs
  // no input annotation of its own.
  @get:Internal public abstract val packagesDir: DirectoryProperty

  // ADR-190: where `.nupkg` file sources are staged as `<id>.<version>.nupkg`.
  @get:OutputDirectory public abstract val feedDir: DirectoryProperty
  @get:OutputFile public abstract val csprojFile: RegularFileProperty

  init {
    sharedSources.convention(emptyList())
  }

  @TaskAction
  public fun generate() {
    checkSharedSources(sharedSources.get())
    val plan: LocalRestorePlan = stageLocalSources(
      versions = dependencyVersions.get(),
      sources = dependencySources.get(),
      feedDir = feedDir.get().asFile,
    )

    val csproj: String = generateCsproj(
      ids = dependencyIds.get(),
      versions = plan.versions,
      sources = plan.sources,
      targetFramework = targetFramework.get(),
      rids = runtimeIdentifiers.get(),
      packagesPath = packagesDir.orNull?.asFile?.absolutePath,
      shared = sharedSources.get(),
    )

    val file: File = csprojFile.get().asFile
    file.parentFile.mkdirs()
    file.writeText(csproj)
  }
}
