package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #111. The legacy lambda routes map each of a lambda's type arguments through
 * `arg.type?.resolve()?.declaration?.simpleName?.asString() ?: "object"`, which drops both the
 * argument's own type arguments and its namespace:
 *
 * ```kotlin
 * val onStream: suspend (CamId) -> Flow<Snapshot>
 * ```
 * ```csharp
 * public KotlinSuspendFunc<CamId, Flow> OnStreamAsync => ...
 * ```
 * ```
 * Interop.cs(7572,37): error CS0246: The type or namespace name 'Flow' could not be found
 * ```
 *
 * `packNuget` is green either way; the defect only surfaces when the generated `Interop.cs` is
 * compiled, which is why it needs a structural Tier 1 cell per route rather than one end-to-end
 * assertion.
 *
 * **Two outcomes, never one.** Qualifying alone does not fix the report: `Flow<Snapshot>` spelled
 * `global::Interop.Kotlinx.Coroutines.Flow.Flow` is still CS0246, because nothing declares it.
 * So a type argument C# can name is **qualified**, and one it cannot is **skipped named**: the
 * member is absent, with a `SKIPPED_UNSUPPORTED_PROPERTY` / `SKIPPED_UNSUPPORTED_RETURN` behind
 * it. Both halves are asserted for every route below, because a route that only learned one half
 * is still broken, just differently.
 *
 * **Every route gets its own cell**, because each is a separate hand-written copy of the same
 * expression and a fix applied to one is invisible to the others:
 * - `CirClassTranslator.kt:340` ordinary class property, lambda
 * - `CirClassTranslator.kt:352` ordinary class property, **suspend** lambda
 * - `CirClassTranslator.kt:1118` sealed subclass property, lambda (the one position where the
 *   residual legacy route still runs after ADR-111)
 * - `CirFunctionTranslator.kt:134` top-level function, lambda return
 * - `CirFunctionTranslator.kt:450` top-level function, **generic** return, which is not a lambda
 *   at all but carries the identical expression, so it has the identical defect
 *
 * The fixture spans two packages so the qualification is genuinely exercised: `tier1.catcam.lens`
 * renders as the C# namespace `Interop.Catcam.Lens` while everything referring to it lives in
 * `Interop.Catcam`. A same-package fixture would compile with the bare spelling and prove
 * nothing.
 *
 * `nuget.rootPackage` is set for exactly that reason (it is what makes `mapPackageToNamespace`
 * produce two namespaces instead of one), and `kotlinx-coroutines-core` is on the KSP `libraries`
 * path because without it `Flow` resolves to `<ERROR TYPE: Flow>` and the fixture would be
 * asserting on an unresolved type rather than on a real one.
 *
 * The cam watches Oreo (black, white bib) and Mylo (brown and creamy). Neither of them moves.
 */
class Tier1LambdaTypeArgumentTest {

  private val fixture: Map<String, String> = mapOf(
    "Lens.kt" to """
      package tier1.catcam.lens

      class CamId(val value: String)

      class Snapshot(val caption: String)
    """.trimIndent(),
    "CatCam.kt" to """
      package tier1.catcam

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.flowOf
      import tier1.catcam.lens.CamId
      import tier1.catcam.lens.Snapshot

      class Kennel {
        class Bunk(val level: Int)
      }

      class CatCam(val watching: String) {
        val onPick: (CamId) -> Snapshot = { id -> Snapshot(id.value) }
        val onStream: (CamId) -> Flow<Snapshot> = { id -> flowOf(Snapshot(id.value)) }
        val onStreamAsync: suspend (CamId) -> Flow<Snapshot> = { id -> flowOf(Snapshot(id.value)) }
        val onBunk: (CamId) -> Kennel.Bunk = { Kennel.Bunk(1) }
      }

      sealed class CamFeed {
        data class Live(val label: String) : CamFeed() {
          val onPick: (CamId) -> Snapshot = { id -> Snapshot(id.value) }
          val onStream: (CamId) -> Flow<Snapshot> = { id -> flowOf(Snapshot(id.value)) }
        }

        data class Off(val reason: String) : CamFeed()
      }

      class Crate<T>(val item: T)

      fun camPicker(): (CamId) -> Snapshot = { id -> Snapshot(id.value) }

      fun camStreamer(): (CamId) -> Flow<Snapshot> = { id -> flowOf(Snapshot(id.value)) }

      fun crateOfSnapshot(): Crate<Snapshot> = Crate(Snapshot("boxed"))

      fun <T> crateOf(item: T): Crate<T> = Crate(item)
    """.trimIndent(),
  )

  private val qualifiedPick: String =
    "KotlinFunc<global::Interop.Catcam.Lens.CamId, global::Interop.Catcam.Lens.Snapshot>"

  private fun run(): Tier1Result = Tier1Harness.run(
    fixture,
    processorOptions = mapOf("nuget.rootPackage" to "tier1"),
    // Without coroutines on the KSP libraries path `Flow` resolves to `<ERROR TYPE: Flow>`, and
    // the skip cells would be asserting about an unresolved type instead of about `Flow`.
    libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
  )

  /**
   * Both class-property arms at once (`:340` lambda, `:1118` sealed subclass) plus the top-level
   * function arm (`CirFunctionTranslator.kt:134`), because the expressible cell renders the same
   * text on all three and asserting them together is what makes "one copy got fixed" visible.
   */
  @Test
  fun `an expressible lambda type argument is qualified at every route`() {
    val result = run()

    val onPick: List<String> = memberLines(result, "OnPick")
    assertEquals(
      2, onPick.size,
      "expected an OnPick on both the ordinary class and the sealed subclass; got: $onPick",
    )
    onPick.forEach { line ->
      assertTrue(
        line.contains(qualifiedPick),
        "expected the lambda's exported type arguments to be namespace-qualified " +
            "($qualifiedPick); got: ${line.trim()}",
      )
    }

    assertTrue(
      result.generatedCSharp.contains("public static $qualifiedPick CamPicker()"),
      "expected the top-level lambda-returning function to qualify its type arguments too; " +
          "got: ${result.generatedCSharp.lines().filter { it.contains("CamPicker") }}",
    )
  }

  /**
   * The reported repro, at the ordinary-class property arm (`:340`). `Flow<Snapshot>` has no C#
   * spelling on this route: qualifying it would name `global::Interop.Kotlinx.Coroutines.Flow
   * .Flow`, which nothing declares, so the member must be absent instead.
   */
  @Test
  fun `a Flow lambda type argument skips the property named`() {
    val result = run()

    val ordinary: List<String> = memberLines(result, "OnStream").filterNot(::isSealedGetter)
    assertTrue(
      ordinary.isEmpty(),
      "expected no OnStream member on the ordinary class; got: $ordinary",
    )
    assertSkipDiagnostic(
      result, ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY,
      "CatCam.onStream", "kotlinx.coroutines.flow.Flow",
    )
  }

  /**
   * The `suspend` twin, through `:352`. It exists so the two copies of the arm cannot silently
   * diverge: a fix applied to the plain-lambda copy alone leaves this one emitting
   * `KotlinSuspendFunc<CamId, Flow>`.
   */
  @Test
  fun `a Flow suspend-lambda type argument skips the property named`() {
    val result = run()

    val members: List<String> = memberLines(result, "OnStreamAsync")
    assertTrue(members.isEmpty(), "expected no OnStreamAsync member; got: $members")
    assertSkipDiagnostic(
      result, ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY,
      "CatCam.onStreamAsync", "kotlinx.coroutines.flow.Flow",
    )
  }

  /** The sealed subclass arm (`:1118`), skip half. */
  @Test
  fun `a Flow lambda type argument skips a sealed subclass property named`() {
    val result = run()

    val sealedSubclass: List<String> = memberLines(result, "OnStream").filter(::isSealedGetter)
    assertTrue(
      sealedSubclass.isEmpty(),
      "expected no OnStream member on CamFeed.Live either; got: $sealedSubclass",
    )
    assertSkipDiagnostic(
      result, ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY,
      "Live.onStream", "kotlinx.coroutines.flow.Flow",
    )
    assertTrue(
      result.generatedCSharp.contains("public string Label"),
      "control: the sealed subclass's ordinary property must keep binding",
    )
  }

  /**
   * The top-level function arm (`CirFunctionTranslator.kt:134`), skip half. A function site is a
   * return position, so the diagnostic kind differs from the property arms.
   */
  @Test
  fun `a Flow lambda return type argument skips the function named`() {
    val result = run()

    assertFalse(
      result.generatedCSharp.contains("CamStreamer"),
      "expected no CamStreamer member; got: " +
          "${result.generatedCSharp.lines().filter { it.contains("CamStreamer") }}",
    )
    assertSkipDiagnostic(
      result, ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN,
      "camStreamer", "kotlinx.coroutines.flow.Flow",
    )
  }

  /**
   * The other unnameable shape: a type argument that is a perfectly ordinary class but is never
   * *declared* in C#. `Kennel.Bunk` is nested, and only top-level declarations are emitted, so no
   * spelling of it resolves. Same outcome as the `Flow` cells, reached by a different predicate.
   *
   * The diagnostic *kind* is deliberately not pinned here: the repo already routes an undeclared
   * nested type through `SKIPPED_UNSUPPORTED_TYPE` with a bespoke hint elsewhere, and either that
   * or `SKIPPED_UNSUPPORTED_PROPERTY` satisfies "skip named". What is pinned is that the member
   * vanishes and something names it.
   */
  @Test
  fun `an undeclared nested lambda type argument skips the property named`() {
    val result = run()

    val members: List<String> = memberLines(result, "OnBunk")
    assertTrue(members.isEmpty(), "expected no OnBunk member; got: $members")
    assertTrue(
      result.kspWarnings.any { it.contains("[nuget:SKIPPED_") && it.contains("CatCam.onBunk") },
      "expected onBunk to skip named rather than silently; kspWarnings=${result.kspWarnings}",
    )
  }

  /**
   * `CirFunctionTranslator.kt:450`. Not a lambda route at all: it is the *generic return* route,
   * and it carries a byte-identical copy of the same `simpleName` expression, so
   * `fun crateOfSnapshot(): Crate<Snapshot>` renders `Crate<Snapshot>` with a bare argument from
   * a namespace that does not contain it. Listing this site with the lambda ones and then not
   * crossing it would leave the fifth copy free to keep the bug.
   *
   * [crateOf] is the control that stops the obvious over-fix: a type *parameter* must keep
   * rendering as `T`, never as `global::Interop.Catcam.T`.
   */
  @Test
  fun `a generic return type argument is qualified and a type parameter is not`() {
    val result = run()

    assertTrue(
      result.generatedCSharp.contains(
        "public static Crate<global::Interop.Catcam.Lens.Snapshot> CrateOfSnapshot()"
      ),
      "expected the generic return's exported type argument to be namespace-qualified; got: " +
          "${result.generatedCSharp.lines().filter { it.contains("CrateOfSnapshot") }}",
    )
    assertTrue(
      result.generatedCSharp.contains("public static Crate<T> CrateOf<T>(T item)"),
      "control: a type parameter is not a declaration and must never be qualified; got: " +
          "${result.generatedCSharp.lines().filter { it.contains("CrateOf<") }}",
    )
  }

  /** The Kotlin half is untouched by any of this: it hands back a `COpaquePointer` either way. */
  @Test
  fun `the generated Kotlin still compiles`() {
    val result = run()

    assertTrue(result.compiledClean, "expected clean generated Kotlin; got: ${result.compileErrors}")
  }

  /** The generated C# property declaration lines for [member], if it was emitted at all. */
  private fun memberLines(result: Tier1Result, member: String): List<String> =
    result.generatedCSharp.lines().filter { it.contains(" $member =>") }.map(String::trim)

  /**
   * The sealed-subclass route (`CirClassTranslator.kt:1118`) is the only one whose getter swallows
   * the error slot, so `out _` is what separates `CamFeed.Live.OnStream` from `CatCam.OnStream`
   * in a file-wide scan. Without it, a fix applied to only one of the two arms would fail both
   * cells and hide which arm is actually broken.
   */
  private fun isSealedGetter(line: String): Boolean = line.contains("out _")

  private fun assertSkipDiagnostic(
    result: Tier1Result,
    kind: ForwardDiagnosticKind,
    declaration: String,
    typeArgument: String,
  ) {
    val diagnostic: String = requireNotNull(
      result.kspWarnings.firstOrNull {
        it.contains("[nuget:${kind.name}]") && it.contains(declaration)
      },
    ) {
      "expected a ${kind.name} naming $declaration; kspWarnings=${result.kspWarnings}"
    }
    assertTrue(
      diagnostic.contains(typeArgument),
      "expected the $declaration diagnostic to name the offending type argument " +
          "($typeArgument), so the author knows which one to change; got: $diagnostic",
    )
  }
}
