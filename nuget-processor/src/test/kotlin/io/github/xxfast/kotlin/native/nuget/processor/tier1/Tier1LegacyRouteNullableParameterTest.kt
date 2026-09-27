package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #299 / ADR-122 amendment. A nullable scalar parameter (`Int?`, `Char?`, `Boolean?`) or a
 * nullable `String` on a legacy route (suspend member, top-level suspend, Flow/StateFlow member)
 * was bound as non-null on both halves: Kotlin declared `limit: Int`, C# declared `int limit`, so a
 * C# caller could not pass `null`.
 *
 * The fix reuses the plan route's wire exactly: a nullable primitive or `Char` crosses as a
 * `${name}HasValue` BOOLEAN slot followed by the inner value slot (rebuilt in Kotlin as
 * `if (${name}HasValue) name else null`, passed from C# as `x.HasValue, x.GetValueOrDefault()`); a
 * nullable `String` is one nullable pointer slot.
 *
 * Each route is asserted separately because each has its own hand-written parameter builder.
 */
class Tier1LegacyRouteNullableParameterTest {

  private val fixture: String = """
    package tier1.kennel

    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.StateFlow
    import kotlinx.coroutines.flow.flowOf

    class Feeder {
      suspend fun countNaps(limit: Int?): String =
        if (limit == null) "unlimited" else "naps: " + limit
      suspend fun initialOf(initial: Char?): String = initial?.toString() ?: "none"
      suspend fun greet(name: String?): String = name ?: "stranger"
      fun snacks(limit: Int?): Flow<String> = flowOf("snack")
      fun bowlStatus(bowl: String?): StateFlow<String> = MutableStateFlow(bowl ?: "empty")
      fun initials(initial: Char?): StateFlow<String> = MutableStateFlow(initial?.toString() ?: "")
      suspend fun awaitBowlInitial(initial: Char?): StateFlow<String> =
        MutableStateFlow(initial?.toString() ?: "none")
    }

    suspend fun describeIndoor(indoor: Boolean?): String = when (indoor) {
      null -> "unknown"
      true -> "indoor"
      false -> "outdoor"
    }

    suspend fun nameTagFor(name: String?): String = name ?: "no name"
  """.trimIndent()

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      fixture,
      fileName = "Kennel.kt",
      processorOptions = mapOf("nuget.rootPackage" to "tier1"),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
  }

  @Test
  fun `the run succeeds and the generated Kotlin compiles`() {
    assertEquals(emptyList(), result.kspErrors, "KSP errors (an ADR-055 mismatch lands here)")
    assertTrue(result.compiledClean, "generated Kotlin must compile; got: ${result.compileErrors}")
  }

  @Test
  fun `a nullable primitive on a suspend member fans out to a has-value pair`() {
    val export: String = exportEndingWith("_feeder_countNaps_async")
    assertTrue(export.contains("limitHasValue: Boolean,"), export)
    assertTrue(export.contains("limit: Int,"), export)
    assertTrue(export.contains("obj.countNaps(if (limitHasValue) limit else null)"), export)

    assertCsharp("public Task<string> CountNapsAsync(int? limit, CancellationToken")
    assertCsharp(
      "IntPtr handle, IntPtr scopeHandle, bool limitHasValue, int limit, IntPtr callback",
    )
    assertCsharp("limit.HasValue, limit.GetValueOrDefault()")
  }

  @Test
  fun `a nullable Char keeps its UTF-16 marshalling on the value slot`() {
    val export: String = exportEndingWith("_feeder_initialOf_async")
    assertTrue(export.contains("initialHasValue: Boolean,"), export)
    assertTrue(export.contains("initial: Char,"), export)
    assertTrue(export.contains("if (initialHasValue) initial else null"), export)

    assertCsharp("public Task<string> InitialOfAsync(char? initial, CancellationToken")
    assertCsharp("bool initialHasValue, [MarshalAs(UnmanagedType.U2)] char initial")
  }

  @Test
  fun `a nullable String on a suspend member is one nullable slot`() {
    val export: String = exportEndingWith("_feeder_greet_async")
    assertTrue(export.contains("name: String?,"), export)
    assertTrue(export.contains("obj.greet(name)"), export)

    assertCsharp("public Task<string> GreetAsync(string? name, CancellationToken")
    assertCsharp(
      "IntPtr scopeHandle, [MarshalAs(UnmanagedType.LPUTF8Str)] string? name, IntPtr callback",
    )
  }

  @Test
  fun `a nullable parameter on a top-level suspend function is bound nullable`() {
    val indoor: String = exportEndingWith("_describeIndoor_async")
    assertTrue(indoor.contains("indoorHasValue: Boolean,"), indoor)
    assertTrue(indoor.contains("indoor: Boolean,"), indoor)
    assertTrue(indoor.contains("if (indoorHasValue) indoor else null"), indoor)
    assertCsharp("public static Task<string> DescribeIndoorAsync(bool? indoor, CancellationToken")
    assertCsharp("bool indoorHasValue, bool indoor, IntPtr callback")
    assertCsharp("indoor.HasValue, indoor.GetValueOrDefault()")

    val tag: String = exportEndingWith("_nameTagFor_async")
    assertTrue(tag.contains("name: String?,"), tag)
    assertCsharp("public static Task<string> NameTagForAsync(string? name, CancellationToken")
  }

  @Test
  fun `a nullable parameter on a Flow member is bound nullable on every export`() {
    val collect: String = exportEndingWith("_feeder_snacks_collect")
    assertTrue(collect.contains("limitHasValue: Boolean,"), collect)
    assertTrue(collect.contains("snacks(if (limitHasValue) limit else null)"), collect)
    assertCsharp("public KotlinFlow<string> Snacks(int? limit)")
    assertCsharp("IntPtr scopeHandle, bool limitHasValue, int limit, IntPtr onNext")

    val value: String = exportEndingWith("_feeder_bowlStatus_value")
    assertTrue(value.contains("bowl: String?"), value)
    assertCsharp("public KotlinStateFlow<string> BowlStatus(string? bowl)")

    val initials: String = exportEndingWith("_feeder_initials_value")
    assertTrue(initials.contains("initialHasValue: Boolean,"), initials)
    assertCsharp("public KotlinStateFlow<string> Initials(char? initial)")
    assertCsharp(
      "IntPtr handle, bool initialHasValue, [MarshalAs(UnmanagedType.U2)] char initial);",
    )
  }

  @Test
  fun `a nullable parameter on a StateFlow-returning suspend member is bound nullable`() {
    val export: String = exportEndingWith("_feeder_awaitBowlInitial_async")
    assertTrue(export.contains("initialHasValue: Boolean,"), export)
    assertCsharp(
      "public Task<KotlinStateFlow<string>> AwaitBowlInitialAsync(char? initial, CancellationToken",
    )
  }

  private fun assertCsharp(fragment: String) {
    assertTrue(
      result.generatedCSharp.contains(fragment),
      "expected generated C# to contain \"$fragment\"; relevant lines:\n" +
          result.generatedCSharp.lines()
            .filter { line -> fragment.split(" ").take(3).any { line.contains(it) } }
            .take(40)
            .joinToString("\n"),
    )
  }

  /** The whole export whose `@CName` ends with [suffix], up to the next export. */
  private fun exportEndingWith(suffix: String): String {
    val generated: String = result.generated
    val marker: Regex = Regex("@CName\\(\"[^\"]*${Regex.escape(suffix)}\"\\)")
    val match: MatchResult = requireNotNull(marker.find(generated)) {
      "no export ending with $suffix; exports present: " +
          generated.lines().filter { it.contains("@CName(") }.map(String::trim)
    }
    val rest: String = generated.substring(match.range.last)
    val next: Int = rest.indexOf("@CName(\"")
    return if (next >= 0) rest.substring(0, next) else rest
  }
}
