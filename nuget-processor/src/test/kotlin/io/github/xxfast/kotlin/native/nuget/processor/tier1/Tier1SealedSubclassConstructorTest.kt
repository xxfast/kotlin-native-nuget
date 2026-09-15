package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #222 / ADR-148, the `CLASS` half of [Tier1SealedSubclassObjectTest]. A sealed subclass of
 * kind `CLASS` never exported its public constructor, so every arm shipped with only its
 * `internal Deep(IntPtr handle)` and a consumer had no way to build one to hand back. That made
 * ADR-105's sealed-arm parameter position (issue #126) dead API: the signature was right and
 * nothing could fill it.
 *
 * The invariant: where a class sits in a hierarchy is a detail of the declaration route, not of
 * the API. If the parameters are bridgeable the constructor is bridgeable, and it is spelled
 * exactly as a non-subclass class with the same parameters is spelled.
 *
 * Both declaration positions, once each: `Deep` is nested inside its base, `Label` is a sibling
 * declared beside it (ADR-125 composes the same `${base}_${arm}` prefix for both). `Zoomies` is
 * the `data object` control that was already reachable off a return position and must not gain
 * one, since Kotlin gives an object no public constructor to export.
 *
 * Oreo naps twelve hours at a stretch. Mylo does not nap so much as stop moving at speed.
 */
class Tier1SealedSubclassConstructorTest {

  private val source: String =
    """
    package tier1.armctor

    sealed class Nap {
      data class Deep(val minutes: Int) : Nap()
      data object Zoomies : Nap()
    }

    data class Label(val text: String) : Nap()

    class Newsroom {
      fun napMinutes(nap: Nap.Deep): Int = nap.minutes
      fun describe(nap: Nap): String = when (nap) {
        is Nap.Deep -> "deep"
        is Label -> nap.text
        Nap.Zoomies -> "zoomies"
      }
    }
    """.trimIndent()

  private fun run(): Tier1Result = Tier1Harness.run(
    source,
    processorOptions = mapOf("nuget.rootPackage" to "tier1"),
  )

  @Test
  fun `a nested sealed subclass exports its public constructor`() {
    val result = run()

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    val cs: String = result.generatedCSharp

    // Chained through the inherited handle, the route `HighPerch : Roost.Perch` already uses: the
    // base owns `internal IntPtr _handle`, so the arm sets it after the native call.
    assertContains(cs, "public Deep(int minutes) : base(IntPtr.Zero)")
    assertContains(cs, "IntPtr handle = Native_Create(minutes, out IntPtr error);")
    assertContains(cs, "EntryPoint = \"nap_deep_create\"")
    // The handle constructor stays, and stays internal: a consumer has no legitimate handle.
    assertContains(cs, "internal Deep(IntPtr handle) : base(handle)")
  }

  @Test
  fun `the arm's constructor export is minted under the sealed prefix`() {
    val result = run()

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    val kotlin: String = result.generated

    assertContains(kotlin, "@CName(\"nap_deep_create\")")
    assertContains(kotlin, "tier1.armctor.Nap.Deep(minutes)")
    // Not the plain-class prefix: that one belongs to no declaration here (issue #110).
    assertFalse(
      kotlin.contains("@CName(\"deep_create\")"),
      "expected the arm prefix, not a plain-class one; generated=$kotlin",
    )
  }

  @Test
  fun `a sibling sealed subclass exports its public constructor too`() {
    val result = run()

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")

    assertContains(result.generatedCSharp, "public Label(string text) : base(IntPtr.Zero)")
    assertContains(result.generated, "@CName(\"nap_label_create\")")
  }

  @Test
  fun `an object arm gains no public constructor`() {
    val result = run()

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    val cs: String = result.generatedCSharp

    assertContains(cs, "internal Zoomies(IntPtr handle) : base(handle)")
    assertFalse(
      cs.contains("public Zoomies("),
      "an object arm has no public Kotlin constructor to export; generated=$cs",
    )
    assertFalse(
      result.generated.contains("nap_zoomies_create"),
      "expected no create export for an object arm; generated=${result.generated}",
    )
  }

  @Test
  fun `each arm declares exactly one public constructor`() {
    val result = run()

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    val cs: String = result.generatedCSharp

    // Issue #110's invariant, unmoved: the arm is declared once, so its constructor is too. A
    // second declaration of the same arm would show up here before it showed up as CS0101.
    assertEquals(
      1,
      Regex(Regex.escape("public Deep(")).findAll(cs).count(),
      "expected exactly one public Deep constructor; generated=$cs",
    )
    assertEquals(
      1,
      Regex(Regex.escape("public Label(")).findAll(cs).count(),
      "expected exactly one public Label constructor; generated=$cs",
    )
  }
}
