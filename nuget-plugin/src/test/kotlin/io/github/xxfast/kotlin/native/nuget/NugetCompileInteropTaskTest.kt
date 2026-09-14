package io.github.xxfast.kotlin.native.nuget

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-138: `packNuget` compiles the generated C# bindings before it packs them. Covers the csproj
 * rendering (pinned against `GeneratedBindingsCheck.csproj`, whose property set it must mirror),
 * the task wiring, the `dotnet`-absent skip, and one real `dotnet build` that must reject a
 * duplicate type.
 */
class NugetCompileInteropTaskTest {
  private fun buildProject(): Project {
    val project: Project = ProjectBuilder.builder().build()
    project.plugins.apply("org.jetbrains.kotlin.multiplatform")
    project.plugins.apply("io.github.xxfast.kotlin.native.nuget")

    val kotlin: KotlinMultiplatformExtension =
      project.extensions.getByType(KotlinMultiplatformExtension::class.java)
    kotlin.macosArm64 {
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
      packageId = "TestLibrary"
      version = "1.0.0"
      authors = "Test Author"
      description = "Test description"
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
  fun `renders the GeneratedBindingsCheck property set plus AllowUnsafeBlocks`() {
    val csproj: String = generateCheckCsproj(emptyList(), emptyMap(), emptyList())

    assertContains(csproj, "<TargetFramework>net8.0</TargetFramework>")
    assertContains(csproj, "<LangVersion>12.0</LangVersion>")
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
  fun `renders no RestoreSources and no PackageReference for a forward-only project`() {
    val csproj: String = generateCheckCsproj(emptyList(), emptyMap(), emptyList())

    assertFalse(csproj.contains("RestoreSources"), "no bound package means no extra feed")
    assertFalse(csproj.contains("PackageReference"), "no bound package means no reference")
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
  fun `a bound dependency adds the shims dir and the generate-shims dependency`() {
    val project: Project = buildProject()
    publish(project)

    project.extensions.getByType(NugetExtension::class.java).dependencies {
      dependency("TestDependency", version = "1.0.0") {
        bind { }
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
    task.dependencySources.set(emptyList())

    task.compile()

    assertFalse(
      File(out, "interop-check.csproj").exists(),
      "the skip must not write a csproj it never builds",
    )
  }

  @Test
  fun `a duplicate class fails the task with the compiler error text`() {
    // Skipped when the .NET SDK is absent, exactly as the task itself skips.
    findExecutable("dotnet") ?: return

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
    task.dependencySources.set(emptyList())

    val failure: GradleException = assertFailsWith<GradleException> { task.compile() }
    assertContains(failure.message.orEmpty(), "CS0101")
  }
}
