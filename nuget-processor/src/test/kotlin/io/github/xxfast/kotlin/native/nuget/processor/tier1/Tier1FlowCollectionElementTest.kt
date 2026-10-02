package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #127 / ADR-123. A `Flow` or `StateFlow` whose **element** is a Kotlin collection spells
 * that element by running a Kotlin builtin through the user-type namespace mapping, and drops the
 * type argument on the way:
 *
 * ```csharp
 * public KotlinStateFlow<global::Interop.Hub.Kotlin.Collections.Set> Items { get; }
 * ```
 * ```
 * Interop.cs(1176,62): error CS0234: The type or namespace name 'Kotlin' does not exist
 * ```
 *
 * `packNuget` is green with this in it: the failure only shows when a consumer compiles the
 * generated `Interop.cs`. Tier 1 asserts the C# text structurally (ADR-060), so the cells below
 * read the rendered signature rather than compiling it.
 *
 * The gap is visible on one declaration: in `visible` the `List<Kind>` **parameter** is already
 * right (ADR-114) while the element on the same signature is not. So the cells assert both
 * halves of one signature together, and the runtime read alongside the spelling: a fix that only
 * spells the element correctly leaves `FromHandle<IReadOnlyList<T>>` throwing at the first
 * emission.
 *
 * Oreo and Mylo are nodes 1 and 2; node 3 is the empty spot on the windowsill.
 */
class Tier1FlowCollectionElementTest {

  private val fixture: String = """
    package tier1.hub

    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.StateFlow
    import kotlinx.coroutines.flow.flowOf

    // A component that PROJECTS at the seam: crosses as its underlying Int.
    @JvmInline
    value class NodeId(val value: Int)

    // A component that does not project: an ordinary handle, one box per element.
    class Kind(val label: String)

    class Hub {
      // The issue's exact property shape, with a projecting component.
      val items: StateFlow<Set<NodeId>> = MutableStateFlow(setOf(NodeId(1)))

      // The same route with a scalar component, so the cell above cannot pass by treating every
      // element alike.
      val plain: StateFlow<List<String>> = MutableStateFlow(listOf("Oreo"))

      // The third kind, two type arguments on one element.
      val counts: StateFlow<Map<String, Int>> = MutableStateFlow(mapOf("Oreo" to 4))

      // A plain Flow rather than a StateFlow, with a handle component.
      val ticks: Flow<List<Kind>> = flowOf(listOf(Kind("nap")))

      // The method route: a collection parameter (already right) and a collection element.
      fun visible(kinds: List<Kind>): StateFlow<List<NodeId>> =
        MutableStateFlow(kinds.map { NodeId(it.label.length) })

      // A plain Flow-returning *method*, which the renderer builds separately from both the Flow
      // property above and the StateFlow method beside it.
      fun tracked(): Flow<List<String>> = flowOf(listOf("Oreo"))

      // Refusal: a generic element with no wire shape here.
      val paired: StateFlow<Pair<String, Int>> = MutableStateFlow("Oreo" to 9)

      // Refusal by the nullable rule ADR-114 and ADR-119 already apply on their routes.
      val maybe: StateFlow<List<String>?> = MutableStateFlow(null)

      // Control: a scalar element must be untouched by any of this.
      val name: StateFlow<String> = MutableStateFlow("Oreo")
    }
  """.trimIndent()

  private fun run(): Tier1Result = Tier1Harness.run(
    fixture,
    fileName = "Hub.kt",
    processorOptions = mapOf("nuget.rootPackage" to "tier1"),
    libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
  )

  /**
   * The headline cell, and the whole of issue #127: the element carries its type argument, spelled
   * exactly as the parameter route on the same class spells the same Kotlin type, and never
   * through the root namespace.
   */
  @Test
  fun `a state flow of a set renders IReadOnlySet of the qualified element`() {
    val result = run()

    val missing: List<String> = listOf(
      "public KotlinStateFlow<IReadOnlySet<global::Interop.Hub.NodeId>> Items",
      "public KotlinStateFlow<IReadOnlyList<string>> Plain",
      "public KotlinStateFlow<IReadOnlyDictionary<string, int>> Counts",
      "public KotlinFlow<IReadOnlyList<global::Interop.Hub.Kind>> Ticks",
      // Control: the scalar element is unchanged.
      "public KotlinStateFlow<string> Name",
    ).filterNot(result.generatedCSharp::contains)

    assertTrue(
      missing.isEmpty(),
      "expected each flow element to carry its type argument, spelled as the ordinary routes " +
          "spell it; missing: $missing; got: ${csharpLinesFor(result, "Kotlin")}",
    )
    assertFalse(
      result.generatedCSharp.contains("Kotlin.Collections"),
      "expected no Kotlin builtin run through the root-package namespace mapping; got: " +
          "${csharpLinesFor(result, "Kotlin.Collections")}",
    )
  }

  /**
   * Requirement 3: the element is materialised through the same `nuget_list_*` / `nuget_set_*`
   * helpers the property route uses, never through `FromHandle<IReadOnlyList<T>>`, which has no
   * collection branch and would throw at the first emission.
   */
  @Test
  fun `a flow collection element is read through the existing collection helpers`() {
    val result = run()

    val missing: List<String> = listOf(
      "read: static h => NugetMarshal.ReadSet<global::Interop.Hub.NodeId>(h",
      "read: static h => NugetMarshal.ReadList<string>(h",
      "read: static h => NugetMarshal.ReadMap<string, int>(h",
      "read: static h => NugetMarshal.ReadList<global::Interop.Hub.Kind>(h",
    ).filterNot(result.generatedCSharp::contains)

    assertTrue(
      result.generatedCSharp.contains("public KotlinFlow<IReadOnlyList<string>> Tracked()"),
      "expected a plain Flow-returning method to bind too: it is a third construction site, " +
          "built by neither the property branch nor the StateFlow method branch; got: " +
          "${csharpLinesFor(result, "Tracked")}",
    )

    assertTrue(
      missing.isEmpty(),
      "expected a per-member read lambda over NugetMarshal.Read*; missing: $missing; got: " +
          "${csharpLinesFor(result, "read:")}",
    )
    assertTrue(
      result.generatedCSharp.contains("_read = read ?? NugetMarshal.FromHandle<T>;"),
      "expected the read delegate to default to FromHandle<T>, so every non-collection element " +
          "keeps its shipped behaviour; got: ${csharpLinesFor(result, "_read")}",
    )
  }

  /**
   * The method route, both halves of one signature. The issue is that these two disagreed while
   * naming the same Kotlin type constructor, so they are asserted in one string.
   */
  @Test
  fun `a flow method return renders its element and its parameter with one spelling`() {
    val result = run()

    assertTrue(
      result.generatedCSharp.contains(
        "public KotlinStateFlow<IReadOnlyList<global::Interop.Hub.NodeId>> Visible(" +
            "IReadOnlyList<global::Interop.Hub.Kind> kinds)"
      ),
      "expected the element and the parameter on Visible to agree; got: " +
          "${csharpLinesFor(result, "Visible")}",
    )
  }

  /**
   * The Kotlin half. A value-class component has to leave as its underlying (ADR-081) or the C#
   * per-element `FromHandle<int>` read decodes a `NodeId` box as an int. `NugetHandles.retain`
   * stays the only mint, so the ADR-120 counter still sees it.
   */
  @Test
  fun `a projecting flow element component is projected in the Kotlin emission`() {
    val result = run()

    val missing: List<String> = listOf(
      "NugetHandles.retain(value.mapTo(mutableSetOf()) { it.value } as Any)",
      "NugetHandles.retain(obj.items.value.mapTo(mutableSetOf()) { it.value } as Any)",
    ).filterNot(result.generated::contains)

    assertTrue(
      missing.isEmpty(),
      "expected the value-class component to be projected per element on both the collect and " +
          "the value export; missing: $missing; got: " +
          "${result.generated.lines().filter { it.contains("mapTo") }.map(String::trim)}",
    )
    assertTrue(
      result.generated.contains("NugetHandles.retain(value as Any)"),
      "expected a non-projecting element to keep its shipped, unprojected box",
    )
  }

  /**
   * Requirement 5: an element the route cannot marshal is absent from **both** halves and named,
   * so a C# import never arrives with no Kotlin export behind it, and a real project reading the
   * build log learns what to change.
   */
  @Test
  fun `a refused flow element skips the member named on both halves`() {
    val result = run()

    assertFalse(
      result.generatedCSharp.contains("Paired"),
      "expected no Paired member: a Pair<String, Int> element has no wire shape here; got: " +
          "${csharpLinesFor(result, "Paired")}",
    )
    assertFalse(
      result.generated.contains("hub_get_paired_collect"),
      "expected no hub_get_paired_collect export either; got: " +
          "${result.generated.lines().filter { it.contains("paired") }.map(String::trim)}",
    )

    val diagnostic: String? = result.kspWarnings.firstOrNull {
      it.contains("[nuget:${ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY.name}]") &&
          it.contains("paired")
    }
    assertTrue(
      diagnostic != null,
      "expected a SKIPPED_UNSUPPORTED_PROPERTY naming Hub.paired rather than a silent vanish; " +
          "kspWarnings=${result.kspWarnings}",
    )
    assertTrue(
      diagnostic.contains("Pair<String, Int>"),
      "expected the diagnostic to quote the author's own spelling; got: $diagnostic",
    )
  }

  /** ADR-114 refuses a nullable collection parameter; the element position keeps the same rule. */
  @Test
  fun `a nullable collection flow element skips the member named`() {
    val result = run()

    assertFalse(
      result.generatedCSharp.contains("Maybe"),
      "expected no Maybe member for a StateFlow<List<String>?>; got: " +
          "${csharpLinesFor(result, "Maybe")}",
    )
    assertTrue(
      result.kspWarnings.any {
        it.contains("[nuget:${ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY.name}]") &&
            it.contains("maybe") && it.contains("List<String>?")
      },
      "expected a SKIPPED_UNSUPPORTED_PROPERTY naming Hub.maybe and quoting `List<String>?`; " +
          "kspWarnings=${result.kspWarnings}",
    )
  }

  /** The generated Kotlin has to keep compiling with the projection and the filters applied. */
  @Test
  fun `the generated Kotlin still compiles`() {
    val result = run()

    assertTrue(
      result.compiledClean,
      "expected clean generated Kotlin for collection elements on the flow routes; got: " +
          "${result.compileErrors}",
    )
  }

  // ADR-127 deleted the helper-gate cell that stood here. The `nuget_list_*` / `nuget_map_*` /
  // `nuget_set_*` exports now ship unconditionally from the `:nuget-runtime` klib, so there is no
  // gate left to miss a route and no declaration of them in the generated file.
  // `scripts/verify-runtime-exports.sh` checks the 67 names on the linked binary instead.

  /**
   * ADR-068's `suspend fun` returning `StateFlow<T>` reads every element through the module-wide
   * `nuget_stateflow_value` export, which has no per-member projection seam, so a collection
   * element is refused there rather than half-bound. Before this ADR it rendered
   * `Task<KotlinStateFlow<global::Interop.Sill.Kotlin.Collections.List>>`.
   */
  @Test
  fun `a collection element on the suspend state flow route skips the member named`() {
    val result = Tier1Harness.run(
      """
      package tier1.awaited

      import kotlinx.coroutines.flow.MutableStateFlow
      import kotlinx.coroutines.flow.StateFlow

      class Desk {
        suspend fun roster(): StateFlow<List<String>> = MutableStateFlow(listOf("Oreo"))
        suspend fun lead(): StateFlow<String> = MutableStateFlow("Mylo")
      }
      """.trimIndent(),
      fileName = "Desk.kt",
      processorOptions = mapOf("nuget.rootPackage" to "tier1"),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertFalse(
      result.generatedCSharp.contains("RosterAsync"),
      "expected no RosterAsync: the ADR-068 route has no per-member projection seam; got: " +
          "${csharpLinesFor(result, "Roster")}",
    )
    assertTrue(
      result.generatedCSharp.contains("public Task<KotlinStateFlow<string>> LeadAsync("),
      "expected a scalar element on that route to be untouched; got: " +
          "${csharpLinesFor(result, "Lead")}",
    )
    assertTrue(
      result.kspWarnings.any {
        it.contains("[nuget:${ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN.name}]") &&
            it.contains("roster")
      },
      "expected a SKIPPED_UNSUPPORTED_RETURN naming Desk.roster; kspWarnings=${result.kspWarnings}",
    )
  }

  private fun csharpLinesFor(result: Tier1Result, needle: String): List<String> =
    result.generatedCSharp.lines().filter { it.contains(needle) }.map(String::trim)
}
