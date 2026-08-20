package io.github.xxfast.kotlin.native.nuget

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File

/**
 * ADR-100: re-emits the forward direction's ADR-064 diagnostics through Gradle's own `Task.logger`,
 * the same call the reverse direction makes (`NugetGenerateBindingsTask`'s
 * `diagnosticWarnings(rir).forEach { logger.warn(it) }`), so both directions land at Gradle's WARN
 * level with the same `[nuget:...]` prefix.
 *
 * Two defects make this necessary, both measured: the processor's own `KSPLogger` output never
 * reaches the console (KSP runs it on a Worker API thread whose stdout Gradle drops, even KSP's own
 * startup line is absent at `--info`), and a normal `packNuget` does not run the KSP task at all
 * (`FROM-CACHE`, then `UP-TO-DATE`). The second is why the input is a *declared KSP output file*
 * rather than anything computed during the KSP task action: the file is restored on a cache hit and
 * present on an up-to-date run, so this task can speak on every build.
 *
 * Never up-to-date on purpose: reporting that only happens when something else changed is exactly
 * the failure mode being fixed here.
 */
@DisableCachingByDefault(
  because = "Reporting only; must speak on every build, including cached ones",
)
abstract class NugetReportDiagnosticsTask : DefaultTask() {
  /**
   * The KSP resources dir(s) `NugetDiagnostics.json` lands in, i.e. the same directory `packNuget`
   * already reads `Interop.cs` from. A missing file is a silent no-op: a project with `bind {}` and
   * no `publish {}` never runs the forward processor.
   */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val diagnosticsFiles: ConfigurableFileCollection

  init {
    outputs.upToDateWhen { false }
  }

  @TaskAction
  fun report() {
    val files: List<File> = diagnosticsFiles.files
      .flatMap { file ->
        when {
          file.isDirectory -> file.listFiles()?.filter { it.name == DIAGNOSTICS_FILE }.orEmpty()
          file.name == DIAGNOSTICS_FILE -> listOf(file)
          else -> emptyList()
        }
      }

    files
      .flatMap { file -> parseForwardDiagnostics(file.readText(), file.path) }
      // The message is the exact string ForwardDiagnostic.format() produced, source location
      // included. Printed verbatim: one renderer, no drift between the two sides.
      .forEach { entry -> logger.warn(entry.message) }
  }

  private companion object {
    const val DIAGNOSTICS_FILE: String = "NugetDiagnostics.json"
  }
}

/** ADR-100: one entry of `NugetDiagnostics.json`, as written by the KSP processor. */
internal data class ForwardDiagnosticEntry(
  val severity: String,
  val kind: String,
  val declaration: String,
  val message: String,
)

/**
 * ADR-100: reads the processor-written diagnostics file.
 *
 * Hand-rolled, following the `bound-types.json` precedent, but not with that file's `\{([^{}]*)}`
 * regex: a rendered message legitimately contains braces (`add include("...") to nuget { publish
 * { } }`) and newlines, so object boundaries have to be found with strings skipped properly. Every
 * value this writer emits is a JSON string, so within an object the quoted tokens alternate
 * key, value.
 *
 * Fails fast with the offending file named rather than silently reporting nothing, since "no
 * warnings" is indistinguishable from "the file was unreadable" at the console, and silence is the
 * bug this ADR exists to fix.
 */
internal fun parseForwardDiagnostics(
  json: String,
  source: String = "NugetDiagnostics.json",
): List<ForwardDiagnosticEntry> {
  if (json.isBlank()) return emptyList()
  require(json.trimStart().startsWith("[")) {
    "[nuget] $source is not a JSON array: ${json.take(80)}"
  }

  val entries: MutableList<ForwardDiagnosticEntry> = mutableListOf()
  var fields: MutableMap<String, String> = mutableMapOf()
  val tokens: MutableList<String> = mutableListOf()
  var index = 0

  while (index < json.length) {
    when (json[index]) {
      '{' -> {
        tokens.clear()
        fields = mutableMapOf()
        index++
      }

      '}' -> {
        tokens.chunked(2).forEach { pair -> if (pair.size == 2) fields[pair[0]] = pair[1] }
        entries += ForwardDiagnosticEntry(
          severity = fields.field("severity", source),
          kind = fields.field("kind", source),
          declaration = fields.field("declaration", source),
          message = fields.field("message", source),
        )
        tokens.clear()
        index++
      }

      '"' -> {
        val (value: String, next: Int) = json.readJsonString(index, source)
        tokens += value
        index = next
      }

      else -> index++
    }
  }
  return entries
}

private fun Map<String, String>.field(name: String, source: String): String =
  requireNotNull(this[name]) { "[nuget] $source has an entry with no `$name` field: $this" }

/**
 * Reads the JSON string starting at [start] (a `"`), returning its value and the index after it.
 */
private fun String.readJsonString(start: Int, source: String): Pair<String, Int> {
  val text = StringBuilder()
  var index: Int = start + 1
  while (index < length) {
    when (val c: Char = this[index]) {
      '"' -> return text.toString() to index + 1
      '\\' -> {
        index++
        require(index < length) { "[nuget] $source ends inside a string escape" }
        when (val escape: Char = this[index]) {
          'n' -> text.append('\n')
          'r' -> text.append('\r')
          't' -> text.append('\t')
          'u' -> {
            require(index + 4 < length) { "[nuget] $source ends inside a unicode escape" }
            text.append(substring(index + 1, index + 5).toInt(16).toChar())
            index += 4
          }

          else -> text.append(escape)
        }
        index++
      }

      else -> {
        text.append(c)
        index++
      }
    }
  }
  error("[nuget] $source has an unterminated string starting at offset $start")
}
