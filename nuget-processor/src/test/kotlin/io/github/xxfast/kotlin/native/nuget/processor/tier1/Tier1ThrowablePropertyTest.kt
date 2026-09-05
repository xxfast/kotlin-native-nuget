package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ADR-107: a `kotlin.Throwable`-typed **property** reads as a constructed, unthrown
 * `System.Exception`, rebuilt from the very error envelope a *thrown* exception already crosses
 * on. Two separate routes have to learn it: the planner route (ordinary classes) and the legacy
 * ADR-009 sealed-subclass route, which never consults `ForwardPropertyPlanner`.
 *
 * The absence cells matter as much as the binding ones: every position ADR-107 defers (method
 * return, parameter, collection component, setter) must stay a *named* skip, because
 * `BridgeType.Throwable` now reaches every classifier caller, not just the property planner.
 */
class Tier1ThrowablePropertyTest {

  /** Planner route: nullable getter, non-null getter, and a `var` that binds get-only. */
  @Test
  fun `class properties typed Throwable bind as System Exception getters`() {
    val result = Tier1Harness.run(
      """
      package tier1.throwableproperty

      class Failure(val reason: String, val error: Throwable?, val fatal: Throwable) {
        var lastError: Throwable? = error
      }

      fun failure(): Failure =
        Failure("Oreo raided the treat jar", IllegalArgumentException("on a diet"), IllegalStateException("jar empty"))
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected the generated getters to compile; got: ${result.compileErrors}",
    )
    // Nullable getter: the envelope is built only for a non-null value, then boxed.
    assertTrue(
      "?.let(::buildError)" in result.generated,
      "expected the nullable getter to build the envelope; generated=${result.generated}",
    )
    // Non-null getter: unconditional envelope.
    assertTrue(
      "buildError(handle.asStableRef<tier1.throwableproperty.Failure>().get().fatal)" in result.generated,
      "expected the non-null getter to build the envelope; generated=${result.generated}",
    )
    assertTrue(
      "public global::System.Exception? Error" in result.generatedCSharp,
      "expected a nullable System.Exception property; generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      "public global::System.Exception Fatal" in result.generatedCSharp,
      "expected a non-null System.Exception property; generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      "NugetErrorNative.BuildException(nativeResult)" in result.generatedCSharp,
      "expected the C# getter to reconstruct the exception; generatedCSharp=${result.generatedCSharp}",
    )
  }

  /**
   * The `var` is get-only: C# cannot mint a typed Kotlin `Throwable`, so no setter export and no
   * C# setter, named by the existing read-only diagnostic rather than dropped silently.
   */
  @Test
  fun `var Throwable property binds get-only with a named read-only diagnostic`() {
    val result = Tier1Harness.run(
      """
      package tier1.throwablesetter

      class Failure(val reason: String) {
        var lastError: Throwable? = null
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected the generated getter to compile; got: ${result.compileErrors}",
    )
    assertTrue(
      "failure_set_lastError" !in result.generated,
      "expected no setter export; generated=${result.generated}",
    )
    assertTrue(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT.name) &&
            it.contains("lastError") && it.contains("read-only")
      },
      "expected the read-only setter diagnostic for lastError; kspWarnings=${result.kspWarnings}",
    )
  }

  /** The legacy ADR-009 route: the same property on a sealed subclass, decision 1 of the ADR. */
  @Test
  fun `sealed subclass property typed Throwable binds as System Exception`() {
    val result = Tier1Harness.run(
      """
      package tier1.throwablesealed

      sealed class LoadState {
        data object Loading : LoadState()
        data class Failure(val error: Throwable?) : LoadState()
      }

      fun failedLoad(): LoadState = LoadState.Failure(IllegalStateException("clinic offline"))
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected the generated sealed getters to compile; got: ${result.compileErrors}",
    )
    assertTrue(
      "?.let(::buildError)" in result.generated,
      "expected the sealed Kotlin getter to build the envelope, not box the Throwable itself; " +
          "generated=${result.generated}",
    )
    assertTrue(
      "global::System.Exception?" in result.generatedCSharp,
      "expected the sealed subclass property to spell System.Exception?; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      "NugetErrorNative.BuildException(nativeResult)" in result.generatedCSharp,
      "expected the sealed C# getter to reconstruct the exception; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
    // The known leak in the existing nullable-reference arm calls the export twice; this one must
    // call it exactly once, since each call mints a fresh envelope StableRef.
    val getterCalls: Int = Regex("Native_Get_error\\(_handle").findAll(result.generatedCSharp).count()
    assertTrue(
      getterCalls == 1,
      "expected exactly one native getter call in the sealed C# property, found $getterCalls; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
  }

  /**
   * Absence cell: `Throwable` at a **method return** is explicitly deferred by ADR-107, so it must
   * skip with a named diagnostic rather than plan a shape the emitters have no arm for.
   */
  @Test
  fun `method returning Throwable fires a named skip and is omitted`() {
    val result = Tier1Harness.run(
      """
      package tier1.throwablereturn

      class Failure(val reason: String) {
        fun cause(): Throwable? = null
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected no broken source for the Throwable return; got: ${result.compileErrors}",
    )
    assertTrue(
      "export_failure_cause" !in result.generated,
      "expected the method to be absent; generated=${result.generated}",
    )
    assertTrue(
      result.kspWarnings.any { it.contains("SKIPPED_") && it.contains("cause") },
      "expected a named skip for Failure.cause; kspWarnings=${result.kspWarnings}",
    )
  }

  /**
   * Absence cell: a `Throwable` **parameter** (the shape the fixture's data-class constructor
   * has) is out of scope entirely — C# cannot construct a Kotlin Throwable — so the callable
   * skips named and the surrounding class still survives.
   */
  @Test
  fun `method with a Throwable parameter fires a named skip and is omitted`() {
    val result = Tier1Harness.run(
      """
      package tier1.throwableparameter

      class Failure(val reason: String) {
        fun record(error: Throwable): Int = error.hashCode()
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected no broken source for the Throwable parameter; got: ${result.compileErrors}",
    )
    assertTrue(
      "export_failure_record" !in result.generated,
      "expected the method to be absent; generated=${result.generated}",
    )
    assertTrue(
      result.kspWarnings.any { it.contains("SKIPPED_") && it.contains("record") },
      "expected a named skip for Failure.record; kspWarnings=${result.kspWarnings}",
    )
  }

  /**
   * Absence cell: `List<Throwable>` is deferred (the component would have to box through
   * `nuget_wrap_*`, which has no envelope arm), so it skips named — issue #52's rule — rather
   * than crashing the projection.
   */
  @Test
  fun `List of Throwable property fires SKIPPED_UNSUPPORTED_PROPERTY and is omitted`() {
    val result = Tier1Harness.run(
      """
      package tier1.throwablelist

      class Failure(val reason: String, val errors: List<Throwable>)
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected no broken source for the List<Throwable> property; got: ${result.compileErrors}",
    )
    assertTrue(
      "failure_get_errors" !in result.generated,
      "expected the collection property to be absent; generated=${result.generated}",
    )
    assertTrue(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY.name) &&
            it.contains("errors")
      },
      "expected a named property skip for Failure.errors; kspWarnings=${result.kspWarnings}",
    )
  }
}
