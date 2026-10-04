package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A value class's own member keeps the ADR-014 no-errorOut ABI, and neither half of that route
 * has a nullable result arm: the Kotlin emitter's `Nullable` arm required an error slot and threw,
 * so ANY nullable result (`String?`, `Int?`, `Throwable?`, a nullable getter) failed the whole KSP
 * run with `ERROR_INTERNAL_GENERATOR_FAILURE`. It is now a named `SKIPPED_UNSUPPORTED_RETURN` at
 * the planner, both halves omit the member, and the value class's other members still bind.
 *
 * A value class OVER a nullable type is a different shape: its constructor carries an error slot,
 * so its nullable underlying result still binds.
 *
 * `@JvmInline` is only for the JVM harness (ADR-060's Tier 1 constraint).
 *
 * Mylo's collar tag has his name on it; his nickname is a matter of opinion.
 */
class Tier1ValueClassNullableResultTest {

  @Test
  fun `a nullable result on a value class member skips named and its siblings bind`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.vcnullable

      @JvmInline
      value class Tag(val name: String) {
        fun label(): String? = null
        fun count(): Int? = null
        fun mishap(): Throwable? = null
        val nick: String? get() = null
        fun upper(): String = name.uppercase()
      }

      @JvmInline
      value class Maybe(val inner: String?)

      fun tag(): Tag = Tag("Mylo")
      fun maybe(): Maybe = Maybe(null)
      """.trimIndent(),
      processorOptions = mapOf("nuget.rootPackage" to "tier1"),
    )

    assertTrue(result.kspSucceeded, "expected KSP to succeed; got: ${result.kspErrors}")
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    val cs: String = result.generatedCSharp
    assertContains(cs, "public string Upper()")
    for (member in listOf(" Label()", " Count()", " Mishap()", " Nick\r?$")) {
      assertFalse(
        Regex("""public [^\n]*$member""", RegexOption.MULTILINE).containsMatchIn(cs),
        "expected $member to be absent; generated=$cs",
      )
    }
    for (member in listOf("label", "count", "mishap", "nick")) {
      assertFalse(
        "tag_$member" in result.generated,
        "expected no Kotlin export for $member; generated=${result.generated}",
      )
      assertTrue(
        result.kspWarnings.any {
          it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN.name) &&
              it.contains("Tag.$member") && it.contains("nullable return type")
        },
        "expected a named return skip for Tag.$member; kspWarnings=${result.kspWarnings}",
      )
    }
    Tier1CSharpCompile.assertCompiles(result, "public static class Consumer { }")
  }
}
