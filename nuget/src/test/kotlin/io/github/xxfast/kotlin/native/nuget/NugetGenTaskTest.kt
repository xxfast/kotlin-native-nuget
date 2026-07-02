package io.github.xxfast.kotlin.native.nuget

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NugetGenTaskTest {
  @Test
  fun `csproj always pins TargetFramework to net8 0`() {
    val csproj: String = generateCsproj(
      ids = listOf("Newtonsoft.Json"),
      versions = mapOf("Newtonsoft.Json" to "13.0.3"),
      sources = emptyMap(),
      targetFramework = "net8.0",
      rids = listOf("osx-arm64"),
    )

    assertContains(csproj, "<TargetFramework>net8.0</TargetFramework>")
  }

  @Test
  fun `csproj omits RestoreSources when no custom sources declared`() {
    val csproj: String = generateCsproj(
      ids = listOf("Newtonsoft.Json"),
      versions = mapOf("Newtonsoft.Json" to "13.0.3"),
      sources = emptyMap(),
      targetFramework = "net8.0",
      rids = listOf("osx-arm64"),
    )

    assertFalse(
      csproj.contains("RestoreSources"),
      "RestoreSources must be absent when no custom sources",
    )
  }

  @Test
  fun `csproj includes nuget org plus custom source when any source is present`() {
    val csproj: String = generateCsproj(
      ids = listOf("Acme.Pkg"),
      versions = mapOf("Acme.Pkg" to "1.0.0"),
      sources = mapOf("Acme.Pkg" to "https://pkgs.dev.azure.com/myorg/feed/nuget/v3/index.json"),
      targetFramework = "net8.0",
      rids = listOf("osx-arm64"),
    )

    assertContains(csproj, "https://api.nuget.org/v3/index.json")
    assertContains(csproj, "https://pkgs.dev.azure.com/myorg/feed/nuget/v3/index.json")
  }

  @Test
  fun `csproj omits Version attribute when dependency has no version`() {
    val csproj: String = generateCsproj(
      ids = listOf("Serilog"),
      versions = emptyMap(),
      sources = emptyMap(),
      targetFramework = "net8.0",
      rids = listOf("osx-arm64"),
    )

    assertContains(csproj, """<PackageReference Include="Serilog" />""")
    assertFalse(
      csproj.contains("Version="),
      "Version attribute must be absent for version-less dependency",
    )
  }

  @Test
  fun `csproj includes version when declared`() {
    val csproj: String = generateCsproj(
      ids = listOf("Newtonsoft.Json"),
      versions = mapOf("Newtonsoft.Json" to "13.0.3"),
      sources = emptyMap(),
      targetFramework = "net8.0",
      rids = listOf("osx-arm64"),
    )

    assertContains(csproj, """<PackageReference Include="Newtonsoft.Json" Version="13.0.3" />""")
  }

  @Test
  fun `csproj includes RuntimeIdentifiers from multiple targets joined with semicolons`() {
    val csproj: String = generateCsproj(
      ids = listOf("Newtonsoft.Json"),
      versions = mapOf("Newtonsoft.Json" to "13.0.3"),
      sources = emptyMap(),
      targetFramework = "net8.0",
      rids = listOf("osx-arm64", "win-x64", "linux-x64"),
    )

    assertContains(csproj, "<RuntimeIdentifiers>osx-arm64;win-x64;linux-x64</RuntimeIdentifiers>")
  }

  @Test
  fun `csproj RestoreSources deduplicates custom source urls`() {
    val csproj: String = generateCsproj(
      ids = listOf("Pkg.A", "Pkg.B"),
      versions = emptyMap(),
      sources = mapOf(
        "Pkg.A" to "https://my.feed/v3/index.json",
        "Pkg.B" to "https://my.feed/v3/index.json",
      ),
      targetFramework = "net8.0",
      rids = listOf("osx-arm64"),
    )

    val count: Int = csproj.split("https://my.feed/v3/index.json").size - 1
    assertTrue(
      count == 1,
      "Duplicate custom source URL must appear only once but appeared $count times",
    )
  }
}
