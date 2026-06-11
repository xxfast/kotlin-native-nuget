package io.github.xxfast.nuget

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File

abstract class PackNugetTask : DefaultTask() {
  @get:Input
  abstract val packageId: Property<String>

  @get:Input
  abstract val packageVersion: Property<String>

  @get:Input
  abstract val authors: Property<String>

  @get:Input
  abstract val packageDescription: Property<String>

  @get:Input
  abstract val libraryName: Property<String>

  @get:Input
  abstract val nativeLibDirs: MapProperty<String, String>

  @get:InputFile
  abstract val headerFile: RegularFileProperty

  @get:OutputDirectory
  abstract val outputDir: DirectoryProperty

  @TaskAction
  fun pack() {
    val id: String = packageId.get()
    val version: String = packageVersion.get()
    val libName: String = libraryName.get()
    val outDir: File = outputDir.get().asFile

    val nupkgDir = File(outDir, "$id.$version")
    nupkgDir.deleteRecursively()
    nupkgDir.mkdirs()

    for ((rid, libPath) in nativeLibDirs.get()) {
      val nativeDir = File(nupkgDir, "runtimes/$rid/native")
      nativeDir.mkdirs()

      val libs: List<File> = File(libPath).listFiles()
        ?.filter { it.extension in listOf("dll", "dylib", "so") }
        ?: continue

      for (lib in libs) {
        lib.copyTo(File(nativeDir, lib.name), overwrite = true)
      }
    }

    val contentDir = File(nupkgDir, "content")
    contentDir.mkdirs()

    val header: File = headerFile.get().asFile
    header.copyTo(File(contentDir, header.name), overwrite = true)

    val namespace = "$id.Interop"
    File(contentDir, "NativeTypeName.cs").writeText(
      """
        |using System;
        |using System.Diagnostics;
        |
        |namespace $namespace
        |{
        |    [AttributeUsage(AttributeTargets.All, AllowMultiple = false)]
        |    [Conditional("DEBUG")]
        |    internal sealed class NativeTypeNameAttribute : Attribute
        |    {
        |        public NativeTypeNameAttribute(string name) { }
        |    }
        |}
      """.trimMargin()
    )

    val buildDir = File(nupkgDir, "build")
    buildDir.mkdirs()

    File(buildDir, "$id.targets").writeText(generateTargets(id, libName, header.name))

    // TODO Remove when Phase 2 replaces ClangSharp with a custom Source Generator.
    // Workaround: macOS SIP strips DYLD_* from child processes, so MSBuild's <Exec> can't
    // pass library paths to ClangSharp. This wrapper script sets them before exec'ing the tool.
    val script = File(buildDir, "run-clangsharp.sh")
    script.writeText(
      """
        |#!/bin/sh
        |export DYLD_LIBRARY_PATH="${'$'}1"
        |export LD_LIBRARY_PATH="${'$'}1"
        |shift
        |exec ClangSharpPInvokeGenerator "${'$'}@"
      """.trimMargin()
    )
    script.setExecutable(true)

    File(nupkgDir, "$id.nuspec").writeText(generateNuspec(id, version))

    logger.lifecycle("NuGet package staged at: ${nupkgDir.absolutePath}")
  }

  private fun generateTargets(
    id: String,
    libName: String,
    headerFileName: String,
  ): String = """
    |<Project xmlns="http://schemas.microsoft.com/developer/msbuild/2003">
    |  <PropertyGroup>
    |    <${id}_Header>${'$'}(MSBuildThisFileDirectory)..\content\$headerFileName</${id}_Header>
    |    <${id}_GeneratedDir>${'$'}(IntermediateOutputPath)Generated\$id\</${id}_GeneratedDir>
    |    <${id}_ClangNativeLibs>${'$'}(NuGetPackageRoot)libclang.runtime.osx-arm64/21.1.8/runtimes/osx-arm64/native:${'$'}(NuGetPackageRoot)libclangsharp.runtime.osx-arm64/21.1.8.2/runtimes/osx-arm64/native</${id}_ClangNativeLibs>
    |  </PropertyGroup>
    |
    |  <Target Name="${id}_GenerateBindings"
    |          BeforeTargets="CoreCompile"
    |          Inputs="${'$'}(${id}_Header)"
    |          Outputs="${'$'}(${id}_GeneratedDir)Interop.cs"
    |          Condition="!Exists('${'$'}(${id}_GeneratedDir)Interop.cs')">
    |    <MakeDir Directories="${'$'}(${id}_GeneratedDir)" />
    |    <Exec Command="${'$'}(MSBuildThisFileDirectory)run-clangsharp.sh &quot;${'$'}(${id}_ClangNativeLibs)&quot; --file &quot;${'$'}(${id}_Header)&quot; --traverse &quot;${'$'}(${id}_Header)&quot; --namespace $id.Interop --output &quot;${'$'}(${id}_GeneratedDir)Interop.cs&quot; --libraryPath $libName --methodClassName ${id}Native --config latest-codegen --config single-file"
    |          Condition="'${'$'}(OS)' != 'Windows_NT'" />
    |    <Exec Command="ClangSharpPInvokeGenerator --file &quot;${'$'}(${id}_Header)&quot; --traverse &quot;${'$'}(${id}_Header)&quot; --namespace $id.Interop --output &quot;${'$'}(${id}_GeneratedDir)Interop.cs&quot; --libraryPath $libName --methodClassName ${id}Native --config latest-codegen --config single-file"
    |          Condition="'${'$'}(OS)' == 'Windows_NT'" />
    |    <Copy SourceFiles="${'$'}(MSBuildThisFileDirectory)..\content\NativeTypeName.cs"
    |          DestinationFolder="${'$'}(${id}_GeneratedDir)" />
    |  </Target>
    |
    |  <Target Name="${id}_IncludeGenerated"
    |          BeforeTargets="CoreCompile"
    |          DependsOnTargets="${id}_GenerateBindings">
    |    <ItemGroup>
    |      <Compile Include="${'$'}(${id}_GeneratedDir)**\*.cs" />
    |    </ItemGroup>
    |  </Target>
    |
    |  <PropertyGroup>
    |    <AllowUnsafeBlocks>true</AllowUnsafeBlocks>
    |  </PropertyGroup>
    |</Project>
  """.trimMargin()

  private fun generateNuspec(id: String, version: String): String = """
    |<?xml version="1.0" encoding="utf-8"?>
    |<package xmlns="http://schemas.microsoft.com/packaging/2013/05/nuspec.xsd">
    |  <metadata>
    |    <id>$id</id>
    |    <version>$version</version>
    |    <authors>${authors.get()}</authors>
    |    <description>${packageDescription.get()}</description>
    |    <dependencies>
    |      <group targetFramework="net8.0">
    |        <dependency id="libclang.runtime.osx-arm64" version="21.1.8" />
    |        <dependency id="libClangSharp.runtime.osx-arm64" version="21.1.8.2" />
    |      </group>
    |    </dependencies>
    |  </metadata>
    |</package>
  """.trimMargin()
}
