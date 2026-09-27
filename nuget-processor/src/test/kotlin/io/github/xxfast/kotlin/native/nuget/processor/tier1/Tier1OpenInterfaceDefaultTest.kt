package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * An interface default inherited by an `open` owner is overridable in Kotlin, so a Kotlin subclass
 * can override it. The C# twin has to agree: `virtual` on the open owner, `override` on the
 * subclass, or the pair is CS0506 (`override` of a non-virtual member).
 */
class Tier1OpenInterfaceDefaultTest {

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      """
      package tier1.opendefault

      interface Groomable {
        fun groom(): String = "a quick lick"
        val brushes: Int get() = 1
      }

      open class Shelf : Groomable

      class Ledge : Shelf() {
        override fun groom(): String = "a long brush"
        override val brushes: Int get() = 3
      }

      sealed class Perch {
        open class Arm : Perch(), Groomable
      }

      class Sill : Perch.Arm() {
        override fun groom(): String = "a sunny stretch"
        override val brushes: Int get() = 2
      }
      """.trimIndent(),
    )
  }

  @Test
  fun `the generated Kotlin compiles`() {
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
  }

  @Test
  fun `an open class makes an inherited interface default virtual`() {
    val cs: String = result.generatedCSharp
    assertContains(cs, "public virtual string Groom()")
    assertContains(cs, "public virtual int Brushes")
    assertContains(cs, "public override string Groom()")
    assertContains(cs, "public override int Brushes")
  }

  @Test
  fun `an open sealed arm makes an inherited interface default virtual`() {
    val cs: String = result.generatedCSharp
    val arm: String = cs.substring(cs.indexOf("public class Arm : Perch"))
    assertContains(arm.substringBefore("\n        }\n"), "public virtual string Groom()")
    assertContains(arm.substringBefore("\n        }\n"), "public virtual int Brushes")
    val sill: String = cs.substring(cs.indexOf("public class Sill : Perch.Arm"))
    assertContains(sill, "public override string Groom()")
    assertContains(sill, "public override int Brushes")
  }
}
