package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A method dropped because its type has no bridge at all was reported through the unnamed
 * "its UNSUPPORTED type combination is not supported" sentence, while the same drop on a property
 * named the offending type. Both routes now read the one sentence, which names the type.
 */
class Tier1UnsupportedTypeDiagnosticTest {

  private val source: String = """
    package tier1.unsupportedtype

    annotation class Tagged(val tag: String)

    class Holder {
      fun marker(): Tagged = Tagged("x")
      val tag: Tagged get() = Tagged("x")
    }
  """.trimIndent()

  private fun diagnostic(result: Tier1Result, member: String, kind: ForwardDiagnosticKind): String =
    requireNotNull(
      result.kspWarnings.firstOrNull { it.contains(kind.name) && it.contains(member) },
    ) { "expected a ${kind.name} skip for $member; kspWarnings=${result.kspWarnings}" }

  @Test
  fun `a method whose type has no bridge names the type`() {
    val result = Tier1Harness.run(source)

    val diagnostic: String =
      diagnostic(result, "Holder.marker", ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_TYPE)
    assertTrue(
      diagnostic.contains("its type `tier1.unsupportedtype.Tagged` is not supported"),
      "expected the type-naming sentence; got: $diagnostic",
    )
    assertFalse(
      diagnostic.contains("UNSUPPORTED type combination"),
      "expected the unnamed sentence to be gone; got: $diagnostic",
    )
  }

  @Test
  fun `the property route reads the same sentence`() {
    val result = Tier1Harness.run(source)

    val diagnostic: String =
      diagnostic(result, "Holder.tag", ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY)
    assertTrue(
      diagnostic.contains("its type `tier1.unsupportedtype.Tagged` is not supported"),
      "expected one sentence for both routes; got: $diagnostic",
    )
  }
}
