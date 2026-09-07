package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ROADMAP Phase 3: a class whose *every* public constructor is skipped still ships as a C# type,
 * but a dead-looking one: only `internal Foo(IntPtr handle)`, so C# can receive an instance from
 * a Kotlin factory and can never construct one. Keeping the type is deliberate (`Issue54Drawing`
 * is exactly this shape and is produced by `sleepingCats()`/`curledCats()`); what was missing is
 * the signal.
 *
 * The two skip families reach the same outcome by different routes, so both are pinned:
 * - (a) a `droppedFromCSharp = true` skip (`UNDECLARED_ENUM`), which already warns *per
 *   constructor* but never said the type ends up unconstructible;
 * - (b) a `droppedFromCSharp = false` legacy-route deferral (`SEALED_PROTOCOL`), which never
 *   reaches `droppedCallables` and, since no legacy route re-emits a constructor, used to be
 *   silent in every channel.
 *
 * The controls guard against over-firing: a class where one of two constructors survives (c) and
 * an abstract class (d) are both constructible-or-not-by-design, and a factory returning the
 * unconstructible class (e) must still bind: the warning reports, it never drops.
 */
class Tier1NoPublicConstructorWarningTest {

  private val source: String = """
    package tier1.noctor

    class Owner {
      enum class Mode { ON, OFF }
      val label: String = "owner"
    }

    class Dial(mode: Owner.Mode) {
      val label: String = mode.name
    }

    sealed class Shape {
      data object Empty : Shape()
      data class Circle(val radius: Double) : Shape()
    }

    class Drawing(val shape: Shape) {
      val label: String = "drawing"
    }

    fun make(): Drawing = Drawing(Shape.Empty)

    class Meter(mode: Owner.Mode) {
      constructor(ticks: Int) : this(Owner.Mode.ON)

      val label: String = "meter"
    }

    abstract class Gauge(mode: Owner.Mode) {
      val label: String = "gauge"
    }
  """.trimIndent()

  private fun warnings(result: Tier1Result): List<String> = result.kspWarnings
    .filter { it.contains(ForwardDiagnosticKind.WARNING_NO_PUBLIC_CONSTRUCTOR.name) }

  @Test
  fun `a class whose only constructor is dropped warns once and keeps its type`() {
    val result = Tier1Harness.run(source)

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val dial: List<String> = warnings(result).filter { it.contains("Dial") }
    assertEquals(1, dial.size, "expected exactly one Dial warning; got: ${warnings(result)}")
    assertTrue(
      dial.single().contains("Keeping"),
      "expected the verb to say the type is kept, not skipped; got: ${dial.single()}",
    )
    assertTrue(
      dial.single().contains("factories"),
      "expected the message to point at Kotlin factories; got: ${dial.single()}",
    )
    assertTrue(
      result.generatedCSharp.contains("internal Dial(IntPtr handle)"),
      "expected Dial to keep its handle constructor; generatedCSharp=" +
          "${result.generatedCSharp.lines().filter { it.contains("Dial") }}",
    )
    assertFalse(
      result.generatedCSharp.contains("public Dial("),
      "expected no public Dial constructor; generatedCSharp=" +
          "${result.generatedCSharp.lines().filter { it.contains("Dial") }}",
    )
  }

  @Test
  fun `a legacy-route deferral that no route re-emits warns too`() {
    val result = Tier1Harness.run(source)

    val drawing: List<String> = warnings(result).filter { it.contains("Drawing") }
    assertEquals(
      1,
      drawing.size,
      "expected exactly one Drawing warning; got: ${warnings(result)}",
    )
    assertTrue(
      result.generatedCSharp.contains("internal Drawing(IntPtr handle)"),
      "expected Drawing to keep its handle constructor; generatedCSharp=" +
          "${result.generatedCSharp.lines().filter { it.contains("Drawing") }}",
    )
  }

  @Test
  fun `a class with one surviving constructor never warns`() {
    val result = Tier1Harness.run(source)

    assertTrue(
      warnings(result).none { it.contains("Meter") },
      "expected no Meter warning; got: ${warnings(result)}",
    )
    assertTrue(
      result.generated.contains("meter_create_2"),
      "expected the surviving Meter constructor to still export; generated=${result.generated}",
    )
  }

  @Test
  fun `an abstract class never warns`() {
    val result = Tier1Harness.run(source)

    assertTrue(
      warnings(result).none { it.contains("Gauge") },
      "expected no Gauge warning; got: ${warnings(result)}",
    )
  }

  @Test
  fun `a factory returning the unconstructible class still binds`() {
    val result = Tier1Harness.run(source)

    assertTrue(
      result.generated.contains("make"),
      "expected the factory to survive the warning; generated=${result.generated}",
    )
    assertTrue(
      result.generatedCSharp.contains("Drawing make()"),
      "expected the factory to bind in C#; generatedCSharp=" +
          "${result.generatedCSharp.lines().filter { it.contains("make") }}",
    )
  }
}
