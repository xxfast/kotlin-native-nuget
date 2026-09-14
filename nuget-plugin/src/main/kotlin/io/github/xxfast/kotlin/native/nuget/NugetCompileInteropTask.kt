package io.github.xxfast.kotlin.native.nuget

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.process.ExecResult
import org.gradle.work.DisableCachingByDefault
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

private const val NUGET_ORG_FEED = "https://api.nuget.org/v3/index.json"

/**
 * ADR-138: the throwaway csproj `nugetCompileInterop` builds. Its property set is
 * `GeneratedBindingsCheck/GeneratedBindingsCheck.csproj`'s, verbatim, plus `AllowUnsafeBlocks`
 * (which a real consumer gets from the package's own `build/<id>.targets`, and this project has no
 * package to import it from). If that csproj changes, this function changes with it;
 * `NugetCompileInteropTaskTest` pins every property so the drift is loud.
 */
internal fun generateCheckCsproj(
  csFiles: List<File>,
  dependencyVersions: Map<String, String>,
  dependencySources: List<String>,
): String {
  val restoreSourcesLine: String = if (dependencySources.isEmpty()) {
    ""
  } else {
    val urls: List<String> = (listOf(NUGET_ORG_FEED) + dependencySources).distinct()
    "\n    <RestoreSources>${urls.joinToString(";")}</RestoreSources>"
  }

  val compileItems: String = csFiles.joinToString("\n") { file ->
    """    <Compile Include="${file.absolutePath}" />"""
  }

  // Exact-version pins, the same [v] strings the .nuspec <dependencies> block uses, so the check
  // compiles against the versions the package declares rather than whatever restore floats to.
  val packageReferences: String = dependencyVersions.entries
    .sortedBy { it.key }
    .joinToString("\n") { (id, version) ->
      """    <PackageReference Include="$id" Version="[$version]" />"""
    }

  val referenceGroup: String = if (packageReferences.isEmpty()) {
    ""
  } else {
    "\n  <ItemGroup>\n$packageReferences\n  </ItemGroup>"
  }

  return """
    |<Project Sdk="Microsoft.NET.Sdk">
    |  <PropertyGroup>
    |    <TargetFramework>net8.0</TargetFramework>
    |    <LangVersion>12.0</LangVersion>
    |    <Nullable>enable</Nullable>
    |    <TreatWarningsAsErrors>true</TreatWarningsAsErrors>
    |    <EnableDefaultCompileItems>false</EnableDefaultCompileItems>
    |    <AllowUnsafeBlocks>true</AllowUnsafeBlocks>
    |    <GenerateDocumentationFile>true</GenerateDocumentationFile>
    |    <NoWarn>${'$'}(NoWarn);CS1591</NoWarn>$restoreSourcesLine
    |  </PropertyGroup>
    |  <ItemGroup>
    |$compileItems
    |  </ItemGroup>$referenceGroup
    |</Project>
  """.trimMargin().trim()
}

/**
 * ADR-138: compiles the generated C# bindings with `dotnet build` before `packNuget` stages them,
 * so a binding that does not compile fails the author's pack instead of every consumer's build.
 * Skips with a warning when `dotnet` is absent, because publishing is documented as needing no
 * .NET SDK.
 */
@DisableCachingByDefault(
  because = "dotnet build manages its own obj/ and bin/ state and resolves packages through the " +
    "global NuGet cache, which is outside project scope and not tracked by Gradle's build cache"
)
abstract class NugetCompileInteropTask : DefaultTask() {
  // The same producers packNuget stages: the KSP resources dir, plus nugetGenerateShims's
  // csharpOutputDir when the project also binds a package.
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val generatedCsDirs: ConfigurableFileCollection

  // The exact resolved version per bound package, the same map packNuget writes into the .nuspec
  // <dependencies> block. Empty for a forward-only project.
  @get:Input
  abstract val dependencyVersions: MapProperty<String, String>

  // The extra feeds declared with dependency(id, source = ...), so a package that only exists on a
  // private feed restores here too.
  @get:Input
  abstract val dependencySources: ListProperty<String>

  // Where interop-check.csproj and its obj/ and bin/ land: build/nuget-compile/.
  @get:OutputDirectory
  abstract val projectDir: DirectoryProperty

  // Overridable so a test can point it at an empty directory and exercise the skip.
  @get:Internal
  abstract val dotnetSearchPath: Property<String>

  @get:Inject
  abstract val execOps: ExecOperations

  @TaskAction
  fun compile() {
    val csFiles: List<File> = generatedCsFiles(generatedCsDirs.files)
    if (csFiles.isEmpty()) {
      logger.info("[nuget] No generated C# to compile; nothing to check before packing.")
      return
    }

    val dotnet: String? = findExecutable("dotnet", dotnetSearchPath.orNull ?: System.getenv("PATH"))
    if (dotnet == null) {
      logger.warn(
        "w: [nuget] dotnet is not on PATH, so the generated C# bindings were not compiled " +
          "before packing. A binding that does not compile will only surface in a consumer's " +
          "build. Install the .NET SDK 8.0 or later from https://dot.net/download to check at pack."
      )
      return
    }

    val dir: File = projectDir.get().asFile
    dir.mkdirs()

    val csproj = File(dir, "interop-check.csproj")
    csproj.writeText(
      generateCheckCsproj(csFiles, dependencyVersions.get(), dependencySources.get())
    )

    // Both streams are captured: the C# compiler writes `error CS....` and `Build FAILED.` to
    // stdout, not stderr, so a stderr-only capture (NugetRestoreTask's) would report a failure
    // with no errors in it.
    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()
    val result: ExecResult = execOps.exec { spec ->
      spec.commandLine(dotnet, "build", csproj.absolutePath, "--nologo", "-v", "quiet")
      spec.standardOutput = stdout
      spec.errorOutput = stderr
      spec.isIgnoreExitValue = true
    }

    val exitCode: Int = result.exitValue
    if (exitCode != 0) {
      throw GradleException(
        "[nuget] The generated C# bindings do not compile (dotnet build exit code $exitCode). " +
          "This is a generator defect: the package would fail in every consumer's build. " +
          "Compiler output:\n" +
          (stdout.toString().trimEnd() + "\n" + stderr.toString().trimEnd()).trim()
      )
    }
  }
}
