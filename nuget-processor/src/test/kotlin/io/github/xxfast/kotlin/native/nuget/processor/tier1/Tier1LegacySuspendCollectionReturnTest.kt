package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #122 / ADR-119. A `suspend` member returning `List<T>` renders the type argument away, as
 * bare `List`, on the C# half of the legacy suspend route:
 *
 * ```csharp
 * public Task<List> FetchAsync(int limit, int offset)
 * {
 *     var tcs = new TaskCompletionSource<List>(TaskCreationOptions.RunContinuationsAsynchronously);
 * ```
 * ```
 * Interop.cs(12626,21): error CS0305: Using the generic type 'List<T>' requires 1 type arguments
 * ```
 *
 * `packNuget` is green with this in it: the failure only shows when the generated `Interop.cs` is
 * compiled by a consumer. Tier 1 asserts the C# text structurally (ADR-060), so the cells below
 * read the rendered signature rather than compiling it.
 *
 * The precedent is on the same class: the **property** route spells the same `List<Member>` as
 * `IReadOnlyList<global::Interop.Roster.Member>` and already reads it through the `nuget_list_*`
 * helpers. ADR-119 is ADR-114's return-side twin: a `List`/`Set`/`Map` return crosses as a handle
 * to the same boxed wire container the ordinary route uses, and every other generic return skips
 * *named* (`SKIPPED_UNSUPPORTED_RETURN`) rather than emitting a C# member that cannot compile.
 *
 * Three owners, because the suspend route has three composition sites: a sealed arm (the issue's
 * shape verbatim), an ordinary class, and a top-level function.
 *
 * Oreo runs the roster; Mylo is on it, and would like that reflected in the headcount.
 */
class Tier1LegacySuspendCollectionReturnTest {

  private val fixture: String = """
    package tier1.roster

    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.flowOf

    sealed class Assignment

    data class Member(val id: Int)

    // The issue's shape verbatim: both spellings on one class, for the same element type.
    data class Existing(val members: List<Member>) : Assignment() {
      suspend fun fetch(limit: Int, offset: Int): List<Member> = members.drop(offset).take(limit)
    }

    class Roster(val names: List<String>) {
      // The three kinds, on an ordinary class, with components that need no seam conversion.
      suspend fun tags(): List<String> = names
      suspend fun ids(): Set<Int> = names.indices.toSet()
      suspend fun ages(): Map<String, Int> = names.associateWith { it.length }

      // The refusal arm: a generic return that is not a supported collection.
      suspend fun paired(): Pair<String, Int> = names.first() to 1

      // ADR-119 amendment: nullable collection returns bind as `Task<...?>`.
      suspend fun maybe(): List<String>? = null
      suspend fun maybeAges(): Map<String, Int>? = null

      // ADR-119 amendment: a collection of the sealed base, at a suspend return and a Flow element.
      suspend fun assignments(): List<Assignment> = listOf(Existing(emptyList()))
      fun rotation(): Flow<List<Assignment>> = flowOf(listOf(Existing(emptyList())))

      // Control: a scalar suspend return must be unaffected by any of this.
      suspend fun count(): Int = names.size
    }

    // The top-level suspend route is a third copy of the same composition.
    suspend fun everyone(roster: Roster): List<String> = roster.names
    suspend fun assignmentsLater(present: Boolean): List<Assignment>? =
      if (present) listOf(Existing(emptyList())) else null
  """.trimIndent()

  private fun run(): Tier1Result = Tier1Harness.run(
    fixture,
    fileName = "Roster.kt",
    processorOptions = mapOf("nuget.rootPackage" to "tier1"),
    libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
  )

  /**
   * The headline cell, and the whole of issue #122: the public C# return type carries its type
   * argument, spelled exactly as the property route on the same class spells it. The two are
   * asserted together on purpose: a fix that spells the element differently on the two routes
   * would be worse than the current error.
   */
  @Test
  fun `a suspend List return on a sealed arm is spelled as the property route spells it`() {
    val result = run()

    val element = "global::Interop.Roster.Member"
    val missing: List<String> = listOf(
      "public IReadOnlyList<$element> Members",
      "public Task<IReadOnlyList<$element>> FetchAsync(int limit, int offset",
      "new TaskCompletionSource<IReadOnlyList<$element>>(",
    ).filterNot(result.generatedCSharp::contains)

    assertTrue(
      missing.isEmpty(),
      "expected the suspend return to carry its type argument, spelled as the property route " +
          "does; missing: $missing; got: ${csharpLinesFor(result, "Fetch")}",
    )
    assertFalse(
      result.generatedCSharp.contains("Task<List>"),
      "expected no bare `Task<List>` (CS0305); got: ${csharpLinesFor(result, "Task<List>")}",
    )
  }

  /**
   * Requirement 2: the awaited handle is read through the same `nuget_list_*` helpers the
   * property route uses (`NugetMarshal.ReadList` is `NugetListNative.Count`/`Get`/`Dispose`
   * behind a `finally`), never through `new IReadOnlyList<...>(resultPtr)`.
   */
  @Test
  fun `a suspend collection return is read through the existing collection helpers`() {
    val result = run()

    val missing: List<String> = listOf(
      "NugetMarshal.ReadList<global::Interop.Roster.Member>(resultPtr",
      "NugetMarshal.ReadList<string>(resultPtr",
      "NugetMarshal.ReadSet<int>(resultPtr",
      "NugetMarshal.ReadMap<string, int>(resultPtr",
    ).filterNot(result.generatedCSharp::contains)

    assertTrue(
      missing.isEmpty(),
      "expected every collection return to be read through NugetMarshal.Read*; missing: " +
          "$missing; got: ${csharpLinesFor(result, "resultPtr")}",
    )
  }

  /** The three kinds on an ordinary class, and the top-level route, all carry their arguments. */
  @Test
  fun `every collection kind is spelled with its type arguments on every suspend owner`() {
    val result = run()

    val missing: List<String> = listOf(
      "public Task<IReadOnlyList<string>> TagsAsync(",
      "public Task<IReadOnlySet<int>> IdsAsync(",
      "public Task<IReadOnlyDictionary<string, int>> AgesAsync(",
      "public static Task<IReadOnlyList<string>> EveryoneAsync(",
      // Control: the scalar return is untouched.
      "public Task<int> CountAsync(",
    ).filterNot(result.generatedCSharp::contains)

    assertTrue(
      missing.isEmpty(),
      "expected typed collection returns on the class and top-level suspend routes; missing: " +
          "$missing; got: ${csharpSignatures(result)}",
    )
  }

  /**
   * Requirement 3: a generic return the route cannot marshal is absent on **both** halves and
   * named, so a C# import never arrives with no Kotlin export behind it (and vice versa).
   */
  @Test
  fun `a non-collection generic suspend return skips the member named on both halves`() {
    val result = run()

    assertFalse(
      result.generatedCSharp.contains("PairedAsync"),
      "expected no PairedAsync member: a Pair<String, Int> return has no wire shape here; got: " +
          "${csharpLinesFor(result, "Paired")}",
    )
    assertFalse(
      result.generated.contains("roster_paired_async"),
      "expected no roster_paired_async export either; got: " +
          "${result.generated.lines().filter { it.contains("paired") }.map(String::trim)}",
    )

    val diagnostic: String? = result.kspWarnings.firstOrNull {
      it.contains("[nuget:${ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN.name}]") &&
          it.contains("paired")
    }
    assertTrue(
      diagnostic != null,
      "expected a SKIPPED_UNSUPPORTED_RETURN naming Roster.paired rather than a silent vanish; " +
          "kspWarnings=${result.kspWarnings}",
    )
    assertTrue(
      diagnostic!!.contains("Pair<String, Int>"),
      "expected the diagnostic to quote the author's own spelling of the return type; got: " +
          diagnostic,
    )
  }

  /**
   * ADR-119 amendment: a nullable collection return binds as `Task<IReadOnlyList<T>?>`, read
   * behind an `IntPtr.Zero` guard (the Kotlin half already sends `null` as a null result pointer).
   * ADR-114's nullable *parameter* deferral is untouched: the two positions share no wire.
   */
  @Test
  fun `a nullable collection suspend return binds nullable behind a null guard`() {
    val result = run()

    val missing: List<String> = listOf(
      "public Task<IReadOnlyList<string>?> MaybeAsync(",
      "public Task<IReadOnlyDictionary<string, int>?> MaybeAgesAsync(",
      "t.SetResult(resultPtr == IntPtr.Zero ? null : NugetMarshal.ReadList<string>(resultPtr, ",
      "t.SetResult(resultPtr == IntPtr.Zero ? null : NugetMarshal.ReadMap<string, int>(resultPtr, ",
    ).filterNot(result.generatedCSharp::contains)
    assertTrue(
      missing.isEmpty(),
      "expected nullable collection returns declared `?` and guarded; missing: $missing; got: " +
          "${csharpLinesFor(result, "Maybe")} " +
          "${csharpLinesFor(result, "resultPtr == IntPtr.Zero")}",
    )
    assertTrue(
      result.generated.contains("if (result == null) null else NugetHandles.retain("),
      "expected the Kotlin half to pin a nullable result behind its null test",
    )
    assertFalse(
      result.kspWarnings.any {
        it.contains("[nuget:${ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN.name}]") &&
            it.contains("maybe")
      },
      "expected no SKIPPED_UNSUPPORTED_RETURN for maybe(); kspWarnings=${result.kspWarnings}",
    )
  }

  /**
   * ADR-119 amendment: a collection of an ADR-009 sealed base is classified through ADR-105's
   * `sealedAsHandle()`, so each element is read through `FromHandle<Base>` exactly as the ordinary
   * route's sync `List<Base>` return reads it, on every suspend owner and on a `Flow` element.
   */
  @Test
  fun `a collection of a sealed base binds on every suspend owner and a Flow element`() {
    val result = run()

    val base = "global::Interop.Roster.Assignment"
    val missing: List<String> = listOf(
      "public Task<IReadOnlyList<$base>> AssignmentsAsync(",
      "public static Task<IReadOnlyList<$base>?> AssignmentsLaterAsync(",
      "static h1 => NugetMarshal.FromHandle<$base>(h1)",
      "IReadOnlyList<$base>",
    ).filterNot(result.generatedCSharp::contains)
    assertTrue(
      missing.isEmpty(),
      "expected sealed-base collection returns to bind; missing: $missing; got: " +
          "${csharpLinesFor(result, "Assignment")}",
    )
    assertTrue(
      csharpLinesFor(result, "Rotation").any { it.contains("IReadOnlyList<$base>") },
      "expected Flow<List<Assignment>> to bind its element; got: " +
          "${csharpLinesFor(result, "Rotation")}",
    )
    assertFalse(
      result.kspWarnings.any {
        it.contains("SKIPPED_UNSUPPORTED") &&
            (it.contains("assignments") || it.contains("rotation"))
      },
      "expected no skip for the sealed-base collections; kspWarnings=${result.kspWarnings}",
    )
  }

  /** The generated Kotlin has to keep compiling with the refusal filter applied to it. */
  @Test
  fun `the generated Kotlin still compiles`() {
    val result = run()

    assertTrue(
      result.compiledClean,
      "expected clean generated Kotlin for collection returns on the suspend route; got: " +
          "${result.compileErrors}",
    )
  }

  // ADR-127 deleted the helper-gate cell that stood here. The `nuget_list_*` / `nuget_map_*` /
  // `nuget_set_*` exports now ship unconditionally from the `:nuget-runtime` klib, so there is no
  // gate left to miss a route and no declaration of them in the generated file.
  // `scripts/verify-runtime-exports.sh` checks the 68 names on the linked binary instead.

  private fun csharpSignatures(result: Tier1Result): List<String> =
    result.generatedCSharp.lines()
      .filter { it.contains("Async(") && it.contains("public") }
      .map(String::trim)

  private fun csharpLinesFor(result: Tier1Result, needle: String): List<String> =
    result.generatedCSharp.lines().filter { it.contains(needle) }.map(String::trim)
}
