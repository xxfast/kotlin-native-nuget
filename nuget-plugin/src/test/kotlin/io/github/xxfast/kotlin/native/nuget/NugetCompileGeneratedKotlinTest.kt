package io.github.xxfast.kotlin.native.nuget

import io.github.xxfast.kotlin.native.nuget.rir.RirAssembly
import io.github.xxfast.kotlin.native.nuget.rir.RirFile
import io.github.xxfast.kotlin.native.nuget.rir.RirInterface
import io.github.xxfast.kotlin.native.nuget.rir.RirMethod
import io.github.xxfast.kotlin.native.nuget.rir.RirNamespace
import io.github.xxfast.kotlin.native.nuget.rir.RirProperty
import io.github.xxfast.kotlin.native.nuget.rir.RirStringType
import io.github.xxfast.kotlin.native.nuget.rir.RirVoidType
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * ADR-193: compile reverse-generated Kotlin with the host kotlinc-native, so invalid output
 * fails in `:nuget-plugin:test` instead of at the end of `scripts/verify.sh`.
 *
 * nativeMain plus one host actual set (posixMain on macOS and Linux, mingwMain on Windows).
 * Both actual sets together define the same actuals twice, so the other set is not compiled.
 */
class NugetCompileGeneratedKotlinTest {

  @Test
  fun `compiles the generated reverse Kotlin for an interface with a settable property`() {
    val compiled: GeneratedCompile = compileGenerated(generate(), "positive")
    val output: CompileOutput = compiled.output
    assertEquals(
      0,
      output.exitCode,
      "kotlinc-native failed\n${output.stdout}\n${output.stderr}",
    )
    assertTrue(
      compiled.klib.isFile,
      "kotlinc-native exited 0 but ${compiled.klib.absolutePath} is missing\n" +
        "${output.stdout}\n${output.stderr}",
    )
  }

  @Test
  fun `a dropped NugetHandleOwner import does not compile`() {
    val compiled: GeneratedCompile = compileGenerated(
      dropHandleOwnerImport(generate()),
      "negative",
    )
    val output: CompileOutput = compiled.output
    val text: String = output.stdout + output.stderr
    assertNotEquals(0, output.exitCode, "expected a compile failure\n$text")
    assertContains(text, "unresolved reference 'NugetHandleOwner'")
  }

  private fun generate(): List<GeneratedFile> {
    val iface = RirInterface(
      name = "IFeedable",
      methods = listOf(
        RirMethod(name = "Feed", returnType = RirVoidType),
      ),
      properties = listOf(
        RirProperty(name = "Label", type = RirStringType(nullable = false), isReadOnly = false),
      ),
    )
    val rir = RirFile(
      assemblies = listOf(
        RirAssembly(
          packageId = "TestDependency",
          assemblyName = "TestDependency",
          namespaces = listOf(
            RirNamespace(name = "Test.Menagerie", types = listOf(iface)),
          ),
        ),
      ),
    )
    return generateKotlinStubs(rir)
  }

  private fun dropHandleOwnerImport(files: List<GeneratedFile>): List<GeneratedFile> {
    val importLine = "import io.github.xxfast.kotlin.native.nuget.internal.NugetHandleOwner"
    val result: MutableList<GeneratedFile> = mutableListOf()
    var dropped = false
    for (file in files) {
      if (!file.relativePath.endsWith("Handle.kt")) {
        result.add(file)
        continue
      }
      val kept: List<String> = file.content.lines().filter { line -> line != importLine }
      check(kept.size < file.content.lines().size) {
        "no `$importLine` in ${file.relativePath}"
      }
      dropped = true
      result.add(file.copy(content = kept.joinToString("\n")))
    }
    check(dropped) { "generator emitted no handle file" }
    return result
  }

  private fun compileGenerated(files: List<GeneratedFile>, name: String): GeneratedCompile {
    val actual: String = hostActual()
    val toolchain: Shared = shared
    val dir: File = File(workDir(), name)
    dir.deleteRecursively()
    dir.mkdirs()
    check(dir.isDirectory) { "could not create ${dir.absolutePath}" }

    val written: WrittenSources = writeSources(files, dir, actual)
    check(written.common.isNotEmpty()) { "generator emitted no nativeMain files" }
    check(written.platform.isNotEmpty()) { "generator emitted no $actual files" }

    val annotation: File = File(property("nuget.annotationSource"))
    check(annotation.isFile) { "annotation source not found: ${annotation.absolutePath}" }

    val sources: MutableList<File> = mutableListOf()
    sources.addAll(written.common)
    sources.addAll(written.platform)
    sources.add(annotation)
    val common: MutableList<File> = mutableListOf()
    common.addAll(written.common)
    common.add(annotation)

    val klib: File = File(dir, "generated.klib")
    val output: CompileOutput = compileKlib(
      compiler = toolchain.compiler,
      sources = sources,
      common = common,
      libraries = listOf(toolchain.runtimeKlib, toolchain.coroutines),
      output = File(dir, "generated"),
      workDir = dir,
    )
    return GeneratedCompile(output, klib)
  }

  private fun writeSources(files: List<GeneratedFile>, dir: File, actual: String): WrittenSources {
    val common: MutableList<File> = mutableListOf()
    val platform: MutableList<File> = mutableListOf()
    for (file in files) {
      val nativeMain: Boolean = file.relativePath.startsWith("nativeMain/")
      val hostActual: Boolean = file.relativePath.startsWith("$actual/")
      if (!nativeMain && !hostActual) continue
      val out: File = File(dir, file.relativePath)
      val parent: File? = out.parentFile
      if (parent != null) parent.mkdirs()
      out.writeText(file.content)
      if (nativeMain) common.add(out) else platform.add(out)
    }
    return WrittenSources(common, platform)
  }

  private fun hostActual(): String {
    val actual: String? = System.getProperty("nuget.hostActualSet")
    if (actual == null || actual.isBlank()) {
      error("nuget.hostActualSet is not set. Run this test through Gradle's test task.")
    }
    if (actual == "posixMain" || actual == "mingwMain") return actual
    error(
      "unsupported Kotlin/Native host os.name='${System.getProperty("os.name")}' " +
        "os.arch='${System.getProperty("os.arch")}'. " +
        "Expected macOS or Linux (posixMain) or Windows x64 (mingwMain).",
    )
  }

  private fun property(name: String): String {
    val value: String? = System.getProperty(name)
    check(!value.isNullOrBlank()) {
      "$name is not set. Run this test through Gradle's test task."
    }
    return value
  }

  private fun workDir(): File {
    val dir: File = File(property("nuget.compileWorkDir"))
    dir.mkdirs()
    check(dir.isDirectory) { "could not create ${dir.absolutePath}" }
    return dir
  }

  private class WrittenSources(val common: List<File>, val platform: List<File>)

  private class GeneratedCompile(val output: CompileOutput, val klib: File)

  private class CompileOutput(val exitCode: Int, val stdout: String, val stderr: String)

  private class Shared(val compiler: File, val coroutines: File, val runtimeKlib: File)

  companion object {
    private val shared: Shared by lazy { prepare() }

    private fun prepare(): Shared {
      val coroutines: File = File(property("nuget.coroutinesKlib"))
      check(coroutines.isFile && coroutines.extension == "klib") {
        "expected a coroutines klib, got ${coroutines.absolutePath}"
      }
      val compiler: File = compilerBinary()
      val runtimeKlib: File = compileRuntime(compiler, coroutines)
      return Shared(compiler, coroutines, runtimeKlib)
    }

    private fun property(name: String): String {
      val value: String? = System.getProperty(name)
      check(!value.isNullOrBlank()) {
        "$name is not set. Run this test through Gradle's test task."
      }
      return value
    }

    private fun compilerBinary(): File {
      val unpackDir: File = File(property("nuget.compileWorkDir"), "kotlin-native")
      val marker: File = File(unpackDir, ".unpacked")
      val existing: File? = findCompiler(unpackDir)
      if (marker.isFile && existing != null) return existing

      unpackDir.deleteRecursively()
      unpackDir.mkdirs()
      val archive: File = File(property("nuget.kotlinNativeArchive"))
      check(archive.isFile && archive.name.endsWith(".tar.gz")) {
        "expected a kotlin-native-prebuilt tar.gz, got ${archive.absolutePath}"
      }
      val extracted: CompileOutput = run(
        command = listOf("tar", "-xzf", archive.absolutePath, "-C", unpackDir.absolutePath),
        workDir = unpackDir,
        timeout = 5.minutes,
      )
      check(extracted.exitCode == 0) {
        "failed to unpack ${archive.absolutePath}\n${extracted.stdout}\n${extracted.stderr}"
      }
      val compiler: File = findCompiler(unpackDir)
        ?: error("bin/kotlinc-native not found under ${unpackDir.absolutePath}")
      val bin: Array<File>? = compiler.parentFile.listFiles()
      bin?.forEach { if (it.isFile) it.setExecutable(true) }
      marker.writeText("ok\n")
      return compiler
    }

    private fun findCompiler(unpackDir: File): File? {
      val tops: Array<File>? = unpackDir.listFiles()
      if (tops == null) return null
      for (top in tops) {
        if (!top.isDirectory || top.name.startsWith(".")) continue
        val script: File = File(top, "bin/kotlinc-native")
        if (script.isFile) return script
        val bat: File = File(top, "bin/kotlinc-native.bat")
        if (bat.isFile) return bat
      }
      return null
    }

    private fun compileRuntime(compiler: File, coroutines: File): File {
      val dir: File = File(property("nuget.compileWorkDir"), "runtime")
      dir.mkdirs()
      check(dir.isDirectory) { "could not create ${dir.absolutePath}" }
      val harness: File = File(dir, "NugetRuntimeVersion.kt")
      harness.writeText(
        "package io.github.xxfast.kotlin.native.nuget.runtime\n" +
          "\n" +
          "internal const val NUGET_RUNTIME_VERSION = \"test\"\n",
      )
      val sourcesRoot: File = File(property("nuget.runtimeSources"))
      check(sourcesRoot.isDirectory) { "runtime sources not found: ${sourcesRoot.absolutePath}" }
      val sources: MutableList<File> = mutableListOf(harness)
      sourcesRoot.walkTopDown().forEach { file ->
        if (file.isFile && file.extension == "kt") sources.add(file)
      }
      check(sources.size > 1) { "no kotlin sources under ${sourcesRoot.absolutePath}" }
      val klib: File = File(dir, "runtime.klib")
      val output: CompileOutput = compileKlib(
        compiler = compiler,
        sources = sources,
        common = emptyList(),
        libraries = listOf(coroutines),
        output = File(dir, "runtime"),
        workDir = dir,
      )
      check(output.exitCode == 0 && klib.isFile) {
        "runtime klib failed to compile\n${output.stdout}\n${output.stderr}"
      }
      return klib
    }

    private fun compileKlib(
      compiler: File,
      sources: List<File>,
      common: List<File>,
      libraries: List<File>,
      output: File,
      workDir: File,
    ): CompileOutput {
      val command: MutableList<String> = mutableListOf(compiler.absolutePath)
      sources.forEach { command.add(it.absolutePath) }
      if (common.isNotEmpty()) {
        command.add("-Xmulti-platform")
        common.forEach { command.add("-Xcommon-sources=${it.absolutePath}") }
      }
      libraries.forEach { library ->
        command.add("-l")
        command.add(library.absolutePath)
      }
      command.add("-p")
      command.add("library")
      command.add("-o")
      command.add(output.absolutePath)
      return run(command, workDir, timeout = 3.minutes)
    }

    private fun run(command: List<String>, workDir: File, timeout: Duration): CompileOutput {
      val builder = ProcessBuilder(command)
      builder.directory(workDir)
      val process: Process = builder.start()
      val stdout: StringBuilder = StringBuilder()
      val stderr: StringBuilder = StringBuilder()
      val outThread = Thread {
        stdout.append(process.inputStream.bufferedReader().readText())
      }
      val errThread = Thread {
        stderr.append(process.errorStream.bufferedReader().readText())
      }
      outThread.start()
      errThread.start()
      val finished: Boolean = process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
      if (!finished) process.destroyForcibly()
      outThread.join()
      errThread.join()
      check(finished) {
        "command timed out: ${command.first()}\n$stdout\n$stderr"
      }
      return CompileOutput(process.exitValue(), stdout.toString(), stderr.toString())
    }
  }
}
