package io.github.xxfast.kotlin.native.nuget

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
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
public abstract class NugetReportDiagnosticsTask : DefaultTask() {
  /**
   * The KSP resources dir(s) `NugetDiagnostics.json` lands in, i.e. the same directory `packNuget`
   * already reads `Interop.cs` from. A missing file is a silent no-op: a project with `bind {}` and
   * no `publish {}` never runs the forward processor.
   */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  public abstract val diagnosticsFiles: ConfigurableFileCollection

  init {
    outputs.upToDateWhen { false }
  }

  @TaskAction
  public fun report() {
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
      // The message body is the exact string ForwardDiagnostic.format() produced: one renderer, no
      // drift between the two sides. ADR-162 (ROADMAP line 58) adds the one thing this side owns,
      // the LEADING `<path>:<line>: ` that `format()` deliberately does not carry (KSP's own Gradle
      // logger already prefixes it on the `e:`/`w:` path, so putting it in `format()` would print
      // it twice there). Gradle's Logger has no source-location overload, so the kotlinc shape in
      // plain text is the linkifiable form available without the incubating Problems API.
      .forEach { entry -> logger.warn(entry.consoleLine()) }
  }

  private companion object {
    const val DIAGNOSTICS_FILE: String = "NugetDiagnostics.json"
  }
}

/** ADR-100: one entry of `NugetDiagnostics.json`, as written by the KSP processor. */
@Serializable
internal data class ForwardDiagnosticEntry(
  val severity: String,
  val kind: String,
  val declaration: String,
  val message: String,
  /**
   * ADR-162: the originating Kotlin source location, additive and optional. Absent for a
   * diagnostic with no single declaration to point at (a scope-level one) and for a
   * `NugetDiagnostics.json` written by a processor from before these fields existed, which is why
   * neither field is required.
   */
  val file: String? = null,
  val line: String? = null,
)

/**
 * ADR-162: the console line, leading with `<path>:<line>: ` when the entry carries a location.
 *
 * The composition lives here rather than in the processor's `ForwardDiagnostic.format()` because
 * the two consumers of that one string need opposite things: KSP's Gradle logger prefixes the
 * location itself, so `format()` staying location-led-free is what keeps the `e: [ksp] ...` line
 * from printing it twice.
 */
internal fun ForwardDiagnosticEntry.consoleLine(): String =
  if (file != null && line != null) "$file:$line: $message" else message


/** ADR-182: the `NugetDiagnostics.json` schema this plugin reads; the processor writes the same. */
internal const val FORWARD_DIAGNOSTICS_SCHEMA_VERSION: Int = 1

@Serializable
private data class ForwardDiagnosticsFile(
  val schemaVersion: Int,
  val diagnostics: List<ForwardDiagnosticEntry>,
)

private val diagnosticsJson: Json = Json { ignoreUnknownKeys = true }

/**
 * ADR-100: reads the processor-written diagnostics file.
 *
 * ADR-182: the root is `{ "schemaVersion": 1, "diagnostics": [ ... ] }` and the reader is
 * kotlinx.serialization (already on the plugin's classpath for `reverse-ir.json`), replacing the
 * hand-rolled token walk that required every value to be a string. Unknown keys are ignored, so an
 * additive field never needs a version bump; the version is checked BEFORE the entries are decoded,
 * so a file from another release fails with what it is rather than with whichever field moved.
 *
 * Fails fast with the offending file named rather than silently reporting nothing, since "no
 * warnings" is indistinguishable from "the file was unreadable" at the console, and silence is the
 * bug ADR-100 exists to fix.
 */
internal fun parseForwardDiagnostics(
  json: String,
  source: String = "NugetDiagnostics.json",
): List<ForwardDiagnosticEntry> {
  if (json.isBlank()) return emptyList()
  val root: JsonElement = try {
    diagnosticsJson.parseToJsonElement(json)
  } catch (e: SerializationException) {
    throw IllegalArgumentException("[nuget] $source is not valid JSON: ${e.message}", e)
  }
  val version: JsonPrimitive? = (root as? JsonObject)?.get("schemaVersion") as? JsonPrimitive
  require(version?.intOrNull == FORWARD_DIAGNOSTICS_SCHEMA_VERSION) {
    val found: String = when {
      root !is JsonObject -> "no schemaVersion (a pre-0.9.0 bare array)"
      version == null -> "no schemaVersion"
      else -> "schemaVersion ${version.content}"
    }
    "[nuget] $source has $found, but this plugin reads schemaVersion " +
        "$FORWARD_DIAGNOSTICS_SCHEMA_VERSION. It is a stale KSP output from a different plugin " +
        "release; rebuild the project (`./gradlew clean packNuget`) to regenerate it."
  }
  return try {
    diagnosticsJson.decodeFromJsonElement(ForwardDiagnosticsFile.serializer(), root).diagnostics
  } catch (e: SerializationException) {
    throw IllegalArgumentException("[nuget] $source has a malformed diagnostic: ${e.message}", e)
  }
}
