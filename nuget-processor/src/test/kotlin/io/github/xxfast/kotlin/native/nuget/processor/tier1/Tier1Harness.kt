package io.github.xxfast.kotlin.native.nuget.processor.tier1

import com.google.devtools.ksp.impl.KotlinSymbolProcessing
import com.google.devtools.ksp.processing.KSPJvmConfig
import io.github.xxfast.kotlin.native.nuget.processor.NugetProcessorProvider
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import java.io.File
import java.nio.file.Files

/**
 * ADR-060 Tier 1 harness: Kotlin source in, real [NugetProcessorProvider] processor run through
 * KSP2's own programmatic entry point, generated `CNameExports.kt` **compiled** for the JVM —
 * see the ADR's "Tier 1 compiles; it does not substring-match". This is the seam every K-cell
 * (4, 5, 6, 10, 15, 16, 17, 18, 20, 23) and the structural cell 14 assert through.
 *
 * No kotlin-compile-testing / kctfork: `KotlinSymbolProcessing` and `KSPJvmConfig.Builder` ship
 * in KSP's own `symbol-processing-aa-embeddable` / `symbol-processing-common-deps`, already
 * `testImplementation` dependencies of this module, and the compile step is the real, in-process
 * `K2JVMCompiler` from `kotlin-compiler-embeddable`.
 */
internal object Tier1Harness {

  /**
   * Runs [kotlinSource] through the real processor, then — if it produced a `CNameExports.kt` —
   * compiles that file for the JVM alongside [Tier1CinteropStub]'s stand-ins.
   *
   * KSP2's standalone Analysis API session leaves non-daemon threads running after
   * `execute()` returns (verified, ADR-060 Consequences). That is cosmetic inside a Gradle test
   * worker — Gradle reclaims the worker process itself once the task finishes rather than
   * waiting on natural JVM exit (verified: a Tier 1 run here returns control to `gradlew`
   * normally) — and KSP2 is re-entrant within one JVM (ADR-060 Verification), so repeated calls
   * to [run] within the same test task share one warm JVM instead of each paying the ~5-6s
   * cold-start cost (verified: a second call in the same worker dropped from ~6s to ~0.6s).
   */
  fun run(kotlinSource: String, fileName: String = "Fixture.kt"): Tier1Result {
    val workDir: File = Files.createTempDirectory("nuget-tier1-").toFile()
    try {
      return runIn(workDir, kotlinSource, fileName)
    } finally {
      workDir.deleteRecursively()
    }
  }

  private fun runIn(workDir: File, kotlinSource: String, fileName: String): Tier1Result {
    val sourceDir: File = workDir.resolve("src").apply { mkdirs() }
    val fixtureFile: File = sourceDir.resolve(fileName).apply { writeText(kotlinSource) }

    val kotlinOutputDir: File = workDir.resolve("ksp-out").apply { mkdirs() }
    val classOutputDir: File = workDir.resolve("ksp-class-out").apply { mkdirs() }
    val resourceOutputDir: File = workDir.resolve("ksp-res-out").apply { mkdirs() }
    val javaOutputDir: File = workDir.resolve("ksp-java-out").apply { mkdirs() }
    val cachesDir: File = workDir.resolve("ksp-caches").apply { mkdirs() }

    val logger = RecordingKSPLogger()

    val config: KSPJvmConfig = KSPJvmConfig.Builder().apply {
      moduleName = "tier1-fixture"
      sourceRoots = listOf(sourceDir)
      libraries = listOf(Tier1Classpath.kotlinStdlib)
      projectBaseDir = workDir
      outputBaseDir = workDir
      this.cachesDir = cachesDir
      this.classOutputDir = classOutputDir
      this.kotlinOutputDir = kotlinOutputDir
      this.resourceOutputDir = resourceOutputDir
      this.javaOutputDir = javaOutputDir
      jvmTarget = "17"
      jdkHome = File(System.getProperty("java.home"))
      // A stable, unexotic language/API version: every K-cell fixture is deliberately plain
      // Kotlin (no bleeding-edge language features), so this need not track the repo's pinned
      // Kotlin 2.4.0 compiler version exactly.
      languageVersion = "2.0"
      apiVersion = "2.0"
    }.build()

    val kspExitCode = KotlinSymbolProcessing(
      config,
      listOf(NugetProcessorProvider()),
      logger,
    ).execute()

    // `CNameExports.kt` lands under `kotlinOutputDir` (extension "kt"), but `Interop.cs` does
    // not — KSP2's `CodeGeneratorImpl.extensionToDirectory` only special-cases "class"/"java"/
    // "kt"; every other extension, including "cs", falls through to `resourceOutputDir`
    // (verified by reading `CodeGeneratorImpl.kt`). Both are merged here so the **structural**
    // assertion mode (cells 1, 8, 9, 12, 14) can read the generated C# the same way the
    // **compile** mode reads the generated Kotlin.
    val generatedFiles: Map<String, String> =
      (kotlinOutputDir.walkTopDown() + resourceOutputDir.walkTopDown())
        .filter { it.isFile }
        .associate { file ->
          val root = if (file.startsWith(kotlinOutputDir)) kotlinOutputDir else resourceOutputDir
          file.relativeTo(root).path.replace('\\', '/') to file.readText()
        }

    val generated: String? = generatedFiles.entries
      .firstOrNull { it.key.endsWith("CNameExports.kt") }
      ?.value

    if (generated == null) {
      return Tier1Result(
        kspExitCode = kspExitCode.name,
        kspErrors = logger.errors,
        kspWarnings = logger.warnings,
        generatedFiles = generatedFiles,
        compileErrors = emptyList(),
        compileWarnings = emptyList(),
      )
    }

    val compileMessages = RecordingMessageCollector()
    compileGenerated(workDir, fixtureFile, generated, compileMessages)

    return Tier1Result(
      kspExitCode = kspExitCode.name,
      kspErrors = logger.errors,
      kspWarnings = logger.warnings,
      generatedFiles = generatedFiles,
      compileErrors = compileMessages.errors,
      compileWarnings = compileMessages.warnings,
    )
  }

  private fun compileGenerated(
    workDir: File,
    fixtureFile: File,
    generatedCNameExports: String,
    collector: RecordingMessageCollector,
  ) {
    val compileSourceDir: File = workDir.resolve("compile-src").apply { mkdirs() }
    val compileOutDir: File = workDir.resolve("compile-out").apply { mkdirs() }

    // `CNameExports.kt` calls straight into the fixture's own declarations (e.g. `ChartId(...)`),
    // so it must be compiled in the same pass as the original fixture source, exactly as
    // `:test-library:compileKotlinMingwX64` compiles the real generated file alongside the rest
    // of the library rather than in isolation.
    val sourceFiles: List<File> = buildList {
      add(fixtureFile)
      add(compileSourceDir.resolve("CNameExports.kt").apply { writeText(generatedCNameExports) })
      Tier1CinteropStub.files.forEach { (name, content) ->
        add(compileSourceDir.resolve(name).apply { writeText(content) })
      }
    }

    val arguments = K2JVMCompilerArguments().apply {
      freeArgs = sourceFiles.map { it.absolutePath }
      destination = compileOutDir.absolutePath
      classpath = listOf(Tier1Classpath.kotlinStdlib, Tier1Classpath.kotlinxCoroutinesCore)
        .joinToString(File.pathSeparator) { it.absolutePath }
      noStdlib = true
      noReflect = true
      jvmTarget = "17"
      // The stub file (Tier1CinteropStub) declares into `kotlin.native` / `kotlin.experimental`,
      // matching Kotlin/Native's real package layout for the API `CNameExports.kt` imports —
      // verified: without this flag kotlinc refuses with "only the Kotlin standard library is
      // allowed to use the 'kotlin' package".
      allowKotlinPackage = true
    }

    K2JVMCompiler().exec(collector, Services.EMPTY, arguments)
  }
}
