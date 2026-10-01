package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #365 / ADR-122 amendment. A nullable class or sealed handle parameter on a legacy route
 * (suspend member, sealed arm member, top-level suspend, Flow/StateFlow member, suspend returning
 * StateFlow) used to be refused. It now binds as public `T?` on the same one pointer slot the
 * non-null handle takes: `null` crosses as `IntPtr.Zero` (`x?._handle ?? IntPtr.Zero`), and Kotlin
 * reads `x?.asStableRef<T>()?.get()`, so the member receives `null`.
 *
 * Each route is asserted separately because each has its own hand-written parameter builder.
 */
class Tier1LegacyRouteNullableHandleParameterTest {

  private val fixture: String = """
    package tier1.clinic

    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.StateFlow
    import kotlinx.coroutines.flow.flowOf

    class Cat(val name: String)

    sealed class Observation {
      data class Calm(val note: String) : Observation() {
        suspend fun compare(other: Cat?): String = other?.name ?: "none"
      }
      data class Restless(val note: String) : Observation()
    }

    class Vet {
      suspend fun examine(cat: Cat?): String = cat?.name ?: "none"
      suspend fun observe(observation: Observation?): String =
        if (observation == null) "unobserved" else "observed"
      fun patients(cat: Cat?): Flow<String> = flowOf(cat?.name ?: "none")
      fun status(cat: Cat?): StateFlow<String> = MutableStateFlow(cat?.name ?: "none")
      suspend fun awaitStatus(cat: Cat?): StateFlow<String> = MutableStateFlow(cat?.name ?: "none")
    }

    suspend fun triage(cat: Cat?): String = cat?.name ?: "none"
  """.trimIndent()

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      fixture,
      fileName = "Clinic.kt",
      processorOptions = mapOf("nuget.rootPackage" to "tier1"),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
  }

  @Test
  fun `the run succeeds and the generated Kotlin compiles`() {
    assertEquals(emptyList(), result.kspErrors, "KSP errors (an ADR-055 mismatch lands here)")
    assertTrue(result.compiledClean, "generated Kotlin must compile; got: ${result.compileErrors}")
    assertTrue(
      result.kspWarnings.none { it.contains("SKIPPED_UNSUPPORTED_INPUT") },
      "expected no member skipped for its nullable handle; got: ${result.kspWarnings}",
    )
  }

  @Test
  fun `a nullable class handle on a suspend member is one nullable pointer slot`() {
    val export: String = exportEndingWith("_vet_examine_async")
    assertTrue(export.contains("cat: COpaquePointer?,"), export)
    assertTrue(export.contains("cat?.asStableRef<tier1.clinic.Cat>()?.get()"), export)

    assertCsharp("Task<string> ExamineAsync(global::Interop.Clinic.Cat? cat, CancellationToken")
    assertCsharp("cat?._handle ?? IntPtr.Zero")
  }

  @Test
  fun `a nullable sealed handle on a suspend member is one nullable pointer slot`() {
    val export: String = exportEndingWith("_vet_observe_async")
    assertTrue(export.contains("observation: COpaquePointer?,"), export)
    assertTrue(
      export.contains("observation?.asStableRef<tier1.clinic.Observation>()?.get()"),
      export,
    )
    assertCsharp("ObserveAsync(global::Interop.Clinic.Observation? observation, CancellationToken")
    assertCsharp("observation?._handle ?? IntPtr.Zero")
  }

  @Test
  fun `a nullable handle on a sealed arm member binds`() {
    val export: String = exportEndingWith("_compare_async")
    assertTrue(export.contains("other: COpaquePointer?,"), export)
    assertTrue(export.contains("other?.asStableRef<tier1.clinic.Cat>()?.get()"), export)
    assertCsharp("CompareAsync(global::Interop.Clinic.Cat? other, CancellationToken")
  }

  @Test
  fun `a nullable handle on a top-level suspend function binds`() {
    val export: String = exportEndingWith("_triage_async")
    assertTrue(export.contains("cat: COpaquePointer?,"), export)
    assertTrue(export.contains("cat?.asStableRef<tier1.clinic.Cat>()?.get()"), export)
    assertCsharp("TriageAsync(global::Interop.Clinic.Cat? cat, CancellationToken")
  }

  @Test
  fun `a nullable handle on a Flow and StateFlow member binds on every export`() {
    val collect: String = exportEndingWith("_vet_patients_collect")
    assertTrue(collect.contains("cat: COpaquePointer?,"), collect)
    assertTrue(collect.contains("cat?.asStableRef<tier1.clinic.Cat>()?.get()"), collect)
    assertCsharp("public KotlinFlow<string> Patients(global::Interop.Clinic.Cat? cat)")

    val value: String = exportEndingWith("_vet_status_value")
    assertTrue(value.contains("cat: COpaquePointer?"), value)
    assertCsharp("public KotlinStateFlow<string> Status(global::Interop.Clinic.Cat? cat)")
  }

  @Test
  fun `a nullable handle on a StateFlow-returning suspend member binds`() {
    val export: String = exportEndingWith("_vet_awaitStatus_async")
    assertTrue(export.contains("cat: COpaquePointer?,"), export)
    assertCsharp("AwaitStatusAsync(global::Interop.Clinic.Cat? cat, CancellationToken")
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
