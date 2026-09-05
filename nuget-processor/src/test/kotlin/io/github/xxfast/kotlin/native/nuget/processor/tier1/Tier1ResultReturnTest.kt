package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ADR-108: `kotlin.Result<T>` is lowered to `T` at an ordinary **return** position, with
 * `.getOrThrow()` appended inside the export's existing `try`, and keeps its named skip
 * everywhere else. The absence cells are the load-bearing half: a `Result` property or parameter
 * must stay a *named* skip (never a silent drop, never a broken emit), and a `Result<Shape>` over
 * a payload with no return shape must keep the ORIGINAL value-class skip rather than falling into
 * the sealed legacy deferral, whose re-emit keys on the declared return type and would drop the
 * method silently.
 */
class Tier1ResultReturnTest {

  /**
   * The issue's exact repro: `Result<Unit>` becomes a `void` export, and the invocation carries
   * `.getOrThrow()` so a `Result.failure` writes the existing `errorOut` slot.
   */
  @Test
  fun `class method returning Result of Unit binds as a void export with getOrThrow`() {
    val result = Tier1Harness.run(
      """
      package tier1.resultunit

      class Service {
        fun run(): Result<Unit> = Result.success(Unit)
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected the generated export to compile; got: ${result.compileErrors}",
    )
    assertTrue(
      "export_service_run" in result.generated,
      "expected run to be exported; generated=${result.generated}",
    )
    assertTrue(
      ".run().getOrThrow()" in result.generated,
      "expected the invocation to unwrap the Result; generated=${result.generated}",
    )
    assertTrue(
      "public void Run()" in result.generatedCSharp,
      "expected a void C# method, not a Unit-typed one; generatedCSharp=${result.generatedCSharp}",
    )
  }

  /** A payload with a real wire keeps that payload's own result shape (`string Feed(...)`). */
  @Test
  fun `class method returning Result of String binds as the payload type`() {
    val result = Tier1Harness.run(
      """
      package tier1.resultstring

      class Service {
        fun feed(name: String): Result<String> = Result.success(name)
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected the generated export to compile; got: ${result.compileErrors}",
    )
    assertTrue(
      ".feed(name).getOrThrow()" in result.generated,
      "expected the invocation to unwrap the Result; generated=${result.generated}",
    )
    assertTrue(
      "public string Feed(" in result.generatedCSharp,
      "expected a string-returning C# method; generatedCSharp=${result.generatedCSharp}",
    )
  }

  /**
   * Absence cell: at a **property** position `Result` keeps classifying as a value class over an
   * unsupported underlying, so the property is dropped with a named
   * [ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY], never bound with a broken getter.
   */
  @Test
  fun `Result typed property fires SKIPPED_UNSUPPORTED_PROPERTY and is omitted`() {
    val result = Tier1Harness.run(
      """
      package tier1.resultproperty

      class Holder(val name: String) {
        val outcome: Result<String> = Result.success(name)
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected no broken source for the Result property; got: ${result.compileErrors}",
    )
    assertTrue(
      "outcome" !in result.generated,
      "expected the Result property to be entirely absent; generated=${result.generated}",
    )
    assertTrue(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY.name) &&
            it.contains("outcome")
      },
      "expected a named property skip for Holder.outcome; kspWarnings=${result.kspWarnings}",
    )
  }

  /**
   * Absence cell: at a **parameter** position there is no sensible lowering (C# cannot mint a
   * Kotlin `Result`), so the callable keeps its named skip.
   *
   * The kind is [ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_TYPE], not the
   * `SKIPPED_UNSUPPORTED_INPUT` ADR-108 §Context predicts: `ForwardDiagnostic.kt:227` maps the
   * `VALUE_CLASS` skip reason to `SKIPPED_UNSUPPORTED_TYPE` at every position, input included.
   * Both are `droppedFromCSharp = true` named skips, so the ADR's guarantee holds; only its
   * spelling of the kind was wrong.
   */
  @Test
  fun `Result typed parameter fires a named skip and is omitted`() {
    val result = Tier1Harness.run(
      """
      package tier1.resultparameter

      class Holder(val name: String) {
        fun record(outcome: Result<String>): Int = outcome.hashCode()
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected no broken source for the Result parameter; got: ${result.compileErrors}",
    )
    assertTrue(
      "export_holder_record" !in result.generated,
      "expected the method to be entirely absent; generated=${result.generated}",
    )
    assertTrue(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_TYPE.name) && it.contains("record")
      },
      "expected a named skip for Holder.record; kspWarnings=${result.kspWarnings}",
    )
  }

  /**
   * The fallback rule: a payload with **no return shape** (a sealed base, which is a legacy-route
   * protocol) must keep the ORIGINAL `Result`'s `VALUE_CLASS` skip, so the diagnostic is the
   * `droppedFromCSharp = true` [ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_TYPE] and not the
   * sealed protocol's silent legacy deferral.
   */
  @Test
  fun `Result over a sealed payload keeps the value-class skip rather than deferring to the sealed route`() {
    val result = Tier1Harness.run(
      """
      package tier1.resultsealed

      sealed class Shape {
        data class Circle(val radius: Int) : Shape()
      }

      class Studio {
        fun draw(): Result<Shape> = Result.success(Shape.Circle(1))
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected no broken source for the Result<Shape> return; got: ${result.compileErrors}",
    )
    assertTrue(
      "export_studio_draw" !in result.generated,
      "expected draw to be entirely absent from the generated Kotlin; generated=${result.generated}",
    )
    assertTrue(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_TYPE.name) && it.contains("draw")
      },
      "expected the named VALUE_CLASS skip for Studio.draw, not a silent sealed deferral; " +
          "kspWarnings=${result.kspWarnings}",
    )
  }

  /**
   * Probe cell, ADR-108 §Scope's open question: `suspend fun f(): Result<T>` is **not** plan-routed
   * (the legacy suspend route reads the KSP return type directly), so this ADR does not change it.
   * The contract asserted here is the weakest honest one: the shape must either bind with a
   * correctly-typed C# signature, or be skipped with a named diagnostic. What it must NOT do is
   * emit a C# member typed over `Result`, which would not compile for a consumer.
   */
  @Test
  @XFail(
    "Legacy suspend route emits `public Task<Result> LoadAsync(...)` over the unmapped simple " +
        "name of kotlin.Result, with no diagnostic: an unresolvable C# type (CS0246) in the " +
        "consumer's Interop.cs. Split out of ADR-108, which leaves the suspend route untouched."
  )
  fun `suspend method returning Result either binds correctly or skips named`() {
    val result = Tier1Harness.run(
      """
      package tier1.resultsuspend

      class Service {
        suspend fun load(): Result<String> = Result.success("ok")
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected no broken generated Kotlin for the suspend Result return; " +
          "got: ${result.compileErrors}",
    )
    // The `Result` test is deliberately narrow: `TaskCompletionSource`/`SetResult` legitimately
    // contain the substring "Result", so a whole-file search would report a real fix as still
    // broken and keep this @XFail alive forever.
    val boundCleanly: Boolean = "Load(" in result.generatedCSharp &&
        "<Result>" !in result.generatedCSharp
    val skippedNamed: Boolean = result.kspWarnings.any {
      it.contains("SKIPPED_") && it.contains("load")
    }
    assertTrue(
      boundCleanly || skippedNamed,
      "expected the suspend Result return either to bind with no `Result` in the C# surface or " +
          "to be skipped with a named diagnostic; kspWarnings=${result.kspWarnings}; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
  }
}
