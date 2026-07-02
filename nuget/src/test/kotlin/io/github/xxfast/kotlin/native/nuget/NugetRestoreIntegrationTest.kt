package io.github.xxfast.kotlin.native.nuget

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests that invoke `dotnet restore` as a subprocess. Skipped when `dotnet` is absent
 * from PATH. These tests hit the network and the global NuGet cache.
 */
class NugetRestoreIntegrationTest {
  private fun findDotnet(): String? {
    val process: Process = ProcessBuilder("which", "dotnet").start()
    val line: String? = process.inputStream.bufferedReader().readLine()?.takeIf { it.isNotBlank() }
    process.waitFor()
    return line
  }

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
}
