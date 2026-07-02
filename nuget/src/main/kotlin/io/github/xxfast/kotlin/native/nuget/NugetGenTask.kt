package io.github.xxfast.kotlin.native.nuget

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File

private const val NUGET_ORG = "https://api.nuget.org/v3/index.json"

fun generateCsproj(
  ids: List<String>,
  versions: Map<String, String>,
  sources: Map<String, String>,
  targetFramework: String,
  rids: List<String>,
): String {
  val ridsJoined: String = rids.joinToString(";")

  val restoreSourcesLine: String = if (sources.isEmpty()) {
    ""
  } else {
    val urls: List<String> = (listOf(NUGET_ORG) + sources.values).distinct()
    "\n    <RestoreSources>${urls.joinToString(";")}</RestoreSources>"
  }

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
    |    <GenerateAssemblyInfo>false</GenerateAssemblyInfo>$restoreSourcesLine
    |  </PropertyGroup>
    |  <ItemGroup>
    |$packageReferences
    |  </ItemGroup>
    |</Project>
  """.trimMargin().trim()
}

abstract class NugetGenTask : DefaultTask() {
  @get:Input abstract val dependencyIds: ListProperty<String>
  @get:Input abstract val dependencyVersions: MapProperty<String, String>
  @get:Input abstract val dependencySources: MapProperty<String, String>
  @get:Input abstract val targetFramework: Property<String>
  @get:Input abstract val runtimeIdentifiers: ListProperty<String>
  @get:OutputFile abstract val csprojFile: RegularFileProperty

  @TaskAction
  fun generate() {
    val csproj: String = generateCsproj(
      ids = dependencyIds.get(),
      versions = dependencyVersions.get(),
      sources = dependencySources.get(),
      targetFramework = targetFramework.get(),
      rids = runtimeIdentifiers.get(),
    )

    val file: File = csprojFile.get().asFile
    file.parentFile.mkdirs()
    file.writeText(csproj)
  }
}
