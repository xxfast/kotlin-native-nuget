package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * ADR-110 amendment (ROADMAP line 32): the CS0102 property/method name-collision guard on every
 * owner route that renders properties and methods into one C# type, and the CS0108 shape where a
 * declared member takes the C# name of an inherited member of the other kind.
 *
 * Kotlin gives properties and functions separate namespaces; C# does not. Every shape here is legal
 * Kotlin that used to generate uncompilable C# (CS0102) or a CS0108 hiding warning, which
 * `nugetCompileInterop` and a warnings-as-errors consumer both treat as a build failure. The
 * correct outcome is a FAILED generation naming the owner and both Kotlin declarations, which is
 * why these are Tier 1 cells and not `test-library` fixtures: a fixture would fail `packNuget`.
 */
class Tier1MemberNameCollisionTest {

  private fun Tier1Result.collision(vararg fragments: String): String {
    val error: String? = kspErrors.firstOrNull { message ->
      message.contains(ForwardDiagnosticKind.ERROR_CSHARP_NAME_COLLISION.name) &&
          fragments.all { fragment -> message.contains(fragment) }
    }
    assertTrue(
      error != null,
      "expected ERROR_CSHARP_NAME_COLLISION containing ${fragments.toList()}; kspErrors=$kspErrors",
    )
    return error
  }

  private fun Tier1Result.assertNoCollision() {
    assertTrue(
      kspErrors.none { it.contains(ForwardDiagnosticKind.ERROR_CSHARP_NAME_COLLISION.name) },
      "expected no collision error; kspErrors=$kspErrors",
    )
  }

  // ---- Rule 1: one C# type, a property/const and another member sharing a name (CS0102) ----

  @Test
  fun `a class property and method sharing a C# name fail and name both`() {
    val result = Tier1Harness.run(
      """
      package tier1.membercollision.klass

      class Counter(val start: Int) {
        val count: Int get() = start
        fun count(): Int = start + 1
      }
      """.trimIndent(),
    )

    val error: String = result.collision("Counter.Count")
    assertContains(error, "val count")
    assertContains(error, "fun count()")
    assertContains(error, "CS0102")
  }

  @Test
  fun `a method with parameters still collides with a property of its name`() {
    val result = Tier1Harness.run(
      """
      package tier1.membercollision.params

      class Tag(val text: String) {
        val label: String get() = text
        fun label(prefix: String): String = prefix + text
      }
      """.trimIndent(),
    )

    val error: String = result.collision("Tag.Label")
    assertContains(error, "val label")
    assertContains(error, "fun label()")
  }

  @Test
  fun `two Kotlin names that meet only after casing collide`() {
    val result = Tier1Harness.run(
      """
      package tier1.membercollision.casing

      class Box(val n: Int) {
        val size: Int get() = n
        fun Size(): Int = n
      }
      """.trimIndent(),
    )

    val error: String = result.collision("Box.Size")
    assertContains(error, "val size")
    assertContains(error, "fun Size()")
  }

  @Test
  fun `an instance method and a companion property share the one C# type`() {
    val result = Tier1Harness.run(
      """
      package tier1.membercollision.companioninstance

      class Till(val n: Int) {
        fun total(): Int = n
        companion object {
          val total: Int = 1
        }
      }
      """.trimIndent(),
    )

    val error: String = result.collision("Till.Total")
    assertContains(error, "val total")
    assertContains(error, "fun total()")
  }

  @Test
  fun `a companion property and a companion method collide`() {
    val result = Tier1Harness.run(
      """
      package tier1.membercollision.companionboth

      class Pool(val n: Int) {
        companion object {
          val shared: Int = 1
          fun shared(): Int = 2
        }
      }
      """.trimIndent(),
    )

    val error: String = result.collision("Pool.Shared")
    assertContains(error, "val shared")
    assertContains(error, "fun shared()")
  }

  @Test
  fun `a companion const collides with an instance method after constant casing`() {
    val result = Tier1Harness.run(
      """
      package tier1.membercollision.companionconst

      class Limit(val n: Int) {
        fun max(): Int = n
        companion object {
          const val MAX: Int = 1
        }
      }
      """.trimIndent(),
    )

    val error: String = result.collision("Limit.Max")
    assertContains(error, "const val MAX")
    assertContains(error, "fun max()")
  }

  @Test
  fun `an instance property and a companion property of one name collide`() {
    val result = Tier1Harness.run(
      """
      package tier1.membercollision.twoproperties

      class Stock(val n: Int) {
        val count: Int get() = n
        companion object {
          val count: Int = 0
        }
      }
      """.trimIndent(),
    )

    val error: String = result.collision("Stock.Count")
    assertContains(error, "val count")
  }

  @Test
  fun `a generic class property and method collide`() {
    val result = Tier1Harness.run(
      """
      package tier1.membercollision.generic

      class Crate<T>(val item: T) {
        fun item(n: Int): T = item
      }
      """.trimIndent(),
    )

    val error: String = result.collision("Crate.Item")
    assertContains(error, "val item")
    assertContains(error, "fun item()")
  }

  @Test
  fun `a value class underlying property and a method of its name collide`() {
    val result = Tier1Harness.run(
      """
      package tier1.membercollision.valueclass

      @JvmInline
      value class Meters(val value: Double) {
        fun value(x: Int): Double = value * x
      }
      """.trimIndent(),
    )

    val error: String = result.collision("Meters.Value")
    assertContains(error, "val value")
    assertContains(error, "fun value()")
  }

  @Test
  fun `a sealed base property and method collide`() {
    val result = Tier1Harness.run(
      """
      package tier1.membercollision.sealedbase

      sealed class Shape {
        abstract val area: Double
        fun area(scale: Double): Double = area * scale

        data class Square(val side: Double) : Shape() {
          override val area: Double get() = side * side
        }
      }
      """.trimIndent(),
    )

    val error: String = result.collision("Shape.Area")
    assertContains(error, "val area")
    assertContains(error, "fun area()")
  }

  @Test
  fun `a sealed arm property and method collide`() {
    val result = Tier1Harness.run(
      """
      package tier1.membercollision.sealedarm

      sealed class Figure {
        data class Circle(val r: Double) : Figure() {
          fun r(): Double = r
        }
      }
      """.trimIndent(),
    )

    val error: String = result.collision("Circle.R")
    assertContains(error, "val r")
    assertContains(error, "fun r()")
  }

  // ---- The folded const-val cross-owner backlog item: two VALUE members after casing ----

  @Test
  fun `two consts meeting after casing on an object collide`() {
    val result = Tier1Harness.run(
      """
      package tier1.membercollision.objectconsts

      object Settings {
        const val MAX_RETRIES: Int = 3
        const val maxRetries: Int = 4
      }
      """.trimIndent(),
    )

    val error: String = result.collision("Settings.MaxRetries")
    assertContains(error, "const val MAX_RETRIES")
    assertContains(error, "const val maxRetries")
  }

  @Test
  fun `two consts meeting after casing on a companion collide`() {
    val result = Tier1Harness.run(
      """
      package tier1.membercollision.companionconsts

      class Retry(val n: Int) {
        companion object {
          const val MAX_RETRIES: Int = 3
          const val maxRetries: Int = 4
        }
      }
      """.trimIndent(),
    )

    val error: String = result.collision("Retry.MaxRetries")
    assertContains(error, "const val MAX_RETRIES")
    assertContains(error, "const val maxRetries")
  }

  @Test
  fun `two top-level consts meeting after casing collide on the file class`() {
    val result = Tier1Harness.run(
      """
      package tier1.membercollision.toplevelconsts

      const val MAX_RETRIES: Int = 3
      const val maxRetries: Int = 4
      """.trimIndent(),
      fileName = "Limits.kt",
    )

    val error: String = result.collision("Limits.MaxRetries")
    assertContains(error, "MAX_RETRIES")
    assertContains(error, "maxRetries")
  }

  @Test
  fun `a top-level const and a top-level property of one C# name collide`() {
    val result = Tier1Harness.run(
      """
      package tier1.membercollision.toplevelmixed

      const val LIMIT: Int = 3
      val limit: Int get() = 4
      """.trimIndent(),
      fileName = "Caps.kt",
    )

    result.collision("Caps.Limit")
  }

  // ---- Rule 2: a declared member hiding an inherited member of the other kind (CS0108) ----

  @Test
  fun `a method hiding an inherited generic-base property fails and points at the base`() {
    val result = Tier1Harness.run(
      """
      package tier1.membercollision.inheritedgeneric

      open class Parcel<T>(val value: T)

      class NamedParcel(name: String) : Parcel<String>(name) {
        fun value(prefix: String): String = prefix
      }
      """.trimIndent(),
    )

    val error: String = result.collision("NamedParcel.Value")
    assertContains(error, "Parcel")
    assertContains(error, "val value")
    assertContains(error, "fun value()")
    assertContains(error, "CS0108")
  }

  @Test
  fun `a method hiding an inherited property of a plain base fails`() {
    val result = Tier1Harness.run(
      """
      package tier1.membercollision.inheritedplain

      open class Base(val tag: String)

      class Derived(tag: String) : Base(tag) {
        fun tag(n: Int): String = n.toString()
      }
      """.trimIndent(),
    )

    val error: String = result.collision("Derived.Tag")
    assertContains(error, "Base")
    assertContains(error, "CS0108")
  }

  @Test
  fun `a property hiding an inherited method fails too`() {
    val result = Tier1Harness.run(
      """
      package tier1.membercollision.inheritedreverse

      open class Base2 {
        fun tag(n: Int): String = n.toString()
      }

      class Derived2(val tag: String) : Base2()
      """.trimIndent(),
    )

    val error: String = result.collision("Derived2.Tag")
    assertContains(error, "Base2")
    assertContains(error, "fun tag()")
    assertContains(error, "val tag")
  }

  @Test
  fun `a sealed arm method over a base property fails`() {
    val result = Tier1Harness.run(
      """
      package tier1.membercollision.inheritedsealed

      sealed class Pet {
        val label: String get() = "pet"

        class Dog : Pet() {
          fun label(x: Int): String = x.toString()
        }
      }
      """.trimIndent(),
    )

    result.collision("Dog.Label")
  }

  // ---- Controls ----

  @Test
  fun `an object property and method still fail with the object wording`() {
    val result = Tier1Harness.run(
      """
      package tier1.membercollision.objectcontrol

      object Jar {
        val count: Int get() = 1
        fun count(): Int = 2
      }
      """.trimIndent(),
    )

    val error: String = result.collision("Jar.Count")
    assertContains(error, "object Jar declares")
    assertContains(error, "CS0102")
  }

  @Test
  fun `a class with no shared names still generates`() {
    val result = Tier1Harness.run(
      """
      package tier1.membercollision.clean

      open class Base(val tag: String) {
        fun describe(): String = tag
      }

      class Derived(tag: String, val size: Int) : Base(tag) {
        fun grow(n: Int): Int = size + n
        companion object {
          const val MAX: Int = 3
          fun create(): Derived = Derived("x", 1)
        }
      }
      """.trimIndent(),
    )

    result.assertNoCollision()
    assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
    assertContains(result.generatedCSharp, "public int Grow(")
  }

  /**
   * ADR-113 post-implementation note 4 on the class route: issue #112's class keeps
   * `val collarTag` beside an unbridgeable `fun collarTag(code: Int): Sequence<Int>`, so only one
   * `CollarTag` survives projection. A guard over declarations would fail this working library.
   */
  @Test
  fun `a property beside an unbridgeable namesake method stays green`() {
    val result = Tier1Harness.run(
      """
      package tier1.membercollision.unbridgeable

      class Kennel(val collarTag: String) {
        fun collarTag(code: Int): Sequence<Int> = sequenceOf(code)
      }
      """.trimIndent(),
      fileName = "Issue112Sample.kt",
    )

    result.assertNoCollision()
    assertContains(result.generatedCSharp, "CollarTag")
  }
}
