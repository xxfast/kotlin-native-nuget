package io.github.xxfast.kotlin.native.nuget

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.process.ExecOperations
import org.gradle.process.ExecResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.StringReader
import java.util.Properties
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// The `local.properties` key (then Gradle property) naming the `dotnet` executable to use when it
// is not on Gradle's PATH, e.g. an IDE-launched daemon. The CocoaPods plugin's
// `kotlin.apple.cocoapods.bin` precedent.
internal const val DOTNET_KEY: String = "nuget.dotnet"

internal const val RESTORE_ATTEMPTS: Int = 3
internal val RESTORE_BACKOFF: Duration = 2.seconds

internal fun findExecutable(name: String, searchPath: String? = System.getenv("PATH")): String? {
  val paths: String = searchPath ?: return null
  val extensions: List<String> = listOf("", ".exe", ".cmd", ".bat")
  return paths.split(File.pathSeparator)
    .flatMap { dir -> extensions.map { ext -> File(dir, "$name$ext") } }
    .firstOrNull { it.canExecute() }
    ?.absolutePath
}

/**
 * The configured `dotnet` when [configured] is set, else the one on [searchPath]. A configured
 * path that is not an absolute executable file fails, naming where it came from ([source]); it
 * never falls back to PATH.
 */
internal fun resolveDotnet(configured: String?, source: String?, searchPath: String?): String? {
  if (configured == null) return findExecutable("dotnet", searchPath)

  val file = File(configured)
  if (!file.isAbsolute || !file.isFile || !file.canExecute()) {
    throw GradleException(
      "[nuget] $DOTNET_KEY is set to '$configured' in ${source ?: "the build"}, but that is not " +
        "an absolute path to an executable file. Point it at the dotnet executable of a " +
        ".NET SDK 10.0 or later (https://dot.net/download), or remove it to use dotnet from PATH."
    )
  }

  return file.absolutePath
}

internal fun requireDotnet(
  purpose: String,
  searchPath: String? = System.getenv("PATH"),
  configured: String? = null,
  source: String? = null,
): String {
  val dotnet: String? = resolveDotnet(configured, source, searchPath)
  if (dotnet == null) {
    throw GradleException(
      "[nuget] dotnet is required to $purpose but was not found on PATH. " +
        "Install the .NET SDK 10.0 or later from https://dot.net/download, then re-run, " +
        "or set $DOTNET_KEY in local.properties to the dotnet executable's absolute path."
    )
  }

  return dotnet
}

/**
 * Wires [DOTNET_KEY] into a task: the root project's `local.properties` first (machine-specific),
 * then the Gradle property. Both are read through providers, so the configuration cache tracks
 * them. [source] says where the value came from, for the failure message.
 */
internal fun wireDotnet(project: Project, path: Property<String>, source: Property<String>) {
  val file: RegularFile = project.rootProject.layout.projectDirectory.file("local.properties")
  val local: Provider<String> = project.providers.fileContents(file).asText.map { text ->
    val properties = Properties()
    properties.load(StringReader(text))
    properties.getProperty(DOTNET_KEY)
  }

  val gradle: Provider<String> = project.providers.gradleProperty(DOTNET_KEY)
  path.set(local.orElse(gradle))
  source.set(
    local.map { "local.properties (${file.asFile.absolutePath})" }
      .orElse(gradle.map { "the Gradle property" })
  )
}

internal data class ProcessOutcome(val exitCode: Int, val stdout: String, val stderr: String)

/**
 * The NuGet code when [output] shows a feed that could not be reached, else null. Only NU1301
 * ("the listed source is unavailable", https://learn.microsoft.com/nuget/reference/errors-and-warnings/nu1301)
 * qualifies: NuGet reports timeouts, refused connections and HTTP failures from a feed under it.
 * Its documented "local source doesn't exist" form is a configuration error, so it does not.
 */
internal fun transientFeedCode(output: String): String? {
  val transient: Boolean = output.lineSequence()
    .filter { line -> line.contains("NU1301") }
    .any { line -> !line.contains("local source", ignoreCase = true) }

  return if (transient) "NU1301" else null
}

// The code to retry on after a failed [attempt] (1-based), or null to stop.
internal fun restoreRetryCode(
  exitCode: Int,
  output: String,
  attempt: Int,
  attempts: Int = RESTORE_ATTEMPTS,
): String? {
  if (exitCode == 0 || attempt >= attempts) return null
  return transientFeedCode(output)
}

internal fun retryTransientFeedFailures(
  warn: (String) -> Unit,
  sleep: (Duration) -> Unit = { Thread.sleep(it.inWholeMilliseconds) },
  run: () -> ProcessOutcome,
): ProcessOutcome {
  var attempt = 1
  while (true) {
    val outcome: ProcessOutcome = run()
    val code: String = restoreRetryCode(outcome.exitCode, outcome.stdout + outcome.stderr, attempt)
      ?: return outcome

    warn(
      "w: [nuget] dotnet restore attempt $attempt of $RESTORE_ATTEMPTS failed with transient " +
        "feed error $code; retrying in ${RESTORE_BACKOFF.inWholeSeconds}s."
    )
    sleep(RESTORE_BACKOFF)
    attempt++
  }
}

/**
 * Runs [commandLine], capturing both streams. `dotnet restore` reports its NU errors on stdout, so
 * stdout is captured too, and still echoed so a successful restore logs as before.
 */
internal fun ExecOperations.execCapturing(commandLine: List<String>): ProcessOutcome {
  val stdout = ByteArrayOutputStream()
  val stderr = ByteArrayOutputStream()
  val result: ExecResult = exec { spec ->
    spec.commandLine(commandLine)
    spec.standardOutput = Tee(stdout, System.out)
    spec.errorOutput = stderr
    spec.isIgnoreExitValue = true
  }

  return ProcessOutcome(result.exitValue, stdout.toString(), stderr.toString())
}

private class Tee(
  private val first: OutputStream,
  private val second: OutputStream,
) : OutputStream() {
  override fun write(b: Int) {
    first.write(b)
    second.write(b)
  }

  override fun write(b: ByteArray, off: Int, len: Int) {
    first.write(b, off, len)
    second.write(b, off, len)
  }

  override fun flush() {
    first.flush()
    second.flush()
  }
}
