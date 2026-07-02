package io.github.xxfast.kotlin.native.nuget

import io.github.xxfast.kotlin.native.nuget.rir.RirDiagnostic
import io.github.xxfast.kotlin.native.nuget.rir.RirDiagnosticKind
import io.github.xxfast.kotlin.native.nuget.rir.RirFile
import io.github.xxfast.kotlin.native.nuget.rir.RirNamespace
import io.github.xxfast.kotlin.native.nuget.rir.deriveDllPaths
import io.github.xxfast.kotlin.native.nuget.rir.parseReverseIr
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests that invoke the C# metadata reader as a subprocess. Skipped when `dotnet` is
 * absent from PATH. These tests hit the network, the global NuGet cache, and compile the bundled
 * reader project on first run.
 */
class NugetExtractApiIntegrationTest {
  private fun findDotnet(): String? {
    val process: Process = ProcessBuilder("which", "dotnet").start()
    val line: String? = process.inputStream.bufferedReader().readLine()?.takeIf { it.isNotBlank() }
    process.waitFor()
    return line
  }

  private fun runMetadataReader(
    dotnet: String,
    readerProjectDir: File,
    dllPaths: Map<String, List<String>>,
  ): String {
    val cmd: List<String> = metadataReaderCommand(
      dotnet = dotnet,
      readerProjectDir = readerProjectDir,
      dllPaths = dllPaths,
      includes = emptyList(),
      excludes = emptyList(),
    )
    val process: Process = ProcessBuilder(cmd).redirectErrorStream(false).start()
    val stdout: String = process.inputStream.bufferedReader().readText()
    val stderr: String = process.errorStream.bufferedReader().readText()
    val exitCode: Int = process.waitFor()
    assertEquals(0, exitCode, "metadata reader must succeed\n$stderr")
    return stdout
  }

  @Test
  fun `metadata reader emits reverse-ir json for Newtonsoft Json dll`() {
    val dotnet: String = findDotnet() ?: return

    // 1. dotnet restore Newtonsoft.Json 13.0.3 to a temp dir → real project.assets.json
    val restoreDir: File = Files.createTempDirectory("nuget-extract-api-test").toFile()

    val csproj: String = generateCsproj(
      ids = listOf("Newtonsoft.Json"),
      versions = mapOf("Newtonsoft.Json" to "13.0.3"),
      sources = emptyMap(),
      targetFramework = "net8.0",
      rids = listOf("osx-arm64"),
    )

    val csprojFile = File(restoreDir, "interop.csproj")
    csprojFile.writeText(csproj)

    val restoreProcess: Process = ProcessBuilder(dotnet, "restore", csprojFile.absolutePath)
      .directory(restoreDir)
      .redirectErrorStream(true)
      .start()

    val restoreOutput: String = restoreProcess.inputStream.bufferedReader().readText()
    val restoreExit: Int = restoreProcess.waitFor()

    assertEquals(0, restoreExit, "dotnet restore must succeed for Newtonsoft.Json 13.0.3\n$restoreOutput")

    // 2. deriveDllPaths to locate the DLL
    val assetsFile = File(restoreDir, "obj/project.assets.json")
    assertTrue(assetsFile.exists(), "project.assets.json must exist after restore")

    val dllPaths: Map<String, List<String>> = deriveDllPaths(
      assetsJson = assetsFile.readText(),
      packageIds = setOf("Newtonsoft.Json"),
    )

    assertTrue(dllPaths.containsKey("Newtonsoft.Json"), "deriveDllPaths must find Newtonsoft.Json")
    val dlls: List<String> = requireNotNull(dllPaths["Newtonsoft.Json"])
    assertTrue(dlls.isNotEmpty(), "at least one DLL must be resolved for Newtonsoft.Json")

    // 3. Unpack the bundled metadata reader and invoke it
    val toolDir: File = Files.createTempDirectory("nuget-metadata-reader-test").toFile()
    unpackMetadataReader(toolDir, javaClass.classLoader)

    val json: String = runMetadataReader(dotnet, toolDir, dllPaths)

    // 4. Parse the result
    val file: RirFile = parseReverseIr(json)

    // 5. Assert expected content
    assertEquals(1, file.assemblies.size)
    assertEquals("Newtonsoft.Json", file.assemblies[0].packageId)

    val namespaces: List<String> = file.assemblies[0].namespaces.map { it.name }
    assertTrue(
      namespaces.contains("Newtonsoft.Json"),
      "namespace 'Newtonsoft.Json' must be present, found: $namespaces",
    )

    val newtonsoftNs: RirNamespace = file.assemblies[0].namespaces.first { it.name == "Newtonsoft.Json" }
    val typeNames: List<String> = newtonsoftNs.types.map { it.name }
    assertTrue(
      typeNames.contains("JsonConvert"),
      "type 'JsonConvert' must be present in Newtonsoft.Json namespace, found: $typeNames",
    )

    val diagnostics: List<RirDiagnostic> = file.assemblies[0].diagnostics
    assertTrue(
      diagnostics.any { it.kind == RirDiagnosticKind.SKIPPED_OVERLOAD_SET },
      "at least one SKIPPED_OVERLOAD_SET diagnostic must be present",
    )
  }

  @Test
  fun `metadata reader emits skipped_overload_set diagnostic for overloaded methods`() {
    val dotnet: String = findDotnet() ?: return

    val restoreDir: File = Files.createTempDirectory("nuget-extract-overloads-test").toFile()

    val csproj: String = generateCsproj(
      ids = listOf("Newtonsoft.Json"),
      versions = mapOf("Newtonsoft.Json" to "13.0.3"),
      sources = emptyMap(),
      targetFramework = "net8.0",
      rids = listOf("osx-arm64"),
    )

    File(restoreDir, "interop.csproj").writeText(csproj)

    val restoreProcess: Process = ProcessBuilder(dotnet, "restore", "$restoreDir/interop.csproj")
      .directory(restoreDir)
      .redirectErrorStream(true)
      .start()

    val restoreExit: Int = restoreProcess.waitFor()
    restoreProcess.inputStream.bufferedReader().readText() // drain

    assertEquals(0, restoreExit, "dotnet restore must succeed for Newtonsoft.Json 13.0.3")

    val assetsFile = File(restoreDir, "obj/project.assets.json")
    val dllPaths: Map<String, List<String>> = deriveDllPaths(
      assetsJson = assetsFile.readText(),
      packageIds = setOf("Newtonsoft.Json"),
    )

    val toolDir: File = Files.createTempDirectory("nuget-metadata-reader-overloads").toFile()
    unpackMetadataReader(toolDir, javaClass.classLoader)

    val json: String = runMetadataReader(dotnet, toolDir, dllPaths)

    val file: RirFile = parseReverseIr(json)

    val overloadDiagnostics: List<RirDiagnostic> = file.assemblies[0].diagnostics
      .filter { it.kind == RirDiagnosticKind.SKIPPED_OVERLOAD_SET }

    assertNotNull(
      overloadDiagnostics.firstOrNull { it.memberName == "SerializeObject" },
      "SerializeObject must produce a SKIPPED_OVERLOAD_SET diagnostic",
    )
  }
}
