package io.github.xxfast.kotlin.native.nuget.rir

import io.github.xxfast.kotlin.native.nuget.ForwardDiagnosticEntry
import io.github.xxfast.kotlin.native.nuget.NugetGenerateBindingsTask
import io.github.xxfast.kotlin.native.nuget.RealPackageFixture
import io.github.xxfast.kotlin.native.nuget.consoleLine
import io.github.xxfast.kotlin.native.nuget.parseForwardDiagnostics
import io.github.xxfast.kotlin.native.nuget.reverseDiagnosticsJson
import io.github.xxfast.kotlin.native.nuget.unpackMetadataReader
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ADR-182 amendment: every reverse diagnostic kind reaches `NugetDiagnostics.json`, the structured
 * report `nugetGenerateBindings` writes beside its Kotlin output.
 */
class RirDiagnosticCoverageTest {
  /**
   * ADR-070 Decision 5 promises these two, but nothing constructs them: the supertype drop
   * (RirBridging.kt `classImplementsInterface`) and the unbound-base drop are silent today.
   * Tracked on the ROADMAP; empty this set when they are emitted.
   */
  private val notYetEmitted: Set<RirDiagnosticKind> = setOf(
    RirDiagnosticKind.SKIPPED_INTERFACE_SUPERTYPE,
    RirDiagnosticKind.INFO_INHERITED_INTERFACE_MEMBERS_ABSENT,
  )

  /**
   * Kinds a producer constructs that no compilable C# source reaches. Each needs a one-line reason
   * and a non-C# fixture (hand-written IL) before it can leave this set.
   */
  private val unreachableFromCSharp: Set<RirDiagnosticKind> = setOf(
    // The reader only checks a custom MODIFIER (Program.cs GetModifiedType); C# encodes `dynamic`
    // as `object` plus a `[Dynamic]` attribute, so `Accept(dynamic)` drops with no diagnostic.
    RirDiagnosticKind.SKIPPED_DYNAMIC,
  )

  @Test
  fun `every kind round-trips through the report with its code and severity`() {
    val diagnostics: List<Pair<String, RirDiagnostic>> = RirDiagnosticKind.entries.map { kind ->
      "Acme.Api" to RirDiagnostic(kind, "Widget", "Run", "Run()", "why", "Do this.")
    }

    val entries: List<ForwardDiagnosticEntry> =
      parseForwardDiagnostics(reverseDiagnosticsJson(diagnostics))

    assertEquals(RirDiagnosticKind.entries.map { it.name }, entries.map { it.kind })
    entries.forEach { entry ->
      assertEquals(RirDiagnosticKind.valueOf(entry.kind).severity.name, entry.severity)
      assertContains(entry.consoleLine(), "[nuget:${entry.kind}]")
    }
  }

  /**
   * Fails when a kind is added without a shape in `diagnostics/EveryKind.cs` (or `Fatal.cs`) that
   * makes a real producer emit it. Runs the real reader over real compiled assemblies, then the
   * real task action, and reads the file it wrote: a hand-built `RirFile` would prove the writer
   * and nothing about the reader (the ADR-072 trap).
   */
  @Test
  fun `every kind is written to the report from a real assembly`() {
    val dotnet: String = RealPackageFixture.findDotnet() ?: return
    val everyKind: File = RealPackageFixture.compileFixture(
      dotnet, resource("diagnostics/EveryKind.cs"), "EveryKind",
    )
    val fatal: File = RealPackageFixture.compileFixture(
      dotnet, resource("diagnostics/Fatal.cs"), "Fatal",
    )
    val readerDir: File = Files.createTempDirectory("NugetMetadataReader-every-kind").toFile()
    unpackMetadataReader(readerDir, javaClass.classLoader)
    val reverseIr: String = RealPackageFixture.runMetadataReader(
      dotnet, readerDir,
      mapOf(
        "EveryKind" to listOf(everyKind.absolutePath),
        "Fatal" to listOf(fatal.absolutePath),
      ),
    )

    val (report: File, failure: RuntimeException) = generate(reverseIr)

    val entries: List<ForwardDiagnosticEntry> =
      parseForwardDiagnostics(report.readText(), report.path)
    val observed: Set<String> = entries.map { it.kind }.toSet()
    val expected: Set<String> = (RirDiagnosticKind.entries - notYetEmitted - unreachableFromCSharp)
      .map { it.name }
      .toSet()
    assertEquals(expected.sorted(), observed.sorted(), "missing: ${expected - observed}")
    assertTrue(observed.none { kind -> notYetEmitted.any { it.name == kind } })
    assertTrue(observed.none { kind -> unreachableFromCSharp.any { it.name == kind } })

    // Errors land in the file AND fail the build, each rendered once; no warning in the failure.
    val errors: List<String> = entries.filter { it.severity == "ERROR" }.map { it.message }
    assertEquals(errors, failure.message!!.lines())
  }

  private fun generate(reverseIr: String): Pair<File, RuntimeException> {
    val project: Project = ProjectBuilder.builder().build()
    val dir: File = project.layout.buildDirectory.get().asFile
    val irFile = File(dir, "reverse-ir.json")
    irFile.parentFile.mkdirs()
    irFile.writeText(reverseIr)
    val task: NugetGenerateBindingsTask = project.tasks
      .register("generate", NugetGenerateBindingsTask::class.java)
      .get()
    task.reverseIrFile.set(irFile)
    task.packageNameOverrides.set(emptyMap())
    task.namespaceAliases.set(emptyMap())
    task.kotlinOutputDir.set(File(dir, "kotlin"))
    task.boundTypesManifestFile.set(File(dir, "bound-types.json"))
    task.diagnosticsFile.set(File(dir, "NugetDiagnostics.json"))
    val failure: RuntimeException = assertFailsWith { task.generate() }
    return task.diagnosticsFile.get().asFile to failure
  }

  private fun resource(path: String): String =
    requireNotNull(javaClass.classLoader.getResource(path)) { "missing test resource $path" }
      .readText()
}
