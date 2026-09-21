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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

  /**
   * ADR-152: the REAL reader against a REAL compiled assembly, per CLAUDE.md's hand-built-fixture
   * warning. Every claim here is about metadata the C# compiler wrote, not about JSON this test
   * typed out: the `NullableAttribute` bytes are pre-order over the whole tree with the `Task`
   * node counted first, and nothing but running the compiler produces them.
   */
  /**
   * ADR-155. A hand-built RIR proves nothing about the reader (CLAUDE.md), and this is the half a
   * generator test cannot fake: the `NullableAttribute` bytes are pre-order over the whole tree
   * with the COLLECTION node counted first and a value-type argument contributing none, so
   * `IReadOnlyList<string?>` and `IReadOnlyList<string>?` differ only in which node the `2` lands
   * on. Only the C# compiler produces those bytes.
   */
  @Test
  fun `metadata reader maps BCL collections to a collection type ref`() {
    val dotnet: String = findDotnet() ?: return

    val source: String = """
      using System.Collections.Generic;

      namespace Probe.Collections;

      public interface ILabelled { string Label { get; } }

      public sealed class Tag : ILabelled
      {
          public Tag(string label) { Label = label; }
          public string Label { get; }
      }

      public sealed class Roster
      {
          public IReadOnlyList<int> Ages() => new List<int> { 9, 7 };
          public IReadOnlyList<string> Names() => new List<string> { "Oreo" };
          public IReadOnlyList<string?> Nicknames() => new List<string?> { "O", null };
          public IReadOnlyList<string>? MaybeNames() => null;
          public IReadOnlyDictionary<string, int> Scores() => new Dictionary<string, int>();
          public ISet<string> Labels() => new HashSet<string>();
          public IList<Tag> Tags() => new List<Tag>();
          public int Enroll(IEnumerable<string> names) => 0;
          public void Rank(IDictionary<string, int> scores) { }
          public List<int?> Maybes() => new List<int?>();
          public string[] Codes() => new string[0];
          public Queue<int> Waiting() => new Queue<int>();
      }
    """.trimIndent()

    val dll: File = compileFixture(dotnet, source, "CollectionReaderFixture")
    val toolDir: File = Files.createTempDirectory("NugetMetadataReader-collection-fixture").toFile()
    unpackMetadataReader(toolDir, javaClass.classLoader)
    val root: JsonObject = Json.parseToJsonElement(
      runMetadataReader(dotnet, toolDir, mapOf("CollectionFixture" to listOf(dll.absolutePath))),
    ).jsonObject

    val type: JsonObject = root.type("Probe.Collections", "Roster")
    val methods: List<JsonObject> = type.getValue("methods").jsonArray.map { it.jsonObject }
    fun returnOf(name: String): JsonObject = methods
      .single { it.getValue("name").jsonPrimitive.content == name }
      .getValue("returnType").jsonObject

    fun kind(o: JsonObject): String = o.getValue("kind").jsonPrimitive.content
    fun nullable(o: JsonObject): Boolean = o["nullable"]?.jsonPrimitive?.boolean ?: false
    fun args(o: JsonObject): List<JsonObject> =
      o.getValue("typeArguments").jsonArray.map { it.jsonObject }

    // Matched on namespace + name only: IReadOnlyList`1 resolves to System.Runtime while List`1
    // and Dictionary`2 resolve to System.Collections, so an assembly-qualified match misses half.
    assertEquals("collection", kind(returnOf("Ages")))
    assertEquals("list", returnOf("Ages").getValue("collection").jsonPrimitive.content)
    assertEquals(
      "System.Collections.Generic.IReadOnlyList`1",
      returnOf("Ages").getValue("definition").jsonPrimitive.content,
      "the shim casts the container it built to the DECLARED definition, so it must survive",
    )
    assertEquals("map", returnOf("Scores").getValue("collection").jsonPrimitive.content)
    assertEquals("set", returnOf("Labels").getValue("collection").jsonPrimitive.content)
    assertEquals(
      "System.Collections.Generic.IList`1",
      returnOf("Tags").getValue("definition").jsonPrimitive.content,
    )
    assertEquals("handle", kind(args(returnOf("Tags")).single()))

    // The two `2` bytes, one node apart.
    assertEquals(false, nullable(returnOf("Nicknames")))
    assertEquals(true, nullable(args(returnOf("Nicknames")).single()))
    assertEquals(true, nullable(returnOf("MaybeNames")))
    assertEquals(false, nullable(args(returnOf("MaybeNames")).single()))
    // A value-type argument contributes no byte at all, and the whole member may carry none.
    assertEquals(false, nullable(returnOf("Ages")))
    assertEquals(false, nullable(args(returnOf("Ages")).single()))

    // Parameters ride the same decode.
    val enroll: JsonObject = methods
      .single { it.getValue("name").jsonPrimitive.content == "Enroll" }
    val names: JsonObject = enroll.getValue("parameters").jsonArray.single()
      .jsonObject.getValue("type").jsonObject
    assertEquals("collection", kind(names))
    assertEquals(
      "System.Collections.Generic.IEnumerable`1",
      names.getValue("definition").jsonPrimitive.content,
    )

    val diagnostics: List<JsonObject> = root.getValue("assemblies").jsonArray.single().jsonObject
      .getValue("diagnostics").jsonArray.map { it.jsonObject }
    fun diagnosed(member: String): String? = diagnostics
      .firstOrNull { it.getValue("memberName").jsonPrimitive.content == member }
      ?.getValue("kind")?.jsonPrimitive?.content

    // `List<int?>` is List<Nullable<int>>: it must be refused BY NAME before the inner
    // instantiation reaches ADR-072 Decision 9's diagnostic and blames the BCL.
    assertEquals("skipped_collection_element", diagnosed("Maybes"))
    // Arrays are deferred, and today vanish with no diagnostic at all.
    assertEquals("skipped_array", diagnosed("Codes"))
    // An unmapped BCL definition keeps the existing named skip.
    assertEquals("skipped_unbound_generic_instantiation", diagnosed("Waiting"))
    listOf("Maybes", "Codes", "Waiting").forEach { member ->
      assertTrue(
        methods.none { it.getValue("name").jsonPrimitive.content == member },
        "`$member` is outside ADR-155's v1 vocabulary and must not bind",
      )
    }
  }

  @Test
  fun `metadata reader maps Task returns to an async method and skips the rest`() {
    val dotnet: String = findDotnet() ?: return

    val source: String = """
      using System.Collections.Generic;
      using System.Threading;
      using System.Threading.Tasks;

      namespace Probe.Async;

      public sealed class Kitten
      {
          public Kitten(string name) { Name = name; }
          public string Name { get; }
      }

      public sealed class Kennel
      {
          public Task NapAsync() => Task.CompletedTask;
          public Task<int> CountAsync() => Task.FromResult(2);
          public Task<string> NameAsync() => Task.FromResult("Oreo & Mylo");
          public Task<Kitten> AdoptAsync(string name) => Task.FromResult(new Kitten(name));
          public Task<string?> WhisperAsync() => Task.FromResult<string?>(null);
          public Task<string>? MaybeAsync() => null;
          public ValueTask<int> PurrsAsync() => ValueTask.FromResult(3);
          public ValueTask SettleAsync() => ValueTask.CompletedTask;
          public void Queue(Task pending) { }
          public Task<string> LedgerAsync(string? a, string? b, string? c) =>
              Task.FromResult($"{a}{b}{c}");

          // ADR-156. Only the SIGNATURE is metadata, so these need no iterator bodies.
          public IAsyncEnumerable<string> BarksAsync(int count) => null!;
          public IAsyncEnumerable<Kitten> LitterAsync(CancellationToken ct = default) => null!;
          public static IAsyncEnumerable<int> Ticks() => null!;
          public IAsyncEnumerable<int> HowlsAsync() => null!;
          // Deferred, each a NAMED skip: an element with no reverse mapping (System.Nullable<int>),
          // the parameter position, and the nested shape the `not RirAsyncType` guard exists for
          // (without it `Nested` would bind with `Task<int>` as its element type, silently).
          public IAsyncEnumerable<int?> NullableTicks() => null!;
          public int Herd(IAsyncEnumerable<int> arrivals) => 0;
          public IAsyncEnumerable<Task<int>> Nested() => null!;
      }
    """.trimIndent()

    val dll: File = compileFixture(dotnet, source, "AsyncReaderFixture")
    val toolDir: File = Files.createTempDirectory("NugetMetadataReader-async-fixture").toFile()
    unpackMetadataReader(toolDir, javaClass.classLoader)
    val root: JsonObject = Json.parseToJsonElement(
      runMetadataReader(dotnet, toolDir, mapOf("AsyncFixture" to listOf(dll.absolutePath))),
    ).jsonObject

    val methods: List<JsonObject> = root.type("Probe.Async", "Kennel")
      .getValue("methods").jsonArray.map { it.jsonObject }

    fun method(name: String): JsonObject = methods.single {
      it.getValue("name").jsonPrimitive.content == name
    }

    fun asyncKind(name: String): String? =
      method(name)["asyncKind"]?.jsonPrimitive?.contentOrNull

    // The awaited type is the return type; non-generic `Task` awaits to void.
    assertEquals("task", asyncKind("NapAsync"))
    assertEquals(
      "void", method("NapAsync").getValue("returnType").jsonObject
        .getValue("kind").jsonPrimitive.content
    )
    assertEquals("task", asyncKind("CountAsync"))
    assertEquals(
      "int", method("CountAsync").getValue("returnType").jsonObject
        .getValue("name").jsonPrimitive.content
    )
    assertEquals("task", asyncKind("AdoptAsync"))
    assertEquals(
      "handle", method("AdoptAsync").getValue("returnType").jsonObject
        .getValue("kind").jsonPrimitive.content
    )

    // Nullability is resolved over the WHOLE tree before the Task node is unwrapped:
    // `Task<string>` is [1, 1] and `Task<string?>` is [1, 2]. Unwrap first and both read byte 1.
    assertEquals(
      false,
      method("NameAsync").getValue("returnType").jsonObject
        .getValue("nullable").jsonPrimitive.boolean,
    )
    assertEquals(
      true,
      method("WhisperAsync").getValue("returnType").jsonObject
        .getValue("nullable").jsonPrimitive.boolean,
    )

    // Three nullable parameters put LedgerAsync in a NullableContext(2), so its all-non-null
    // two-node return tree is written as the SINGLE-byte NullableAttribute form. ADR-152 inferred
    // that byte expands to the node count (claim D); it did not, and this member was skipped.
    assertEquals("task", asyncKind("LedgerAsync"))
    assertEquals(
      false,
      method("LedgerAsync").getValue("returnType").jsonObject
        .getValue("nullable").jsonPrimitive.boolean,
    )

    // ADR-156: an `IAsyncEnumerable<T>` METHOD RETURN carries its own kind, so the generator can
    // tell "suspend fun over Begin/End" from "plain fun returning Flow over Enumerate/Current"
    // without re-deriving it from the return type.
    listOf("BarksAsync", "LitterAsync", "Ticks", "HowlsAsync").forEach { member ->
      assertEquals("async_enumerable", asyncKind(member), "`$member` must bind as a Flow source")
    }

    // A synchronous method still carries no async kind at all. A SET, not a list: the fixture's
    // declaration order is not the contract, membership is.
    assertEquals(
      setOf(
        "NapAsync", "CountAsync", "NameAsync", "AdoptAsync", "WhisperAsync", "LedgerAsync",
        "BarksAsync", "LitterAsync", "Ticks", "HowlsAsync",
      ),
      methods.filter { it["asyncKind"]?.jsonPrimitive?.contentOrNull != null }
        .map { it.getValue("name").jsonPrimitive.content }.toSet(),
    )

    // Everything deferred keeps a NAMED skip, never a silent drop: ValueTask (both arities),
    // `Task<T>?`, and a `Task`-typed parameter.
    val diagnostics: List<JsonObject> = root.getValue("assemblies").jsonArray.single().jsonObject
      .getValue("diagnostics").jsonArray.map { it.jsonObject }
    // ADR-156 adds two of its own: `Herd`, an `IAsyncEnumerable<T>` at a PARAMETER (return
    // position only), and `NullableTicks`, whose `int?` element is System.Nullable<int> and has
    // no reverse mapping at all — the split-out nullable-value-element item. Both must be NAMED,
    // never a silent bind that drops the nulls or binds the parameter as something else.
    listOf("PurrsAsync", "SettleAsync", "MaybeAsync", "Queue", "Herd").forEach { member ->
      assertTrue(
        diagnostics.any {
          it.getValue("kind").jsonPrimitive.content == "info_async_not_yet_mapped" &&
              it.getValue("memberName").jsonPrimitive.content == member
        },
        "`$member` must be skipped with info_async_not_yet_mapped, found: " +
            diagnostics.map {
              it.getValue("memberName").jsonPrimitive.content to
                  it.getValue("kind").jsonPrimitive.content
            },
      )
    }

    // The split-out item, asserted by its SYMPTOM rather than its diagnostic kind: nothing maps
    // System.Nullable<int> on the reverse side, so `IAsyncEnumerable<int?>` must be skipped by
    // SOME named diagnostic and must not appear as a bound method at all. Binding it as a plain
    // `Flow<Int>` would silently drop every null the C# source yields.
    listOf("NullableTicks", "Nested").forEach { member ->
      assertTrue(
        diagnostics.any { it.getValue("memberName").jsonPrimitive.content == member },
        "`$member` must be named by a diagnostic, found: " +
            diagnostics.map { it.getValue("memberName").jsonPrimitive.content },
      )
      assertTrue(
        methods.none { it.getValue("name").jsonPrimitive.content == member },
        "`$member` must not bind: binding it silently would drop nulls (NullableTicks) or use " +
            "the wrong element type (Nested)",
      )
    }

    // The bug ADR-152 fixes on the way: non-generic `Task` used to reach
    // `skipped_unbound_type_reference`, which told the user to bind System.Private.CoreLib.
    assertTrue(
      diagnostics.none {
        it.getValue("kind").jsonPrimitive.content == "skipped_unbound_type_reference" &&
            it.getValue("memberName").jsonPrimitive.content == "NapAsync"
      },
      "non-generic Task must not be reported as an unbound type reference",
    )
  }

  /**
   * ADR-153: the REAL reader again, for the same CLAUDE.md reason. Everything asserted here is
   * about metadata the C# compiler wrote: `CancellationToken` is a `TypeReference` into another
   * assembly, `CancellationToken?` is a `GENERICINST Nullable<...>` that never reaches the same
   * decode branch, and a defaulted token differs only in its `Param` row. A hand-built `RirClass`
   * proves none of that.
   */
  @Test
  fun `metadata reader elides a single CancellationToken from an async method`() {
    val dotnet: String = findDotnet() ?: return

    val source: String = """
      using System.Threading;
      using System.Threading.Tasks;

      namespace Probe.Cancel;

      public sealed class Kennel
      {
          public Kennel() { }
          public Kennel(CancellationToken ct) { }
          public Task<int> StayAsync(string name, CancellationToken ct) => Task.FromResult(0);
          public Task<int> DawdleAsync(CancellationToken ct = default) => Task.FromResult(9);
          public Task BoltAsync() => Task.CompletedTask;
          public Task<int> FetchAsync(CancellationToken ct, int count) => Task.FromResult(count);
          public Task<int> CallAsync() => CallAsync(CancellationToken.None);
          public Task<int> CallAsync(CancellationToken ct) => Task.FromResult(1);
          public int Wait(CancellationToken ct) => 0;
          public Task<int> TwiceAsync(CancellationToken a, CancellationToken b) =>
              Task.FromResult(2);
          public Task<int> MaybeAsync(CancellationToken? ct) => Task.FromResult(3);
      }
    """.trimIndent()

    val dll: File = compileFixture(dotnet, source, "CancelReaderFixture")
    val toolDir: File = Files.createTempDirectory("NugetMetadataReader-cancel-fixture").toFile()
    unpackMetadataReader(toolDir, javaClass.classLoader)
    val root: JsonObject = Json.parseToJsonElement(
      runMetadataReader(dotnet, toolDir, mapOf("CancelFixture" to listOf(dll.absolutePath))),
    ).jsonObject

    val type: JsonObject = root.type("Probe.Cancel", "Kennel")
    val methods: List<JsonObject> = type.getValue("methods").jsonArray.map { it.jsonObject }
    fun method(name: String): JsonObject = methods.single {
      it.getValue("name").jsonPrimitive.content == name
    }

    fun token(name: String): Int? = method(name)["cancellationToken"]?.jsonPrimitive?.intOrNull
    fun parameters(name: String): List<String> =
      method(name).getValue("parameters").jsonArray.map {
        it.jsonObject.getValue("name").jsonPrimitive.content
      }

    // The index is the C# one, where the shim puts `cts.Token` back in; the parameter itself is
    // gone from the list Kotlin binds.
    assertEquals(1, token("StayAsync"))
    assertEquals(listOf("name"), parameters("StayAsync"))
    // A default value changes only the Param row, so the reader does not need to read it.
    assertEquals(0, token("DawdleAsync"))
    assertEquals(emptyList(), parameters("DawdleAsync"))
    // Any position, not just last.
    assertEquals(0, token("FetchAsync"))
    assertEquals(listOf("count"), parameters("FetchAsync"))
    // The control: no token, no index at all (ADR-152's shape, untouched).
    assertEquals(null, token("BoltAsync"))

    // The fold: after elision both CallAsync overloads are `suspend fun call()`, which does not
    // compile in the consumer. The token overload is the one kept.
    assertEquals(
      1,
      methods.count { it.getValue("name").jsonPrimitive.content == "CallAsync" },
      "the token-less sibling must be folded away, not emitted beside the token overload",
    )
    assertEquals(0, token("CallAsync"))

    val diagnostics: List<JsonObject> = root.getValue("assemblies").jsonArray.single().jsonObject
      .getValue("diagnostics").jsonArray.map { it.jsonObject }
    assertTrue(
      diagnostics.any {
        it.getValue("kind").jsonPrimitive.content == "info_cancellation_overload_folded" &&
            it.getValue("memberName").jsonPrimitive.content == "CallAsync"
      },
      "the dropped sibling must say so by name, found: " + diagnostics.map {
        it.getValue("memberName").jsonPrimitive.content to it.getValue("kind").jsonPrimitive.content
      },
    )

    // Deferred shapes keep a NAMED skip, never the old "bind System.Private.CoreLib" advice.
    listOf("Wait", "TwiceAsync", "MaybeAsync").forEach { member ->
      assertTrue(
        methods.none { it.getValue("name").jsonPrimitive.content == member },
        "`$member` is out of ADR-153's scope and must not bind",
      )
      assertTrue(
        diagnostics.any {
          it.getValue("kind").jsonPrimitive.content == "info_cancellation_token_not_yet_mapped" &&
              it.getValue("memberName").jsonPrimitive.content == member
        },
        "`$member` must be skipped with info_cancellation_token_not_yet_mapped, found: " +
            diagnostics.map {
              it.getValue("memberName").jsonPrimitive.content to
                  it.getValue("kind").jsonPrimitive.content
            },
      )
    }
    assertTrue(
      diagnostics.none {
        it.getValue("kind").jsonPrimitive.content == "skipped_unbound_type_reference" &&
            it.getValue("memberName").jsonPrimitive.content == "Wait"
      },
      "a CancellationToken is not an unbound type reference; that hint tells the user to bind " +
          "the BCL",
    )

    // A token on a constructor is out of scope too: only the parameterless ctor survives.
    assertEquals(1, type.getValue("constructors").jsonArray.size)
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

  @Test
  fun `an init-only property is read-only and flagged init-only on a class and on an interface`() {
    val dotnet: String = findDotnet() ?: return

    // An `init` accessor reaches metadata as a public setter carrying
    // `modreq(System.Runtime.CompilerServices.IsExternalInit)` on its RETURN type: nothing in the
    // property loop used to look at the setter's signature, so all three of these read back
    // identically writable and the generated C# thunk assigned the init-only ones (CS8852/CS8854).
    val source: String = """
      namespace Probe.Init;

      public interface IBadge
      {
          string Label { get; init; }
      }

      public sealed class Kennel
      {
          public Kennel() { }
          public string Motto { get; init; } = "sit, stay";
          public int Capacity { get; init; } = 2;
          public string Mutable { get; set; } = "";
          public string Fixed { get; } = "";
      }
    """.trimIndent()

    val dll: File = compileFixture(dotnet, source, "InitReaderFixture")
    val toolDir: File = Files.createTempDirectory("NugetMetadataReader-init-fixture").toFile()
    unpackMetadataReader(toolDir, javaClass.classLoader)
    val file: RirFile = parseReverseIr(
      runMetadataReader(dotnet, toolDir, mapOf("InitFixture" to listOf(dll.absolutePath))),
    )
    val ns: RirNamespace = file.assemblies.single().namespaces.single { it.name == "Probe.Init" }
    val kennel: RirClass = ns.types.filterIsInstance<RirClass>().single { it.name == "Kennel" }
    fun property(name: String) = kennel.properties.single { it.name == name }

    // init-only: read-only AND flagged, one converting member type and one direct.
    assertTrue(property("Motto").isReadOnly, "an init-only property is read-only after construction")
    assertTrue(property("Motto").isInitOnly)
    assertTrue(property("Capacity").isReadOnly)
    assertTrue(property("Capacity").isInitOnly)

    // An ordinary setter is untouched, and a genuinely get-only property is read-only but NOT
    // init-only (the interface bridge must still emit a get-only property for that one).
    assertFalse(property("Mutable").isReadOnly)
    assertFalse(property("Mutable").isInitOnly)
    assertTrue(property("Fixed").isReadOnly)
    assertFalse(property("Fixed").isInitOnly)

    // The interface half, which is where the flag is load-bearing (ADR-085 bridge accessor).
    val badge = ns.types.filterIsInstance<io.github.xxfast.kotlin.native.nuget.rir.RirInterface>()
      .single { it.name == "IBadge" }
    assertTrue(badge.properties.single().isReadOnly)
    assertTrue(badge.properties.single().isInitOnly)
  }
}
