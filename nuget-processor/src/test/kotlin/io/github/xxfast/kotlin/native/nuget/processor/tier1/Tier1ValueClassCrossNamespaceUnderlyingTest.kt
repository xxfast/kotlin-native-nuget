package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * The value-class mirror of #41: a value class whose underlying is declared in a *different*
 * package must spell that underlying `global::Namespace.Name` in the generated struct.
 *
 * Every other value-class fixture in this suite keeps the underlying in the value class's own
 * package, so the bare Kotlin simple name the translator used to pass through happened to resolve
 * from inside the struct's own C# namespace and the gap never surfaced. Here the two enum/handle
 * underlyings live one namespace over, so a bare `Mood` / `Patient` inside `namespace Interop` is
 * a CS0246 the moment the consumer compiles the bindings.
 *
 * `nuget.rootPackage` is mandatory to this cell, not decoration: the provider defaults it to `""`,
 * and `mapPackageToNamespace` then collapses *every* package onto the bare `rootNamespace`, so
 * without the option a two-package fixture would land in one namespace and re-hide the bug.
 *
 * Both underlying kinds are here because they are two distinct render sites off the one
 * translator spelling -- `renderValueClass`'s `{ get; }` member for the enum, and
 * `renderReferenceValueClass`'s positional record header for the handle.
 */
class Tier1ValueClassCrossNamespaceUnderlyingTest {

  @Test
  fun `a value class qualifies an underlying declared in another package`() {
    val result = Tier1Harness.run(
      mapOf(
        "Clinic.kt" to
            """
          package tier1.vcx.clinic

          enum class Mood { CALM, ANXIOUS }

          class Patient(val name: String)
          """.trimIndent(),
        "Desk.kt" to
            """
          package tier1.vcx

          import tier1.vcx.clinic.Mood
          import tier1.vcx.clinic.Patient

          @JvmInline
          value class Disposition(val mood: Mood)

          @JvmInline
          value class PatientRef(val patient: Patient)

          class Desk {
            var current: Disposition = Disposition(Mood.CALM)

            fun flip(disposition: Disposition): Disposition =
              if (disposition.mood == Mood.CALM) Disposition(Mood.ANXIOUS)
              else Disposition(Mood.CALM)

            fun describe(referral: PatientRef): String = referral.patient.name
          }
          """.trimIndent(),
      ),
      processorOptions = mapOf("nuget.rootPackage" to "tier1.vcx"),
    )

    assertTrue(
      result.compiledClean,
      "expected the cross-package underlyings to compile; got: ${result.compileErrors}",
    )

    val cs: String = result.generatedCSharp
    // Enum underlying: the struct member and the constructor body's cast now agree on one spelling.
    assertContains(cs, "public global::Interop.Clinic.Mood Mood { get; }")
    assertContains(cs, "Mood = (global::Interop.Clinic.Mood)CreateChecked(mood);")
    // Handle underlying: the positional record header carries the same qualified spelling.
    assertContains(
      cs,
      "public readonly record struct PatientRef(global::Interop.Clinic.Patient Patient)",
    )
    // The wire is untouched by the spelling: the enum still rides its int ordinal, the handle its
    // IntPtr, so a regression that qualified the *native* signature would fail here too.
    assertContains(cs, "private static extern int Native_Create(int mood, out IntPtr error);")
    assertContains(cs, "Native_Flip(_handle, (int)disposition.Mood, out IntPtr error)")
    assertContains(cs, "Native_Describe(_handle, referral.Patient._handle, out IntPtr error)")
  }
}
