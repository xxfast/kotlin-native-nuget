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

  // ADR-154: the hint no longer echoes the author's `include(...)` scope back at them (the
  // additive `admit(...)` line needs no such context). What a hint DOES read now is the author's
  // own `exclude(...)` entries, so it can quote the entry that matched (ROADMAP line 37).
  private val excludeEntries: List<String> = listOf("dep.models")

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
      .diagnosticHint("dep.models.TopStory", excludeEntries = excludeEntries)

    assertFalse("include(\"dep.models\")" in hint, "include cannot override an exclude; got: $hint")
    assertTrue("""exclude("dep.models")""" in hint, "got: $hint")
    assertTrue("dep.models.TopStory" in hint, "got: $hint")
    assertTrue("remove that exclude entry" in hint, "got: $hint")
  }

  /**
   * ROADMAP line 37 / issue #53: the same type, excluded BY NAME rather than by package. The
   * shipped hint derived a package from the type name and so quoted `exclude("dep.models")`, an
   * entry this author never wrote; the matched entry is now carried in and quoted verbatim.
   */
  @Test
  fun `a type-level exclude is quoted as the type, not as the package it derives from`() {
    val hint: String = ForwardPlanSkipReason.EXCLUDED_DEPENDENCY_TYPE
      .diagnosticHint(
        "dep.models.TopStory",
        excludeEntries = listOf("dep.models.TopStory"),
      )

    assertTrue("""exclude("dep.models.TopStory")""" in hint, "got: $hint")
    assertFalse("""exclude("dep.models")""" in hint, "no such entry was written; got: $hint")
    assertTrue("excluded by name" in hint, "got: $hint")
  }

  /** A NESTED type under a package-level exclude keeps quoting the package the author wrote. */
  @Test
  fun `a nested type under a package-level exclude quotes the package entry`() {
    val hint: String = ForwardPlanSkipReason.EXCLUDED_DEPENDENCY_TYPE
      .diagnosticHint("dep.models.Broadcast.AdBand", excludeEntries = excludeEntries)

    assertTrue("""exclude("dep.models")""" in hint, "got: $hint")
    assertFalse("""exclude("dep.models.Broadcast")""" in hint, "not an entry written; got: $hint")
  }

  @Test
  fun `an expect declaration in a dependency says no scope change can reach its actual`() {
    val hint: String = ForwardPlanSkipReason.EXPECT_DEPENDENCY_TYPE
      .diagnosticHint("dep.models.Ticker")

    assertTrue("dep.models.Ticker" in hint, "got: $hint")
    assertTrue("`expect` declaration" in hint, "got: $hint")
    assertTrue("cannot be brought into scope with include(...)" in hint, "got: $hint")
    assertFalse("add include(" in hint, "got: $hint")
  }

  @Test
  fun `cross-module admission off names admit and rootPackage, the two gates that open it`() {
    val hint: String = ForwardPlanSkipReason.CROSS_MODULE_DISABLED_DEPENDENCY_TYPE
      .diagnosticHint("dep.models.TopStory")

    assertTrue("rootPackage" in hint, "got: $hint")
    assertTrue("dep.models.TopStory" in hint, "got: $hint")
    // ADR-154 §2: `admit(...)` opens admission rule 4's gate on its own, and unlike the
    // `include(...)` line this hint used to spell, it cannot drop the module's own files — so the
    // "would drop your own files" trap this test used to pin no longer exists on this route.
    assertTrue("""admit("dep.models.TopStory")""" in hint, "got: $hint")
    assertFalse("include(" in hint, "the replacement verb is not the remedy here; got: $hint")
  }

  /** ADR-154 §5: the un-admitted case, now the ADDITIVE verb rather than the #60 include line. */
  @Test
  fun `a not-admitted dependency type names the additive admit line`() {
    val hint: String = ForwardPlanSkipReason.UNEXPORTED_DEPENDENCY_TYPE
      .diagnosticHint("dep.models.TopStory")

    assertTrue("""add admit("dep.models.TopStory")""" in hint, "got: $hint")
    assertTrue(""""dep.models"""" in hint, "the prefix arm is offered too; got: $hint")
    assertTrue("""exclude("dep.models.TopStory")""" in hint, "got: $hint")
    assertFalse("include(" in hint, "an additive verb needs no replacement line; got: $hint")
  }

  /**
   * ADR-154 implementation note 4: a NESTED refused type is admitted through its OWNER. Admitting
   * the nested name alone repairs nothing (the closure climbs to the owner, finds the longer entry
   * is not a prefix of it, and propagates the owner's refusal back down), so a hint quoting the
   * nested name would be a remedy that changes nothing — the exact defect the `EXCLUDED` hint was
   * fixed for on the other side. `exclude(...)` keeps the full name: it is tested on the
   * declaration itself, before the climb.
   */
  @Test
  fun `a nested dependency type is admitted through its owner, and excluded by its own name`() {
    val hint: String = ForwardPlanSkipReason.UNEXPORTED_DEPENDENCY_TYPE
      .diagnosticHint("dep.models.Broadcast.AdBand")

    assertTrue("""add admit("dep.models.Broadcast")""" in hint, "got: $hint")
    assertFalse("""admit("dep.models.Broadcast.AdBand")""" in hint, "no-op remedy; got: $hint")
    assertTrue("""exclude("dep.models.Broadcast.AdBand")""" in hint, "got: $hint")
  }

  /** With no type name in hand the hint still must not print the literal placeholder. */
  @Test
  fun `a detail-less dependency skip never prints the placeholder package`() {
    val hint: String = ForwardPlanSkipReason.UNEXPORTED_DEPENDENCY_TYPE.diagnosticHint()

    assertFalse("the dependency's package" in hint, "got: $hint")
    assertTrue("admit(" in hint, "got: $hint")
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

        ForwardAdmissionRefusal.NESTED_DECLARATION ->
          ForwardPlanSkipReason.UNDECLARED_CLASS
      }
    }
    assertEquals(reasons.size, reasons.distinct().size, "one hint per refusal: $reasons")
  }
}
