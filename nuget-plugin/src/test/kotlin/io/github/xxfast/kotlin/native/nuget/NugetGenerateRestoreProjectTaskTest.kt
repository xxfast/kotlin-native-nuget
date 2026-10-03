package io.github.xxfast.kotlin.native.nuget

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NugetGenerateRestoreProjectTaskTest {
  @Test
  fun `csproj pins the TargetFramework it is given`() {
    val csproj: String = generateCsproj(
      ids = listOf("Newtonsoft.Json"),
      versions = mapOf("Newtonsoft.Json" to "13.0.3"),
      sources = emptyMap(),
      targetFramework = "net10.0",
      rids = listOf("osx-arm64"),
    )

    assertContains(csproj, "<TargetFramework>net10.0</TargetFramework>")
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

  @Test
  fun `a source is remote, a nupkg file or a directory`() {
    assertIs<DependencySource.Remote>(classifySource("Acme", "https://feed/v3/index.json"))
    assertIs<DependencySource.Package>(classifySource("Acme", "/libs/Acme.1.0.0.nupkg"))
    assertIs<DependencySource.Directory>(classifySource("Acme", "/libs/artifacts"))
  }

  @Test
  fun `a file url fails fast naming the dependency`() {
    val error: IllegalArgumentException = assertFailsWith {
      classifySource("Acme", "file:///libs/artifacts")
    }

    assertContains(error.message.orEmpty(), "Acme")
    assertContains(error.message.orEmpty(), "plain path")
  }

  @Test
  fun `a nupkg is staged under its nuspec id and version and the feed is synced`() {
    val dir: File = Files.createTempDirectory("stage-local").toFile()
    val nupkg: File = writeNupkg(File(dir, "bin/whatever.nupkg"), "Acme.Local", "1.2.0")
    val feed = File(dir, "feed")
    feed.mkdirs()
    File(feed, "Gone.1.0.0.nupkg").writeText("stale")

    val plan: LocalRestorePlan = stageLocalSources(
      versions = emptyMap(),
      sources = mapOf("Acme.Local" to nupkg.absolutePath, "Remote" to "https://feed"),
      feedDir = feed,
    )

    assertEquals(setOf("Acme.Local.1.2.0.nupkg"), feed.list().orEmpty().toSet())
    assertEquals(mapOf("Acme.Local" to "1.2.0"), plan.versions)
    assertEquals(
      mapOf("Acme.Local" to feed.absolutePath, "Remote" to "https://feed"),
      plan.sources,
    )
  }

  @Test
  fun `a declared version that differs from the nupkg fails naming both`() {
    val dir: File = Files.createTempDirectory("stage-version").toFile()
    val nupkg: File = writeNupkg(File(dir, "Acme.nupkg"), "Acme", "1.2.0")

    val error: IllegalArgumentException = assertFailsWith {
      stageLocalSources(
        mapOf("Acme" to "9.9.9"), mapOf("Acme" to nupkg.absolutePath), File(dir, "feed"),
      )
    }

    assertContains(error.message.orEmpty(), "9.9.9")
    assertContains(error.message.orEmpty(), "1.2.0")
    assertContains(error.message.orEmpty(), nupkg.absolutePath)
  }

  @Test
  fun `a nupkg whose id differs from the dependency fails`() {
    val dir: File = Files.createTempDirectory("stage-id").toFile()
    val nupkg: File = writeNupkg(File(dir, "Other.nupkg"), "Other", "1.0.0")

    val error: IllegalArgumentException = assertFailsWith {
      stageLocalSources(emptyMap(), mapOf("Acme" to nupkg.absolutePath), File(dir, "feed"))
    }

    assertContains(error.message.orEmpty(), "'Acme'")
    assertContains(error.message.orEmpty(), "'Other'")
  }

  @Test
  fun `a missing local path fails naming the dependency and the resolved path`() {
    val dir: File = Files.createTempDirectory("stage-missing").toFile()
    val missing = File(dir, "nope")

    val error: IllegalArgumentException = assertFailsWith {
      stageLocalSources(emptyMap(), mapOf("Acme" to missing.absolutePath), File(dir, "feed"))
    }

    assertContains(error.message.orEmpty(), "'Acme'")
    assertContains(error.message.orEmpty(), missing.absolutePath)
  }

  @Test
  fun `a packages path is rendered as RestorePackagesPath and paths are xml escaped`() {
    val csproj: String = generateCsproj(
      ids = listOf("Acme"),
      versions = emptyMap(),
      sources = mapOf("Acme" to "/a&b/feed"),
      targetFramework = "net8.0",
      rids = listOf("osx-arm64"),
      packagesPath = "/a&b/packages",
    )

    assertContains(csproj, "<RestoreSources>https://api.nuget.org/v3/index.json;/a&amp;b/feed")
    assertContains(csproj, "<RestorePackagesPath>/a&amp;b/packages</RestorePackagesPath>")
  }

  // ADR-191: one union, in a stable order: nuget.org, the shared list, then per-dependency feeds.
  @Test
  fun `restore sources are nuget org, then shared, then per-dependency, de-duplicated`() {
    val csproj: String = generateCsproj(
      ids = listOf("Acme", "Other"),
      versions = emptyMap(),
      sources = mapOf("Acme" to "https://dep/v3", "Other" to "https://shared-a/v3"),
      targetFramework = "net8.0",
      rids = listOf("osx-arm64"),
      shared = listOf("https://shared-a/v3", "/shared/b", "https://api.nuget.org/v3/index.json"),
    )

    assertContains(
      csproj,
      "<RestoreSources>https://api.nuget.org/v3/index.json;https://shared-a/v3;/shared/b;" +
        "https://dep/v3</RestoreSources>",
    )
  }

  @Test
  fun `shared sources alone emit RestoreSources`() {
    val csproj: String = generateCsproj(
      ids = listOf("Acme"),
      versions = emptyMap(),
      sources = emptyMap(),
      targetFramework = "net8.0",
      rids = listOf("osx-arm64"),
      shared = listOf("https://shared/v3"),
    )

    assertContains(
      csproj,
      "<RestoreSources>https://api.nuget.org/v3/index.json;https://shared/v3</RestoreSources>",
    )
  }

  @Test
  fun `a nupkg in the shared list is rejected with a pointer to source`() {
    val error: IllegalArgumentException = assertFailsWith {
      checkSharedSources(listOf("/libs/Acme.1.0.0.nupkg"))
    }

    assertContains(error.message.orEmpty(), "/libs/Acme.1.0.0.nupkg")
    assertContains(error.message.orEmpty(), "dependency(\"<id>\") { source = ")
  }

  @Test
  fun `a missing shared directory fails naming the resolved path`() {
    val missing = File(Files.createTempDirectory("shared-missing").toFile(), "nope")

    val error: IllegalArgumentException = assertFailsWith {
      checkSharedSources(listOf("https://ok/v3", missing.absolutePath))
    }

    assertContains(error.message.orEmpty(), missing.absolutePath)
  }

  // ADR-191: the post-restore check for packages no `source` names. When a local feed holds the
  // exact id and version restore resolved, the restored bytes must be that feed's; a different
  // version means another feed legitimately served it, so it is left alone.
  @Test
  fun `a restored package whose id and version a local feed holds must be that feed's bytes`() {
    val dir: File = Files.createTempDirectory("feed-check").toFile()
    val feed = File(dir, "feed")
    writeNupkg(File(feed, "Acme.1.0.nupkg"), "Acme", "1.0")
    val extracted: File =
      writeNupkg(File(dir, "packages/acme/1.0.0/acme.1.0.0.nupkg"), "Acme", "1.0.0")
    val restored = RestoredPackage("Acme", "1.0.0", extracted.parentFile)

    val error: IllegalStateException = assertFailsWith {
      verifyFeedPackages(listOf(feed), listOf(restored))
    }
    assertContains(error.message.orEmpty(), "Acme 1.0.0")
    assertContains(error.message.orEmpty(), feed.absolutePath)

    File(feed, "Acme.1.0.nupkg").copyTo(extracted, overwrite = true)
    verifyFeedPackages(listOf(feed), listOf(restored))
  }

  @Test
  fun `a local feed holding another version of a restored id does not fail the check`() {
    val dir: File = Files.createTempDirectory("feed-check-version").toFile()
    val feed = File(dir, "feed")
    writeNupkg(File(feed, "Acme.2.0.0.nupkg"), "Acme", "2.0.0")
    val extracted: File =
      writeNupkg(File(dir, "packages/acme/1.0.0/acme.1.0.0.nupkg"), "Acme", "1.0.0")

    val restored = RestoredPackage("Acme", "1.0.0", extracted.parentFile)
    verifyFeedPackages(listOf(feed), listOf(restored))
  }
}

/** A minimal `.nupkg`: a zip with `<id>.nuspec` at its root, which is all the plugin reads. */
internal fun writeNupkg(file: File, id: String, version: String): File {
  file.parentFile.mkdirs()
  ZipOutputStream(file.outputStream()).use { zip ->
    zip.putNextEntry(ZipEntry("$id.nuspec"))
    val nuspec = """
      |<?xml version="1.0" encoding="utf-8"?>
      |<package xmlns="http://schemas.microsoft.com/packaging/2013/05/nuspec.xsd">
      |  <metadata>
      |    <id>$id</id>
      |    <version>$version</version>
      |    <dependencies><dependency id="Dep" version="1.0.0" /></dependencies>
      |  </metadata>
      |</package>
    """.trimMargin()
    zip.write(nuspec.toByteArray())
    zip.closeEntry()
  }
  return file
}
