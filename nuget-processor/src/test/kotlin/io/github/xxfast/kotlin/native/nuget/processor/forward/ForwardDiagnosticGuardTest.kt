package io.github.xxfast.kotlin.native.nuget.processor.forward

import io.github.xxfast.kotlin.native.nuget.processor.tier1.RecordingKSPLogger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-162: the containment boundary's own unit. The behaviour this pins is the one the item
 * exists for — a second offending declaration is still reported after the first — and it is
 * pinned here rather than only end to end because there is no shipped Kotlin shape that reaches a
 * raw `error(...)` today (every historical one, issue #52 / ADR-080 / ADR-081 / ADR-097, has since
 * been fixed, and the research spikes had to inject a throw). A test may not inject one, so the
 * guard is exercised directly.
 */
class ForwardDiagnosticGuardTest {

  @BeforeTest
  fun resetRound() {
    ForwardDiagnosticSink.reset()
  }

  @Test
  fun `two failing declarations are both reported and neither escapes`() {
    val logger = RecordingKSPLogger()

    val results: List<Unit?> = listOf("sample.First", "sample.Second").map { name ->
      guarded(declaration = name, node = null, logger = logger) {
        error("No C# property type for SpecializedProtocol($name)")
      }
    }

    assertEquals(listOf<Unit?>(null, null), results, "a contained failure yields no value")
    assertEquals(2, logger.errors.size, "both failures must be reported; errors=${logger.errors}")
    assertTrue(
      logger.errors.all { ForwardDiagnosticKind.ERROR_INTERNAL_GENERATOR_FAILURE.name in it },
      "every contained failure carries the greppable kind; errors=${logger.errors}",
    )
    assertTrue(
      logger.errors[0].contains("sample.First") &&
          logger.errors[0].contains("No C# property type for SpecializedProtocol(sample.First)"),
      "the first failure names its declaration and the failure detail; errors=${logger.errors}",
    )
    assertTrue(
      logger.errors[1].contains("exclude(\"sample.Second\")"),
      "the hint names the exclude(...) line that unblocks this author; errors=${logger.errors}",
    )
    assertTrue(
      logger.warnings.isEmpty(),
      "a contained failure is fatal, never a warning; warnings=${logger.warnings}",
    )
  }

  /**
   * The same declaration is walked by both projections of ADR-062's plan, so a bug in a shared
   * helper fails twice for one declaration. The author should read it once.
   */
  @Test
  fun `the same declaration failing twice the same way is reported once`() {
    val logger = RecordingKSPLogger()

    repeat(2) {
      guarded(declaration = "sample.Twice", node = null, logger = logger) {
        error("cannot build an input parameter for RawKSType")
      }
    }

    assertEquals(
      1, logger.errors.size, "deduped on (declaration, failure); errors=${logger.errors}",
    )
  }

  /** A different failure on the same declaration is a different bug and must not be swallowed. */
  @Test
  fun `a different failure on the same declaration is reported again`() {
    val logger = RecordingKSPLogger()

    guarded(declaration = "sample.Twice", node = null, logger = logger) {
      error("first invariant")
    }
    guarded(declaration = "sample.Twice", node = null, logger = logger) {
      error("second invariant")
    }

    assertEquals(2, logger.errors.size, "errors=${logger.errors}")
  }

  /**
   * `Exception` only. An `Error` is not a fact about one declaration and must still abort the
   * round, or a build that ran out of memory would report a hundred "generator bug" lines and
   * ship nothing useful.
   */
  @Test
  fun `an Error is not contained`() {
    val logger = RecordingKSPLogger()

    val thrown: Throwable? = runCatching {
      guarded(declaration = "sample.Oom", node = null, logger = logger) {
        throw StackOverflowError("deep recursion in the classifier")
      }
    }.exceptionOrNull()

    assertTrue(thrown is StackOverflowError, "expected the Error to propagate; thrown=$thrown")
    assertTrue(logger.errors.isEmpty(), "errors=${logger.errors}")
  }

  /** A successful block is transparent: the guard is not allowed to change the happy path. */
  @Test
  fun `a successful block returns its value`() {
    val logger = RecordingKSPLogger()

    val value: String? = guarded("sample.Fine", node = null, logger = logger) { "planned" }

    assertEquals("planned", value)
    assertTrue(logger.errors.isEmpty())
  }

  /** The plan-time half of the same kind: the skip reason a contained plan failure carries. */
  @Test
  fun `the plan-time internal failure reason maps to the fatal kind`() {
    assertEquals(
      ForwardDiagnosticKind.ERROR_INTERNAL_GENERATOR_FAILURE,
      ForwardPlanSkipReason.INTERNAL_FAILURE.toDiagnosticKind(),
    )
    assertTrue(
      ForwardPlanSkipReason.INTERNAL_FAILURE.droppedFromCSharp,
      "a contained plan failure must reach the drop-reporting path, or nothing reports it",
    )
    val reason: String = ForwardPlanSkipReason.INTERNAL_FAILURE.diagnosticReason(
      detail = "IllegalStateException: boom",
    )
    assertTrue(reason.contains("IllegalStateException: boom"), "reason=$reason")
    assertTrue(
      ForwardPlanSkipReason.INTERNAL_FAILURE.diagnosticHint().contains("exclude(...)"),
      "the plan-time hint carries the same unblocking advice",
    )
  }

  /**
   * ADR-162: the additive `file`/`line` the Gradle re-emitter composes its leading location from.
   */
  @Test
  fun `a recorded diagnostic with no node carries no location fields`() {
    val logger = RecordingKSPLogger()
    ForwardDiagnosticSink.emit(
      listOf(
        ForwardDiagnostic(
          kind = ForwardDiagnosticKind.SKIPPED_ALL_DECLARATIONS,
          symbol = null,
          declaration = "sample",
          reason = "nothing is in scope",
          hint = "widen the scope",
          owner = null,
        ),
      ),
      logger,
    )

    val record: ForwardDiagnosticRecord = ForwardDiagnosticSink.recorded().single()
    assertNull(record.file)
    assertNull(record.line)
    assertTrue(
      "\"file\"" !in renderForwardDiagnosticsJson(listOf(record)),
      "an absent location is omitted, not written as null",
    )
  }
}
