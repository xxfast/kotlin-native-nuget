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
   * `tier1.enumclash2.Coat.grooming`. Kotlin resolves `receiver.grooming` to the MEMBER, so the
   * extension is unreachable by call syntax and its export body would read the member. The
   * extension-property route skips it as `SHADOWED_BY_MEMBER` (reported under
   * `SKIPPED_UNSUPPORTED_PROPERTY`, naming the enum member), which also removes the second claim on
   * the C entry point `…__coat_get_grooming` that used to stop generation at ADR-117's collision.
   * The member, found on the enum class's own declarations, keeps its export on both halves.
   */
  @Test
  fun `an enum member property shadows an extension property of one name, which is skipped`() {
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

    assertTrue(result.kspErrors.isEmpty(), "expected no KSP errors; kspErrors=${result.kspErrors}")
    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val shadowed: List<String> = result.kspWarnings.filter {
      it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY.name) &&
          it.contains("SHADOWED_BY_MEMBER")
    }
    assertTrue(shadowed.size == 1, "expected one shadow warning; kspWarnings=${result.kspWarnings}")
    assertTrue(shadowed.single().contains("`Coat.grooming` shadows it"), shadowed.single())

    // The member's export survives on both halves, exactly once.
    val entryPoint = "library_tier1_enumclash2__coat_get_grooming"
    assertTrue(
      Regex(Regex.escape("@CName(\"$entryPoint\")")).findAll(result.generated).count() == 1,
      "expected the member's single Kotlin export; generated=${result.generated}",
    )
    assertTrue(
      Regex(Regex.escape("EntryPoint = \"$entryPoint\"")).findAll(result.generatedCSharp).count() == 1,
      "expected the member's single C# import; csharp=${result.generatedCSharp}",
    )
  }
}
