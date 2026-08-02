package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-074: `List<T>`/`MutableList<T>` input parameters are narrowed to the same
 * `isWrappableComponent()` predicate `Map`/`Set` already use (ADR-073), instead of the wider
 * `isBridgeableComponent()`. Six negative shapes, each asserting `compiledClean`, the export
 * symbol **absent** from the generated Kotlin, and a `SKIPPED_UNSUPPORTED_INPUT` warning naming
 * the callable (not the parameter -- ADR-074 corrects ADR-073's test-comment claim on that point).
 *
 * Structured like the four ADR-073 cells in [Tier1NamedSkipDiagnosticsTest].
 */
class Tier1ListInputComponentNarrowingTest {

  /** Nullable element: admitted by `isBridgeableComponent()`, but `elementKotlinTypeName` has no
   *  `Nullable` branch -- before this ADR, this crashed `packNuget` with an `error(...)` rather
   *  than skipping. */
  @Test
  fun `class method with List nullable-String-element parameter fires SKIPPED_UNSUPPORTED_INPUT and is omitted`() {
    val result = Tier1Harness.run(
      """
      package tier1.skiplistnullableinput

      class Patient(val name: String) {
        fun addAliases(aliases: List<String?>): Int = aliases.size
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected no broken source for addAliases; got: ${result.compileErrors}",
    )
    assertFalse(
      result.generated.contains("export_patient_addAliases"),
      "expected addAliases to be entirely absent from the generated Interop.kt; " +
          "generated=${result.generated}",
    )
    assertTrue(
      result.kspWarnings.any { it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT.name) },
      "expected a SKIPPED_UNSUPPORTED_INPUT diagnostic naming Patient.addAliases; " +
          "kspWarnings=${result.kspWarnings}",
    )
  }

  /** Value-class element: admitted via `underlying.isBridgeableComponent()`, but
   *  `elementKotlinTypeName` has no `ValueClass` branch -- before this ADR, this crashed
   *  `packNuget`. Uses a locally-declared value class, not the cross-module `StoryCode`, so this
   *  cell is not entangled with ADR-066 reachability. */
  @Test
  fun `class method with List value-class-element parameter fires SKIPPED_UNSUPPORTED_INPUT and is omitted`() {
    val result = Tier1Harness.run(
      """
      package tier1.skiplistvalueclassinput

      @JvmInline
      value class Code(val value: String)

      class Patient(val name: String) {
        fun addCodes(codes: List<Code>): Int = codes.size
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected no broken source for addCodes; got: ${result.compileErrors}",
    )
    assertFalse(
      result.generated.contains("export_patient_addCodes"),
      "expected addCodes to be entirely absent from the generated Interop.kt; " +
          "generated=${result.generated}",
    )
    assertTrue(
      result.kspWarnings.any { it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT.name) },
      "expected a SKIPPED_UNSUPPORTED_INPUT diagnostic naming Patient.addCodes; " +
          "kspWarnings=${result.kspWarnings}",
    )
  }

  /** Nested-collection element: admitted by `isBridgeableComponent()`, but
   *  `elementKotlinTypeName` has no `Collection` branch -- before this ADR, this crashed
   *  `packNuget`. */
  @Test
  fun `class method with List nested-collection-element parameter fires SKIPPED_UNSUPPORTED_INPUT and is omitted`() {
    val result = Tier1Harness.run(
      """
      package tier1.skiplistnestedinput

      class Patient(val name: String) {
        fun addGroups(groups: List<List<String>>): Int = groups.size
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected no broken source for addGroups; got: ${result.compileErrors}",
    )
    assertFalse(
      result.generated.contains("export_patient_addGroups"),
      "expected addGroups to be entirely absent from the generated Interop.kt; " +
          "generated=${result.generated}",
    )
    assertTrue(
      result.kspWarnings.any { it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT.name) },
      "expected a SKIPPED_UNSUPPORTED_INPUT diagnostic naming Patient.addGroups; " +
          "kspWarnings=${result.kspWarnings}",
    )
  }

  /** Enum element: bound today (`type.qualifiedName` branch exists), but `Wrap<T>` has no branch
   *  for a C# `enum` and no `_handle` field either, so every non-empty call throws
   *  `NotSupportedException` (confirmed by execution this session, see ADR-074). Must stop
   *  binding. */
  @Test
  fun `class method with List enum-element parameter fires SKIPPED_UNSUPPORTED_INPUT and is omitted`() {
    val result = Tier1Harness.run(
      """
      package tier1.skiplistenuminput

      enum class Mood { CALM, ANXIOUS }

      class Patient(val name: String) {
        fun addMoods(moods: List<Mood>): Int = moods.size
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected no broken source for addMoods; got: ${result.compileErrors}",
    )
    assertFalse(
      result.generated.contains("export_patient_addMoods"),
      "expected addMoods to be entirely absent from the generated Interop.kt; " +
          "generated=${result.generated}",
    )
    assertTrue(
      result.kspWarnings.any { it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT.name) },
      "expected a SKIPPED_UNSUPPORTED_INPUT diagnostic naming Patient.addMoods; " +
          "kspWarnings=${result.kspWarnings}",
    )
  }

  /** Narrow-primitive element: bound today, but `Wrap<T>` has no `short` branch and `short` has
   *  no `_handle` field, so every non-empty call throws. Must stop binding. */
  @Test
  fun `class method with List Short-element parameter fires SKIPPED_UNSUPPORTED_INPUT and is omitted`() {
    val result = Tier1Harness.run(
      """
      package tier1.skiplistshortinput

      class Patient(val name: String) {
        fun addWeights(weights: List<Short>): Int = weights.size
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected no broken source for addWeights; got: ${result.compileErrors}",
    )
    assertFalse(
      result.generated.contains("export_patient_addWeights"),
      "expected addWeights to be entirely absent from the generated Interop.kt; " +
          "generated=${result.generated}",
    )
    assertTrue(
      result.kspWarnings.any { it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT.name) },
      "expected a SKIPPED_UNSUPPORTED_INPUT diagnostic naming Patient.addWeights; " +
          "kspWarnings=${result.kspWarnings}",
    )
  }

  /** `Char` element: bound today, but `Wrap<T>` has no `char` branch and `char` has no `_handle`
   *  field, so every non-empty call throws. Must stop binding. */
  @Test
  fun `class method with List Char-element parameter fires SKIPPED_UNSUPPORTED_INPUT and is omitted`() {
    val result = Tier1Harness.run(
      """
      package tier1.skiplistcharinput

      class Patient(val name: String) {
        fun addInitials(initials: List<Char>): Int = initials.size
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected no broken source for addInitials; got: ${result.compileErrors}",
    )
    assertFalse(
      result.generated.contains("export_patient_addInitials"),
      "expected addInitials to be entirely absent from the generated Interop.kt; " +
          "generated=${result.generated}",
    )
    assertTrue(
      result.kspWarnings.any { it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT.name) },
      "expected a SKIPPED_UNSUPPORTED_INPUT diagnostic naming Patient.addInitials; " +
          "kspWarnings=${result.kspWarnings}",
    )
  }

  /** Positive controls: the two component types `isWrappableComponent()` admits (`String`,
   *  `ObjectHandle`) must keep binding for `List`, exactly as they do for `Map`/`Set`. This is
   *  the primary over-narrowing regression gate. */
  @Test
  fun `class method with List of wrappable components binds instead of skipping`() {
    val result = Tier1Harness.run(
      """
      package tier1.bindlistinput

      class Buddy(val name: String)
      class Patient(val name: String) {
        fun addTags(tags: List<String>): Int = tags.size
        fun addBuddies(buddies: List<Buddy>): Int = buddies.size
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected no broken source for addTags/addBuddies; got: ${result.compileErrors}",
    )
    assertTrue(
      result.generated.contains("export_patient_addTags"),
      "expected addTags (List<String>) to keep binding; generated=${result.generated}",
    )
    assertTrue(
      result.generated.contains("export_patient_addBuddies"),
      "expected addBuddies (List<Buddy>, an ObjectHandle element) to keep binding; " +
          "generated=${result.generated}",
    )
    assertTrue(
      result.kspWarnings.none { it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT.name) },
      "expected no SKIPPED_UNSUPPORTED_INPUT diagnostic for either wrappable-component callable; " +
          "kspWarnings=${result.kspWarnings}",
    )
  }

  /** Return-position control: the single most likely over-narrowing regression is applying
   *  `isWrappableComponent()` at the return position by mistake. `List<Mood>` as a *return* type
   *  must keep binding; only the *input* position is narrowed. */
  @Test
  fun `class method with List enum-element return still binds`() {
    val result = Tier1Harness.run(
      """
      package tier1.bindlistenumreturn

      enum class Mood { CALM, ANXIOUS }

      class Patient(val name: String) {
        fun moods(): List<Mood> = listOf(Mood.CALM)
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected no broken source for moods; got: ${result.compileErrors}",
    )
    assertTrue(
      result.generated.contains("export_patient_moods"),
      "expected moods() (a List<Mood> return, untouched by this ADR) to keep binding; " +
          "generated=${result.generated}",
    )
    assertTrue(
      result.kspWarnings.none { it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT.name) },
      "expected no SKIPPED_UNSUPPORTED_INPUT diagnostic for the List<Mood> return; " +
          "kspWarnings=${result.kspWarnings}",
    )
  }

  /** `MutableList` cell: proves the deleted planner arm covered both `LIST` and `MUTABLE_LIST`. */
  @Test
  fun `class method with MutableList enum-element parameter fires SKIPPED_UNSUPPORTED_INPUT and is omitted`() {
    val result = Tier1Harness.run(
      """
      package tier1.skipmutablelistenuminput

      enum class Mood { CALM, ANXIOUS }

      class Patient(val name: String) {
        fun addMoods(moods: MutableList<Mood>): Int = moods.size
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected no broken source for addMoods; got: ${result.compileErrors}",
    )
    assertFalse(
      result.generated.contains("export_patient_addMoods"),
      "expected addMoods (MutableList<Mood>) to be entirely absent from the generated Interop.kt; " +
          "generated=${result.generated}",
    )
    assertTrue(
      result.kspWarnings.any { it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT.name) },
      "expected a SKIPPED_UNSUPPORTED_INPUT diagnostic naming Patient.addMoods; " +
          "kspWarnings=${result.kspWarnings}",
    )
  }

  /** Constructor cell: the collateral position ADR-074's "What breaks" describes -- a class whose
   *  only constructor takes an unwrappable list parameter must skip its constructor rather than
   *  crash the build or offer a throwing one. */
  @Test
  fun `constructor with List enum-element parameter fires SKIPPED_UNSUPPORTED_INPUT and is omitted`() {
    val result = Tier1Harness.run(
      """
      package tier1.skiplistconstructorinput

      enum class Mood { CALM, ANXIOUS }

      class Ward(val moods: List<Mood>)
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected no broken source for Ward; got: ${result.compileErrors}",
    )
    assertFalse(
      result.generated.contains("export_ward_create"),
      "expected Ward's constructor to be entirely absent from the generated Interop.kt; " +
          "generated=${result.generated}",
    )
    assertTrue(
      result.kspWarnings.any { it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT.name) },
      "expected a SKIPPED_UNSUPPORTED_INPUT diagnostic naming Ward's constructor; " +
          "kspWarnings=${result.kspWarnings}",
    )
  }
}
