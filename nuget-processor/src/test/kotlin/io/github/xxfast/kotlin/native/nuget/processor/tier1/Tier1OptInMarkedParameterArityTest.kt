package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #128 / ADR-115 + ADR-096: a parameter whose **type** is opt-in-marked makes *every* arity
 * of the callable illegal, so ADR-096's trailing-omitting overload cannot repair it.
 *
 * Kotlin propagates the opt-in requirement from the callee's declared value-parameter types, never
 * from what the default expression happens to read (verified against Kotlin 2.4.10: `Mixed(a = 5)`
 * is rejected exactly like `Mixed(1, Mode.Slow)`, while `DefaultReadsMarked()` whose default reads
 * a marked declaration through an `Int` parameter compiles). So the shorter call the omitting
 * overload emits is not callable either, and before this fix the generated `CNameExports.kt` did
 * not compile.
 *
 * [Tier1Harness] compiles the fixture and the generated file **together**, and only the fixture's
 * own declarations opt in, which is exactly the consumer situation: the generated file never does.
 * `compiledClean` is therefore the load-bearing assertion here, not a smoke check.
 *
 * The over-skip guard is the last case: a marker on the *property* of an unmarked-typed parameter
 * stays repairable by the shorter arity (ADR-115 gate (b)), and must not be swept up.
 */
class Tier1OptInMarkedParameterArityTest {

  private val source: String = """
    package tier1.optin.arity

    @RequiresOptIn(level = RequiresOptIn.Level.ERROR, message = "internal")
    @Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION)
    annotation class InternalApi

    @InternalApi
    enum class Grooming { DAILY, WEEKLY }

    @OptIn(InternalApi::class)
    data class GroomingPlan(
      val name: String = "Oreo",
      val grooming: Grooming = Grooming.DAILY,
    )

    // Keeps GroomingPlan in the export closure, which is what makes the bad call reachable.
    @OptIn(InternalApi::class)
    fun plan(): GroomingPlan = GroomingPlan()

    @OptIn(InternalApi::class)
    fun schedule(name: String = "Oreo", grooming: Grooming = Grooming.DAILY): String = name

    // ADR-115 gate (b) control: the marker sits on the PROPERTY, the parameter's type is plain
    // `String`, so omitting it is legal and the shorter arity must survive.
    data class GroomingLog(val note: String = "clean", @InternalApi val ledger: String = "l-1")
  """.trimIndent()

  private fun optInWarnings(result: Tier1Result): List<String> = result.kspWarnings
    .filter { it.contains(ForwardDiagnosticKind.SKIPPED_OPT_IN_MARKER.name) }

  @Test
  fun `every arity of a constructor with an opt-in-marked parameter type is skipped`() {
    val result = Tier1Harness.run(source)

    assertTrue(
      result.compiledClean,
      "the generated file has no opt-in, so any surviving arity fails to compile; " +
          "got: ${result.compileErrors}",
    )
    assertFalse(
      "GroomingPlan(" in result.generated,
      "no arity of the constructor may be called; generated:\n${result.generated}",
    )
    val skipped: List<String> = optInWarnings(result)
    // The trailing `:` is load-bearing: `<init>` is a prefix of `<init>_2`.
    listOf("GroomingPlan.<init>:", "GroomingPlan.<init>_2:", "GroomingPlan.<init>_3:")
      .forEach { symbol ->
        assertEquals(
          1,
          skipped.count { it.contains(symbol) },
          "expected `$symbol` skipped exactly once; kspWarnings=${result.kspWarnings}",
        )
      }
    assertTrue(
      skipped.any { it.contains("GroomingPlan.<init>_3:") && it.contains("Grooming`") },
      "the skip names the marked type it dropped; kspWarnings=${result.kspWarnings}",
    )
    assertFalse(
      "public GroomingPlan(" in result.generatedCSharp,
      "no C# constructor either; generated C#:\n${result.generatedCSharp}",
    )
    assertTrue(
      "Name" in result.generatedCSharp,
      "the unmarked property survives; generated C#:\n${result.generatedCSharp}",
    )
  }

  @Test
  fun `a class whose every constructor arity is marked reports no public constructor`() {
    val result = Tier1Harness.run(source)

    assertEquals(
      1,
      result.kspWarnings.count {
        it.contains(ForwardDiagnosticKind.WARNING_NO_PUBLIC_CONSTRUCTOR.name) &&
            it.contains("GroomingPlan")
      },
      "the type stays reachable through `plan()`, just not constructible; " +
          "kspWarnings=${result.kspWarnings}",
    )
  }

  @Test
  fun `copy is skipped for the same reason`() {
    // Regression only: `copy` is built at full arity, so the marked parameter was always
    // classified. The assertion pins that it stays that way now that the arities move.
    val result = Tier1Harness.run(source)

    assertEquals(
      1,
      optInWarnings(result).count { it.contains("GroomingPlan.copy") },
      "kspWarnings=${result.kspWarnings}",
    )
    val copyInKotlin: Boolean = "GroomingPlan.copy" in result.generated
    val copyInCSharp: Boolean = "public GroomingPlan Copy(" in result.generatedCSharp
    assertFalse(
      copyInKotlin || copyInCSharp,
      "generated:\n${result.generated}\n${result.generatedCSharp}",
    )
  }

  @Test
  fun `a function with an opt-in-marked parameter type synthesizes no shorter overload`() {
    val result = Tier1Harness.run(source)

    assertFalse(
      "schedule(" in result.generated,
      "requirement 4: the function half, not just constructors; generated:\n${result.generated}",
    )
    // The four function routes gate their omitting overloads on the DECLARED entry having planned,
    // and the declared entry classifies every parameter, so a marked parameter type stops the
    // synthesis one step earlier than it does for constructors. Only `schedule` itself is named.
    assertEquals(
      1,
      optInWarnings(result).count { it.contains("tier1.optin.arity.schedule") },
      "kspWarnings=${result.kspWarnings}",
    )
  }

  @Test
  fun `a property-targeted marker on an unmarked type keeps its reduced arity`() {
    val result = Tier1Harness.run(source)

    assertTrue(
      "groominglog_create_2" in result.generated,
      "ADR-115 gate (b) is unchanged: omitting a marked PROPERTY with an unmarked type is " +
          "legal Kotlin; generated:\n${result.generated}",
    )
  }
}
