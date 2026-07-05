package io.github.xxfast.kotlin.native.nuget

import io.github.xxfast.kotlin.native.nuget.rir.RirClass
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

  private fun runMetadataReader(
    dotnet: String,
    readerProjectDir: File,
    dllPaths: Map<String, List<String>>,
  ): String {
    val cmd: List<String> = metadataReaderCommand(
      dotnet = dotnet,
      readerProjectDir = readerProjectDir,
      dllPaths = dllPaths,
      includes = emptyMap(),
      excludes = emptyMap(),
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

    assertEquals(
      0,
      restoreExit,
      "dotnet restore must succeed for Newtonsoft.Json 13.0.3\n$restoreOutput",
    )

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

    val newtonsoftNs: RirNamespace = file.assemblies[0].namespaces
      .first { it.name == "Newtonsoft.Json" }
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

  @Test
  fun `metadata reader excludes internal methods from MimeMapping dll (KnownMimeTypes LookupType)`() {
    val dotnet: String = findDotnet() ?: return

    // 1. dotnet restore MimeMapping 4.0.0 to a temp dir → real project.assets.json
    val restoreDir: File = Files.createTempDirectory("nuget-extract-api-mimemapping-test").toFile()

    val csproj: String = generateCsproj(
      ids = listOf("MimeMapping"),
      versions = mapOf("MimeMapping" to "4.0.0"),
      sources = emptyMap(),
      targetFramework = "net8.0",
      rids = listOf("win-x64"),
    )

    val csprojFile = File(restoreDir, "interop.csproj")
    csprojFile.writeText(csproj)

    val restoreProcess: Process = ProcessBuilder(dotnet, "restore", csprojFile.absolutePath)
      .directory(restoreDir)
      .redirectErrorStream(true)
      .start()

    val restoreOutput: String = restoreProcess.inputStream.bufferedReader().readText()
    val restoreExit: Int = restoreProcess.waitFor()

    assertEquals(0, restoreExit, "dotnet restore must succeed for MimeMapping 4.0.0\n$restoreOutput")

    // 2. deriveDllPaths to locate the DLL
    val assetsFile = File(restoreDir, "obj/project.assets.json")
    assertTrue(assetsFile.exists(), "project.assets.json must exist after restore")

    val dllPaths: Map<String, List<String>> = deriveDllPaths(
      assetsJson = assetsFile.readText(),
      packageIds = setOf("MimeMapping"),
    )

    assertTrue(dllPaths.containsKey("MimeMapping"), "deriveDllPaths must find MimeMapping")
    val dlls: List<String> = requireNotNull(dllPaths["MimeMapping"])
    assertTrue(dlls.isNotEmpty(), "at least one DLL must be resolved for MimeMapping")

    // 3. Unpack the bundled metadata reader and invoke it
    val toolDir: File = Files.createTempDirectory("nuget-metadata-reader-mimemapping-test").toFile()
    unpackMetadataReader(toolDir, javaClass.classLoader)

    val json: String = runMetadataReader(dotnet, toolDir, dllPaths)

    // 4. Parse the result
    val file: RirFile = parseReverseIr(json)

    // 5. MimeUtility.GetMimeMapping (public) must be present, and only that method.
    assertEquals(1, file.assemblies.size)
    val mimeMappingNs: RirNamespace = file.assemblies[0].namespaces
      .first { it.name == "MimeMapping" }

    val mimeUtility: RirClass = mimeMappingNs.types.filterIsInstance<RirClass>()
      .first { it.name == "MimeUtility" }
    assertEquals(
      listOf("GetMimeMapping"),
      mimeUtility.methods.map { it.name },
      "MimeUtility must expose only the public GetMimeMapping method",
    )

    // 6. KnownMimeTypes.LookupType is a real but *internal* (non-public) method in the DLL. It
    // must not leak through the metadata reader's visibility filter — the bug this test pins: a
    // naive `(attrs & MethodAttributes.Public) != 0` check also matches Assembly/Family
    // accessibility, since `Public` (0x6) sits inside the 3-bit MemberAccessMask (0x7) and ANDs
    // non-zero against internal (0x3) and protected (0x4) too. If KnownMimeTypes appears at all
    // (a consts-only class may still show up with an empty member list), it must expose zero
    // bridgeable methods.
    val knownMimeTypes: RirClass? = mimeMappingNs.types.filterIsInstance<RirClass>()
      .firstOrNull { it.name == "KnownMimeTypes" }
    assertTrue(
      knownMimeTypes?.methods?.isEmpty() ?: true,
      "KnownMimeTypes must not expose any bridgeable methods (LookupType is internal, not " +
        "public) but found: ${knownMimeTypes?.methods?.map { it.name }}",
    )
  }
}
