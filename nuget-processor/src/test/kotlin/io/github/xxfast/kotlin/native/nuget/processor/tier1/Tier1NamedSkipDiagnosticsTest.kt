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
   * (`isWrappableComponent()`): a `nuget_wrap_*` primitive, an object handle, and
   * (ADR-081/097) anything that projects to one of those per element. A *nested* collection value
   * is outside that subset -- there is no wire for a collection inside a collection at all -- so
   * it must still fire `SKIPPED_UNSUPPORTED_INPUT`.
   *
   * ADR-097 moved this cell off a `Mood` value and ADR-098 off a `Short` one: a bare enum rides
   * the int-ordinal wire and every narrow primitive now has a `nuget_wrap_*` of its own, so
   * neither demonstrates the skip any more. ADR-099 moved this cell off a plain nested collection:
   * nesting is wrappable now (the inner handle is the component's wire value), so the remaining
   * bridgeable-but-unwrappable component is a NULLABLE nested collection, which ADR-099 guards
   * explicitly (its write projection has no null arm).
   */
  @Test
  fun `class method with Map nested-collection value parameter fires SKIPPED_UNSUPPORTED_INPUT and is omitted`() {
    val result = Tier1Harness.run(
      """
      package tier1.skipmapinput

      class Patient(val name: String) {
        fun setMoods(moods: Map<String, List<String>?>): Int = moods.size
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
   * ADR-073's sibling case for `Set`. ADR-098 minted `nuget_wrap_char`, so the `Char` element this
   * cell used to carry binds now. ADR-099 moved this cell off a plain nested collection: nesting is
   * wrappable now (the inner handle is the component's wire value), so the remaining
   * bridgeable-but-unwrappable component is a NULLABLE nested collection, which ADR-099 guards
   * explicitly (its write projection has no null arm).
   */
  @Test
  fun `class method with Set nested-collection element parameter fires SKIPPED_UNSUPPORTED_INPUT and is omitted`() {
    val result = Tier1Harness.run(
      """
      package tier1.skipsetinput

      class Patient(val name: String) {
        fun setInitials(initials: Set<List<String>?>): Int = initials.size
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
   * ADR-083 supersedes the ADR-073 skip this cell used to assert: a nullable map *value*
   * (`Map<String, Int?>`) now rides the null pointer in `nuget_map_put`'s value slot, so the
   * method binds. The nullable *key* spelling keeps a named skip of its own, one cell below.
   */
  @Test
  fun `class method with Map nullable-value parameter binds`() {
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
      "export_patient_setOptionalScores" in result.generated,
      "expected setOptionalScores to bind now that a nullable map value is admitted; " +
          "generated=${result.generated}",
    )
  }

  /**
   * ADR-083: a nullable map *key* (`Map<String?, Int>`) stays excluded by name -- a C#
   * `Dictionary` cannot hold a null key, so there is no idiomatic projection for it -- even though
   * the same nullable spelling is admitted in the value slot by the cell above.
   */
  @Test
  fun `class method with Map nullable-key parameter fires SKIPPED_UNSUPPORTED_INPUT and is omitted`() {
    val result = Tier1Harness.run(
      """
      package tier1.skipmapnullablekeyinput

      class Patient(val name: String) {
        fun setKeyedScores(scores: Map<String?, Int>): Int = scores.size
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected no broken source for setKeyedScores; got: ${result.compileErrors}",
    )
    assertTrue(
      "export_patient_setKeyedScores" !in result.generated,
      "expected setKeyedScores to be entirely absent from the generated CNameExports.kt; " +
          "generated=${result.generated}",
    )
    assertTrue(
      result.kspWarnings.any { it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT.name) },
      "expected a SKIPPED_UNSUPPORTED_INPUT diagnostic naming Patient.setKeyedScores's Map " +
          "parameter; kspWarnings=${result.kspWarnings}",
    )
  }

  /**
   * ADR-073: an unwrappable `Set` element must still fire `SKIPPED_UNSUPPORTED_INPUT`. ADR-099
   * moved this cell off a plain nested collection: nesting is wrappable now (the inner handle is
   * the component's wire value), so the remaining bridgeable-but-unwrappable component is a
   * NULLABLE nested collection, which ADR-099 guards explicitly (its write projection has no null
   * arm).
   */
  @Test
  fun `class method with Set nested-collection-element parameter fires SKIPPED_UNSUPPORTED_INPUT and is omitted`() {
    val result = Tier1Harness.run(
      """
      package tier1.skipsetnestedinput

      class Patient(val name: String) {
        fun setTagGroups(groups: Set<List<String>?>): Int = groups.size
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

  /**
   * The property planner's own honest-skip cell. `isPlannable` rejects a type the property
   * position has no shape for, and until now the property just vanished from the generated C#
   * with no diagnostic at all: the one gap in ADR-064's position naming (`_INPUT` covered a
   * parameter, `_RETURN` a return, nothing covered a property). A `Box<Int>`-typed property is
   * the simplest such type: the classifier hands back a generic declaration, which no property
   * getter or setter can wire.
   */
  @Test
  fun `class property with an unplannable type fires SKIPPED_UNSUPPORTED_PROPERTY and is omitted`() {
    val result = Tier1Harness.run(
      """
      package tier1.skipproperty

      class Box<T>(val value: T)

      class Patient(val name: String) {
        var box: Box<Int> = Box(1)
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected no broken source for Patient.box; got: ${result.compileErrors}",
    )
    assertFalse(
      result.generated.contains("export_patient_get_box"),
      "expected the unplannable property to be entirely absent from the generated exports; " +
          "generated=${result.generated}",
    )
    assertTrue(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY.name) &&
            it.contains("Patient.box")
      },
      "expected a SKIPPED_UNSUPPORTED_PROPERTY diagnostic naming Patient.box; " +
          "kspWarnings=${result.kspWarnings}",
    )
  }

  /**
   * The exclusion that keeps the new kind honest. A `StateFlow<T>` property is *not* dropped: it
   * is unplannable by the plan path on purpose and re-emitted by `CirClassTranslator`'s legacy
   * flow adapter, so warning about it would be a false positive telling a consumer their working
   * property vanished.
   */
  @Test
  fun `class property with a StateFlow type fires no SKIPPED_UNSUPPORTED_PROPERTY`() {
    val result = Tier1Harness.run(
      """
      package tier1.skippropertyflow

      import kotlinx.coroutines.flow.MutableStateFlow
      import kotlinx.coroutines.flow.StateFlow

      class Patient(val name: String) {
        val mood: StateFlow<Int> = MutableStateFlow(0)
      }
      """.trimIndent(),
      // Without coroutines on KSP's own classpath the classifier sees `<ERROR TYPE: StateFlow>`
      // and the fixture would prove nothing about the exclusion.
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    // `compiledClean` is deliberately not asserted here: the legacy flow adapter emits
    // `CFunction`-typed subscription callbacks, which Tier1CinteropStub does not model (a
    // pre-existing harness limit, unrelated to this diagnostic). What matters is that the export
    // is emitted at all, which is exactly why warning about it would be a false positive.
    assertTrue(
      result.generated.contains("patient_get_mood_collect"),
      "expected the StateFlow property to still be emitted by the legacy flow adapter; " +
          "generated=${result.generated}",
    )
    assertFalse(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY.name)
      },
      "a StateFlow property still binds through the legacy flow adapter, so warning that it was " +
          "skipped would be a false positive; kspWarnings=${result.kspWarnings}",
    )
  }

  /**
   * The false negative in the exclusion above: `LEGACY_ROUTED_PROTOCOLS` is position-agnostic, but
   * the legacy flow/lambda adapters live in `CirClassTranslator` and only re-emit **class**
   * properties. An extension property typed `StateFlow<T>` on a perfectly supported receiver has no
   * adapter at all, so the exclusion silences a genuine drop.
   */
  @Test
  fun `extension property with a StateFlow type fires SKIPPED_UNSUPPORTED_PROPERTY and is omitted`() {
    val result = Tier1Harness.run(
      """
      package tier1.skipextensionflow

      import kotlinx.coroutines.flow.MutableStateFlow
      import kotlinx.coroutines.flow.StateFlow

      class Patient(val name: String)

      val Patient.status: StateFlow<Int> get() = MutableStateFlow(0)
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(
      result.compiledClean,
      "expected no broken source for Patient.status; got: ${result.compileErrors}",
    )
    assertFalse(
      result.generated.contains("patient_get_status"),
      "expected the extension StateFlow property to be entirely absent from the generated " +
          "exports; generated=${result.generated}",
    )
    assertTrue(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY.name) &&
            it.contains("Patient.status")
      },
      "no adapter re-emits an extension StateFlow property, so it must be named as a skip; " +
          "kspWarnings=${result.kspWarnings}",
    )
  }

  /**
   * The *receiver* half of the same gap. The sibling cell above covers an unplannable property
   * **type**; an extension property whose receiver type the planner cannot wire vanished just as
   * silently, from a completely separate bail (`ForwardPropertyPlanner.extensionProperty`), and
   * needed its own record because the property's own type (`String` here) is perfectly supported,
   * so the type-based wording would name the wrong thing.
   */
  @Test
  fun `extension property with an unsupported receiver fires SKIPPED_UNSUPPORTED_PROPERTY and is omitted`() {
    val result = Tier1Harness.run(
      """
      package tier1.skipreceiver

      class Box<T>(val value: T)

      class Patient(val name: String)

      val Box<Int>.label: String get() = "x"
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected no broken source for Box<Int>.label; got: ${result.compileErrors}",
    )
    assertFalse(
      result.generated.contains("box_get_label"),
      "expected the unsupported-receiver extension property to be entirely absent from the " +
          "generated exports; generated=${result.generated}",
    )
    assertTrue(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY.name) &&
            it.contains("Box.label")
      },
      "expected a SKIPPED_UNSUPPORTED_PROPERTY diagnostic naming the Box receiver; " +
          "kspWarnings=${result.kspWarnings}",
    )
  }

  /**
   * The negative half: a supported receiver still binds and stays silent. There is no exclusion
   * cell for this diagnostic beyond this one, because nothing legacy-routes an extension property
   * by *receiver*: the `LEGACY_ROUTED_PROTOCOLS` escape hatch keys off the property's own type, so
   * an unsupported receiver is always a genuine drop.
   */
  @Test
  fun `extension property with a supported receiver binds and fires no SKIPPED_UNSUPPORTED_PROPERTY`() {
    val result = Tier1Harness.run(
      """
      package tier1.bindreceiver

      val String.wordCount: Int get() = trim().split(" ").size
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected no broken source for String.wordCount; got: ${result.compileErrors}",
    )
    assertTrue(
      result.generated.contains("string_get_wordCount"),
      "expected the String-receiver extension property to still bind; " +
          "generated=${result.generated}",
    )
    assertFalse(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY.name)
      },
      "a supported receiver is not a skip; kspWarnings=${result.kspWarnings}",
    )
  }
}
