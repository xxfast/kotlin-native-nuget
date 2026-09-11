package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #109 / ADR-114. A collection-typed parameter on a `Flow`/`StateFlow`-returning or
 * `suspend` member is spelled by pasting the declaration's own Kotlin type name through
 * `ClassName.bestGuess`, which drops the type arguments, so the generated `CNameExports.kt` does
 * not compile at all:
 *
 * ```kotlin
 * @CName("treatboard_served_collect")
 * public fun export_treatboard_served_collect(
 *   handle: COpaquePointer, scopeHandle: COpaquePointer, kinds: List, ...
 * ```
 * ```
 * e: CNameExports.kt:97:10 One type argument expected for 'interface List<out E> : Collection<E>'.
 * e: CNameExports.kt:342:8 One type argument expected for 'interface Set<out E> : Collection<E>'.
 * ```
 *
 * `packNuget` dies at that compile, so the whole package fails to build, not just the offending
 * member. Tier 1 is where that is cheapest to see: [Tier1Result.compiledClean] is the same
 * compiler's verdict on the same file, without a native link.
 *
 * **Three routes, asserted separately, because they are three hand-written copies of the same
 * mistake and they have drifted before:**
 * - `_collect` (`ClassExports.kt:281`, parameters via `addFlowParameters` at `:283-290`)
 * - `_value` (`ClassExports.kt:317`, same `addFlowParameters`)
 * - `_async` (`SuspendFunctionExports.kt:76-81`, a `suspend` class method)
 *
 * The top-level `suspend` route is included too, and is broken *differently*: it goes through
 * `addParameters` / `toBridgeTypeName`, which preserves type arguments (the BUG-005 fix), so it
 * emits `ids: Set<String>` and compiles. It still hands C# `ForgetAllAsync(IntPtr ids)`, a public
 * parameter no caller can produce, so it needs the same handle treatment.
 *
 * **Component variety**, because the wire container boxes each element through its own
 * projection: `List<String>` needs conversion at the seam, `List<Int>` does not, and
 * `List<Treat>` crosses as a handle.
 *
 * **The refusal arm stays** (ADR-114 alternative 4, narrowed). ADR-114 marshals collections and
 * refuses every *other* generic parameter on these routes by name rather than emitting
 * non-compiling Kotlin: [TreatBoard.paired] takes a `Pair<String, Int>` on a flow-returning
 * member, which emits `entry: Pair` today and is the same build break.
 *
 * Oreo (black with the white bib) and Mylo (brown and creamy) audit the treat board nightly. It
 * has never once balanced.
 */
class Tier1LegacyRouteCollectionParameterTest {

  private val fixture: String = """
    package tier1.treats

    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.StateFlow
    import kotlinx.coroutines.flow.asFlow

    class Treat(val label: String)

    class TreatBoard {
      private val _served: MutableStateFlow<String> = MutableStateFlow("biscuit")

      // _collect and _value: StateFlow return, List<String> parameter, component needs conversion.
      fun served(kinds: List<String>): StateFlow<String> =
        MutableStateFlow(kinds.joinToString(", "))

      // _collect and _value: List<Int> parameter, component needs no conversion at the seam.
      fun rations(portions: List<Int>): StateFlow<Int> = MutableStateFlow(portions.sum())

      // _collect only: plain Flow return with a List<String> parameter.
      fun servings(kinds: List<String>): Flow<String> = kinds.asFlow()

      // _collect with an exported-class element, so the component crosses as a handle.
      fun feeding(treats: List<Treat>): Flow<String> = treats.map { it.label }.asFlow()

      // _async: issue #109's second shape verbatim, a Set parameter on a suspend class method.
      suspend fun forget(ids: Set<String>): Int = ids.size

      // _async with a List parameter, so the suspend route crosses both kinds.
      suspend fun tally(portions: List<Int>): Int = portions.sum()

      // Refusal arm: a generic parameter that is not a collection, on a flow-returning member.
      fun paired(entry: Pair<String, Int>): Flow<String> = listOf(entry.first).asFlow()

      // Control: no collection parameter at all, must be unaffected by any of this.
      fun servedAll(): StateFlow<String> = _served
    }

    // The top-level suspend route, broken differently: it compiles, and lands IntPtr in C#.
    suspend fun forgetAll(ids: Set<String>): Int = ids.size
  """.trimIndent()

  private fun run(): Tier1Result = Tier1Harness.run(
    fixture,
    fileName = "TreatBoard.kt",
    processorOptions = mapOf("nuget.rootPackage" to "tier1"),
    // Without coroutines on the KSP libraries path `Flow` resolves to `<ERROR TYPE: Flow>` and
    // none of these members would take the legacy routes at all.
    libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
  )

  /**
   * The headline cell, and the whole of issue #109: the generated Kotlin has to compile. Pre-fix
   * this is `One type argument expected for 'interface List<out E>'` (and the `Set` twin), which
   * is `packNuget` dying on any module that declares these shapes.
   */
  @Test
  fun `a collection parameter on a legacy route still generates compiling Kotlin`() {
    val result = run()

    assertTrue(
      result.compiledClean,
      "expected clean generated Kotlin for collection parameters on the flow and suspend " +
          "routes; got: ${result.compileErrors}",
    )
  }

  /**
   * The Kotlin half of the ownership model (ADR-114 alternative 1): the parameter is declared
   * `COpaquePointer`, never the collection type. Spelling it `kotlin.collections.List<String>`
   * would also compile, and would be the wrong fix: `@CName` hands a Kotlin collection across as
   * a pinned `kref` struct while C# declares `IntPtr`, trading a loud build failure for a silent
   * memory-safety hazard (ADR-114 "The trap: fixing only the spelling").
   */
  @Test
  fun `a collection parameter crosses as an opaque handle on every legacy route`() {
    val result = run()

    val wrong: List<String> = listOf(
      // _collect
      "treatboard_served_collect" to "kinds",
      "treatboard_rations_collect" to "portions",
      "treatboard_servings_collect" to "kinds",
      "treatboard_feeding_collect" to "treats",
      // _value
      "treatboard_served_value" to "kinds",
      "treatboard_rations_value" to "portions",
      // _async, class method and top level
      "treatboard_forget_async" to "ids",
      "treatboard_tally_async" to "portions",
      "forgetAll_async" to "ids",
    ).filterNot { (export, param) ->
      exportSignature(result, export).contains("$param: COpaquePointer")
    }.map { (export, param) -> "$export($param)" }

    assertTrue(
      wrong.isEmpty(),
      "expected every collection parameter to be declared COpaquePointer (ADR-114: the wire " +
          "container is a handle to MutableList<Any?>/MutableSet<Any?>); these were not: $wrong",
    )
  }

  /**
   * The C# half. `mapParamType` returns `IntPtr` for anything outside its 13-entry
   * primitive/`String` table, so today every one of these members takes an `IntPtr` a C# caller
   * has no way to produce. The public signature must be the collection; only the `DllImport`
   * stays `IntPtr`.
   */
  @Test
  fun `a collection parameter is spelled as a collection in the public C# signature`() {
    val result = run()

    val missing: List<String> = listOf(
      // _collect + _value on one member
      "public KotlinStateFlow<string> Served(IReadOnlyList<string> kinds)",
      "public KotlinStateFlow<int> Rations(IReadOnlyList<int> portions)",
      // _collect alone
      "public KotlinFlow<string> Servings(IReadOnlyList<string> kinds)",
      // _async, class method: Set and List
      "public Task<int> ForgetAsync(IReadOnlySet<string> ids",
      "public Task<int> TallyAsync(IReadOnlyList<int> portions",
      // _async, top level: compiles today, still lands IntPtr in C#
      "public static Task<int> ForgetAllAsync(IReadOnlySet<string> ids",
    ).filterNot(result.generatedCSharp::contains)

    assertTrue(
      missing.isEmpty(),
      "expected the public C# signature to take the collection rather than IntPtr; missing: " +
          "$missing; got: ${csharpSignatures(result)}",
    )
  }

  /**
   * The object-element cell. `List<Treat>` boxes each element as a handle rather than through a
   * `nuget_wrap_*` value, so it is a different projection from the `string`/`int` cells above and
   * a fix that only handled boxed primitives would leave it broken.
   */
  @Test
  fun `a collection of an exported class keeps its element type in C#`() {
    val result = run()

    assertTrue(
      result.generatedCSharp.contains(
        "public KotlinFlow<string> Feeding(IReadOnlyList<global::Interop.Treats.Treat> treats)"
      ),
      "expected the object-element collection to keep its (qualified) element type; got: " +
          "${csharpLinesFor(result, "Feeding")}",
    )
  }

  // ADR-127 deleted the helper-gate cell that stood here. The `nuget_list_*` / `nuget_map_*` /
  // `nuget_set_*` exports now ship unconditionally from the `:nuget-runtime` klib, so there is no
  // gate left to miss a route and no declaration of them in the generated file.
  // `scripts/verify-runtime-exports.sh` checks the 66 names on the linked binary instead.

  /**
   * The refusal arm ADR-114 keeps: a generic parameter that is not a supported collection must
   * skip *named*, not emit `entry: Pair`. The gate is "the type has type arguments", so the two
   * outcomes are marshal-it or refuse-it-by-name, never emit-it-broken.
   */
  @Test
  fun `a non-collection generic parameter on a legacy route skips the member named`() {
    val result = run()

    assertFalse(
      result.generatedCSharp.contains("Paired"),
      "expected no Paired member: a Pair<String, Int> parameter has no wire shape here; got: " +
          "${csharpLinesFor(result, "Paired")}",
    )
    assertFalse(
      result.generated.contains("treatboard_paired_collect"),
      "expected no _paired_collect export either; got: " +
          "${result.generated.lines().filter { it.contains("paired") }.map(String::trim)}",
    )

    val diagnostic: String? = result.kspWarnings.firstOrNull {
      it.contains("[nuget:${ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT.name}]") &&
          it.contains("paired")
    }
    assertTrue(
      diagnostic != null,
      "expected a SKIPPED_UNSUPPORTED_INPUT naming TreatBoard.paired rather than a silent " +
          "vanish; kspWarnings=${result.kspWarnings}",
    )
    assertTrue(
      diagnostic.contains("Pair"),
      "expected the diagnostic to name the offending type, so the author knows what to change; " +
          "got: $diagnostic",
    )
  }

  /**
   * Control. Neither the marshalling nor the refusal above may cost the rest of the class
   * anything: a parameterless StateFlow member on the same class keeps binding exactly as today.
   */
  @Test
  fun `a parameterless flow member on the same class keeps binding`() {
    val result = run()

    assertTrue(
      result.generatedCSharp.contains("public KotlinStateFlow<string> ServedAll()"),
      "control: a flow member with no collection parameter must be untouched; got: " +
          "${csharpLinesFor(result, "ServedAll")}",
    )
  }

  /** The declared parameter list of the `@CName`-named export, up to its return type. */
  private fun exportSignature(result: Tier1Result, export: String): String {
    val generated: String = result.generated
    val start: Int = generated.indexOf("@CName(\"$export\")")
    require(start >= 0) {
      "no @CName(\"$export\") in the generated Kotlin; exports present: " +
          generated.lines().filter { it.contains("@CName(") }.map(String::trim)
    }
    return generated.substring(start).substringBefore("): ")
  }

  private fun csharpLinesFor(result: Tier1Result, member: String): List<String> =
    result.generatedCSharp.lines().filter { it.contains(member) }.map(String::trim)

  private fun csharpSignatures(result: Tier1Result): List<String> =
    result.generatedCSharp.lines()
      .map(String::trim)
      .filter { it.startsWith("public ") && it.contains("(") }
}
