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

      // Nullable collection returns are refused too, mirroring ADR-114's parameter rule.
      suspend fun maybe(): List<String>? = null

      // Control: a scalar suspend return must be unaffected by any of this.
      suspend fun count(): Int = names.size
    }

    // The top-level suspend route is a third copy of the same composition.
    suspend fun everyone(roster: Roster): List<String> = roster.names
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

  /** ADR-114 refuses a nullable collection parameter; the return side keeps the same rule. */
  @Test
  fun `a nullable collection suspend return skips the member named`() {
    val result = run()

    assertFalse(
      result.generatedCSharp.contains("MaybeAsync"),
      "expected no MaybeAsync member for a List<String>? return; got: " +
          "${csharpLinesFor(result, "Maybe")}",
    )
    assertTrue(
      result.kspWarnings.any {
        it.contains("[nuget:${ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN.name}]") &&
            it.contains("maybe") && it.contains("List<String>?")
      },
      "expected a SKIPPED_UNSUPPORTED_RETURN naming Roster.maybe and quoting `List<String>?`; " +
          "kspWarnings=${result.kspWarnings}",
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

  /**
   * The helper gate, ADR-114 answer 4's return-side twin. A sealed arm's suspend member is on the
   * legacy route since ADR-118, but the `needs*Support` walk never looked at a sealed arm's suspend
   * members at all, and no declaration scan reads a suspend return. With only this member in the
   * module, the C# side would call `nuget_list_count` against a native library that never exported
   * it, an `EntryPointNotFoundException` at first await rather than a build failure.
   */
  @Test
  fun `the collection helper exports are emitted for a suspend-return-only collection`() {
    val result = Tier1Harness.run(
      """
      package tier1.headcount

      sealed class Shift

      data class Night(val lead: String) : Shift() {
        suspend fun crew(): List<String> = listOf(lead)
        suspend fun badges(): Set<Int> = setOf(1)
      }
      """.trimIndent(),
      fileName = "Shift.kt",
      processorOptions = mapOf("nuget.rootPackage" to "tier1"),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    val missing: List<String> = listOf(
      "nuget_list_count", "nuget_list_get", "nuget_set_count", "nuget_set_element_at",
    ).filterNot { result.generated.contains("@CName(\"$it\")") }

    assertTrue(
      missing.isEmpty(),
      "expected the collection helper exports a suspend-route collection return needs; " +
          "missing: $missing",
    )
    assertTrue(
      result.generatedCSharp.contains("public Task<IReadOnlyList<string>> CrewAsync("),
      "expected the arm's List return to bind; got: ${csharpLinesFor(result, "Crew")}",
    )
  }

  private fun csharpSignatures(result: Tier1Result): List<String> =
    result.generatedCSharp.lines()
      .filter { it.contains("Async(") && it.contains("public") }
      .map(String::trim)

  private fun csharpLinesFor(result: Tier1Result, needle: String): List<String> =
    result.generatedCSharp.lines().filter { it.contains(needle) }.map(String::trim)
}
