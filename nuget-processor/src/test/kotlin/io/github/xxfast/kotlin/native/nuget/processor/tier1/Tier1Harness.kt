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
  fun run(
    kotlinSource: String,
    fileName: String = "Fixture.kt",
    processorOptions: Map<String, String> = emptyMap(),
    libraries: List<File> = emptyList(),
    coroutinesOnCompileClasspath: Boolean = true,
  ): Tier1Result = run(
    mapOf(fileName to kotlinSource),
    processorOptions,
    libraries,
    coroutinesOnCompileClasspath = coroutinesOnCompileClasspath,
  )

  /**
   * Multi-file overload: a Kotlin file may declare only one `package`, so a fixture spanning
   * several packages (e.g. ADR-063's export-scoping cells) needs several source files in one
   * compilation unit. [sources] maps file name to file content.
   *
   * @param libraries ADR-066: extra KSP `libraries` classpath entries beyond `kotlin-stdlib`, e.g.
   *   a [Tier1DependencyLibrary]-compiled jar standing in for a genuinely separate Gradle module.
   * @param commonSources ADR-074: an `expect`/`actual` fixture's `expect` half, compiled as a
   *   separate `commonSourceRoots` root from [sources]' `sourceRoots` — mirroring a real
   *   multiplatform compilation's `nativeMain`/`{target}Main` split (Verified: a JVM KSP2 run so
   *   configured processes both, `EXIT=OK`, `getAllFiles()` listing both). File names must not
   *   collide with [sources]'.
   */
  fun run(
    sources: Map<String, String>,
    processorOptions: Map<String, String> = emptyMap(),
    libraries: List<File> = emptyList(),
    commonSources: Map<String, String> = emptyMap(),
    // `false` drops `kotlinx-coroutines-core` from the compile step's classpath, so a
    // fixture with no suspend/Flow surface can prove the generated file does not merely avoid
    // *mentioning* coroutines, but genuinely compiles without the dependency present, which is
    // exactly what a consumer library that never depends on coroutines does.
    coroutinesOnCompileClasspath: Boolean = true,
  ): Tier1Result {
    val workDir: File = Files.createTempDirectory("nuget-tier1-").toFile()
    try {
      return runIn(
        workDir,
        sources,
        processorOptions,
        libraries,
        commonSources = commonSources,
        coroutinesOnCompileClasspath = coroutinesOnCompileClasspath,
      )
    } finally {
      workDir.deleteRecursively()
    }
  }

  /**
   * Two-pass incremental run: pass 1 processes [initial] fully against a fresh `cachesDir`,
   * pass 2 rewrites [editedFile] to [editedContent] and re-runs KSP2 against the SAME
   * `cachesDir` / `projectBaseDir` / `outputBaseDir`, with `incremental = true` and
   * `modifiedSources` naming only the edited file — the in-process equivalent of Gradle's
   * build-edit-build. KSP2 keys incrementality off `cachesDir` state, not process state
   * (`rebuild = !cachesUpToDateFile.exists()`, written by `updateCachesAndOutputs` at the end
   * of a run; verified by reading KSP 2.3.9 sources), so two [KotlinSymbolProcessing.execute]
   * calls in one JVM against the same dirs reproduce that cache state transition. ADR-060's
   * "KSP2 is re-entrant within one JVM" note only verified the non-incremental path; this is
   * the first Tier 1 use of the incremental engine across two in-process runs.
   *
   * Returns pass 2's [Tier1Result]. Pass 2 re-runs the compile step against pass 2's generated
   * `CNameExports.kt` alone — a partial regeneration may not compile standalone against the
   * full fixture, so callers exercising this path should treat [Tier1Result.compileErrors] as
   * informational and assert on [Tier1Result.generatedFiles] instead.
   */
  fun runIncremental(
    initial: Map<String, String>,
    editedFile: String,
    editedContent: String,
    processorOptions: Map<String, String> = emptyMap(),
    libraries: List<File> = emptyList(),
  ): Tier1Result {
    val workDir: File = Files.createTempDirectory("nuget-tier1-incr-").toFile()
    try {
      // Pass 1 must ALSO run with incremental = true: KSP2's incremental engine keys off
      // `cachesUpToDateFile.exists()` in `cachesDir`, written by `updateCachesAndOutputs` only
      // when a run actually executed the incremental machinery. A pass 1 that runs the plain
      // non-incremental path never writes that marker, so pass 2's `incremental = true` would
      // see no up-to-date cache and fall back to a full rebuild — silently defeating the whole
      // point of this harness by never taking the partial/dirty-files-only branch at all.
      runIn(workDir, initial, processorOptions, libraries, incremental = true)

      val sourceDir: File = workDir.resolve("src")
      val editedFileHandle: File = sourceDir.resolve(editedFile)
      require(editedFileHandle.exists()) {
        "editedFile '$editedFile' was not among the initial sources written to $sourceDir"
      }
      editedFileHandle.writeText(editedContent)

      return runIn(
        workDir,
        initial + (editedFile to editedContent),
        processorOptions,
        libraries,
        incremental = true,
        modifiedSources = listOf(editedFileHandle),
      )
    } finally {
      workDir.deleteRecursively()
    }
  }

  private fun runIn(
    workDir: File,
    sources: Map<String, String>,
    processorOptions: Map<String, String> = emptyMap(),
    libraries: List<File> = emptyList(),
    incremental: Boolean = false,
    modifiedSources: List<File> = emptyList(),
    removedSources: List<File> = emptyList(),
    // ADR-074: an `expect`/`actual` fixture's `expect` half. Written under its own root and
    // wired through `KSPConfig.Builder.commonSourceRoots` rather than mixed into [sourceDir],
    // mirroring the real `nativeMain` (common) vs `{target}Main` (platform) split KSP2 sees for
    // an actual native compilation (ADR-074 spike finding 1).
    commonSources: Map<String, String> = emptyMap(),
    coroutinesOnCompileClasspath: Boolean = true,
  ): Tier1Result {
    val sourceDir: File = workDir.resolve("src").apply { mkdirs() }
    val fixtureFiles: List<File> = sources.map { (fileName, kotlinSource) ->
      sourceDir.resolve(fileName).apply { writeText(kotlinSource) }
    }

    val commonSourceDir: File = workDir.resolve("common-src").apply { mkdirs() }
    val commonFixtureFiles: List<File> = commonSources.map { (fileName, kotlinSource) ->
      commonSourceDir.resolve(fileName).apply { writeText(kotlinSource) }
    }

    val kotlinOutputDir: File = workDir.resolve("ksp-out").apply { mkdirs() }
    val classOutputDir: File = workDir.resolve("ksp-class-out").apply { mkdirs() }
    val resourceOutputDir: File = workDir.resolve("ksp-res-out").apply { mkdirs() }
    val javaOutputDir: File = workDir.resolve("ksp-java-out").apply { mkdirs() }
    val cachesDir: File = workDir.resolve("ksp-caches").apply { mkdirs() }

    val logger = RecordingKSPLogger()

    val config: KSPJvmConfig = KSPJvmConfig.Builder().apply {
      moduleName = "tier1-fixture"
      sourceRoots = listOf(sourceDir)
      if (commonFixtureFiles.isNotEmpty()) commonSourceRoots = listOf(commonSourceDir)
      this.libraries = listOf(Tier1Classpath.kotlinStdlib) + libraries
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
      this.processorOptions = processorOptions
      this.incremental = incremental
      this.modifiedSources = modifiedSources
      this.removedSources = removedSources
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
    // ADR-074: best-effort only when `commonSources` is non-empty. `K2JVMCompiler.exec()` here
    // is a plain single-target compile with no `-Xcommon-sources`/multiplatform wiring, unlike
    // KSP2's own Analysis API session above, so an expect/actual fixture may legitimately fail
    // this step even though generation itself succeeded; callers exercising such a fixture must
    // treat `compileErrors` as informational (mirrors `runIncremental`'s existing precedent).
    compileGenerated(
      workDir, fixtureFiles + commonFixtureFiles, generated, compileMessages, libraries,
      coroutinesOnCompileClasspath,
    )

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
    fixtureFiles: List<File>,
    generatedCNameExports: String,
    collector: RecordingMessageCollector,
    libraries: List<File> = emptyList(),
    coroutinesOnCompileClasspath: Boolean = true,
  ) {
    val compileSourceDir: File = workDir.resolve("compile-src").apply { mkdirs() }
    val compileOutDir: File = workDir.resolve("compile-out").apply { mkdirs() }

    // `CNameExports.kt` calls straight into the fixture's own declarations (e.g. `ChartId(...)`),
    // so it must be compiled in the same pass as the original fixture source, exactly as
    // `:test-library:compileKotlinMingwX64` compiles the real generated file alongside the rest
    // of the library rather than in isolation.
    val sourceFiles: List<File> = buildList {
      addAll(fixtureFiles)
      add(compileSourceDir.resolve("CNameExports.kt").apply { writeText(generatedCNameExports) })
      Tier1CinteropStub.files.forEach { (name, content) ->
        add(compileSourceDir.resolve(name).apply { writeText(content) })
      }
    }

    val arguments = K2JVMCompilerArguments().apply {
      freeArgs = sourceFiles.map { it.absolutePath }
      destination = compileOutDir.absolutePath
      val coroutines: List<File> =
        if (coroutinesOnCompileClasspath) listOf(Tier1Classpath.kotlinxCoroutinesCore)
        else emptyList()
      classpath = (listOf(Tier1Classpath.kotlinStdlib) + coroutines + libraries)
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
