package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ADR-006 amendment: moving enum member properties onto the forward property plan puts them in the
 * same property catalog, and in the same `{Enum}Extensions` partial class, as the extensions
 * declared over that enum. Two shapes an author can legally write meet there.
 */
class Tier1EnumMemberExtensionNameClashTest {

  /**
   * A member `val grooming` and an extension `fun Coat.grooming()` both render
   * `Grooming(this Coat …)` in `CoatExtensions`: CS0111. Reported at generation time as ADR-034's
   * collision, naming both Kotlin declarations, rather than as a C# compile error in the consumer.
   */
  @Test
  fun `an enum member property and an extension function of one name fail generation naming both`() {
    val result = Tier1Harness.run(
      """
      package tier1.enumclash

      enum class Coat {
        TUXEDO, TABBY;

        val grooming: Int get() = ordinal
      }

      fun Coat.grooming(): Int = ordinal + 10
      """.trimIndent(),
    )

    val collisions: List<String> = result.kspErrors.filter {
      it.contains(ForwardDiagnosticKind.ERROR_CSHARP_SIGNATURE_COLLISION.name)
    }
    assertTrue(collisions.size == 1, "expected one collision; kspErrors=${result.kspErrors}")
    val message: String = collisions.single()
    assertTrue(message.contains("CoatExtensions.Grooming"), message)
    assertTrue(message.contains("`val grooming`"), "names the member: $message")
    assertTrue(message.contains("`fun Coat.grooming()`"), "names the extension: $message")
  }

  /**
   * A member `val grooming` and a (shadowed) extension `val Coat.grooming` share the plan symbol
   * `tier1.enumclash2.Coat.grooming`. The extension route's `propertyFor` lookup must return the
   * EXTENSION plan, never the member's. Both still claim the one C entry point
   * `…__coat_get_grooming` (the same scheme on both routes, and on the old enum route before it),
   * so generation stops at ADR-117's entry-point collision naming both owners, not at a processor
   * crash or a silently wrong binding.
   */
  @Test
  fun `an enum member property and an extension property of one name resolve to their own plans`() {
    val result = Tier1Harness.run(
      """
      package tier1.enumclash2

      enum class Coat {
        TUXEDO, TABBY;

        val grooming: Int get() = ordinal
      }

      @Suppress("EXTENSION_SHADOWED_BY_MEMBER")
      val Coat.grooming: Int get() = ordinal + 10
      """.trimIndent(),
    )

    assertTrue(
      result.kspErrors.none { it.contains("duplicate plans") },
      "the catalog lookup must not see two plans for one symbol; kspErrors=${result.kspErrors}",
    )
    val collisions: List<String> = result.kspErrors.filter {
      it.contains(ForwardDiagnosticKind.ERROR_C_ENTRY_POINT_COLLISION.name)
    }
    assertTrue(
      collisions.size == 1,
      "expected one entry-point collision; kspErrors=${result.kspErrors}",
    )
    assertTrue(collisions.single().contains("coat_get_grooming"), collisions.single())
    // Each route exported off its OWN plan: the member's export is tagged with its role, the
    // extension's is not. Had `propertyFor` handed the extension route the member's plan, the
    // extension projection would have refused it (it requires an EXTENSION plan) and the build
    // would have stopped at an internal generator failure instead.
    assertTrue(
      collisions.single().contains("  - tier1.enumclash2.Coat.grooming (enum member property)"),
      collisions.single(),
    )
    assertTrue(
      Regex("""  - tier1\.enumclash2\.Coat\.grooming(\.|\r?\n)""")
        .containsMatchIn(collisions.single()),
      collisions.single(),
    )
    assertTrue(
      result.kspErrors.none {
        it.contains(ForwardDiagnosticKind.ERROR_INTERNAL_GENERATOR_FAILURE.name)
      },
      "kspErrors=${result.kspErrors}",
    )
  }
}
