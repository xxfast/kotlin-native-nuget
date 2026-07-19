package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-064's newly-named forward skip diagnostics, beyond cells 21/23 (see
 * [Tier1DiagnosticCellsTest]). Each of these constructs is *already* absent from the generated
 * C# API (or, for value-class inherited members, is not yet even skipped at all) — the point of
 * this ADR is a **named, precise kind** rather than the single generic
 * "its {reason} type combination is not supported" string, so a test can assert the exact kind
 * rather than a vague substring (ADR-064 Alternative 2, rejected).
 *
 * Every test here asserts both halves ADR-064 calls "honest skip": the kind fires, and the
 * skipped member is genuinely absent from the generated Kotlin (never a partial / broken
 * emission).
 */
class Tier1NamedSkipDiagnosticsTest {

  /**
   * ROADMAP line 78. `Map`/`Set` (and their mutable variants) as a method *parameter* have no
   * `CreateMap`/`CreateSet` helper, so the planner already skips them
   * (`ForwardPlanSkipReason.COLLECTION`, `droppedFromCSharp = true`) with today's generic
   * message: "its COLLECTION type combination is not supported" (verified through this harness).
   * ADR-064 names this specific case `SKIPPED_UNSUPPORTED_INPUT`.
   */
  @Test
  fun `class method with Map parameter fires SKIPPED_UNSUPPORTED_INPUT and is omitted`() {
    val result = Tier1Harness.run(
      """
      package tier1.skipmapinput

      class Patient(val name: String) {
        fun setTags(tags: Map<String, String>): Int = tags.size
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected no broken source for setTags; got: ${result.compileErrors}",
    )
    assertTrue(
      "export_patient_setTags" !in result.generated,
      "expected setTags to be entirely absent from the generated CNameExports.kt; " +
          "generated=${result.generated}",
    )
    assertTrue(
      result.kspWarnings.any { it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT.name) },
      "expected a SKIPPED_UNSUPPORTED_INPUT diagnostic naming Patient.setTags's Map parameter; " +
          "kspWarnings=${result.kspWarnings}",
    )
  }

  /**
   * ROADMAP line 78. `Set`/`MutableSet` as a method parameter, the sibling shape to the `Map`
   * case above — a distinct `CollectionKind` at the same `inputSkipReason()` site.
   */
  @Test
  fun `class method with Set parameter fires SKIPPED_UNSUPPORTED_INPUT and is omitted`() {
    val result = Tier1Harness.run(
      """
      package tier1.skipsetinput

      class Patient(val name: String) {
        fun setAllergies(allergies: Set<String>): Int = allergies.size
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected no broken source for setAllergies; got: ${result.compileErrors}",
    )
    assertTrue(
      "export_patient_setAllergies" !in result.generated,
      "expected setAllergies to be entirely absent from the generated CNameExports.kt; " +
          "generated=${result.generated}",
    )
    assertTrue(
      result.kspWarnings.any { it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT.name) },
      "expected a SKIPPED_UNSUPPORTED_INPUT diagnostic naming Patient.setAllergies's Set " +
          "parameter; kspWarnings=${result.kspWarnings}",
    )
  }

  /**
   * ROADMAP line 79 (ADR-061 deferred width). A nullable `Boolean` method *return* has no
   * single-call ABI shape (`nullableResultShape` only handles non-boolean primitives), so the
   * planner already skips it (`ForwardPlanSkipReason.NULLABLE`, `droppedFromCSharp = true`) with
   * today's generic message (verified through this harness). ADR-064 names this
   * `SKIPPED_UNSUPPORTED_RETURN`.
   */
  @Test
  fun `class method with nullable Boolean return fires SKIPPED_UNSUPPORTED_RETURN and is omitted`() {
    val result = Tier1Harness.run(
      """
      package tier1.skipnullboolreturn

      class Patient(val name: String) {
        fun isEligible(): Boolean? = null
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected no broken source for isEligible; got: ${result.compileErrors}",
    )
    assertTrue(
      "export_patient_isEligible" !in result.generated,
      "expected isEligible to be entirely absent from the generated CNameExports.kt; " +
          "generated=${result.generated}",
    )
    assertTrue(
      result.kspWarnings.any { it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN.name) },
      "expected a SKIPPED_UNSUPPORTED_RETURN diagnostic naming Patient.isEligible's nullable " +
          "Boolean return; kspWarnings=${result.kspWarnings}",
    )
  }

  /**
   * ROADMAP line 77 (product decision left open after ADR-062 Phase 9). A value class delegating
   * to `CharSequence` inherits members (`length`, `get`, `isEmpty`, ...) it never declares
   * itself. **Verified through this harness, this is not yet even a skip**: `getAllFunctions()` /
   * `getAllProperties()` in `valueClassMethodEntries` / `valueClassPropertyEntries` do not filter
   * by `parentDeclaration`, so today the planner happily plans and emits
   * `export_password_get_length`, `export_password_get` and `export_password_isEmpty` — all
   * three compile clean, with zero KSP diagnostics of any kind. ADR-064's v1 product decision is
   * that these are unsupported and must become a named skip
   * (`SKIPPED_INHERITED_MEMBER`), not silently bridged.
   *
   * `@JvmInline` is added only so the fixture compiles for the JVM inside this harness
   * (ADR-060's stated Tier 1 constraint); it is invisible to `addValueClassExports`, which
   * branches on `Modifier.VALUE` alone.
   */
  @Test
  fun `value class inherited CharSequence members fire SKIPPED_INHERITED_MEMBER and are omitted`() {
    val result = Tier1Harness.run(
      """
      package tier1.skipinherited

      @JvmInline
      value class Password(val value: String) : CharSequence by value
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected no broken source for Password; got: ${result.compileErrors}",
    )
    listOf("export_password_get_length", "export_password_get", "export_password_isEmpty").forEach { export ->
      assertFalse(
        result.generated.contains(export),
        "expected $export (an inherited CharSequence member, not declared by Password itself) " +
            "to be entirely absent from the generated CNameExports.kt; " +
            "generated=${result.generated}",
      )
    }
    assertTrue(
      result.kspWarnings.any { it.contains(ForwardDiagnosticKind.SKIPPED_INHERITED_MEMBER.name) },
      "expected at least one SKIPPED_INHERITED_MEMBER diagnostic naming a Password member " +
          "inherited from the CharSequence delegate; today there is no diagnostic at all " +
          "(kspWarnings=${result.kspWarnings}) because these members are silently bridged, not " +
          "skipped",
    )
  }
}
