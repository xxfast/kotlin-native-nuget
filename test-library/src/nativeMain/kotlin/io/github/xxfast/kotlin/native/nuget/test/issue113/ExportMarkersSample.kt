package io.github.xxfast.kotlin.native.nuget.test.issue113

/**
 * Fixture for ADR-115's amendment (alternative 3): `nuget { publish { exportMarkers("...") } }`
 * names a `@RequiresOptIn` marker whose declarations keep exporting, the inverse of
 * `binary-compatibility-validator`'s `nonPublicMarkers`.
 *
 * The neighbouring [Issue113Sample] owns the default (everything behind a marker is dropped). This
 * file owns the escape hatch, and the two share the package on purpose: [ExperimentalDiet] is
 * waived in `test-library/build.gradle.kts`, [LedgerApi] and [CatteryInternalApi] next door are
 * not, so one build shows both behaviours.
 *
 * `ERROR` level, deliberately. `WARNING` would prove nothing: the load-bearing half of the waiver
 * is that the generated `CNameExports.kt` carries `@OptIn(ExperimentalDiet::class)`, and only an
 * `ERROR`-level marker makes the generated file's own compile fail without it. That failure is
 * issue #113's original one, re-entered from the other side.
 *
 * `String` throughout, and an unmarked sibling for the marked member, for [Issue113Sample]'s
 * reason: this whole feature is asserted by presence and absence, and the pipeline drops members
 * for a dozen unrelated reasons.
 */
@RequiresOptIn(level = RequiresOptIn.Level.ERROR, message = "Diet plans are still settling")
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION)
annotation class ExperimentalDiet

/**
 * The whole class is behind the waived marker, so every export it produces reads a marked
 * declaration: the C# `Nutritionist` type exists with its constructor, [name] and [plan].
 */
@ExperimentalDiet
class Nutritionist(val name: String) {
  fun plan(): String = "kibble"
}

/**
 * A member-level waiver next to a member-level control: [dietName] exports, [ledgerName] does not.
 */
class DietPlanner {
  fun plainName(): String = "Mylo"

  @ExperimentalDiet
  fun dietName(): String = "kibble"

  @LedgerApi
  fun ledgerName(): String = "ledger"
}
