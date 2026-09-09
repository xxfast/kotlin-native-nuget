package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * ADR-060 obs classes **C** (1, 8, 9, 12), **N** (14), and cell **25** (object export naming) —
 * cells where the generated `CNameExports.kt` compiles clean and the defect is only visible in
 * the generated `Interop.cs` text. Tier 1 deliberately does **not** compile the generated C#
 * (ADR-060 "Rejected: compiling the generated C# in Tier 1" — the forward direction needs no
 * .NET SDK, docs/topics/prerequisites.md), so these assert on [Tier1Result.generatedCSharp] directly: the
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
    assertContains(generated, "public static global::Interop.Factory Create()")
    assertContains(generated, "public static IReadOnlyList<string> Labels()")
    assertContains(generated, "NugetMarshal.ReadList<string>(listHandle, static h1 => NugetMarshal.FromHandle<string>(h1))")
  }

  /**
   * Cell 1 · obs C. `object` method return × `String`. Guards the fix: object methods now route
   * through the class path's static-function marshalling, so a `String` return marshals to a real
   * `string`. Before the fix the object path had no marshalling at all — it rendered the *native*
   * return type as the public one, so `greet` surfaced as `public static IntPtr Greet(string name)`
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
  fun `cell 9 - top-level function with nullable String parameter renders nullable string in Interop cs`() {
    val result = Tier1Harness.run(
      """
      package tier1.cell9

      fun greetNickname(name: String?): String = "hi ${'$'}{name ?: "stranger"}"
      """.trimIndent()
    )

    assertContains(
      result.generatedCSharp,
      "public static string GreetNickname(string? name)",
      message = "expected GreetNickname's 'name' parameter to render string?, matching its " +
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
   * Cell 14 · LANDS NOW (MIGRATION.md Phase 8). Class property × `Char` on the shared property
   * plan — public C# surface includes `char Grade`.
   */
  @Test
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

  /**
   * MIGRATION.md Phase 7 (input positions). Class-method Enum parameter, on a plannable (`Int`)
   * result so the fixture actually exercises the shared callable plan rather than the pre-existing
   * legacy enum-param cast (which only ever ran for the ineligible-result legacy route).
   */
  @Test
  fun `class method with Enum parameter renders the enum type in Interop cs`() {
    val result = Tier1Harness.run(
      """
      package tier1.paramenum

      enum class Mood { HAPPY, SAD }

      class Patient(val name: String) {
        fun describe(mood: Mood): Int = mood.ordinal
      }
      """.trimIndent()
    )

    assertTrue(result.compiledClean, "expected Patient.describe to compile; got: ${result.compileErrors}")
    // Phase 8 qualifies enums as global::Namespace.Name so reverse-generated / cross-namespace
    // enums resolve; same-namespace simple names remain a substring of that form.
    assertContains(result.generatedCSharp, "public int Describe(")
    assertContains(result.generatedCSharp, "Mood mood)")
  }

  /** MIGRATION.md Phase 7. Class-method non-nullable object-handle parameter. */
  @Test
  fun `class method with non-nullable object-handle parameter renders the class type in Interop cs`() {
    val result = Tier1Harness.run(
      """
      package tier1.paramhandle

      class Buddy(val name: String)

      class Patient(val name: String) {
        fun attach(buddy: Buddy): Int = buddy.name.length
      }
      """.trimIndent()
    )

    assertTrue(result.compiledClean, "expected Patient.attach to compile; got: ${result.compileErrors}")
    assertContains(result.generatedCSharp, "public int Attach(global::Interop.Buddy buddy)")
  }

  /** MIGRATION.md Phase 7. Class-method nullable object-handle parameter. */
  @Test
  fun `class method with nullable object-handle parameter renders a nullable class type in Interop cs`() {
    val result = Tier1Harness.run(
      """
      package tier1.paramhandlenullable

      class Buddy(val name: String)

      class Patient(val name: String) {
        fun attach(buddy: Buddy?) {}
      }
      """.trimIndent()
    )

    assertTrue(result.compiledClean, "expected Patient.attach to compile; got: ${result.compileErrors}")
    assertContains(result.generatedCSharp, "public void Attach(global::Interop.Buddy? buddy)")
  }

  /** MIGRATION.md Phase 7. Class-method nullable primitive parameter. */
  @Test
  fun `class method with nullable Int parameter renders a nullable int in Interop cs`() {
    val result = Tier1Harness.run(
      """
      package tier1.paramprimnullable

      class Patient(val name: String) {
        fun setWeight(weight: Int?) {}
      }
      """.trimIndent()
    )

    assertTrue(result.compiledClean, "expected Patient.setWeight to compile; got: ${result.compileErrors}")
    assertContains(result.generatedCSharp, "public void SetWeight(int? weight)")
  }

  /** MIGRATION.md Phase 7. Class-method `Char` parameter, on a plannable (`Unit`) result. */
  @Test
  fun `class method with Char parameter on a Unit result renders char in Interop cs`() {
    val result = Tier1Harness.run(
      """
      package tier1.paramchar

      class Patient(val name: String) {
        fun setInitial(initial: Char) {}
      }
      """.trimIndent()
    )

    assertTrue(result.compiledClean, "expected Patient.setInitial to compile; got: ${result.compileErrors}")
    assertContains(result.generatedCSharp, "public void SetInitial(char initial)")
  }

  /** MIGRATION.md Phase 7. Class-method `List<String>` parameter. */
  @Test
  fun `class method with List of String parameter renders IReadOnlyList of string in Interop cs`() {
    val result = Tier1Harness.run(
      """
      package tier1.paramlist

      class Patient(val name: String) {
        fun addTags(tags: List<String>) {}
      }
      """.trimIndent()
    )

    assertTrue(result.compiledClean, "expected Patient.addTags to compile; got: ${result.compileErrors}")
    assertContains(result.generatedCSharp, "public void AddTags(IReadOnlyList<string> tags)")
  }

  /** MIGRATION.md Phase 7. Class-method `List<SomeExportedClass>` parameter. */
  @Test
  fun `class method with List of exported class parameter renders IReadOnlyList of the class in Interop cs`() {
    val result = Tier1Harness.run(
      """
      package tier1.paramlistobj

      class Buddy(val name: String)

      class Patient(val name: String) {
        fun addBuddies(buddies: List<Buddy>) {}
      }
      """.trimIndent()
    )

    assertTrue(result.compiledClean, "expected Patient.addBuddies to compile; got: ${result.compileErrors}")
    assertContains(
      result.generatedCSharp,
      "public void AddBuddies(IReadOnlyList<global::Interop.Buddy> buddies)",
    )
  }

  /**
   * Issue #111, structural pin. Every lambda type argument in a generated `Interop.cs` is either a
   * C# primitive, one of the `KotlinFunc`/`KotlinSuspend*` declarations' own type parameters, or
   * `global::`-qualified. Anything else is a simple name that only resolves when the argument's
   * namespace happens to coincide with the enclosing type's, which is exactly the assumption that
   * shipped `KotlinFunc<CamId, Flow>` and CS0246 to a consumer.
   *
   * This is the cheap guard that does not need to know how many routes exist: five hand-written
   * copies of the same `simpleName` expression were found for #111, and a sixth added later would
   * be caught here without anybody remembering to add a cell for it.
   *
   * The fixture crosses each lambda shape the generator has a different spelling for (plain,
   * suspend-with-result, suspend-`Unit`, zero-arity) with both an exported class argument in
   * another namespace and a primitive one, so a fix that qualified only the result, or only the
   * non-suspend arm, leaves a bare name behind for this to find.
   */
  @Test
  fun `no lambda type argument in Interop cs is a bare simple name`() {
    val result = Tier1Harness.run(
      mapOf(
        "Lens.kt" to """
          package tier1.lambdapin.lens

          class CamId(val value: String)

          class Snapshot(val caption: String)
        """.trimIndent(),
        "Cam.kt" to """
          package tier1.lambdapin

          import tier1.lambdapin.lens.CamId
          import tier1.lambdapin.lens.Snapshot

          class Cam(val watching: String) {
            val onPick: (CamId) -> Snapshot = { id -> Snapshot(id.value) }
            val onLabel: (String) -> Snapshot = { label -> Snapshot(label) }
            val onCount: (CamId) -> Int = { 1 }
            val onLatest: () -> Snapshot = { Snapshot("latest") }
            val onFetch: suspend (CamId) -> Snapshot = { id -> Snapshot(id.value) }
            val onLog: suspend (CamId) -> Unit = { }
          }

          fun picker(): (CamId) -> Snapshot = { id -> Snapshot(id.value) }
        """.trimIndent(),
      ),
      processorOptions = mapOf("nuget.rootPackage" to "tier1"),
    )

    val bare: List<String> = LAMBDA_TYPE_ARGUMENTS.findAll(result.generatedCSharp)
      .flatMap { match -> match.groupValues[2].split(",").map(String::trim) }
      .filter { argument -> argument.isNotEmpty() }
      .filterNot { argument -> argument in CSHARP_PRIMITIVES }
      .filterNot { argument -> argument.startsWith("global::") }
      .filterNot { argument -> DECLARATION_TYPE_PARAMETER.matches(argument) }
      .distinct()
      .toList()

    assertTrue(
      bare.isEmpty(),
      "every lambda type argument must be a C# primitive or global::-qualified, or the " +
          "generated Interop.cs only compiles by namespace coincidence; bare: $bare",
    )
  }

  private companion object {
    /** No nested `<...>` on purpose: a generic type argument has no spelling on these routes at
     *  all, so one appearing here is itself the defect and must not be quietly matched. */
    val LAMBDA_TYPE_ARGUMENTS =
      Regex("""\b(KotlinFunc|KotlinSuspendFunc|KotlinSuspendAction)<([^<>]*)>""")

    /** The `KotlinFunc<T1, TResult>` / `KotlinSuspendAction<T1>` declarations themselves. */
    val DECLARATION_TYPE_PARAMETER = Regex("""T([0-9]+|Result)?""")

    val CSHARP_PRIMITIVES: Set<String> = setOf(
      "string", "bool", "char", "object", "void",
      "sbyte", "byte", "short", "ushort", "int", "uint", "long", "ulong", "float", "double",
    )
  }
}
