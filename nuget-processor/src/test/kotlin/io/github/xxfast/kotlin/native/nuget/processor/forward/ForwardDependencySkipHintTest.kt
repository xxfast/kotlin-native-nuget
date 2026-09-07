package io.github.xxfast.kotlin.native.nuget.processor.forward

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ROADMAP Phase 3: the four dependency-scope skips share one diagnostic kind and differ only in
 * their remedy. Unit-level because Tier 1 cannot present every refusal: its dependency `.jar` is a
 * JVM compilation, which has no way to carry an `expect` declaration into the KSP round.
 */
class ForwardDependencySkipHintTest {

  private val ownScope: List<String> = listOf("app.models")

  @Test
  fun `all four dependency skips render one kind, so ADR-109's remedy text stays true`() {
    listOf(
      ForwardPlanSkipReason.UNEXPORTED_DEPENDENCY_TYPE,
      ForwardPlanSkipReason.EXCLUDED_DEPENDENCY_TYPE,
      ForwardPlanSkipReason.EXPECT_DEPENDENCY_TYPE,
      ForwardPlanSkipReason.CROSS_MODULE_DISABLED_DEPENDENCY_TYPE,
    ).forEach { reason ->
      assertEquals(
        ForwardDiagnosticKind.SKIPPED_UNEXPORTED_DEPENDENCY_TYPE,
        reason.toDiagnosticKind(),
        "$reason must keep the shipped kind; only its hint differs",
      )
      assertTrue(reason.droppedFromCSharp, "$reason has no legacy route; it is a real drop")
    }
  }

  @Test
  fun `an excluded dependency type names the exclude, not an include that cannot override it`() {
    val hint: String = ForwardPlanSkipReason.EXCLUDED_DEPENDENCY_TYPE
      .diagnosticHint("dep.models.TopStory", ownScope)

    assertFalse("include(\"dep.models\")" in hint, "include cannot override an exclude; got: $hint")
    assertTrue("""exclude("dep.models")""" in hint, "got: $hint")
    assertTrue("dep.models.TopStory" in hint, "got: $hint")
    assertTrue("remove the exclude" in hint, "got: $hint")
  }

  @Test
  fun `an expect declaration in a dependency says no scope change can reach its actual`() {
    val hint: String = ForwardPlanSkipReason.EXPECT_DEPENDENCY_TYPE
      .diagnosticHint("dep.models.Ticker", ownScope)

    assertTrue("dep.models.Ticker" in hint, "got: $hint")
    assertTrue("`expect` declaration" in hint, "got: $hint")
    assertTrue("cannot be brought into scope with include(...)" in hint, "got: $hint")
    assertFalse("add include(" in hint, "got: $hint")
  }

  @Test
  fun `cross-module admission off names rootPackage and keeps the module's own packages`() {
    val hint: String = ForwardPlanSkipReason.CROSS_MODULE_DISABLED_DEPENDENCY_TYPE
      .diagnosticHint("dep.models.TopStory", scope = emptyList())

    assertTrue("rootPackage" in hint, "got: $hint")
    assertTrue("dep.models" in hint, "got: $hint")
    assertTrue("would drop your own files" in hint, "the trap must be named; got: $hint")
  }

  /** The unchanged case: a dependency package that is simply not in `include`. */
  @Test
  fun `a not-included dependency type keeps the include hint`() {
    val hint: String = ForwardPlanSkipReason.UNEXPORTED_DEPENDENCY_TYPE
      .diagnosticHint("dep.models.TopStory", ownScope)

    assertTrue("""add include("app.models", "dep.models")""" in hint, "got: $hint")
  }

  /** Every refusal the closure records must map somewhere; a new one must not silently inherit the
   *  `include(...)` hint. */
  @Test
  fun `every recorded refusal has its own skip reason`() {
    val reasons: List<ForwardPlanSkipReason> = ForwardAdmissionRefusal.entries.map { refusal ->
      when (refusal) {
        ForwardAdmissionRefusal.EXCLUDED_BY_CONFIG ->
          ForwardPlanSkipReason.EXCLUDED_DEPENDENCY_TYPE

        ForwardAdmissionRefusal.NOT_INCLUDED ->
          ForwardPlanSkipReason.UNEXPORTED_DEPENDENCY_TYPE

        ForwardAdmissionRefusal.CROSS_MODULE_ADMISSION_DISABLED ->
          ForwardPlanSkipReason.CROSS_MODULE_DISABLED_DEPENDENCY_TYPE

        ForwardAdmissionRefusal.EXPECT_IN_DEPENDENCY ->
          ForwardPlanSkipReason.EXPECT_DEPENDENCY_TYPE
      }
    }
    assertEquals(reasons.size, reasons.distinct().size, "one hint per refusal: $reasons")
  }
}
