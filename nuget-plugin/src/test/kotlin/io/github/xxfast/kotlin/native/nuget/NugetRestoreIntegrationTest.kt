package io.github.xxfast.kotlin.native.nuget

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration tests that invoke `dotnet restore` as a subprocess. Skipped when `dotnet` is absent
 * from PATH. These tests hit the network and the global NuGet cache.
 */
class NugetRestoreIntegrationTest {
  // Probe for `dotnet` by running it directly, so the skip works on any OS (a `which`/`where`
  // shell-out is platform-specific and throws on the wrong platform instead of skipping).
  private fun findDotnet(): String? = runCatching {
    ProcessBuilder("dotnet", "--version")
      .redirectOutput(ProcessBuilder.Redirect.DISCARD)
      .redirectError(ProcessBuilder.Redirect.DISCARD)
      .start()
      .waitFor()
    "dotnet"
  }.getOrNull()

  @Test
  fun `dotnet restore produces valid project assets json for Newtonsoft Json`() {
    val dotnet: String = findDotnet() ?: return

    val tempDir: File = Files.createTempDirectory("nuget-restore-test").toFile()

    val csproj: String = generateCsproj(
      ids = listOf("Newtonsoft.Json"),
      versions = mapOf("Newtonsoft.Json" to "13.0.3"),
      sources = emptyMap(),
      targetFramework = "net8.0",
      rids = listOf("osx-arm64"),
    )

    val csprojFile = File(tempDir, "interop.csproj")
    csprojFile.writeText(csproj)

    val process: Process = ProcessBuilder(dotnet, "restore", csprojFile.absolutePath)
      .directory(tempDir)
      .redirectErrorStream(true)
      .start()

    val output: String = process.inputStream.bufferedReader().readText()
    val exitCode: Int = process.waitFor()

    assertEquals(0, exitCode, "dotnet restore should succeed for Newtonsoft.Json 13.0.3\n$output")

    val assetsFile = File(tempDir, "obj/project.assets.json")
    assertTrue(assetsFile.exists(), "project.assets.json must exist after a successful restore")

    val assets: String = assetsFile.readText()
    assertContains(assets, "Newtonsoft.Json", ignoreCase = true)
    assertContains(assets, "net8.0")
  }

  @Test
  fun `dotnet restore fails with NU1202 for a package incompatible with target framework`() {
    val dotnet: String = findDotnet() ?: return

    val tempDir: File = Files.createTempDirectory("nuget-restore-nu1202-test").toFile()

    // net6.0 TFM + Microsoft.AspNetCore.OpenApi 8.0.0 (net8.0-only) → NU1202
    val csprojContent = """
      |<Project Sdk="Microsoft.NET.Sdk">
      |  <PropertyGroup>
      |    <TargetFramework>net6.0</TargetFramework>
      |    <OutputType>Library</OutputType>
      |    <GenerateAssemblyInfo>false</GenerateAssemblyInfo>
      |  </PropertyGroup>
      |  <ItemGroup>
      |    <PackageReference Include="Microsoft.AspNetCore.OpenApi" Version="8.0.0" />
      |  </ItemGroup>
      |</Project>
    """.trimMargin()

    val csprojFile = File(tempDir, "interop.csproj")
    csprojFile.writeText(csprojContent)

    val process: Process = ProcessBuilder(dotnet, "restore", csprojFile.absolutePath)
      .directory(tempDir)
      .redirectErrorStream(true)
      .start()

    val output: String = process.inputStream.bufferedReader().readText()
    val exitCode: Int = process.waitFor()

    assertTrue(exitCode != 0, "dotnet restore must fail for a TFM-incompatible package")
    assertTrue(
      output.contains("NU1202"),
      "Error output must contain NU1202 for TFM incompatibility but was:\n$output",
    )
  }

  // ADR-190: packs `Probe.Local 1.0.0` holding one public class named [marker]. Every call packs
  // the SAME id and version, which is the dev loop the local source exists for.
  private fun packProbe(dotnet: String, marker: String, output: File): File {
    val dir: File = Files.createTempDirectory("probe-local-src").toFile()
    File(dir, "Probe.Local.csproj").writeText(
      """
      <Project Sdk="Microsoft.NET.Sdk">
        <PropertyGroup>
          <TargetFramework>netstandard2.0</TargetFramework>
          <PackageId>Probe.Local</PackageId>
          <Version>1.0.0</Version>
        </PropertyGroup>
      </Project>
      """.trimIndent(),
    )
    File(dir, "Marker.cs").writeText(
      "namespace Probe.Local { public class $marker { public int Value() { return 1; } } }",
    )

    val process: Process = ProcessBuilder(
      dotnet, "pack", "--nologo", "-v", "quiet", "-o", output.absolutePath,
    )
      .directory(dir)
      .redirectErrorStream(true)
      .start()
    val log: String = process.inputStream.bufferedReader().readText()
    assertEquals(0, process.waitFor(), "probe pack must succeed\n$log")
    return File(output, "Probe.Local.1.0.0.nupkg")
  }

  private fun consumer(
    id: String,
    version: String?,
    source: String?,
    shared: List<String> = emptyList(),
  ): Project {
    val dir: File = Files.createTempDirectory("local-source-consumer").toFile()
    val project: Project = ProjectBuilder.builder().withProjectDir(dir).build()
    project.plugins.apply("org.jetbrains.kotlin.multiplatform")
    project.plugins.apply("io.github.xxfast.kotlin.native.nuget")
    project.extensions.getByType(KotlinMultiplatformExtension::class.java)
      .mingwX64 { target -> target.binaries { sharedLib { baseName = "test" } } }
    val nuget: NugetExtension = project.extensions.getByType(NugetExtension::class.java)
    nuget.sources.addAll(shared)
    nuget.dependencies { deps ->
      deps.dependency(id, version) { dep ->
        if (source != null) dep.source.set(source)
        dep.bind { }
      }
    }
    return project
  }

  // Drives the real task actions in pipeline order, the way `nugetGenerateBindings` reaches them.
  private fun Project.restoreAndExtract(): String {
    (tasks.getByName(NugetTaskNames.GENERATE_RESTORE_PROJECT) as NugetGenerateRestoreProjectTask)
      .generate()
    (tasks.getByName(NugetTaskNames.RESTORE) as NugetRestoreTask).restore()
    val extract = tasks.getByName(NugetTaskNames.EXTRACT_API) as NugetExtractApiTask
    extract.extract()
    return extract.reverseIrFile.get().asFile.readText()
  }

  private fun assertSameVersionRepackRebinds(sourceOf: (feed: File) -> String) {
    val dotnet: String = findDotnet() ?: return
    val feed: File = Files.createTempDirectory("probe-local-feed").toFile()
    packProbe(dotnet, "MarkerOne", feed)

    val project: Project = consumer("Probe.Local", "1.0.0", sourceOf(feed))
    val first: String = project.restoreAndExtract()
    assertContains(first, "MarkerOne")

    packProbe(dotnet, "MarkerTwo", feed)
    val second: String = project.restoreAndExtract()
    assertContains(second, "MarkerTwo", message = "a same-version repack must reach the bindings")
    assertFalse(second.contains("MarkerOne"), "the V1 assembly must not be bound after a repack")

    val packages: File = project.layout.buildDirectory.dir("nuget-interop/packages").get().asFile
    val extracted = File(packages, "probe.local/1.0.0/probe.local.1.0.0.nupkg")
    assertTrue(extracted.exists(), "the extracted folder keeps its .nupkg at $extracted")
  }

  @Test
  fun `a same-version repack of a nupkg file source is rebound`() {
    assertSameVersionRepackRebinds { feed -> File(feed, "Probe.Local.1.0.0.nupkg").absolutePath }
  }

  @Test
  fun `a same-version repack in a directory source is rebound`() {
    assertSameVersionRepackRebinds { feed -> feed.absolutePath }
  }

  // nuget.org holds Newtonsoft.Json 13.0.3; the declared directory does not. Restore succeeds from
  // nuget.org, which is exactly the silent wrong binding the post-restore check exists to stop.
  @Test
  fun `a directory source that does not hold the package fails even when nuget org does`() {
    findDotnet() ?: return
    val empty: File = Files.createTempDirectory("empty-local-feed").toFile()
    val project: Project = consumer("Newtonsoft.Json", "13.0.3", empty.absolutePath)

    (project.tasks.getByName(NugetTaskNames.GENERATE_RESTORE_PROJECT)
      as NugetGenerateRestoreProjectTask).generate()
    val error: IllegalStateException = assertFailsWith {
      (project.tasks.getByName(NugetTaskNames.RESTORE) as NugetRestoreTask).restore()
    }

    assertContains(error.message.orEmpty(), "Newtonsoft.Json")
    assertContains(error.message.orEmpty(), empty.absolutePath)
  }

  // ADR-191: a shared directory serves a dependency that names no `source`, and, because it joins
  // ADR-190's local-feed set, a same-version repack there is rebound too.
  @Test
  fun `a shared directory feed serves a dependency without a source and rebinds a repack`() {
    val dotnet: String = findDotnet() ?: return
    val feed: File = Files.createTempDirectory("probe-shared-feed").toFile()
    packProbe(dotnet, "MarkerOne", feed)

    val project: Project =
      consumer("Probe.Local", "1.0.0", source = null, shared = listOf(feed.absolutePath))
    assertContains(project.restoreAndExtract(), "MarkerOne")

    packProbe(dotnet, "MarkerTwo", feed)
    val second: String = project.restoreAndExtract()
    assertContains(second, "MarkerTwo")
    assertFalse(second.contains("MarkerOne"), "the V1 assembly must not be bound after a repack")
  }

  // ADR-191: the shared directory holds Newtonsoft.Json, but not the version restore resolves from
  // nuget.org. That is a legitimate resolution, so the post-restore check must not fail it.
  @Test
  fun `a shared directory holding another version does not fail a package nuget org serves`() {
    findDotnet() ?: return
    val feed: File = Files.createTempDirectory("shared-other-version").toFile()
    writeNupkg(File(feed, "Newtonsoft.Json.12.0.1.nupkg"), "Newtonsoft.Json", "12.0.1")
    val project: Project =
      consumer("Newtonsoft.Json", "13.0.3", source = null, shared = listOf(feed.absolutePath))

    (project.tasks.getByName(NugetTaskNames.GENERATE_RESTORE_PROJECT)
      as NugetGenerateRestoreProjectTask).generate()
    (project.tasks.getByName(NugetTaskNames.RESTORE) as NugetRestoreTask).restore()

    val packages: File = project.layout.buildDirectory.dir("nuget-interop/packages").get().asFile
    assertTrue(File(packages, "newtonsoft.json/13.0.3").isDirectory)
  }
}
