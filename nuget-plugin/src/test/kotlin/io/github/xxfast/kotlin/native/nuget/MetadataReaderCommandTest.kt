package io.github.xxfast.kotlin.native.nuget

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the CLI contract between the Gradle plugin and the C# metadata reader. Pure — no dotnet,
 * no subprocess. The reader's argument parser in `NugetMetadataReader/Program.cs` must stay in
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
      includes = emptyMap(),
      excludes = emptyMap(),
    )
    assertEquals(listOf("dotnet", "run", "--project", reader.absolutePath, "--"), cmd.take(5))
  }

  @Test
  fun `each dll becomes a --package id path triple`() {
    val cmd: List<String> = metadataReaderCommand(
      dotnet = "dotnet",
      readerProjectDir = reader,
      dllPaths = mapOf("Newtonsoft.Json" to listOf("/pkgs/newtonsoft.json.dll")),
      includes = emptyMap(),
      excludes = emptyMap(),
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
      includes = emptyMap(),
      excludes = emptyMap(),
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
      includes = mapOf("Acme.Lib" to listOf("Acme.Lib", "Acme.Lib.Core")),
      excludes = emptyMap(),
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
      includes = emptyMap(),
      excludes = mapOf("Acme.Lib" to listOf("Acme.Lib.Internal")),
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
      includes = emptyMap(),
      excludes = emptyMap(),
    )
    assertFalse(cmd.contains("--include"), "no --include flag expected when includes is empty")
    assertFalse(cmd.contains("--exclude"), "no --exclude flag expected when excludes is empty")
  }

  @Test
  fun `package A include is emitted adjacent to package A dll, not after package B`() {
    val cmd: List<String> = metadataReaderCommand(
      dotnet = "dotnet",
      readerProjectDir = reader,
      dllPaths = mapOf("Pkg.A" to listOf("/pkgs/a.dll"), "Pkg.B" to listOf("/pkgs/b.dll")),
      includes = mapOf("Pkg.A" to listOf("Pkg.A.Core")),
      excludes = emptyMap(),
    )
    val args: List<String> = cmd.drop(cmd.indexOf("--") + 1)
    val packageIndices: List<Int> = args.indices.filter { args[it] == "--package" }
    val pkgAIdx: Int = packageIndices.first { args[it + 1] == "Pkg.A" }
    val pkgBIdx: Int = packageIndices.first { args[it + 1] == "Pkg.B" }
    val includeIdx: Int = args.indexOf("--include")
    assertTrue(includeIdx > pkgAIdx, "--include must appear after Pkg.A's --package triple")
    assertTrue(includeIdx < pkgBIdx, "--include must appear before Pkg.B's --package triple")
  }

  @Test
  fun `package with empty includes emits no --include flag after its --package triple`() {
    val cmd: List<String> = metadataReaderCommand(
      dotnet = "dotnet",
      readerProjectDir = reader,
      dllPaths = mapOf("Acme.Lib" to listOf("/pkgs/a.dll")),
      includes = mapOf("Acme.Lib" to emptyList()),
      excludes = emptyMap(),
    )
    assertFalse(
      cmd.contains("--include"),
      "no --include flag expected when package has empty includes list",
    )
  }

  @Test
  fun `package with no entry in includes map emits no --include flag`() {
    val cmd: List<String> = metadataReaderCommand(
      dotnet = "dotnet",
      readerProjectDir = reader,
      dllPaths = mapOf("Pkg.A" to listOf("/pkgs/a.dll"), "Pkg.B" to listOf("/pkgs/b.dll")),
      includes = mapOf("Pkg.A" to listOf("Pkg.A.Core")),
      excludes = emptyMap(),
    )
    val args: List<String> = cmd.drop(cmd.indexOf("--") + 1)
    val packageIndices: List<Int> = args.indices.filter { args[it] == "--package" }
    val pkgBIdx: Int = packageIndices.first { args[it + 1] == "Pkg.B" }
    val pkgBSegment: List<String> = args.drop(pkgBIdx)
    assertFalse(
      pkgBSegment.contains("--include"),
      "no --include expected for Pkg.B when absent from includes map",
    )
  }

  @Test
  fun `package with multiple dlls repeats include filter for each dll`() {
    val cmd: List<String> = metadataReaderCommand(
      dotnet = "dotnet",
      readerProjectDir = reader,
      dllPaths = mapOf("Acme.Lib" to listOf("/pkgs/a.dll", "/pkgs/b.dll")),
      includes = mapOf("Acme.Lib" to listOf("Acme.Lib.Core")),
      excludes = emptyMap(),
    )
    val args: List<String> = cmd.drop(cmd.indexOf("--") + 1)
    val includeIndices: List<Int> = args.indices.filter { args[it] == "--include" }
    assertEquals(2, includeIndices.size, "expected --include to appear once for each dll")
    includeIndices.forEach { idx ->
      assertEquals(
        "Acme.Lib.Core",
        args[idx + 1],
        "--include at index $idx should be followed by Acme.Lib.Core",
      )
    }
  }

  @Test
  fun `two packages with different include sets do not cross-contaminate`() {
    val cmd: List<String> = metadataReaderCommand(
      dotnet = "dotnet",
      readerProjectDir = reader,
      dllPaths = mapOf("Pkg.A" to listOf("/pkgs/a.dll"), "Pkg.B" to listOf("/pkgs/b.dll")),
      includes = mapOf("Pkg.A" to listOf("Pkg.A.Core"), "Pkg.B" to listOf("Pkg.B.Auth")),
      excludes = emptyMap(),
    )
    val args: List<String> = cmd.drop(cmd.indexOf("--") + 1)
    val packageIndices: List<Int> = args.indices.filter { args[it] == "--package" }
    val pkgAIdx: Int = packageIndices.first { args[it + 1] == "Pkg.A" }
    val pkgBIdx: Int = packageIndices.first { args[it + 1] == "Pkg.B" }
    val pkgASegment: List<String> = args.subList(pkgAIdx, pkgBIdx)
    val pkgBSegment: List<String> = args.drop(pkgBIdx)
    assertTrue(pkgASegment.contains("Pkg.A.Core"), "Pkg.A.Core should appear in Pkg.A's segment")
    assertFalse(pkgBSegment.contains("Pkg.A.Core"), "Pkg.A.Core must not appear in Pkg.B's segment")
    assertTrue(pkgBSegment.contains("Pkg.B.Auth"), "Pkg.B.Auth should appear in Pkg.B's segment")
    assertFalse(pkgASegment.contains("Pkg.B.Auth"), "Pkg.B.Auth must not appear in Pkg.A's segment")
  }
}
