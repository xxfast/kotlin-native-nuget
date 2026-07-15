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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
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

  private fun compileFixture(dotnet: String, source: String, name: String): File {
    val dir: File = Files.createTempDirectory(name).toFile()
    File(dir, "$name.csproj").writeText(
      """
      <Project Sdk="Microsoft.NET.Sdk">
        <PropertyGroup>
          <TargetFramework>net8.0</TargetFramework>
          <Nullable>enable</Nullable>
        </PropertyGroup>
      </Project>
      """.trimIndent(),
    )
    File(dir, "Fixture.cs").writeText(source)

    val process: Process = ProcessBuilder(dotnet, "build", "--nologo", "--verbosity", "quiet")
      .directory(dir)
      .redirectErrorStream(true)
      .start()
    val output: String = process.inputStream.bufferedReader().readText()
    assertEquals(0, process.waitFor(), "fixture compilation must succeed\n$output")
    return File(dir, "bin/Debug/net8.0/$name.dll")
  }

  private fun JsonObject.type(namespace: String, name: String): JsonObject {
    val assembly: JsonObject = getValue("assemblies").jsonArray.single().jsonObject
    val ns: JsonObject = assembly.getValue("namespaces").jsonArray
      .map { it.jsonObject }
      .single { it.getValue("name").jsonPrimitive.content == namespace }
    return ns.getValue("types").jsonArray
      .map { it.jsonObject }
      .single { it.getValue("name").jsonPrimitive.content == name }
  }

  private fun JsonArray.signatures(): List<String> = map { member ->
    member.jsonObject.getValue("managedSignature").jsonPrimitive.content
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
    val toolDir: File = Files.createTempDirectory("NugetMetadataReader-test").toFile()
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
      diagnostics.none { it.kind == RirDiagnosticKind.SKIPPED_OVERLOAD_SET },
      "ADR-057 maps overload siblings independently instead of rejecting whole name groups",
    )
  }

  @Test
  fun `metadata reader no longer rejects Newtonsoft overload groups wholesale`() {
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

    val toolDir: File = Files.createTempDirectory("NugetMetadataReader-overloads").toFile()
    unpackMetadataReader(toolDir, javaClass.classLoader)

    val json: String = runMetadataReader(dotnet, toolDir, dllPaths)

    val file: RirFile = parseReverseIr(json)

    assertTrue(
      file.assemblies[0].diagnostics.none {
        it.kind == RirDiagnosticKind.SKIPPED_OVERLOAD_SET && it.memberName == "SerializeObject"
      },
      "SerializeObject siblings must be assessed independently, not rejected as one overload set",
    )
  }

  @Test
  fun `metadata reader preserves overload identities and shape A alternate constructors`() {
    val dotnet: String = findDotnet() ?: return
    val declarationGroups: List<String> = listOf(
      """
      public OverloadLab(int seed) => _origin = $"seed:{seed}";
      public OverloadLab(bool enabled) => _origin = enabled ? "on" : "off";
      public static string Describe(int value) => $"int:{value}";
      public static string Describe(bool value) => value ? "true" : "false";
      public static string Describe(StackText value) => "unsupported";
      public string Apply(string value) => _origin + value;
      public string Apply(int value) => _origin + value;
      """.trimIndent(),
      """
      public string Apply(int value) => _origin + value;
      public string Apply(string value) => _origin + value;
      public static string Describe(StackText value) => "unsupported";
      public static string Describe(bool value) => value ? "true" : "false";
      public static string Describe(int value) => $"int:{value}";
      public OverloadLab(bool enabled) => _origin = enabled ? "on" : "off";
      public OverloadLab(int seed) => _origin = $"seed:{seed}";
      """.trimIndent(),
    )

    val roots: List<JsonObject> = declarationGroups.mapIndexed { index, declarations ->
      val source: String = """
        namespace Probe.Overloads;

        public ref struct StackText { }

        public readonly struct Size
        {
            public Size(int width, int height) { Width = width; Height = height; }
            public int Width { get; }
            public int Height { get; }
        }

        public readonly struct Point
        {
            public Point(int x, int y) { X = x; Y = y; }
            public Point(int value) : this(value, value) { }
            public Point(bool unit) : this(unit ? 1 : 0, unit ? 1 : 0) { }
            public Point(Size size) : this(size.Width, size.Height) { }
            public int X { get; }
            public int Y { get; }
        }

        public sealed class OverloadLab
        {
            private readonly string _origin;
        ${declarations.prependIndent("    ")}
        }
      """.trimIndent()
      val name = "OverloadReaderFixture$index"
      val dll: File = compileFixture(dotnet, source, name)
      val toolDir: File = Files
        .createTempDirectory("NugetMetadataReader-overload-fixture")
        .toFile()
      unpackMetadataReader(toolDir, javaClass.classLoader)
      Json.parseToJsonElement(
        runMetadataReader(dotnet, toolDir, mapOf("OverloadFixture" to listOf(dll.absolutePath))),
      ).jsonObject
    }

    val expectedMethods: Set<String> = setOf(
      "method|static|Probe.Overloads.OverloadLab|Describe|(System.Int32)|System.String",
      "method|static|Probe.Overloads.OverloadLab|Describe|(System.Boolean)|System.String",
      "method|instance|Probe.Overloads.OverloadLab|Apply|(System.String)|System.String",
      "method|instance|Probe.Overloads.OverloadLab|Apply|(System.Int32)|System.String",
    )
    val expectedClassConstructors: Set<String> = setOf(
      "ctor|instance|Probe.Overloads.OverloadLab|.ctor|(System.Int32)|System.Void",
      "ctor|instance|Probe.Overloads.OverloadLab|.ctor|(System.Boolean)|System.Void",
    )
    val expectedStructConstructors: Set<String> = setOf(
      "ctor|instance|Probe.Overloads.Point|.ctor|(System.Int32,System.Int32)|System.Void",
      "ctor|instance|Probe.Overloads.Point|.ctor|(System.Int32)|System.Void",
      "ctor|instance|Probe.Overloads.Point|.ctor|(System.Boolean)|System.Void",
      "ctor|instance|Probe.Overloads.Point|.ctor|(Probe.Overloads.Size)|System.Void",
    )

    roots.forEach { root ->
      val cls: JsonObject = root.type("Probe.Overloads", "OverloadLab")
      val methods: List<String> = cls.getValue("methods").jsonArray.signatures()
      val constructors: List<String> = cls.getValue("constructors").jsonArray.signatures()
      assertEquals(expectedMethods, methods.toSet())
      assertEquals(methods.size, methods.toSet().size, "method signatures must be unique")
      assertEquals(expectedClassConstructors, constructors.toSet())
      assertEquals(
        constructors.size,
        constructors.toSet().size,
        "constructor signatures must be unique",
      )

      val pointConstructors: JsonArray = root.type("Probe.Overloads", "Point")
        .getValue("constructors").jsonArray
      assertEquals(expectedStructConstructors, pointConstructors.signatures().toSet())
      val state: JsonObject = pointConstructors.map { it.jsonObject }
        .single { it.getValue("isState").jsonPrimitive.boolean }
      assertEquals(
        "ctor|instance|Probe.Overloads.Point|.ctor|(System.Int32,System.Int32)|System.Void",
        state.getValue("managedSignature").jsonPrimitive.content,
      )

      val assembly: JsonObject = root.getValue("assemblies").jsonArray.single().jsonObject
      val diagnostics: List<JsonObject> = assembly.getValue("diagnostics").jsonArray
        .map { it.jsonObject }
      assertTrue(
        diagnostics.any { diagnostic ->
          diagnostic.getValue("kind").jsonPrimitive.content == "skipped_ref_struct" &&
              diagnostic.getValue("typeName").jsonPrimitive.content == "OverloadLab" &&
              diagnostic.getValue("memberName").jsonPrimitive.content == "Describe"
        },
        "the unsupported ref-struct sibling must retain its own precise diagnostic",
      )
      assertTrue(
        diagnostics.none { it.getValue("kind").jsonPrimitive.content == "skipped_overload_set" },
        "supported overload sets must not emit skipped_overload_set",
      )
    }

    val forward: JsonObject = roots[0].type("Probe.Overloads", "OverloadLab")
    val reversed: JsonObject = roots[1].type("Probe.Overloads", "OverloadLab")
    assertEquals(
      forward.getValue("methods").jsonArray.signatures().toSet(),
      reversed.getValue("methods").jsonArray.signatures().toSet(),
    )
    assertEquals(
      forward.getValue("constructors").jsonArray.signatures().toSet(),
      reversed.getValue("constructors").jsonArray.signatures().toSet(),
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
    val toolDir: File = Files.createTempDirectory("NugetMetadataReader-mimemapping-test").toFile()
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
