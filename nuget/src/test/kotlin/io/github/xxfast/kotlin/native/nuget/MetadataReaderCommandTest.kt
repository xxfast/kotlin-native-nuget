package io.github.xxfast.kotlin.native.nuget

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Pins the CLI contract between the Gradle plugin and the C# metadata reader. Pure — no dotnet,
 * no subprocess. The reader's argument parser in `nuget-metadata-reader/Program.cs` must stay in
 * sync with the argument order asserted here.
 */
class MetadataReaderCommandTest {
  private val reader: File = File("/tmp/reader")

  @Test
  fun `command starts with dotnet run --project readerDir --`() {
    val cmd: List<String> = metadataReaderCommand(
      dotnet = "dotnet",
      readerProjectDir = reader,
      dllPaths = mapOf("Newtonsoft.Json" to listOf("/pkgs/newtonsoft.json.dll")),
      includes = emptyList(),
      excludes = emptyList(),
    )
    assertEquals(listOf("dotnet", "run", "--project", reader.absolutePath, "--"), cmd.take(5))
  }

  @Test
  fun `each dll becomes a --package id path triple`() {
    val cmd: List<String> = metadataReaderCommand(
      dotnet = "dotnet",
      readerProjectDir = reader,
      dllPaths = mapOf("Newtonsoft.Json" to listOf("/pkgs/newtonsoft.json.dll")),
      includes = emptyList(),
      excludes = emptyList(),
    )
    val packageArgs: List<String> = cmd.drop(cmd.indexOf("--") + 1)
    assertEquals(listOf("--package", "Newtonsoft.Json", "/pkgs/newtonsoft.json.dll"), packageArgs)
  }

  @Test
  fun `a package with multiple dlls repeats the --package triple`() {
    val cmd: List<String> = metadataReaderCommand(
      dotnet = "dotnet",
      readerProjectDir = reader,
      dllPaths = mapOf("Acme.Lib" to listOf("/pkgs/a.dll", "/pkgs/b.dll")),
      includes = emptyList(),
      excludes = emptyList(),
    )
    val packageArgs: List<String> = cmd.drop(cmd.indexOf("--") + 1)
    assertEquals(
      listOf("--package", "Acme.Lib", "/pkgs/a.dll", "--package", "Acme.Lib", "/pkgs/b.dll"),
      packageArgs,
    )
  }

  @Test
  fun `includes are emitted after a single --include flag when present`() {
    val cmd: List<String> = metadataReaderCommand(
      dotnet = "dotnet",
      readerProjectDir = reader,
      dllPaths = mapOf("Acme.Lib" to listOf("/pkgs/a.dll")),
      includes = listOf("Acme.Lib", "Acme.Lib.Core"),
      excludes = emptyList(),
    )
    val includeArgs: List<String> = cmd.drop(cmd.indexOf("--include"))
    assertEquals(listOf("--include", "Acme.Lib", "Acme.Lib.Core"), includeArgs)
  }

  @Test
  fun `excludes are emitted after a single --exclude flag when present`() {
    val cmd: List<String> = metadataReaderCommand(
      dotnet = "dotnet",
      readerProjectDir = reader,
      dllPaths = mapOf("Acme.Lib" to listOf("/pkgs/a.dll")),
      includes = emptyList(),
      excludes = listOf("Acme.Lib.Internal"),
    )
    val excludeArgs: List<String> = cmd.drop(cmd.indexOf("--exclude"))
    assertEquals(listOf("--exclude", "Acme.Lib.Internal"), excludeArgs)
  }

  @Test
  fun `no --include or --exclude flag when both are empty`() {
    val cmd: List<String> = metadataReaderCommand(
      dotnet = "dotnet",
      readerProjectDir = reader,
      dllPaths = mapOf("Acme.Lib" to listOf("/pkgs/a.dll")),
      includes = emptyList(),
      excludes = emptyList(),
    )
    assertFalse(cmd.contains("--include"), "no --include flag expected when includes is empty")
    assertFalse(cmd.contains("--exclude"), "no --exclude flag expected when excludes is empty")
  }
}
