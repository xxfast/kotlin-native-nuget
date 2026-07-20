package io.github.xxfast.kotlin.native.nuget.processor.tier1

import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import java.io.File
import java.nio.file.Files

/**
 * ADR-066: compiles a small Kotlin source file into a standalone `.jar`, so a Tier 1 fixture can
 * put it on the KSP `libraries` classpath as a genuine *separate compilation unit* — the same
 * shape a Gradle-module dependency is to the real processor, just JVM instead of a Kotlin/Native
 * klib. A JVM-classfile-sourced declaration reproduces the two cross-module signals this feature
 * depends on that a same-round, multi-package fixture cannot: `Origin.KOTLIN_LIB` (every member,
 * not just the type itself) and `containingFile == null`.
 *
 * **Deliberately does not reproduce** the `Modifier.VALUE`/`Modifier.INLINE` divergence — that is
 * a Kotlin/Native klib-backend-specific quirk (verified against a real klib, ADR-066), not a JVM
 * one, so a JVM-compiled `@JvmInline value class` here still reports `Modifier.VALUE`. That half
 * of the amendment is guarded at the `scripts/verify.sh` integration level instead (the
 * `:test-models`/`StoryCode` fixture), exactly as the ADR calls for.
 */
internal object Tier1DependencyLibrary {
  fun compile(source: String, fileName: String = "Dependency.kt"): File {
    val workDir: File = Files.createTempDirectory("nuget-tier1-dep-").toFile()
    val sourceDir: File = workDir.resolve("src").apply { mkdirs() }
    val sourceFile: File = sourceDir.resolve(fileName).apply { writeText(source) }
    val jarFile: File = workDir.resolve("dependency.jar")

    val collector = RecordingMessageCollector()
    val arguments = K2JVMCompilerArguments().apply {
      freeArgs = listOf(sourceFile.absolutePath)
      destination = jarFile.absolutePath
      classpath = Tier1Classpath.kotlinStdlib.absolutePath
      noStdlib = true
      noReflect = true
      jvmTarget = "17"
    }
    K2JVMCompiler().exec(collector, Services.EMPTY, arguments)
    check(jarFile.exists()) {
      "Tier 1 dependency library failed to compile $fileName: ${collector.errors}"
    }
    return jarFile
  }
}
