package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-171: a value class at an erased generic position crosses through its own box/unbox export
 * pair. Every underlying the ordinary value-class route carries gets one (String, primitive, enum,
 * object handle, and an exported sealed class through its `FromHandle` discriminator). A value
 * class with no pair has no erased crossing, so a lambda over it is refused by name at build time
 * instead of being bound and throwing at the first `Invoke`.
 *
 * Oreo (black, white middle) and Mylo (brown and creamy) are on the ward again.
 */
class Tier1WrapValueClassTest {

  private val fixture: String = """
    package tier1.wrap

    class Patient(val name: String)

    sealed class Observation {
      data class Alive(val patient: Patient) : Observation()
      data class Gone(val cause: String) : Observation()
    }

    @JvmInline
    value class ChartId(val value: String)

    @JvmInline
    value class ChartRef(val patient: Patient)

    @JvmInline
    value class Finding(val observation: Observation)

    @JvmInline
    value class MaybeId(val value: String?)

    class Courier(val desk: String) {
      val onChart: (ChartId) -> String = { id -> "${'$'}desk filed ${'$'}{id.value}" }
      val onFinding: (Finding) -> String = { finding -> finding.observation.toString() }
      val onMaybe: (MaybeId) -> String = { id -> id.value ?: "none" }
    }
  """.trimIndent()

  private fun run(): Tier1Result = Tier1Harness.run(
    fixture,
    processorOptions = mapOf("nuget.namespace" to "Tier1"),
  )

  @Test
  fun `every handle-backed underlying, sealed included, gets a box and unbox export`() {
    val result = run()

    assertTrue(result.compiledClean, "expected the fixture to compile; got: ${result.compileErrors}")
    val kotlin: String = result.generated
    assertContains(kotlin, "NugetHandles.retain(tier1.wrap.ChartId(unboxed))")
    assertContains(kotlin, "(boxed.asStableRef<Any>().get() as tier1.wrap.ChartRef).patient")
    // ADR-105: the sealed base crosses as its handle, and reads back through the discriminator.
    assertContains(
      kotlin,
      "tier1.wrap.Finding(unboxed.asStableRef<tier1.wrap.Observation>().get())",
    )
    assertFalse(kotlin.contains("StableRef.create("), "handles are minted only through NugetHandles")

    val cs: String = result.generatedCSharp
    assertContains(cs, "global::Tier1.Finding.NugetBox((global::Tier1.Finding)value),")
    assertContains(cs, "[typeof(global::Tier1.Finding)] = static handle => global::Tier1.Finding.NugetUnbox(handle),")
    assertContains(
      cs,
      "return new global::Tier1.Finding(global::Tier1.Observation.FromHandle(nativeResult));",
    )
  }

  @Test
  fun `a lambda over a boxed value class binds, over one with no pair it is refused by name`() {
    val result = run()
    val cs: String = result.generatedCSharp

    assertContains(cs, "KotlinFunc<global::Tier1.ChartId, string> OnChart =>")
    assertContains(cs, "KotlinFunc<global::Tier1.Finding, string> OnFinding =>")

    listOf("OnMaybe" to "tier1.wrap.MaybeId")
      .forEach { (member, valueClass) ->
        assertFalse(cs.contains(" $member =>"), "expected no $member; a $valueClass has no box pair")
        val diagnostic: String? = result.kspWarnings.firstOrNull { warning ->
          warning.contains("[nuget:${ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY.name}]") &&
            warning.contains("Courier.${member.replaceFirstChar { it.lowercase() }}")
        }
        assertTrue(
          diagnostic != null && diagnostic.contains(valueClass),
          "expected a named skip for $member naming $valueClass; kspWarnings=${result.kspWarnings}",
        )
      }
  }

  /**
   * A value class over `Char` or over a plain interface used to abort KSP with
   * `ERROR_INTERNAL_GENERATOR_FAILURE` ("Forward ABI missing Kotlin export for ..._create"): the
   * planner called the underlying a value and planned `_create`, the Kotlin half called it a
   * reference and never emitted it. Both halves read `ForwardValueClassUnderlying` now, which
   * refuses both kinds by name, and every member typed with one skips on its own owner.
   */
  @Test
  fun `a value class over Char or over a plain interface is refused by name, never an abort`() {
    val result = Tier1Harness.run(
      """
      package tier1.refused

      interface Tagged {
        val tag: String
      }

      @JvmInline
      value class Initial(val letter: Char)

      @JvmInline
      value class TagRef(val tagged: Tagged)

      class Registrar(val desk: String) {
        fun initialOf(name: String): Initial = Initial(name.first())
        fun tagOf(ref: TagRef): String = ref.tagged.tag
        val onInitial: (Initial) -> String = { initial -> initial.letter.toString() }
        fun label(): String = desk
      }
      """.trimIndent(),
      processorOptions = mapOf("nuget.namespace" to "Tier1"),
    )

    assertTrue(result.kspErrors.isEmpty(), "expected no KSP error; got: ${result.kspErrors}")
    assertTrue(result.compiledClean, "expected the fixture to compile; got: ${result.compileErrors}")
    val cs: String = result.generatedCSharp
    assertContains(cs, "public string Label()")

    listOf("tier1.refused.Initial" to "kotlin.Char", "tier1.refused.TagRef" to "tier1.refused.Tagged")
      .forEach { (valueClass, underlying) ->
        val simple: String = valueClass.substringAfterLast('.')
        assertFalse(cs.contains("record struct $simple"), "expected no record struct for $simple")
        val declaration: String? = result.kspWarnings.firstOrNull { warning ->
          warning.contains("[nuget:${ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_TYPE.name}]") &&
            warning.contains("value class `$valueClass` wraps `$underlying`")
        }
        assertTrue(
          declaration != null,
          "expected $valueClass refused by name; kspWarnings=${result.kspWarnings}",
        )
      }

    // Members typed with a refused value class skip on their own owner, by name.
    listOf("Registrar.initialOf", "Registrar.tagOf", "Registrar.onInitial").forEach { member ->
      assertTrue(
        result.kspWarnings.any { it.contains("[nuget:SKIPPED_") && it.contains(member) },
        "expected a named skip for $member; kspWarnings=${result.kspWarnings}",
      )
    }
    assertFalse(cs.contains("InitialOf"), "a member typed with a refused value class is not bound")
    assertFalse(cs.contains("OnInitial"), "a lambda over a refused value class is not bound")
  }
}
