package io.github.xxfast.kotlin.native.nuget

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.junit.jupiter.api.Assumptions
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-138: `packNuget` compiles the generated C# bindings before it packs them. Covers the csproj
 * rendering (pinned against `GeneratedBindingsCheck.csproj`, whose property set it must mirror),
 * the task wiring, the `dotnet`-absent skip, and one real `dotnet build` that must reject a
 * duplicate type.
 */
class NugetCompileInteropTaskTest {
  private val contractFeed: File by lazy {
    val root: File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
      .first { File(it, "Kotlin.Native.Interop/Kotlin.Native.Interop.csproj").exists() }
    val feed: File = tempDir("interop-contract-feed")
    val process = ProcessBuilder("dotnet", "pack",
      File(root, "Kotlin.Native.Interop/Kotlin.Native.Interop.csproj").absolutePath,
      "--output", feed.absolutePath).redirectErrorStream(true).start()
    val output: String = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { "Contract fixture pack failed: $output" }
    feed
  }

  private fun localContractSources(): List<String> =
    if (findExecutable("dotnet") == null) emptyList() else listOf(contractFeed.absolutePath)

  private fun buildProject(): Project {
    val project: Project = ProjectBuilder.builder().build()
    project.plugins.apply("org.jetbrains.kotlin.multiplatform")
    project.plugins.apply("io.github.xxfast.kotlin.native.nuget")

    val kotlin: KotlinMultiplatformExtension =
      project.extensions.getByType(KotlinMultiplatformExtension::class.java)
    kotlin.mingwX64 {
      binaries {
        sharedLib {
          baseName = "test"
        }
      }
    }

    return project
  }

  private fun Project.evaluate() {
    (this as ProjectInternal).evaluate()
  }

  private fun publish(project: Project) {
    project.extensions.getByType(NugetExtension::class.java).publish {
      it.packageId.set("TestLibrary")
      it.version.set("1.0.0")
      it.authors.set("Test Author")
      it.description.set("Test description")
    }
  }

  private fun compileTask(): NugetCompileInteropTask {
    val project: Project = ProjectBuilder.builder().build()
    return project.tasks
      .register("nugetCompileInterop", NugetCompileInteropTask::class.java)
      .get()
  }

  private fun tempDir(name: String): File = Files.createTempDirectory(name).toFile()

  @Test
  fun `the framework sweep follows the selected SDK and includes the floor`() {
    assertEquals(listOf("net10.0"), compileTargetFrameworks("net10.0", "10.0.301"))
    assertEquals(
      listOf("net10.0", "net11.0", "net12.0"),
      compileTargetFrameworks("net10.0", "12.0.100"),
    )
    assertEquals(listOf("net11.0", "net12.0"), compileTargetFrameworks("net11.0", "12.0.100"))
    assertEquals(
      listOf("net10.0", "net11.0"),
      compileTargetFrameworks("net10.0", "11.0.100-preview.1"),
    )
    assertEquals(listOf("net10.0"), compileTargetFrameworks("net10.0", "9.0.100"))
    assertFailsWith<GradleException> { compileTargetFrameworks("net10.0", "unrecognized SDK") }
    assertFailsWith<GradleException> { compileTargetFrameworks("net10", "10.0.100") }
  }

  @Test
  fun `renders every selected framework without changing the package floor`() {
    val csproj: String = generateCheckCsproj(
      emptyList(), emptyMap(), emptyList(), targetFrameworks = listOf("net10.0", "net11.0"),
    )
    assertContains(csproj, "<TargetFrameworks>net10.0;net11.0</TargetFrameworks>")
    assertFalse(csproj.contains("<TargetFramework>"))
  }

  @Test
  fun `valid bindings compile on the floor and higher frameworks`() {
    dotnetForTest()
    val sources: File = tempDir("compile-interop-matrix-src")
    File(sources, "Interop.cs").writeText("public class Sample { }")
    val out: File = tempDir("compile-interop-matrix-out")
    val task: NugetCompileInteropTask = compileTask()
    task.generatedCsDirs.from(sources)
    task.projectDir.set(out)
    task.dependencyVersions.set(emptyMap())
    task.dependencySources.set(localContractSources())
    task.targetFramework.set("net9.0")

    task.compile()

    assertTrue(File(out, "bin/Debug/net9.0/interop-check.dll").exists())
    assertTrue(File(out, "bin/Debug/net10.0/interop-check.dll").exists())
    assertFalse(task.outputs.upToDateSpec.isSatisfiedBy(task))
  }

  @Test
  fun `a defect only on a higher framework fails the check`() {
    dotnetForTest()
    val sources: File = tempDir("compile-interop-higher-src")
    File(sources, "Interop.cs").writeText(
      """
      public class Sample { }
      #if NET10_0
      #error HIGHER_TFM_DETECTED
      #endif
      """.trimIndent(),
    )
    val task: NugetCompileInteropTask = compileTask()
    task.generatedCsDirs.from(sources)
    task.projectDir.set(tempDir("compile-interop-higher-out"))
    task.dependencyVersions.set(emptyMap())
    task.dependencySources.set(localContractSources())
    // Internal probe floor: the public DSL still requires net10.0 or higher.
    task.targetFramework.set("net9.0")

    val failure: GradleException = assertFailsWith<GradleException> { task.compile() }
    assertContains(failure.message.orEmpty(), "HIGHER_TFM_DETECTED")
    assertContains(failure.message.orEmpty(), "net10.0")
  }

  @Test
  fun `renders the GeneratedBindingsCheck property set plus AllowUnsafeBlocks`() {
    val csproj: String = generateCheckCsproj(emptyList(), emptyMap(), emptyList(), "net11.0")

    assertContains(csproj, "<TargetFrameworks>net11.0</TargetFrameworks>")
    assertContains(csproj, "<LangVersion>14.0</LangVersion>")
    assertContains(csproj, "<Nullable>enable</Nullable>")
    assertContains(csproj, "<TreatWarningsAsErrors>true</TreatWarningsAsErrors>")
    assertContains(csproj, "<EnableDefaultCompileItems>false</EnableDefaultCompileItems>")
    assertContains(csproj, "<GenerateDocumentationFile>true</GenerateDocumentationFile>")
    assertContains(csproj, "<NoWarn>\$(NoWarn);CS1591</NoWarn>")
    assertContains(csproj, "<AllowUnsafeBlocks>true</AllowUnsafeBlocks>")
  }

  @Test
  fun `renders one absolute Compile Include per file`() {
    val dir: File = tempDir("compile-interop-files")
    val interop = File(dir, "Interop.cs")
    val shim = File(dir, "CatRegistration.cs")
    interop.writeText("")
    shim.writeText("")

    val csproj: String = generateCheckCsproj(listOf(interop, shim), emptyMap(), emptyList())

    assertContains(csproj, """<Compile Include="${interop.absolutePath}" />""")
    assertContains(csproj, """<Compile Include="${shim.absolutePath}" />""")
  }

  @Test
  fun `pins every bound package at an exact version`() {
    val csproj: String = generateCheckCsproj(
      emptyList(),
      mapOf("MimeMapping" to "4.0.0", "TestDependency" to "1.0.0-fixture.17"),
      emptyList(),
    )

    assertContains(csproj, """<PackageReference Include="MimeMapping" Version="[4.0.0]" />""")
    assertContains(
      csproj,
      """<PackageReference Include="TestDependency" Version="[1.0.0-fixture.17]" />""",
    )
  }

  @Test
  fun `forward-only project references the contract without extra restore sources`() {
    val csproj: String = generateCheckCsproj(emptyList(), emptyMap(), emptyList())

    assertFalse(csproj.contains("RestoreSources"), "no bound package means no extra feed")
    assertContains(csproj, "<PackageReference Include=\"$INTEROP_CONTRACT_ID\" Version=\"$INTEROP_CONTRACT_RANGE\" />")
  }

  @Test
  fun `renders RestoreSources with nuget org first when a source is declared`() {
    val csproj: String = generateCheckCsproj(
      emptyList(),
      mapOf("TestDependency" to "1.0.0"),
      listOf("/local/feed"),
    )

    assertContains(
      csproj,
      "<RestoreSources>https://api.nuget.org/v3/index.json;/local/feed</RestoreSources>",
    )
  }

  @Test
  fun `renders RestorePackagesPath when a packages path is given`() {
    val csproj: String = generateCheckCsproj(
      emptyList(), emptyMap(), listOf("/a&b/feed"), packagesPath = "/a&b/packages",
    )

    assertContains(csproj, "<RestorePackagesPath>/a&amp;b/packages</RestorePackagesPath>")
    assertContains(csproj, ";/a&amp;b/feed</RestoreSources>")
  }

  // ADR-190: the check restores a local source from the same resolved feeds into the same
  // project-local folder as nugetRestore, or it compiles against a stale copy.
  @Test
  fun `a local source gives the check absolute feeds and the shared packages folder`() {
    val project: Project = buildProject()
    publish(project)
    writeNupkg(project.file("libs/Acme.Local.1.0.0.nupkg"), "Acme.Local", "1.0.0")
    project.extensions.getByType(NugetExtension::class.java).dependencies {
      it.dependency("Acme.Local") { dep -> dep.source.set("libs/Acme.Local.1.0.0.nupkg") }
      it.dependency("Acme.Text", "1.0.0") { dep -> dep.source.set("artifacts") }
    }

    val compile = project.tasks.getByName("nugetCompileInterop") as NugetCompileInteropTask
    val interop: File = project.layout.buildDirectory.dir("nuget-interop").get().asFile

    assertEquals(
      listOf(File(interop, "feed").absolutePath, project.file("artifacts").absolutePath),
      compile.dependencySources.get(),
    )
    assertEquals(File(interop, "packages"), compile.packagesDir.get().asFile)
  }

  // test-library and test-companion add an absolute directory to dependencySources by hand; that
  // must not move them off the global packages folder.
  @Test
  fun `a directory added to dependencySources by hand keeps the global packages folder`() {
    val project: Project = buildProject()
    publish(project)
    val compile = project.tasks.getByName("nugetCompileInterop") as NugetCompileInteropTask
    compile.dependencySources.add(tempDir("hand-feed").absolutePath)

    assertFalse(compile.packagesDir.isPresent)
  }

  @Test
  fun `packNuget depends on nugetCompileInterop and both stage the same cs dirs`() {
    val project: Project = buildProject()
    publish(project)
    project.evaluate()

    val packNuget = project.tasks.getByName("packNuget") as PackNugetTask
    val compile = project.tasks.getByName("nugetCompileInterop") as NugetCompileInteropTask
    val deps: Set<Task> = packNuget.taskDependencies.getDependencies(packNuget)

    assertTrue(deps.contains(compile), "packNuget must depend on nugetCompileInterop")
    assertEquals(packNuget.generatedCsDirs.files, compile.generatedCsDirs.files)
    assertEquals(emptyMap(), compile.dependencyVersions.get())
  }

  @Test
  fun `check depends on nugetCompileInterop when publish is configured`() {
    val project: Project = buildProject()
    publish(project)
    project.evaluate()

    val check: Task = project.tasks.getByName("check")
    val compile: Task = project.tasks.getByName("nugetCompileInterop")

    assertTrue(
      check.taskDependencies.getDependencies(check).contains(compile),
      "check must compile the generated C# bindings, not only packNuget",
    )
  }

  @Test
  fun `check gains no compile dependency without publish`() {
    val project: Project = buildProject()
    project.extensions.getByType(NugetExtension::class.java).dependencies {
      it.dependency("TestDependency", version = "1.0.0") { dep -> dep.bind { } }
    }
    project.evaluate()

    val check: Task = project.tasks.getByName("check")
    val names: Set<String> = check.taskDependencies.getDependencies(check).map { it.name }.toSet()

    assertNull(project.tasks.findByName("nugetCompileInterop"))
    assertFalse("nugetCompileInterop" in names, "check depended on $names")
  }

  @Test
  fun `a bound dependency adds the shims dir and the generate-shims dependency`() {
    val project: Project = buildProject()
    publish(project)

    project.extensions.getByType(NugetExtension::class.java).dependencies {
      it.dependency("TestDependency", version = "1.0.0") {
        it.bind { }
      }
    }

    project.evaluate()

    val compile = project.tasks.getByName("nugetCompileInterop") as NugetCompileInteropTask
    val shims = project.tasks.getByName("nugetGenerateShims") as NugetGenerateShimsTask
    val shimsDir: File = shims.csharpOutputDir.get().asFile
    val deps: Set<Task> = compile.taskDependencies.getDependencies(compile)

    assertTrue(
      compile.generatedCsDirs.files.contains(shimsDir),
      "the reverse shims are staged by packNuget, so they must be compiled too, " +
        "was ${compile.generatedCsDirs.files}",
    )
    assertTrue(deps.contains(shims), "nugetCompileInterop must run after nugetGenerateShims")
  }

  @Test
  fun `compile skips and writes no csproj when dotnet is not on the search path`() {
    val task: NugetCompileInteropTask = compileTask()
    val empty: File = tempDir("compile-interop-nodotnet")
    val out: File = tempDir("compile-interop-out")
    val sources: File = tempDir("compile-interop-skip-src")
    File(sources, "Interop.cs").writeText("namespace Sample { public class Ok { } }")

    task.generatedCsDirs.from(sources)
    task.dotnetSearchPath.set(empty.absolutePath)
    task.projectDir.set(out)
    task.dependencyVersions.set(emptyMap())
    task.dependencySources.set(localContractSources())

    task.compile()

    assertFalse(
      File(out, "interop-check.csproj").exists(),
      "the skip must not write a csproj it never builds",
    )
  }

  @Test
  fun `a global json above the scratch dir cannot pick the sdk the check builds with`() {
    // Needs the .NET SDK: skipped locally without it, failed under CI.
    dotnetForTest()

    // The pin no machine satisfies, the shape a consumer uses for their own app (issue #224).
    val consumer: File = tempDir("compile-interop-globaljson")
    File(consumer, "global.json").writeText("""{"sdk":{"version":"1.0.0"}}""")
    val out = File(consumer, "build/nuget-compile")
    out.mkdirs()

    val sources: File = tempDir("compile-interop-globaljson-src")
    File(sources, "Interop.cs").writeText("namespace Sample { public class Ok { } }")

    val task: NugetCompileInteropTask = compileTask()
    task.generatedCsDirs.from(sources)
    task.projectDir.set(out)
    task.dependencyVersions.set(emptyMap())
    task.dependencySources.set(localContractSources())

    task.compile()

    assertTrue(File(out, "global.json").exists(), "the check must write its own SDK selection")
    assertTrue(File(out, "NuGet.config").exists(), "the check must write its own feed selection")
    assertTrue(File(out, "interop-check.csproj").exists(), "the check must write its csproj")
    assertTrue(
      File(out, "bin/Debug/net10.0/interop-check.dll").exists(),
      "the check must have compiled under an installed SDK, not the pin above it",
    )
  }

  @Test
  fun `a Directory Build props above the scratch dir cannot relax the check`() {
    // Needs the .NET SDK: skipped locally without it, failed under CI.
    dotnetForTest()

    // The marker target is what actually discriminates: `NoWarn` cannot suppress an error, so the
    // property group alone would leave CS0101 in place whether or not the props file was imported.
    // The error fires before compilation, so an imported props file replaces CS0101 with the
    // marker.
    val consumer: File = tempDir("compile-interop-dirprops")
    File(consumer, "Directory.Build.props").writeText(
      """
      <Project>
        <PropertyGroup>
          <TreatWarningsAsErrors>false</TreatWarningsAsErrors>
          <NoWarn>CS0101</NoWarn>
        </PropertyGroup>
        <Target Name="ConsumerPropsImported" BeforeTargets="PrepareForBuild">
          <Error Text="NUGET_CONSUMER_PROPS_IMPORTED" />
        </Target>
      </Project>
      """.trimIndent()
    )
    val out = File(consumer, "build/nuget-compile")
    out.mkdirs()

    val sources: File = tempDir("compile-interop-dirprops-src")
    File(sources, "Bad.cs").writeText(
      """
      namespace Sample
      {
          public class Duplicate { }
          public class Duplicate { }
      }
      """.trimIndent()
    )

    val task: NugetCompileInteropTask = compileTask()
    task.generatedCsDirs.from(sources)
    task.projectDir.set(out)
    task.dependencyVersions.set(emptyMap())
    task.dependencySources.set(localContractSources())

    val failure: GradleException = assertFailsWith<GradleException> { task.compile() }
    assertContains(
      failure.message.orEmpty(),
      "CS0101",
      message = "the check must still reject the duplicate type",
    )
    assertFalse(
      failure.message.orEmpty().contains("NUGET_CONSUMER_PROPS_IMPORTED"),
      "a consumer's Directory.Build.props must not be imported by the check",
    )
  }

  @Test
  fun `an sdk that cannot run skips the check instead of building`() {
    // A shell script cannot stand in for dotnet.exe on Windows.
    Assumptions.assumeFalse(
      System.getProperty("os.name").startsWith("Windows", ignoreCase = true),
      "needs a shell script as the dotnet executable",
    )

    val bin: File = tempDir("compile-interop-badsdk-bin")
    val marker = File(bin, "built.marker")
    val fake = File(bin, "dotnet")
    fake.writeText(
      """
      #!/bin/sh
      if [ "${'$'}1" = "build" ]; then touch "${marker.absolutePath}"; fi
      echo "the SDK could not be resolved" 1>&2
      exit 155
      """.trimIndent()
    )
    fake.setExecutable(true)

    val out: File = tempDir("compile-interop-badsdk-out")
    val sources: File = tempDir("compile-interop-badsdk-src")
    File(sources, "Interop.cs").writeText("namespace Sample { public class Ok { } }")

    val task: NugetCompileInteropTask = compileTask()
    task.generatedCsDirs.from(sources)
    task.dotnetSearchPath.set(bin.absolutePath)
    task.projectDir.set(out)
    task.dependencyVersions.set(emptyMap())
    task.dependencySources.set(localContractSources())

    task.compile()

    assertFalse(marker.exists(), "an unusable SDK must not reach dotnet build")
  }

  @Test
  fun `strict mode fails when dotnet is not on the search path`() {
    val task: NugetCompileInteropTask = compileTask()
    val sources: File = tempDir("compile-interop-strict-src")
    File(sources, "Interop.cs").writeText("namespace Sample { public class Ok { } }")

    task.generatedCsDirs.from(sources)
    task.dotnetSearchPath.set(tempDir("compile-interop-strict-nodotnet").absolutePath)
    task.projectDir.set(tempDir("compile-interop-strict-out"))
    task.dependencyVersions.set(emptyMap())
    task.dependencySources.set(emptyList())
    task.strictCompileCheck.set(true)

    val failure: GradleException = assertFailsWith<GradleException> { task.compile() }

    assertContains(failure.message.orEmpty(), "dot.net/download")
    assertContains(failure.message.orEmpty(), "nuget.dotnet")
    assertContains(failure.message.orEmpty(), "local.properties")
    assertContains(failure.message.orEmpty(), "strictCompileCheck")
  }

  @Test
  fun `strict mode fails when the sdk cannot run`() {
    // A shell script cannot stand in for dotnet.exe on Windows.
    Assumptions.assumeFalse(
      System.getProperty("os.name").startsWith("Windows", ignoreCase = true),
      "needs a shell script as the dotnet executable",
    )

    val bin: File = tempDir("compile-interop-strict-badsdk-bin")
    val fake = File(bin, "dotnet")
    fake.writeText("#!/bin/sh\necho \"the SDK could not be resolved\" 1>&2\nexit 155\n")
    fake.setExecutable(true)

    val sources: File = tempDir("compile-interop-strict-badsdk-src")
    File(sources, "Interop.cs").writeText("namespace Sample { public class Ok { } }")

    val task: NugetCompileInteropTask = compileTask()
    task.generatedCsDirs.from(sources)
    task.dotnetSearchPath.set(bin.absolutePath)
    task.projectDir.set(tempDir("compile-interop-strict-badsdk-out"))
    task.dependencyVersions.set(emptyMap())
    task.dependencySources.set(emptyList())
    task.strictCompileCheck.set(true)

    val failure: GradleException = assertFailsWith<GradleException> { task.compile() }

    val message: String = failure.message.orEmpty()
    assertContains(message, "exit code 155")
    assertContains(message, "the SDK could not be resolved")
    assertContains(message, "strictCompileCheck")
  }

  @Test
  fun `strict mode still skips when there is no generated C#`() {
    val task: NugetCompileInteropTask = compileTask()
    task.generatedCsDirs.from(tempDir("compile-interop-strict-empty"))
    task.dotnetSearchPath.set(tempDir("compile-interop-strict-empty-path").absolutePath)
    task.projectDir.set(tempDir("compile-interop-strict-empty-out"))
    task.strictCompileCheck.set(true)

    task.compile()
  }

  @Test
  fun `strictCompileCheck defaults to off and is wired from publish`() {
    assertFalse(compileTask().strictCompileCheck.get())

    val project: Project = buildProject()
    publish(project)
    project.extensions.getByType(NugetExtension::class.java).publish {
      it.strictCompileCheck.set(true)
    }
    project.evaluate()

    val compile = project.tasks.getByName("nugetCompileInterop") as NugetCompileInteropTask
    assertTrue(compile.strictCompileCheck.get())
  }

  @Test
  fun `a duplicate class fails the task with the compiler error text`() {
    // Needs the .NET SDK: skipped locally without it, failed under CI.
    dotnetForTest()

    val sources: File = tempDir("compile-interop-bad")
    File(sources, "Bad.cs").writeText(
      """
      namespace Sample
      {
          public class Duplicate { }
          public class Duplicate { }
      }
      """.trimIndent()
    )

    val task: NugetCompileInteropTask = compileTask()
    task.generatedCsDirs.from(sources)
    task.projectDir.set(tempDir("compile-interop-bad-out"))
    task.dependencyVersions.set(emptyMap())
    task.dependencySources.set(localContractSources())

    val failure: GradleException = assertFailsWith<GradleException> { task.compile() }
    assertContains(failure.message.orEmpty(), "CS0101")
  }
}
