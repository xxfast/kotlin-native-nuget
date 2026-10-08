import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

plugins {
  alias(libs.plugins.kotlinMultiplatform) apply false
  alias(libs.plugins.kotlinJvm) apply false
  alias(libs.plugins.mavenPublish) apply false
  id("io.github.xxfast.kotlin.native.nuget") apply false
  id("io.github.xxfast.kotlin.native.nuget.annotations") apply false
}

// The verification scripts need only bash, Gradle and dotnet, so the checks that parse files live
// here as tasks. Neither task depends on anything: each only reads files a script's own build already
// produced, so running it cannot re-run KSP or change the build the script just proved.

/**
 * ADR-100 / ADR-109: reads the fixture publishers' real generated KSP outputs (run by
 * `scripts/verify-forward-diagnostics.sh` after its two `packNuget` builds).
 */
@DisableCachingByDefault(because = "only asserts on generated files that a prior build wrote")
abstract class VerifyForwardDiagnostics : DefaultTask() {
  /** Module directory -> the sibling publisher its duplicate-type warning must name. */
  @get:Input
  abstract val siblings: MapProperty<String, String>

  @get:Input
  abstract val sharedType: Property<String>

  /**
   * Module directory -> declarations no manifest entry may name. ADR-154 amendment (2026-10-08):
   * ktor's `Url` has only an `internal` primary constructor, which must be filtered before planning
   * rather than planned and then skipped; this pins it on a real published klib.
   */
  @get:Input
  abstract val unplanned: MapProperty<String, List<String>>

  // Internal, not an input directory: a missing directory must reach the check below with its own
  // message, and no producer may be inferred from it.
  @get:Internal
  abstract val root: DirectoryProperty

  @TaskAction
  fun verify() {
    for ((module, sibling) in siblings.get()) {
      val generated = root.get().asFile.resolve("$module/build/generated/ksp")
      val files = generated.walkTopDown().filter { it.isFile }.toList()
      val manifests = files.filter { it.name == "NugetDiagnostics.json" }
      val interops = files.filter { it.name == "Interop.cs" }
      check(manifests.isNotEmpty() && interops.isNotEmpty()) { "No real generated outputs for $module" }

      for (manifest in manifests) {
        val data = JsonSlurper().parseText(manifest.readText())
        // ADR-182: a versioned object root, not the pre-0.9.0 bare array.
        check(data is Map<*, *> && data["schemaVersion"] == 1) {
          "$manifest has no schemaVersion 1 object root"
        }
        val entries = (data as Map<*, *>)["diagnostics"] as List<*>
        val warned = entries.any { entry ->
          entry as Map<*, *>
          entry["kind"] == "WARNING_DUPLICATED_DEPENDENCY_TYPE" &&
            entry["declaration"] == sharedType.get() &&
            "$sibling NuGet package" in entry["message"].toString()
        }
        check(warned) { "Missing shared TopStory warning in $manifest" }
        for (declaration in unplanned.get()[module].orEmpty()) {
          val named = entries.filter { entry -> (entry as Map<*, *>)["declaration"] == declaration }
          check(named.isEmpty()) { "$manifest names $declaration, which is never planned: $named" }
        }
      }

      for (interop in interops) {
        check("class TopStory" in interop.readText()) { "Missing publisher's TopStory copy: $interop" }
      }
    }
    println("==> confirmed: both manifests warn and both publishers retain generated TopStory copies")
  }
}

/**
 * Reads the Writerside builder's `report.json` (run by `scripts/verify-docs.sh`) and fails on any
 * error, which is what writerside-checker-action does in the Docs CI.
 */
@DisableCachingByDefault(because = "only asserts on a report that the Writerside builder wrote")
abstract class CheckWritersideReport : DefaultTask() {
  @get:Internal
  abstract val report: RegularFileProperty

  @TaskAction
  fun verify() {
    check(report.isPresent) { "Pass the builder's report with -PwritersideReport=<path to report.json>" }
    val file = report.get().asFile
    check(file.isFile) { "No Writerside report at $file" }
    val data = JsonSlurper().parse(file) as Map<*, *>

    // report.json shape: testsErrors / testsWarnings map a problem id (e.g. MRK003) to a list of
    // {problemId, name, description}; counts live in testsErrorsCount, testsWarningsCount, testsTotal.
    fun emit(bucket: String, label: String): Int {
      val problems = data[bucket] as Map<*, *>? ?: emptyMap<Any, Any>()
      var count = 0
      for ((id, list) in problems.entries.sortedBy { it.key.toString() }) {
        for (problem in list as List<*>) {
          problem as Map<*, *>
          println("$label: $id: ${problem["name"] ?: ""}: ${problem["description"] ?: ""}")
          count++
        }
      }
      return count
    }

    val warnings = emit("testsWarnings", "WARNING")
    val errors = emit("testsErrors", "ERROR")
    if (errors > 0) {
      println()
      println("docs check FAILED: $errors error(s)")
      throw GradleException("docs check FAILED: $errors error(s)")
    }
    println("docs check passed: ${data["testsTotal"]} checks, $warnings warning(s)")
  }
}

/**
 * ADR-182 amendment: reads the reverse report `nugetGenerateBindings` wrote for `test-library`'s
 * `bind {}` dependency during the pack `scripts/verify.sh` just ran.
 */
@DisableCachingByDefault(because = "only asserts on a generated file that a prior build wrote")
abstract class VerifyReverseDiagnostics : DefaultTask() {
  // Internal, not an input file: a missing report must reach the check below with its own message.
  @get:Internal
  abstract val report: RegularFileProperty

  @TaskAction
  fun verify() {
    val file = report.get().asFile
    check(file.isFile) { "No reverse diagnostics report at $file" }
    val data = JsonSlurper().parseText(file.readText())
    check(data is Map<*, *> && data["schemaVersion"] == 1) {
      "$file has no schemaVersion 1 object root"
    }
    val entries: List<*> = data["diagnostics"] as List<*>
    check(entries.isNotEmpty()) { "$file is empty; the bound TestDependency has known skips" }
    entries.forEach { item ->
      val entry = item as Map<*, *>
      val kind = entry["kind"]
      check(entry["severity"] in setOf("WARNING", "INFO") && entry["packageId"] != null) {
        "$file holds a malformed or fatal entry: $entry"
      }
      check((entry["message"] as String).startsWith("[nuget:$kind] ")) {
        "$file entry does not carry its code in the console shape: $entry"
      }
    }
    println("reverse diagnostics report: ${entries.size} entries in $file")
  }
}

tasks.register<VerifyForwardDiagnostics>("verifyForwardDiagnostics") {
  root.set(layout.projectDirectory)
  siblings.set(mapOf("test-library" to "TestCompanion", "test-companion" to "TestLibrary"))
  unplanned.set(mapOf("test-library" to listOf("io.ktor.http.Url.<init>")))
  sharedType.set(
    providers.gradleProperty("sharedType")
      .orElse("io.github.xxfast.kotlin.native.nuget.test.models.TopStory"),
  )
}

tasks.register<CheckWritersideReport>("checkWritersideReport") {
  val projectDirectory = layout.projectDirectory
  report.set(providers.gradleProperty("writersideReport").map { projectDirectory.file(it) })
}

tasks.register<VerifyReverseDiagnostics>("verifyReverseDiagnostics") {
  report.set(layout.projectDirectory.file("test-library/build/nuget-interop/NugetDiagnostics.json"))
}
