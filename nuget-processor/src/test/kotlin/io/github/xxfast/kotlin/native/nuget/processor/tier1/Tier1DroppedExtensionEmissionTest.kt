package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Two cosmetic codegen leftovers of a *dropped* extension property, both spike-verified during the
 * `SKIPPED_UNSUPPORTED_PROPERTY` receiver work and both ROADMAP items:
 *
 * - `CirTranslator` groups extension members by receiver **before** the plan gate, so a receiver
 *   whose members are all unplanned still gets an empty `public static partial class
 *   {Receiver}Extensions { }` in the generated C#.
 * - `NugetProcessor.addExtensionPropertyExports` adds the Kotlin `import` **before**
 *   `ForwardPropertyPlanner`'s decision, so a dropped extension property leaves a dead `import`
 *   line in `CNameExports.kt`.
 *
 * Neither is a compile or runtime defect; both are dead generated output. Each fix gets a negative
 * cell (the leftover is gone) and a positive cell (a surviving member still gets its class / its
 * import).
 */
class Tier1DroppedExtensionEmissionTest {

  /**
   * Fix A, negative half: `Box<Int>.label`'s receiver is an unsupported extension-property
   * receiver (the same shape `Tier1NamedSkipDiagnosticsTest` uses for the receiver diagnostic), so
   * `BoxExtensions` would have zero members and must not be emitted at all.
   */
  @Test
  fun `receiver group with only dropped members emits no empty extensions class`() {
    val result = Tier1Harness.run(
      """
      package tier1.emptyext

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
      result.generatedCSharp.contains("BoxExtensions"),
      "expected no empty BoxExtensions class in the generated C#; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
  }

  /**
   * Fix A, positive half: a receiver group that keeps at least one member still gets its class.
   * The dropped property (`Sequence<String>` has no property shape) and the surviving extension
   * function share one receiver, so this is exactly the case where suppressing the class would be
   * wrong.
   */
  @Test
  fun `receiver group with one surviving member still emits its extensions class`() {
    val result = Tier1Harness.run(
      """
      package tier1.mixedext

      class Patient(val name: String)

      val Patient.tags: Sequence<String> get() = sequenceOf(name)

      fun Patient.greeting(): String = "hi " + name
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected no broken source; got: ${result.compileErrors}",
    )
    assertTrue(
      result.generatedCSharp.contains("PatientExtensions"),
      "expected PatientExtensions to survive for the bound extension function; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      result.generatedCSharp.contains("Greeting"),
      "expected the bound extension function to be a member of PatientExtensions; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
  }

  /**
   * Fix B, negative half: a dropped extension property must not leave its `import` behind in
   * `CNameExports.kt`.
   */
  @Test
  fun `dropped extension property leaves no dead import in the generated Kotlin`() {
    val result = Tier1Harness.run(
      """
      package tier1.deadimport

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
      result.generated.contains("import tier1.deadimport.label"),
      "expected no dead import for the dropped extension property; generated=${result.generated}",
    )
  }

  /** Fix B, positive half: a planned extension property still needs its import. */
  @Test
  fun `planned extension property keeps its import in the generated Kotlin`() {
    val result = Tier1Harness.run(
      """
      package tier1.liveimport

      val String.wordCount: Int get() = trim().split(" ").size
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected no broken source; got: ${result.compileErrors}",
    )
    assertTrue(
      result.generated.contains("import tier1.liveimport.wordCount"),
      "expected the planned extension property's import to survive; generated=${result.generated}",
    )
  }
}
