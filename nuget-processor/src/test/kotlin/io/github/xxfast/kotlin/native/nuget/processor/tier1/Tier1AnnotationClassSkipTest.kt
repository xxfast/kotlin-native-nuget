package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-064's 2026-09-07 amendment: a public `annotation class` has no root bucket at all in the
 * forward direction (every bucket keys on `CLASS`/`OBJECT`/`ENUM_CLASS`/`INTERFACE`), so it used
 * to pass `isExported`, land in `allDeclarations`, and then be matched by nothing: absent from
 * the generated C# with no diagnostic whatsoever. It now skips with a name.
 *
 * Modelled on [Tier1UnexportedSupertypeSkipTest]'s diagnostic-mode assertions; the `expect`/
 * `actual` cell follows [Tier1ExpectActualDeclarationsTest]'s harness caveat and asserts on
 * `kspWarnings` only, never `compiledClean` (the compile step is a plain single-target compile
 * with no multiplatform wiring, so an expect/actual fixture may legitimately fail it).
 */
class Tier1AnnotationClassSkipTest {

  @Test
  fun `a public annotation class is skipped with SKIPPED_ANNOTATION_CLASS`() {
    val result = Tier1Harness.run(
      """
      package tier1.annotationclass

      annotation class Tagged(val tag: String)
      """.trimIndent(),
      fileName = "Tagged.kt",
    )

    val diagnostics: List<String> = result.kspWarnings
      .filter { it.contains(ForwardDiagnosticKind.SKIPPED_ANNOTATION_CLASS.name) }
    assertEquals(
      1,
      diagnostics.size,
      "expected exactly one SKIPPED_ANNOTATION_CLASS diagnostic; kspWarnings=${result.kspWarnings}",
    )
    assertTrue(
      diagnostics.single().contains("tier1.annotationclass.Tagged"),
      "the diagnostic must name the annotation class's qualified name; " +
          "got: ${diagnostics.single()}",
    )
    assertTrue(
      result.generatedFiles
        .filterKeys { it.endsWith("Interop.cs") }
        .values
        .none { it.contains("Tagged") },
      "an annotation class must have no C# projection; " +
          "generatedFiles=${result.generatedFiles.keys}",
    )
    // The whole module is one annotation class, so the `hasNothingToProcess` early return fires.
    // The diagnostic still has to reach the file `nugetReportDiagnostics` re-emits (ADR-100).
    val diagnosticsFile: String = requireNotNull(
      result.generatedFiles.entries
        .firstOrNull { it.key.endsWith("NugetDiagnostics.json") }
        ?.value,
    ) { "expected NugetDiagnostics.json; generatedFiles=${result.generatedFiles.keys}" }
    assertTrue(
      diagnosticsFile.contains(ForwardDiagnosticKind.SKIPPED_ANNOTATION_CLASS.name),
      "the diagnostic must survive the nothing-to-process early return; got: $diagnosticsFile",
    )
  }

  @Test
  fun `applying the annotation to an exported class costs that class nothing`() {
    val result = Tier1Harness.run(
      sources = mapOf(
        "Tagged.kt" to """
        package tier1.annotationclass.usage

        annotation class Tagged(val tag: String)
        """.trimIndent(),
        "Toy.kt" to """
        package tier1.annotationclass.usage

        @Tagged("plaything")
        class Toy(val name: String)
        """.trimIndent(),
      ),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    assertTrue(
      "export_toy_get_name" in result.generated,
      "the annotated class must keep exporting exactly as before; generated:\n${result.generated}",
    )
    assertFalse(
      "Tagged" in result.generatedCSharp,
      "neither the annotation class nor its usage may reach the C# surface; " +
          "generated C#:\n${result.generatedCSharp}",
    )
    assertEquals(
      1,
      result.kspWarnings.count { it.contains(ForwardDiagnosticKind.SKIPPED_ANNOTATION_CLASS.name) },
      "the declaration is diagnosed, the usage is not; kspWarnings=${result.kspWarnings}",
    )
  }

  @Test
  fun `an internal annotation class is silent`() {
    val result = Tier1Harness.run(
      sources = mapOf(
        "Tagged.kt" to """
        package tier1.annotationclass.internalcase

        internal annotation class Tagged(val tag: String)
        """.trimIndent(),
        "Toy.kt" to """
        package tier1.annotationclass.internalcase

        class Toy(val name: String)
        """.trimIndent(),
      ),
    )

    assertTrue(
      result.kspWarnings.none {
        it.contains(ForwardDiagnosticKind.SKIPPED_ANNOTATION_CLASS.name)
      },
      "a non-public annotation class is out of the export set, like every other bucket's " +
          "visibility gate; kspWarnings=${result.kspWarnings}",
    )
  }

  @Test
  fun `an expect actual annotation class is diagnosed once, at the actual`() {
    val result = Tier1Harness.run(
      sources = mapOf(
        "TaggedActual.kt" to """
        package tier1.annotationclass.expectactual

        actual annotation class Tagged(actual val tag: String)
        """.trimIndent(),
      ),
      commonSources = mapOf(
        "TaggedCommon.kt" to """
        package tier1.annotationclass.expectactual

        expect annotation class Tagged(val tag: String)
        """.trimIndent(),
      ),
    )

    val diagnostics: List<String> = result.kspWarnings
      .filter { it.contains(ForwardDiagnosticKind.SKIPPED_ANNOTATION_CLASS.name) }
    assertEquals(
      1,
      diagnostics.size,
      "the `isExpect` filter drops the expect half, so the pair diagnoses once; " +
          "kspWarnings=${result.kspWarnings}",
    )
    assertTrue(
      diagnostics.single().contains("TaggedActual.kt"),
      "the source location must point at the actual's file; got: ${diagnostics.single()}",
    )
  }
}
