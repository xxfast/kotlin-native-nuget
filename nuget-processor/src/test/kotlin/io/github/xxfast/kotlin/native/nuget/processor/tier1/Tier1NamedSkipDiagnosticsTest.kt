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
   * ADR-073 closed the general `Map<String, String>` case (`CreateMap`/`nuget_map_put` now
   * exist), but the write side can only box a strict subset of component types
   * (`isWrappableComponent()`): the six `nuget_wrap_*` primitives plus an object handle. An enum
   * *value* (`Mood`) is outside that subset -- no `nuget_wrap_enum` exists -- so it must still
   * fire `SKIPPED_UNSUPPORTED_INPUT`, exactly as `Tier1NamedSkipDiagnosticsTest` verified for the
   * pre-ADR-073 general case.
   */
  @Test
  fun `class method with Map enum-value parameter fires SKIPPED_UNSUPPORTED_INPUT and is omitted`() {
    val result = Tier1Harness.run(
      """
      package tier1.skipmapinput

      enum class Mood { CALM, ANXIOUS }

      class Patient(val name: String) {
        fun setMoods(moods: Map<String, Mood>): Int = moods.size
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected no broken source for setMoods; got: ${result.compileErrors}",
    )
    assertTrue(
      "export_patient_setMoods" !in result.generated,
      "expected setMoods to be entirely absent from the generated CNameExports.kt; " +
          "generated=${result.generated}",
    )
    assertTrue(
      result.kspWarnings.any { it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT.name) },
      "expected a SKIPPED_UNSUPPORTED_INPUT diagnostic naming Patient.setMoods's Map parameter; " +
          "kspWarnings=${result.kspWarnings}",
    )
  }

  /**
   * ADR-073's sibling case for `Set`: a `Char` element has no `nuget_wrap_char`, so it stays
   * outside `isWrappableComponent()` even though `Set<String>` itself now binds.
   */
  @Test
  fun `class method with Set Char-element parameter fires SKIPPED_UNSUPPORTED_INPUT and is omitted`() {
    val result = Tier1Harness.run(
      """
      package tier1.skipsetinput

      class Patient(val name: String) {
        fun setInitials(initials: Set<Char>): Int = initials.size
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected no broken source for setInitials; got: ${result.compileErrors}",
    )
    assertTrue(
      "export_patient_setInitials" !in result.generated,
      "expected setInitials to be entirely absent from the generated CNameExports.kt; " +
          "generated=${result.generated}",
    )
    assertTrue(
      result.kspWarnings.any { it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT.name) },
      "expected a SKIPPED_UNSUPPORTED_INPUT diagnostic naming Patient.setInitials's Set " +
          "parameter; kspWarnings=${result.kspWarnings}",
    )
  }

  /**
   * ADR-073: a nullable map *value* (`Map<String, Int?>`) is outside `isWrappableComponent()` --
   * `nuget_map_put`'s `value` parameter is a non-nullable `COpaquePointer`, so `null` cannot cross
   * -- and must still fire `SKIPPED_UNSUPPORTED_INPUT` rather than silently crash or bind wrong.
   */
  @Test
  fun `class method with Map nullable-value parameter fires SKIPPED_UNSUPPORTED_INPUT and is omitted`() {
    val result = Tier1Harness.run(
      """
      package tier1.skipmapnullableinput

      class Patient(val name: String) {
        fun setOptionalScores(scores: Map<String, Int?>): Int = scores.size
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected no broken source for setOptionalScores; got: ${result.compileErrors}",
    )
    assertTrue(
      "export_patient_setOptionalScores" !in result.generated,
      "expected setOptionalScores to be entirely absent from the generated CNameExports.kt; " +
          "generated=${result.generated}",
    )
    assertTrue(
      result.kspWarnings.any { it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT.name) },
      "expected a SKIPPED_UNSUPPORTED_INPUT diagnostic naming Patient.setOptionalScores's Map " +
          "parameter; kspWarnings=${result.kspWarnings}",
    )
  }

  /**
   * ADR-073: a nested collection element (`Set<List<String>>`) is outside `isWrappableComponent()`
   * -- there is no way to box a `List<String>` through the same-shaped `Wrap<T>` reflective
   * `_handle` fallback -- and must still fire `SKIPPED_UNSUPPORTED_INPUT`.
   */
  @Test
  fun `class method with Set nested-collection-element parameter fires SKIPPED_UNSUPPORTED_INPUT and is omitted`() {
    val result = Tier1Harness.run(
      """
      package tier1.skipsetnestedinput

      class Patient(val name: String) {
        fun setTagGroups(groups: Set<List<String>>): Int = groups.size
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected no broken source for setTagGroups; got: ${result.compileErrors}",
    )
    assertTrue(
      "export_patient_setTagGroups" !in result.generated,
      "expected setTagGroups to be entirely absent from the generated CNameExports.kt; " +
          "generated=${result.generated}",
    )
    assertTrue(
      result.kspWarnings.any { it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT.name) },
      "expected a SKIPPED_UNSUPPORTED_INPUT diagnostic naming Patient.setTagGroups's Set " +
          "parameter; kspWarnings=${result.kspWarnings}",
    )
  }

  /**
   * ADR-073: `Map<String, Int>` and `Set<String>` parameters now bind (this is the feature this
   * ADR shipped), replacing the pre-ADR-073 general skip case above. This is the positive
   * counterpart to the negative cells above -- proving the narrowed clause still admits the
   * ordinary component types it must.
   */
  @Test
  fun `class method with Map or Set of wrappable components binds instead of skipping`() {
    val result = Tier1Harness.run(
      """
      package tier1.bindmapsetinput

      class Patient(val name: String) {
        fun setTags(tags: Map<String, String>): Int = tags.size
        fun setAllergies(allergies: Set<String>): Int = allergies.size
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected no broken source for setTags/setAllergies; got: ${result.compileErrors}",
    )
    assertTrue(
      "export_patient_setTags" in result.generated,
      "expected setTags (Map<String, String>) to bind now that ADR-073 shipped; " +
          "generated=${result.generated}",
    )
    assertTrue(
      "export_patient_setAllergies" in result.generated,
      "expected setAllergies (Set<String>) to bind now that ADR-073 shipped; " +
          "generated=${result.generated}",
    )
    assertTrue(
      result.kspWarnings.none { it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT.name) },
      "expected no SKIPPED_UNSUPPORTED_INPUT diagnostic for either wrappable-component callable; " +
          "kspWarnings=${result.kspWarnings}",
    )
  }

  /**
   * ADR-069 closed ADR-061's deferred width: a nullable `Boolean` method *return* now has the same
   * single-call `valueOut` ABI shape as every other nullable primitive (`BooleanVar` on the Kotlin
   * side, `[MarshalAs(UnmanagedType.I1)] out bool valueOut` on the C# side), so it is bound rather
   * than skipped. Inverted from the pre-ADR-069 "fires SKIPPED_UNSUPPORTED_RETURN" assertion.
   */
  @Test
  fun `class method with nullable Boolean return is bound, not skipped`() {
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
      "export_patient_isEligible" in result.generated,
      "expected isEligible to be bound in the generated Interop.kt; generated=${result.generated}",
    )
    assertTrue(
      result.kspWarnings.none {
        it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN.name)
      },
      "expected no SKIPPED_UNSUPPORTED_RETURN diagnostic for Patient.isEligible's nullable " +
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
