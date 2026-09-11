package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-111 / ADR-116 amendment (2026-09-11): a sealed **base**'s own `abstract`/`open` members
 * render on the generated C# base, so a consumer holding the base type can read them without
 * pattern-matching to an arm first.
 *
 * Before this, `CirSealedClass` carried neither `properties` nor `methods` and both planners were
 * arm-only, so the base rendered the handle, the constructor, `FromHandle` and `Dispose()` and
 * nothing else. An arm flattened every implemented base property onto itself, and a base `open fun`
 * no arm overrode reached neither artifact.
 *
 * The base member is spelled **concrete `virtual`**, never `abstract`: Kotlin's own dispatch picks
 * the arm's body through the base-keyed export, and an `abstract` base member cannot compile the
 * covariant cell below (`override int Sides` over `int? Sides` is CS1715, and the resulting missing
 * implementation is CS0534).
 */
class Tier1SealedBaseMemberTest {

  @Test
  fun `a sealed base's own members render virtual on the base and the arms spell override or new`() {
    val result = Tier1Harness.run(
      """
      package tier1.basemember

      fun anyOutline(): Outline = Outline.Curve(2.0)

      fun anyErrand(): Errand = Errand.Waiting

      sealed class Outline {
        abstract val sides: Int?

        abstract fun sketch(): String

        open fun self(): Outline = this

        data class Curve(val radius: Double) : Outline() {
          override val sides: Int? = null

          override fun sketch(): String = "curve"

          override fun self(): Curve = this
        }

        data object Empty : Outline() {
          override val sides: Int = 0

          override fun sketch(): String = "empty"
        }
      }

      sealed class Errand {
        open val kind: String = "errand"

        open fun describe(): String = "errand"

        data object Waiting : Errand()

        data object Running : Errand() {
          override val kind: String = "running"

          override fun describe(): String = "running"
        }
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected the base-member exports to compile; got: ${result.compileErrors}",
    )

    val csharp: String = result.generatedCSharp

    // The base carries its own declared members, concretely and virtually.
    assertContains(
      csharp,
      "public virtual int? Sides",
      message = "expected the sealed base to carry its own `abstract val` as a virtual member; " +
          "generatedCSharp:\n$csharp",
    )
    assertContains(
      csharp,
      "public virtual string Sketch()",
      message = "expected the sealed base to carry its own `abstract fun` as a virtual member; " +
          "generatedCSharp:\n$csharp",
    )
    assertContains(
      csharp,
      "public virtual string Kind",
      message = "expected the sealed base to carry its own `open val` as a virtual member; " +
          "generatedCSharp:\n$csharp",
    )
    assertContains(
      csharp,
      "public virtual string Describe()",
      message = "expected the sealed base to carry its own `open fun` as a virtual member; " +
          "generatedCSharp:\n$csharp",
    )
    assertFalse(
      "public abstract int? Sides" in csharp || "public abstract string Sketch()" in csharp,
      "a base member is concrete `virtual`, not `abstract`: an abstract one forces every arm to " +
          "declare an override, which the covariant arm cannot spell (CS1715 then CS0534); " +
          "generatedCSharp:\n$csharp",
    )

    // An arm whose C# shape matches the base's overrides it.
    assertContains(
      csharp,
      "public override int? Sides",
      message = "expected the matching-shape arm to spell its property `override`; " +
          "generatedCSharp:\n$csharp",
    )
    assertContains(
      csharp,
      "public override string Sketch()",
      message = "expected an overriding arm to spell its method `override`; " +
          "generatedCSharp:\n$csharp",
    )
    assertContains(
      csharp,
      "public override string Describe()",
      message = "expected the arm that overrides the base's `open fun` to spell it `override`; " +
          "generatedCSharp:\n$csharp",
    )

    // The covariant arm narrows `Int?` to `Int`, which C# cannot spell as an override.
    assertContains(
      csharp,
      "public new int Sides",
      message = "expected the covariant arm (`Int` over `Int?`) to hide with `new` rather than " +
          "override, which would be CS1715; generatedCSharp:\n$csharp",
    )
    assertFalse(
      "public override int Sides" in csharp,
      "a covariant arm property cannot be a C# override (CS1715); generatedCSharp:\n$csharp",
    )

    // The method-side twin: a covariant *return* (`Curve` narrowing `Outline`). C# decides hiding
    // on name and parameters alone, so the arm hides rather than overrides; without the modifier
    // the hide is CS0108, which `GeneratedBindingsCheck` compiles as an error.
    assertContains(
      csharp,
      "public virtual global::Interop.Outline Self()",
      message = "expected the base's own `open fun` returning the base type to render virtual; " +
          "generatedCSharp:\n$csharp",
    )
    assertContains(
      csharp,
      "public new global::Interop.Outline.Curve Self()",
      message = "expected the covariant-return arm method to hide the base's with `new`; " +
          "generatedCSharp:\n$csharp",
    )

    // An arm that overrides nothing declares nothing: it inherits the base's member.
    val waitingBlock: String = csharp
      .substringAfter("public sealed class Waiting : Errand")
      .substringBefore("public sealed class Running")
    assertFalse(
      "Kind" in waitingBlock || "Describe" in waitingBlock,
      "an arm that does not override inherits the base member and declares none of its own; " +
          "Waiting block:\n$waitingBlock",
    )

    // The base's exports are keyed to the base prefix, and take the base handle.
    val kotlin: String = result.generated
    assertContains(
      kotlin,
      "outline_get_sides",
      message = "expected a base-keyed getter export for the base's own property; " +
          "generated:\n$kotlin",
    )
    assertContains(
      kotlin,
      "errand_describe",
      message = "expected a base-keyed export for the base's own `open fun`; generated:\n$kotlin",
    )
    assertContains(
      kotlin,
      "asStableRef<tier1.basemember.Outline>",
      message = "expected the base export to dispatch through the base type, which is what makes " +
          "Kotlin pick the arm's body; generated:\n$kotlin",
    )
  }
}
