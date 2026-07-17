package io.github.xxfast.kotlin.native.nuget.processor.tier1

import java.io.File

/**
 * ADR-060 Tier 1: resolves the jars the harness needs (kotlin-stdlib, for both the KSP2
 * analysis session and the compile step; kotlinx-coroutines-core-jvm, for the compile step's
 * real `ExperimentalCoroutinesApi` marker) from the *running* test JVM's own classpath, rather
 * than a hardcoded Gradle-cache path. Both are already `testImplementation` dependencies of
 * this module (see `nuget-processor/build.gradle.kts`), so Gradle's test worker always has
 * them on `java.class.path` — verified directly (a throwaway probe test showed the worker
 * lists every jar individually, not a single wrapped manifest jar).
 */
internal object Tier1Classpath {

  private val classpathJars: List<File> by lazy {
    System.getProperty("java.class.path")
      .split(File.pathSeparator)
      .map(::File)
      .filter { it.isFile && it.name.endsWith(".jar") }
  }

  private fun jar(namePrefix: String): File =
    classpathJars.firstOrNull {
      it.name.startsWith(namePrefix) && "-sources" !in it.name && "-javadoc" !in it.name
    } ?: error(
      "Tier 1 (ADR-060) could not find a '$namePrefix*.jar' on the running test JVM's " +
          "classpath (java.class.path had ${classpathJars.size} jars). This means a " +
          "testImplementation dependency was removed from nuget-processor/build.gradle.kts, " +
          "or `java.class.path` stopped listing individual jars for the Gradle test worker."
    )

  /** Needed both as the KSP2 analysis session's `libraries` and on the JVM compile classpath. */
  val kotlinStdlib: File by lazy { jar("kotlin-stdlib-") }

  /**
   * Real, JVM-usable `kotlinx-coroutines-core` (unlike `kotlinx.cinterop`, coroutines-core is
   * genuinely multiplatform and ships a working JVM artifact), so every generated file's
   * unconditional `@OptIn(..., ExperimentalCoroutinesApi::class)` resolves against the real
   * annotation instead of a hand-written stand-in.
   */
  val kotlinxCoroutinesCore: File by lazy { jar("kotlinx-coroutines-core-jvm-") }
}
