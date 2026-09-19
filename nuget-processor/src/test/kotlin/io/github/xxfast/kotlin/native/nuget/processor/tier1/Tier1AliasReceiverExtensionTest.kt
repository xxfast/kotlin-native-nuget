package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-018 ("transparent expansion at every `type.resolve()` site") applied to an extension
 * FUNCTION's receiver: the C entry point and the C# extension class must be derived from the same
 * expanded type.
 *
 * `ForwardCallablePlanner.extensionEntry` used to read the receiver's declaration off a bare
 * `resolve()`, and an alias use resolves to a `KSTypeAlias`, so the `nativePrefix()` cast fell
 * through to the alias's own lowercased simple name. `CirTranslator` expands (ADR-126/133), and so
 * does `ForwardPropertyPlanner.extensionProperty`, so a `typealias Bird = Aviary.Bird` split the
 * two spellings: entry point `bird_sing` against class `AviaryBirdExtensions`, while the same
 * receiver's extension *property* already exported `aviary_bird_get_pitch`.
 */
class Tier1AliasReceiverExtensionTest {

  /**
   * `Bird2` is the control: a genuine top-level receiver whose entry point must NOT move, which is
   * what keeps every shipped extension symbol byte-identical.
   */
  private val source: String = """
    package tier1.aliasreceiver

    class Aviary {
      class Bird(val pitch: Int)
    }

    typealias Bird = Aviary.Bird

    class Bird2(val n: Int)

    fun Bird.sing(): String = "la"
    fun Bird2.sing(): String = "lo"
    val Bird.loudness: Int get() = pitch * 2
  """.trimIndent()

  @Test
  fun `an alias receiver takes the expanded type's entry point`() {
    val result = Tier1Harness.run(source)

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val kotlin: String = result.generated

    assertContains(kotlin, "@CName(\"aviary_bird_sing\")")
    assertFalse(
      kotlin.contains("\"bird_sing\""),
      "the alias's own name must not reach the C ABI; generated=$kotlin",
    )
    // The control, and the property twin that already expanded before this change.
    assertContains(kotlin, "@CName(\"bird2_sing\")")
    assertContains(kotlin, "@CName(\"aviary_bird_get_loudness\")")
  }

  @Test
  fun `the C# extension class and the entry point name the same type`() {
    val result = Tier1Harness.run(source)

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val csharp: String = result.generatedCSharp

    assertContains(csharp, "class AviaryBirdExtensions")
    assertFalse(
      csharp.contains("class BirdExtensions"),
      "the alias must not get an extension class of its own; csharp=$csharp",
    )
    assertContains(csharp, "EntryPoint = \"aviary_bird_sing\"")
    assertContains(csharp, "class Bird2Extensions")
  }

  /**
   * The finding this item was raised on: the stale spelling collides with a genuine top-level
   * `Bird` in another package, and `ERROR_C_ENTRY_POINT_COLLISION` fails the whole KSP round. The
   * expanded spelling is `aviary_bird_sing`, so there is nothing to collide with.
   */
  @Test
  fun `an alias of a nested type does not collide with a top-level receiver of the same name`() {
    val result = Tier1Harness.run(
      mapOf(
        "Aviary.kt" to """
          package tier1.aliasreceiver.nested

          class Aviary {
            class Bird(val pitch: Int)
          }

          typealias Bird = Aviary.Bird

          fun Bird.sing(): String = "la"
        """.trimIndent(),
        "Other.kt" to """
          package tier1.aliasreceiver.other

          class Bird(val pitch: Int)

          fun Bird.sing(): String = "lo"
        """.trimIndent(),
      ),
    )

    assertTrue(
      result.kspErrors.none { message ->
        message.contains(ForwardDiagnosticKind.ERROR_C_ENTRY_POINT_COLLISION.name)
      },
      "the expanded receiver leaves the top-level `Bird` its own symbol; " +
          "kspErrors=${result.kspErrors}",
    )
    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    assertContains(result.generated, "@CName(\"aviary_bird_sing\")")
    assertContains(result.generated, "@CName(\"bird_sing\")")
  }

  /**
   * The memo's open what-question: a nullable alias (`typealias MaybeBird = Aviary.Bird?`).
   * `expandAliases()` walks to the aliased type, whose declaration is still the class, so the
   * prefix is the expanded one here too.
   */
  @Test
  fun `a nullable alias receiver expands as well`() {
    val result = Tier1Harness.run(
      """
      package tier1.aliasreceiver.nullable

      class Aviary {
        class Bird(val pitch: Int)
      }

      typealias MaybeBird = Aviary.Bird?

      fun MaybeBird.hum(): String = "mm"
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    assertFalse(
      result.generated.contains("\"maybebird_hum\""),
      "the alias's own name must not reach the C ABI; generated=${result.generated}",
    )
    assertContains(result.generated, "@CName(\"aviary_bird_hum\")")
  }
}
