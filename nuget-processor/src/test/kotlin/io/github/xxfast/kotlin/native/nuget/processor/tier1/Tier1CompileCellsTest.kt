package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ADR-060 obs class **K** — the twelve cells whose generated `CNameExports.kt` fails to compile
 * for the JVM. Cell 15 (value class primitive-underlying method parameter) has its own file,
 * [Tier1Cell15Test]; the remaining eleven live here, one `@Test` per cell, each driving the real
 * `NugetProcessor` through [Tier1Harness] and asserting the compiler's error list is empty — the
 * **correct** behaviour, never the buggy one (ADR-060 "Strict xfail").
 */
class Tier1CompileCellsTest {

  /**
   * Cell 2 · obs K. `object` method return × object type. `ObjectExports.kt:69` declares the
   * export non-null, but `:79`'s `defaultValueFor` yields `null` for a non-`kotlin.` type (the
   * error-path fallback), so the try/catch expression infers `Patient?` against a declared
   * non-null `Patient` return — "return type mismatch: expected 'Patient', actual 'Patient?'."
   * Same root cause as cells 6 and 10: the object carrier has no `StableRef` branch either.
  */
  @Test
  fun `cell 2 - object method returning an object compiles`() {
    val result = Tier1Harness.run(
      """
      package tier1.cell2

      class Patient(val name: String)

      object Clinic {
        fun intake(name: String): Patient = Patient(name)
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected export_clinic_intake to compile; got: ${result.compileErrors}",
    )
  }

  /**
   * Cell 4 · obs K. Class method return × `String?`. `ClassExports.kt:563`'s method-return loop
   * never re-reads `isMarkedNullable` (unlike the property loop at `:110`), so it declares
   * non-null `String` while the body returns the real nullable value — "return type mismatch:
   * expected 'String', actual 'String?'."
   *
   * ADR-061 fixed this: the class-method return loop now re-reads the real nullability instead
   * of hardcoding non-null.
   */
  @Test
  fun `cell 4 - class method returning nullable String compiles`() {
    val result = Tier1Harness.run(
      """
      package tier1.cell4

      class Patient(val name: String) {
        var nickname: String? = null
        fun alias(): String? = nickname
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected export_patient_alias to compile; got: ${result.compileErrors}",
    )
  }

  /**
   * Cell 5 · obs K. Class method return × `Int?`. Same mechanism as cell 4, primitive facet —
   * "return type mismatch: expected 'Int', actual 'Int?'."
   *
   * ADR-061 fixed this: a nullable-primitive method return now routes through the single-call
   * has-value + `valueOut` out-parameter (ADR-061 §5) instead of the old by-value declaration.
   */
  @Test
  fun `cell 5 - class method returning nullable Int compiles`() {
    val result = Tier1Harness.run(
      """
      package tier1.cell5

      class Patient(val name: String) {
        var weight: Int? = null
        fun ageInMonths(): Int? = weight?.times(2)
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected export_patient_ageInMonths to compile; got: ${result.compileErrors}",
    )
  }

  /**
   * Cell 6 · obs K. Class method return × object type. The method-return loop has no
   * `StableRef` branch, unlike the property loop (`:361`/`:404`) — so ADR-005's object-return
   * handling holds for properties but not methods. "return type mismatch: expected 'Patient',
   * actual 'Patient?'."
   *
   * ADR-061 fixed this: the class-method return loop now boxes an object return via `StableRef`,
   * mirroring the property getter / companion-method loop's already-shipped mechanism.
   */
  @Test
  fun `cell 6 - class method returning an object compiles`() {
    val result = Tier1Harness.run(
      """
      package tier1.cell6

      class Patient(val name: String) {
        var buddy: Patient? = null
        fun companion(): Patient = buddy ?: this
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected export_patient_companion to compile; got: ${result.compileErrors}",
    )
  }

  /**
   * Cell 10 · obs K. Top-level factory function returning a plain class. `FunctionExports.kt`
   * handles nullable, sealed-or-generic (StableRef), enum and `Unit` returns, but a plain class
   * falls to the final fallthrough (`:178`), which returns the class itself yet defaults to
   * `null` on the catch path — "return type mismatch: expected 'Patient', actual 'Patient?'."
   * Nobody noticed because every factory in the corpus is a companion (`Cat.fromName`), which
   * routes through a different, StableRef-using path (`ClassExports.kt:760`).
  */
  @Test
  fun `cell 10 - top-level factory returning a plain class compiles`() {
    val result = Tier1Harness.run(
      """
      package tier1.cell10

      class Patient(val name: String)

      fun admit(name: String): Patient = Patient(name)
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected export_admit to compile; got: ${result.compileErrors}",
    )
  }

  /**
   * Cell 13 · obs K. Class method return × `Char`. Not a wire mismatch — it never reaches the
   * wire: `defaultValueFor("kotlin.Char")` is `"0"` (`Helpers.kt:27`, the `startsWith("kotlin.")`
   * branch), so the catch path yields an `Int` literal from a function declared to return `Char`
   * — "return type mismatch: expected 'Char', actual ...` (an inferred common supertype of
   * `Char` and `Int`, verified against the real compiler)."
   */
  @Test
  @XFail("ADR-060 cell 13 - class method returning Char is invalid Kotlin")
  fun `cell 13 - class method returning Char compiles`() {
    val result = Tier1Harness.run(
      """
      package tier1.cell13

      class Patient(val name: String) {
        fun initial(): Char = name.first()
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected export_patient_initial to compile; got: ${result.compileErrors}",
    )
  }

  /**
   * Cell 16 · obs K. Value class (reference-underlying) × method with a parameter — the *other*
   * `ValueClassExports.kt` branch (`:145-159`), which drops parameters separately from the
   * primitive-underlying branch cell 15 pins. "no value passed for parameter 'suffix'."
   */
  @Test
  @XFail("ADR-060 cell 16 - value class (reference-underlying) method drops its parameter")
  fun `cell 16 - value class (reference-underlying) method parameter survives export`() {
    val result = Tier1Harness.run(
      """
      package tier1.cell16

      class Patient(val name: String)

      @JvmInline
      value class ChartRef(val patient: Patient) {
        fun label(suffix: String): String = "${'$'}{patient.name}${'$'}suffix"
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected export_chartref_label to compile; got: ${result.compileErrors}",
    )
  }

  /**
   * Cell 17 · obs K. Data-class-constructor parameter × `List<T>`. `parameterizedBy` appears
   * zero times in the processor, so every `bestGuess` site renders a raw `List` — "one type
   * argument expected for 'interface List<out E>'." A plain (non-data) class isolates this from
   * cell 18: `addClassExports`'s primary-constructor export (`:45-65`) runs for any class with a
   * primary constructor, but the `copy()` export only exists for `Modifier.DATA` classes
   * (`:621`), so this fixture alone cannot also trip cell 18.
   */
  @Test
  fun `cell 17 - constructor parameter of type List of String compiles`() {
    val result = Tier1Harness.run(
      """
      package tier1.cell17

      class Visit(val patient: String, val symptoms: List<String>)
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected export_visit_create to compile; got: ${result.compileErrors}",
    )
  }

  /**
   * Cell 18 · obs K. The generated `copy()` (`ClassExports.kt:687`) × `List<T>`. A `data class`
   * with a `List<String>` constructor parameter necessarily also exercises cell 17's raw-List
   * constructor export (a data class's primary constructor is exported the same way as any
   * other class's), so this fixture is red for two overlapping reasons today — which is exactly
   * right: fixing only the constructor-export site (cell 17) must not flip this cell green,
   * since `copy()`'s own raw `List` is a separate code site with its own fix.
   */
  @Test
  fun `cell 18 - generated copy of a data class with a List property compiles`() {
    val result = Tier1Harness.run(
      """
      package tier1.cell18

      data class Visit(val patient: String, val symptoms: List<String>)
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected export_visit_copy to compile; got: ${result.compileErrors}",
    )
  }

  /**
   * Cell 20 · obs K. Class property × `List<T>?`. The non-null `List<T>` property (cell 19,
   * `Cat.nicknames`) escapes only by routing through the handle path; the nullable property
   * getter/setter both fall back to `bestGuess` on the raw declaration name, rendering a
   * parameterless `List` — "one type argument expected for 'interface List<out E>'." A plain
   * class with no `List`-typed constructor parameter isolates this from cell 17.
   */
  @Test
  fun `cell 20 - nullable List of String property compiles`() {
    val result = Tier1Harness.run(
      """
      package tier1.cell20

      class Visit(val patient: String) {
        var notes: List<String>? = null
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected export_visit_get_notes/set_notes to compile; got: ${result.compileErrors}",
    )
  }

  /**
   * BUG-005 (NYTimes-KMP). Data-class constructor parameter × `List<T>`, exercising both the
   * primary-constructor export and the generated `copy()` export in the same fixture. Companion
   * to cells 17/18 above, using the exact `List<String>` shape reported upstream.
   */
  @Test
  fun `data class constructor parameter of type List of String compiles`() {
    val result = Tier1Harness.run(
      """
      package tier1.holder

      data class Holder(val tags: List<String>)
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected export_holder_create/export_holder_copy to compile; got: ${result.compileErrors}",
    )
  }

  /**
   * BUG-005 (NYTimes-KMP). Nullable `List<T>` property getter/setter. Companion to cell 20 above,
   * using a `List<Int>` element type to widen coverage beyond `String`.
   */
  @Test
  fun `nullable List of Int property compiles`() {
    val result = Tier1Harness.run(
      """
      package tier1.box

      class Box {
        var items: List<Int>? = null
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected export_box_get_items/set_items to compile; got: ${result.compileErrors}",
    )
  }

  /**
   * Cell 23 · obs K. Extension function × generic + `inline` + `reified` + `suspend` +
   * `Result<T>`. `NugetProcessor.kt`'s suspend/generic filter only partitions top-level
   * `allFunctions`; `extensionFunctions` are never filtered at all, so this shape reaches
   * `ExtensionFunctionExports.kt` unchanged and fails Kotlin type-checking four different ways at
   * once (raw `Result<T>`, an unresolved reified `T`, a suspend call outside a coroutine, and
   * more) — matches BUG-010 from the NYTimes report exactly.
   *
   * Per ROADMAP.md ("Forward unsupported-declaration diagnostics", MVP.md P1) this cell's
   * correct end state is a **named skip**, not working generated code — the same shape of thing
   * as cell 21 (see [Tier1DiagnosticCellsTest]). It stays a compile-mode assertion here because
   * the skip mechanism does not exist yet: until it lands, "the generated file compiles" is
   * still the right positive statement (once diagnostics land, this construct is never emitted
   * at all, so nothing invalid ever reaches the compiler either).
   */
  @Test
  @XFail(
    "ADR-060 cell 23 - suspend+reified+generic extension function is invalid Kotlin ×4 (BUG-010)",
  )
  fun `cell 23 - suspend inline reified extension function returning Result compiles`() {
    val result = Tier1Harness.run(
      """
      package tier1.cell23

      class Patient(val name: String)

      suspend inline fun <reified T> Patient.chartEntry(entry: T): Result<T> = Result.success(entry)
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected export_patient_chartEntry to compile; got: ${result.compileErrors}",
    )
  }

  /**
   * BUG-005 follow-up (ROADMAP line 61 / MVP.md P0). Class-method return × `List<T>`. Fixing the
   * dropped type argument (`ClassExports.kt`'s `methods.forEach` now renders the return type via
   * [io.github.xxfast.kotlin.native.nuget.processor.exports.toBridgeTypeName]) surfaces a
   * distinct, pre-existing defect one layer in: the same loop's catch-branch fallback,
   * `defaultValueFor(methodReturn)`, treats any `kotlin.`-prefixed qualified name as a primitive
   * and emits the literal `0` (`Helpers.kt`'s `else` branch) — including
   * `kotlin.collections.List` — so the try/catch expression infers `Any` against the
   * now-correctly-declared `List<String>`
   * return: "return type mismatch: expected 'List<String>', actual 'Any'." This is the exact
   * defaultValueFor/no-StableRef-branch mechanism already cataloged (and left unfixed) by cells 6,
   * 10 and 13 above, for a return type this bug's four sites don't reach on their own — out of
   * scope for the type-argument fix; tracked separately.
   *
   * ADR-061 fixed this: the class-method return loop now routes a `List<T>` return through the
   * same object-carrier + StableRef cascade the property getter already uses.
   */
  @Test
  fun `class-method return of type List of String compiles`() {
    val result = Tier1Harness.run(
      """
      package tier1.methodreturn
      class Repo {
        fun tags(): List<String> = listOf("a")
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected export_repo_tags to compile; got: ${result.compileErrors}",
    )
  }

  /**
   * BUG-005 follow-up (ROADMAP line 61 / MVP.md P0). Class-method parameter × `List<T>`. The
   * method-parameter loop in `ClassExports.kt`'s `methods.forEach` still renders the parameter
   * type via `ClassName.bestGuess(type)`, dropping the `String` type argument.
   */
  @Test
  fun `class-method parameter of type List of String compiles`() {
    val result = Tier1Harness.run(
      """
      package tier1.methodparam
      class Repo {
        fun replace(items: List<String>) {}
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected export_repo_replace to compile; got: ${result.compileErrors}",
    )
  }

  /**
   * BUG-005 follow-up (ROADMAP line 61 / MVP.md P0). Companion-method parameter and return, both
   * `List<T>`. `ClassExports.kt`'s companion method loop still renders both via
   * `ClassName.bestGuess`, dropping the type argument.
   */
  @Test
  fun `companion-method parameter and return of type List of String compiles`() {
    val result = Tier1Harness.run(
      """
      package tier1.companiongeneric
      class Factory {
        companion object {
          fun of(items: List<String>): List<String> = items
        }
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected export_factory_companion_of to compile; got: ${result.compileErrors}",
    )
  }

  /**
   * BUG-005 follow-up (ROADMAP line 61 / MVP.md P0). Extension-function parameter and return,
   * both `List<T>`, on a non-primitive receiver (handle path). The parameter half of this fixture
   * is fixed clean by this ticket (`ExtensionFunctionExports.kt` now renders it via
   * [io.github.xxfast.kotlin.native.nuget.processor.exports.toBridgeTypeName]). The return half
   * hits the same distinct, pre-existing `defaultValueFor` fallback defect as the class-method
   * return cell above — `ExtensionFunctionExports.kt`'s non-`Unit` branch has no StableRef
   * branch either, so its catch-path literal for `kotlin.collections.List` is also the wrong
   * `0` — out of scope for the type-argument fix; tracked separately.
   *
   * ADR-061 fixed this: the extension-function return path now routes a `List<T>` return through
   * the same object-carrier + StableRef cascade the class-method position (and the property
   * getter before it) already use.
   */
  @Test
  fun `extension function parameter and return of type List of String compiles`() {
    val result = Tier1Harness.run(
      """
      package tier1.extgeneric
      class Holder
      fun Holder.pick(items: List<String>): List<String> = items
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected export_holder_pick to compile; got: ${result.compileErrors}",
    )
  }

  /**
   * BUG-005 follow-up (ROADMAP line 61 / MVP.md P0). Class-method with its own type parameter
   * (`fun <T> pick(x: T): T`) — unlike generic top-level functions and generic classes,
   * `ClassExports.kt`'s method loop filter (~line 451) does not exclude method-level type
   * parameters, so this shape reaches the same `toBridgeTypeName` call this ticket added to the
   * class-method param/return sites. Guarded so a bare unresolved `T` no longer crashes the KSP
   * run itself the way ADR-060 cell 23's `reified T` did before `toBridgeTypeName`'s
   * `NoSuchElementException` guard (`Helpers.kt`); it still fails to *compile* (an unbound type
   * reference), which is fine — generic-method support is out of scope here.
   */
  @Test
  @XFail(
    "class-method with a method-level type parameter is guarded against crashing the KSP run, " +
        "but the generated reference to the unbound T is still invalid Kotlin; generic-method " +
        "support is out of scope for the type-argument fix",
  )
  fun `class-method with its own type parameter compiles`() {
    val result = Tier1Harness.run(
      """
      package tier1.methodtypeparam
      class Repo {
        fun <T> pick(x: T): T = x
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected export_repo_pick to compile; got: ${result.compileErrors}",
    )
  }
}
