package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * ADR-060 obs classes **C** (1, 8, 9, 12), **N** (14), and cell **25** (object export naming) —
 * cells where the generated `CNameExports.kt` compiles clean and the defect is only visible in
 * the generated `Interop.cs` text. Tier 1 deliberately does **not** compile the generated C#
 * (ADR-060 "Rejected: compiling the generated C# in Tier 1" — the forward direction needs no
 * .NET SDK, MVP.md line 62), so these assert on [Tier1Result.generatedCSharp] directly: the
 * **structural** assertion mode. The compile-based proof for cells 1/8/9/12 lands later, in
 * `GeneratedBindingsCheck`'s consumer surface (Tier 2), in the commit that fixes each one.
 */
class Tier1StructuralInteropCsTest {

  @Test
  fun `companion functions use checked private imports for every ordinary return shape`() {
    val result = Tier1Harness.run(
      """
      package tier1.companionerrors

      class Factory {
        companion object {
          fun reset() {}
          fun count(): Int = 1
          fun label(): String = "factory"
          fun create(): Factory = Factory()
          fun labels(): List<String> = listOf("factory")
        }
      }
      """.trimIndent()
    )

    assertTrue(result.compiledClean, "expected companion exports to compile; got: ${result.compileErrors}")

    val generated: String = result.generatedCSharp
    listOf("Reset", "Count", "Label", "Create", "Labels").forEach { method ->
      assertContains(generated, "Native_Companion_$method(out IntPtr error)")
    }
    assertContains(generated, "public static void Reset()")
    assertContains(generated, "public static int Count()")
    assertContains(generated, "public static string Label()")
    assertContains(generated, "public static Factory Create()")
    assertContains(generated, "public static IReadOnlyList<string> Labels()")
    assertContains(generated, "NugetListNative.Count(listHandle)")
  }

  /**
   * Cell 1 · obs C. `object` method return × `String`. Guards the fix: object methods now route
   * through the class path's static-function marshalling, so a `String` return marshals to a real
   * `string`. Before the fix the object path had no marshalling at all — it rendered the *native*
   * return type as the public one, so `greet` surfaced as `public static IntPtr greet(string name)`
   * and a consumer could not get a `string` back. `CatRegistry` (the only other `object` fixture)
   * never returned a `String`, which is why the leak shipped unnoticed. Fixed: ADR-060 cell 1 /
   * ROADMAP Phase 3.
   */
  @Test
  fun `cell 1 - object method returning String renders a public string return in Interop cs`() {
    val result = Tier1Harness.run(
      """
      package tier1.cell1

      object Clinic {
        fun greet(name: String): String = "Welcome to the clinic, ${'$'}name"
      }
      """.trimIndent()
    )

    assertContains(
      result.generatedCSharp,
      "public static string Greet(string name)",
      message = "expected Clinic.greet to return string, not IntPtr, in the generated Interop.cs",
    )
  }

  /**
   * Cell 8 · obs C. Class method parameter × `String?`. `ClassExports.kt:544`'s parameter loop
   * never re-reads `isMarkedNullable`, and `CirClassRenderer`'s `mapParamType` (`:636`) renders
   * the C# parameter non-null `string` — the API lies about accepting `null`, and a consumer
   * passing `null` gets `CS8625` (an error only under `TreatWarningsAsErrors`).
   */
  @Test
  @XFail("ADR-060 cell 8 - class method String? parameter renders a non-nullable C# string")
  fun `cell 8 - class method with nullable String parameter renders nullable string in Interop cs`() {
    val result = Tier1Harness.run(
      """
      package tier1.cell8

      class Patient(val name: String) {
        fun rename(to: String?): String = to ?: name
      }
      """.trimIndent()
    )

    assertContains(
      result.generatedCSharp,
      "public string Rename(string? to)",
      message = "expected Rename's 'to' parameter to render string?, matching its Kotlin " +
          "String? type",
    )
  }

  /**
   * Cell 9 · obs C. Top-level function parameter × `String?` — the same lying-parameter
   * mechanism as cell 8, but through `Helpers.kt:39` / `CirFunctionTranslator.kt:42` for a
   * top-level (not class-member) function. `greetNickname(name: String?)` already exists in
   * `test-library` (`NicknameSample.kt:18`); the fixture was never the problem, only the missing
   * gate was — this is that gate, for Tier 1's own local copy of the shape.
   */
  @Test
  @XFail("ADR-060 cell 9 - top-level function String? parameter renders a non-nullable C# string")
  fun `cell 9 - top-level function with nullable String parameter renders nullable string in Interop cs`() {
    val result = Tier1Harness.run(
      """
      package tier1.cell9

      fun greetNickname(name: String?): String = "hi ${'$'}{name ?: "stranger"}"
      """.trimIndent()
    )

    assertContains(
      result.generatedCSharp,
      "public static string greetNickname(string? name)",
      message = "expected greetNickname's 'name' parameter to render string?, matching its " +
          "Kotlin String? type",
    )
  }

  /**
   * Cell 12 · obs C. Class method parameter × `Char`. Kotlin exports the 2-byte native `KChar`
   * correctly, but `CirClassRenderer`'s `mapParamType` (`:641`) has no `Char` mapping and falls
   * through to `IntPtr` — leaked into the **public** C# signature, so a consumer cannot pass a
   * `char` at all. This is a call-site defect, not a runtime one; there is no wire-mismatch
   * class for this cell (an earlier draft of ADR-060 assumed one and was falsified by driving
   * real konanc).
   */
  @Test
  @XFail("ADR-060 cell 12 - class method Char parameter leaks IntPtr into the public C# API")
  fun `cell 12 - class method with Char parameter renders char in Interop cs`() {
    val result = Tier1Harness.run(
      """
      package tier1.cell12

      class Patient(val name: String) {
        fun tag(initial: Char): String = "${'$'}initial-${'$'}name"
      }
      """.trimIndent()
    )

    assertContains(
      result.generatedCSharp,
      "public string Tag(char initial)",
      message = "expected Tag's 'initial' parameter to render char, not IntPtr, in the " +
          "generated Interop.cs",
    )
  }

  /**
   * Cell 14 · obs N. Class property × `Char`. `CirClassTranslator.kt:179` misclassifies `Char`
   * as a reference type (`Char` is absent from `KOTLIN_TO_CSHARP_RETURN`) and `:184-188` warns
   * and skips it — `ForwardAbiContract` permits a Kotlin export with no C# import by design
   * (ADR-055), so generation still succeeds and the member simply **vanishes** from the C# API.
   * Nothing observes this by definition (obs **N**), so Tier 1 asserting the member's presence
   * directly in the generated text is the *only* place this can ever be red.
   */
  @Test
  @XFail("ADR-060 cell 14 - Char property is silently skipped and absent from Interop.cs")
  fun `cell 14 - Char property is present on the generated Patient class`() {
    val result = Tier1Harness.run(
      """
      package tier1.cell14

      class Patient(val name: String) {
        val grade: Char = 'A'
      }
      """.trimIndent()
    )

    assertContains(
      result.generatedCSharp,
      "Grade",
      message = "expected a Grade member on the generated Patient class in Interop.cs; " +
          "KSP warnings were: ${result.kspWarnings}",
    )
  }

  /**
   * Cell 25 · obs C. `object` method export naming. A distinct symptom from cell 1's marshalling
   * leak but the same root cause — the object path predated the class path's naming layer and
   * never caught up — so the same fix resolved both: routing objects through the class path's
   * static-function machinery PascalCases the public name while keeping the native entry point
   * (`"${prefix}_${cname}"`) lowercased. Before the fix, `translateObject` assigned the raw Kotlin
   * `methodName` straight to the public identifier, so `object Clinic { fun capacity() }` rendered
   * `Clinic.capacity()`, not `Clinic.Capacity()` — off-idiom against every class method and against
   * GOALS.md #2. Found by *not* trusting `CatRegistry` (the only pre-existing `object` fixture) as
   * the oracle for the object path — the same over-trust that let cell 1's leak ship unnoticed.
   * Fixed: ADR-060 cell 25 / ROADMAP Phase 3 (PascalCase per ADR-007).
   */
  @Test
  fun `cell 25 - object method is PascalCased in the generated Interop cs`() {
    val result = Tier1Harness.run(
      """
      package tier1.cell25

      object Clinic {
        fun capacity(): Int = 12
      }
      """.trimIndent()
    )

    assertContains(
      result.generatedCSharp,
      "public static int Capacity()",
      message = "expected Clinic.capacity() to render as the idiomatic PascalCase Capacity() " +
          "in the generated Interop.cs (GOALS.md #2)",
    )
  }
}
