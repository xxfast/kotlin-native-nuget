package io.github.xxfast.kotlin.native.nuget.processor.tier1

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

/** The outcome of one `dotnet build` of a Tier 1 run's generated C#. */
internal data class Tier1CSharpBuild(val exitCode: Int, val log: String) {
  val succeeded: Boolean get() = exitCode == 0
}

/**
 * Compiles the FRESH `Interop.cs` of one Tier 1 run, plus a consumer source, in an isolated temp
 * C# project. Tier 1 otherwise compiles only the generated Kotlin, so this is the one way a cell
 * can prove (or refute) that its C# half builds.
 *
 * The project `ProjectReference`s `Kotlin.Native.Interop.csproj` from source: a prebuilt DLL or a
 * restored package cache could not prove the cell. It FAILS, never skips, when `dotnet` (SDK 10)
 * is absent: CI installs it, and a silent skip is how a C# gap stays invisible.
 */
internal object Tier1CSharpCompile {

  /**
   * Builds [result]'s generated C# with [consumerSource] beside it, defining [defines]. Set
   * [allowUnsafe] for routes whose output uses `unsafe` (Flow), as the packaged `.csproj` and
   * `nugetCompileInterop` both do.
   */
  fun compile(
    result: Tier1Result,
    consumerSource: String,
    defines: String = "",
    allowUnsafe: Boolean = false,
  ): Tier1CSharpBuild {
    val root: File = generateSequence(File(System.getProperty("user.dir")).canonicalFile) {
      it.parentFile
    }.first { it.resolve("Kotlin.Native.Interop/Kotlin.Native.Interop.csproj").isFile }
    val contract: String = root.resolve("Kotlin.Native.Interop/Kotlin.Native.Interop.csproj")
      .path.replace('\\', '/')
    val directory: File = Files.createTempDirectory("nuget-tier1-csharp-").toFile()
    try {
      directory.resolve("Interop.cs").writeText(result.generatedCSharp)
      directory.resolve("Consumer.cs").writeText(consumerSource)
      val unsafeBlocks: String =
        if (allowUnsafe) "<AllowUnsafeBlocks>true</AllowUnsafeBlocks>" else ""
      directory.resolve("Consumer.csproj").writeText(
        """
        <Project Sdk="Microsoft.NET.Sdk">
          <PropertyGroup>
            <TargetFramework>net10.0</TargetFramework>
            <LangVersion>14</LangVersion>
            <Nullable>enable</Nullable>
            <TreatWarningsAsErrors>true</TreatWarningsAsErrors>
            <DefineConstants>$defines</DefineConstants>$unsafeBlocks
          </PropertyGroup>
          <ItemGroup><ProjectReference Include="$contract" /></ItemGroup>
        </Project>
        """.trimIndent(),
      )
      directory.resolve("NuGet.Config").writeText(
        "<configuration><packageSources><clear /></packageSources></configuration>",
      )
      val output: File = directory.resolve("build.log")
      // Every caller builds the one referenced contract project into its own bin/obj, and Gradle
      // runs test classes in parallel forks, so builds are serialised across processes.
      val lockFile: File = root.resolve("nuget-processor/build/tier1-csharp-compile.lock")
      lockFile.parentFile.mkdirs()
      RandomAccessFile(lockFile, "rw").use { access ->
        val channel: FileChannel = access.channel
        val lock: FileLock = channel.lock()
        try {
          val process: Process = ProcessBuilder("dotnet", "build", "Consumer.csproj", "--nologo")
            .directory(directory).redirectErrorStream(true).redirectOutput(output).start()
          val finished: Boolean = process.waitFor(120, TimeUnit.SECONDS)
          if (!finished) process.destroyForcibly().waitFor()
          assertTrue(finished, "dotnet C# compilation timed out: ${output.readText()}")
          return Tier1CSharpBuild(process.exitValue(), output.readText())
        } finally {
          lock.release()
        }
      }
    } finally {
      directory.deleteRecursively()
    }
  }

  /** As [compile], asserting the build succeeds. */
  fun assertCompiles(
    result: Tier1Result,
    consumerSource: String,
    defines: String = "",
    allowUnsafe: Boolean = false,
  ) {
    val build: Tier1CSharpBuild = compile(result, consumerSource, defines, allowUnsafe)
    assertTrue(
      build.succeeded,
      "fresh generated C# must compile (requires dotnet SDK 10): ${build.log}",
    )
  }
}
