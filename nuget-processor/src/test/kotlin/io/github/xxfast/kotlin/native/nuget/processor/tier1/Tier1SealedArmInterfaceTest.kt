package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-101 amendment (2026-09-27): a sealed arm lists its own exported interfaces after its sealed
 * base, by the ordinary class's rule, and binds every member those interfaces owe it, defaults
 * included. It used to render `: Perch` alone, so `arm is ITally` was false in C#.
 *
 * - `Perch.Arm` is the ADR-168 shape on an arm: its `override var count` over the base's `open val
 *   count` stays a get-only public `override` (CS0546), and `ITally.Count` takes the setter
 *   explicitly. `Groomable.brushes()` is a default the arm never declares.
 * - `Nook.Box` is an eligible sealed interface's arm: the sealed interface itself stays off the
 *   arm's base list (it renders as an abstract class, not `INook`), and a plain `var` keeps its
 *   public setter.
 * - `Perch.Draught` implements an interface from a real dependency jar, outside the export set:
 *   named with `SKIPPED_UNEXPORTED_SUPERTYPE`, and its members, a `suspend` default and a
 *   lambda-parameter default included, re-home onto the arm through their own routes.
 * - `Roost.Ledge` sits under a sealed base that already implements `Groomable`: the base carries
 *   it, so the arm lists only `ITally` and binds none of `Groomable`'s members again.
 */
class Tier1SealedArmInterfaceTest {

  private val dependencyJar: File = Tier1DependencyLibrary.compile(
    """
    package dep.outside

    interface OutsideHeater {
      val fins: Int
      val ticks: Int get() = 7
      fun hum(): String = "hums through ${'$'}fins fins"
      suspend fun warmUp(): Int = fins * 2
      fun onTick(block: (Int) -> Unit) { block(ticks) }
    }
    """.trimIndent(),
    fileName = "OutsideHeater.kt",
  )

  private val fixture: String = """
    package tier1.armiface

    import dep.outside.OutsideHeater

    interface Groomable {
      fun groom(): String
      fun brushes(): Int = 1
    }

    interface Tally {
      var count: Int
    }

    sealed class Perch {
      open val count: Int = 0

      class Arm : Perch(), Tally, Groomable {
        override var count: Int = 0
        override fun groom(): String = "g"
      }

      class Draught : Perch(), OutsideHeater {
        override val fins: Int = 4
      }

      object Bare : Perch()
    }

    sealed interface Nook {
      class Box : Nook, Tally, Groomable {
        override var count: Int = 0
        override fun groom(): String = "box"
      }
    }

    sealed class Roost : Groomable {
      override fun groom(): String = "roost"

      class Ledge : Roost(), Tally {
        override var count: Int = 0
      }
    }
  """.trimIndent()

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      fixture,
      processorOptions = mapOf("nuget.rootPackage" to "tier1.armiface"),
      libraries = listOf(dependencyJar),
    )
  }

  private fun arm(header: String): String {
    val cs: String = result.generatedCSharp
    val start: Int = cs.indexOf(header)
    assertTrue(start >= 0, "expected `$header` in:\n$cs")
    return cs.substring(start, cs.indexOf("\n        }", start))
  }

  @Test
  fun `the Kotlin half compiles`() {
    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
  }

  @Test
  fun `an arm lists its own interfaces after the sealed base`() {
    val cs: String = result.generatedCSharp
    assertContains(cs, "public sealed class Arm : Perch, global::Interop.ITally, global::Interop.IGroomable\n")
    assertContains(cs, "public sealed class Bare : Perch\n")
  }

  @Test
  fun `an interface default the arm never declares binds on the arm`() {
    assertContains(result.generated, "export_library_perch_arm_brushes")
    assertContains(arm("public sealed class Arm"), "public int Brushes()")
    assertContains(arm("public sealed class Arm"), "public string Groom()")
  }

  @Test
  fun `an arm over a read-only base takes the interface setter explicitly`() {
    val arm: String = arm("public sealed class Arm")
    assertContains(arm, "public override int Count\n")
    assertContains(arm, "int global::Interop.ITally.Count\n")
    assertContains(arm, "get => Count;")
    assertContains(result.generated, "export_library_perch_arm_set_count")
    assertTrue(
      result.kspWarnings.any {
        it.contains("tier1.armiface.Perch.Arm.count") && it.contains("ITally.Count")
      },
      "expected the CS0546 skip to name ITally.Count; got: ${result.kspWarnings}",
    )
  }

  @Test
  fun `a sealed interface arm lists its interfaces but never the sealed interface`() {
    val cs: String = result.generatedCSharp
    assertContains(cs, "public sealed class Box : Nook, global::Interop.ITally, global::Interop.IGroomable\n")
    assertFalse("INook" in cs, cs)
    val box: String = arm("public sealed class Box")
    assertFalse(box.contains("ITally.Count"), box)
    assertContains(box, "public int Brushes()")
    assertContains(result.generated, "export_library_nook_box_set_count")
  }

  @Test
  fun `an unexported interface on an arm is named and its members re-home onto the arm`() {
    val cs: String = result.generatedCSharp
    // The re-homed `suspend` default hands the arm a coroutine scope of its own (ADR-118).
    assertContains(cs, "public sealed class Draught : Perch, IAsyncDisposable\n")
    assertFalse("OutsideHeater" in cs, cs)
    listOf(
      "library_perch_draught_hum",
      "library_perch_draught_get_fins",
      "library_perch_draught_get_ticks",
      "library_perch_draught_warmUp_async",
      "library_perch_draught_onTick",
    ).forEach { export ->
      assertTrue(export in result.generated, "missing $export; generated:\n${result.generated}")
    }
    val draught: String = arm("public sealed class Draught")
    assertContains(draught, "public string Hum()")
    assertContains(draught, "WarmUpAsync(")
    assertContains(draught, "public void OnTick(")
    val warnings: List<String> = result.kspWarnings
      .filter { it.contains(ForwardDiagnosticKind.SKIPPED_UNEXPORTED_SUPERTYPE.name) }
      .filter { it.contains("Perch.Draught") }
    assertEquals(1, warnings.size, "kspWarnings=${result.kspWarnings}")
    assertContains(warnings.single(), "dep.outside.OutsideHeater")
  }

  @Test
  fun `an interface the sealed base carries stays off the arm`() {
    val cs: String = result.generatedCSharp
    assertContains(cs, "public abstract class Roost : IGroomable, IDisposable, INugetHandle")
    assertContains(cs, "public sealed class Ledge : Roost, global::Interop.ITally\n")
    assertFalse("export_library_roost_ledge_brushes" in result.generated, result.generated)
    assertFalse("export_library_roost_ledge_groom" in result.generated, result.generated)
  }
}
