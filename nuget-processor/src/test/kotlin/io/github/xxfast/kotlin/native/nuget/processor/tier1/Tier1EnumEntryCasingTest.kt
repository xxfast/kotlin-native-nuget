package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #285 (dated amendment to ADR-006): an enum entry's internal capitals must survive the trip
 * to C#, so `SecondValue` is reachable as `Example.SecondValue` and not only as `Secondvalue`. The
 * old rule lowercased every `_` segment whole, which is right for the `SCREAMING_SNAKE_CASE` shape
 * ADR-006 considered and wrong for every other one Kotlin admits.
 *
 * Three cells the `test-library` fixture cannot carry, and why they are here:
 * - the casing cell duplicates the fixture's ground so a red signal does not need a NuGet pack,
 * - `_1ST` / `_` / `__` generate illegal C# today (`1st = 0`, an empty member name, both CS1001 in
 *   `Interop.cs` itself), so in `test-library` they would break every consumer test file,
 * - the collision cell's correct outcome is a FAILED generation, which would break `packNuget`.
 *
 * No ABI assertion anywhere in this file: an entry crosses as its ordinal `int` in every position,
 * so the fix cannot move a single export.
 */
class Tier1EnumEntryCasingTest {

  /** The casing table, one enum, one row per shape. */
  @Test
  fun `an entry keeps its internal capitals`() {
    val result = Tier1Harness.run(
      """
      package tier1.enumcasing

      enum class Example {
        First,
        SecondValue,
        camelCase,
        AB1C,
        SCREAMING_SNAKE,
        snake_case,
        XMLParser_V2,
        HTTP_Status,
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected the enum to bind; got: ${result.compileErrors}")

    val cs: String = result.generatedCSharp
    // Moved rows: the fix.
    assertContains(cs, "SecondValue = 1")
    assertContains(cs, "CamelCase = 2")
    // The mixed row: only a per-segment rule keeps the acronym AND joins across the `_`.
    assertContains(cs, "XMLParserV2 = 6")
    // Unchanged rows: the negative controls a verbatim-spelling fix would break.
    assertContains(cs, "First = 0")
    assertContains(cs, "Ab1c = 3")
    assertContains(cs, "ScreamingSnake = 4")
    assertContains(cs, "SnakeCase = 5")
    assertContains(cs, "HttpStatus = 7")
    // The pre-fix spellings, named so a regression reads as itself rather than as a missing line.
    assertFalse(cs.contains("Secondvalue"), "the PascalCase entry must not be flattened")
    assertFalse(cs.contains("Camelcase"), "the camelCase entry must not be flattened")
    assertFalse(cs.contains("XmlparserV2"), "the acronym segment must not be flattened")
  }

  /**
   * The two names Kotlin accepts as entries and C# cannot spell. Both were emitted raw before, so
   * `Interop.cs` itself did not compile (CS1001) and no diagnostic said why.
   */
  @Test
  fun `a digit-led or empty converted name gets an underscore`() {
    val result = Tier1Harness.run(
      """
      package tier1.enumcasing.guard

      enum class Digit { _1ST, OK }

      enum class Bare { _, OK }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected both enums to bind; got: ${result.compileErrors}")

    val cs: String = result.generatedCSharp
    assertContains(cs, "_1st = 0")
    assertContains(cs, "_ = 0")
    // Both enum bodies really rendered, so the two guarded members are not matching noise.
    assertContains(cs, "Ok = 1")
  }

  /**
   * The after-casing collision, fatal at generation. `FOO` beside `Foo` already collided before the
   * fix and rendered `Foo = 0, Foo = 1` silently (CS0102 at the consumer's compile); `FOO_BAR`
   * beside `FooBar` is the one pair the per-segment rule newly brings together. Both land on the
   * same error, which names every colliding Kotlin entry.
   *
   * Memo spike (a), resolved by running this cell: the error surfaces through
   * `ForwardDiagnosticSink` as a KSP error, not as an uncaught processor exception, so the
   * assertion reads `kspErrors` (the Tier 1 harness does not capture a throw).
   */
  @Test
  fun `two entries landing on one C-sharp name fail generation naming both`() {
    val result = Tier1Harness.run(
      """
      package tier1.enumcasing.collision

      enum class Clash { FOO_BAR, FooBar }
      """.trimIndent(),
    )

    assertTrue(
      result.kspErrors.any { message ->
        message.contains(ForwardDiagnosticKind.ERROR_CSHARP_NAME_COLLISION.name) &&
            message.contains("Clash.FooBar") &&
            message.contains("'FOO_BAR'") &&
            message.contains("'FooBar'")
      },
      "expected a collision naming both entries; kspErrors=${result.kspErrors}",
    )
  }

  /** The old silent pair, and the guarded-name pair, on the same error. */
  @Test
  fun `the pre-existing silent duplicate and the guarded empty pair are both fatal`() {
    val result = Tier1Harness.run(
      """
      package tier1.enumcasing.collision.legacy

      enum class Clash { FOO, Foo }

      enum class Bare { _, __ }
      """.trimIndent(),
    )

    assertTrue(
      result.kspErrors.any { message ->
        message.contains(ForwardDiagnosticKind.ERROR_CSHARP_NAME_COLLISION.name) &&
            message.contains("Clash.Foo") &&
            message.contains("'FOO'")
      },
      "expected the pre-existing FOO/Foo duplicate to be fatal now; kspErrors=${result.kspErrors}",
    )
    assertTrue(
      result.kspErrors.any { message ->
        message.contains(ForwardDiagnosticKind.ERROR_CSHARP_NAME_COLLISION.name) &&
            message.contains("Bare._")
      },
      "expected `_` and `__` to collide on the guarded name; kspErrors=${result.kspErrors}",
    )
  }
}
