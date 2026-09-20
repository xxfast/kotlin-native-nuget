package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ROADMAP line 28: the core helpers (`NugetMarshal`, `NugetErrorNative`) used to be emitted only
 * when `CirTranslator.needsCoreMarshal` saw a top-level function, class, object or sealed class in
 * the module. That was an allow-list over thirteen declaration-kind lists, so every module built
 * out of the other nine kinds rendered `Interop.cs` text that calls `NugetMarshal.HandleOf` /
 * `NugetErrorNative.BuildException` without ever declaring them: C# CS0103 for the consumer, and
 * invisible to the forward ABI contract (which only reports a C# import with no Kotlin export, not
 * the reverse).
 *
 * The invariant pinned here is the durable one, whatever the gate does next: **referenced implies
 * declared**. Each cell is a single-shape module, so nothing else in the fixture can open a gate on
 * the shape's behalf; each cell first asserts the shape genuinely *references* the helper, because
 * an implication over an unreferenced helper is vacuously true and would rot silently.
 */
class Tier1CoreHelpersAlwaysEmittedTest {

  private val coreHelpers: List<String> = listOf("NugetMarshal", "NugetErrorNative")

  /**
   * "Every helper the rendered C# calls into is also declared in the same file." [loadBearing]
   * names the helpers this shape is expected to reach; a helper it does not reach is not asserted
   * on (other shapes cover those), but a load-bearing helper that stops being referenced fails
   * loudly rather than turning the cell green for the wrong reason.
   */
  private fun assertReferencedImpliesDeclared(
    source: String,
    loadBearing: List<String> = coreHelpers,
  ) {
    val cs: String = Tier1Harness.run(source).generatedCSharp

    loadBearing.forEach { helper ->
      assertTrue(
        "$helper." in cs,
        "this fixture no longer reaches $helper, so the cell proves nothing; pick a shape that " +
            "does, or move the helper out of `loadBearing`. generatedCSharp:\n$cs",
      )
    }

    coreHelpers.forEach { helper ->
      if ("$helper." !in cs) return@forEach
      assertTrue(
        "static class $helper" in cs,
        "the generated C# calls $helper.* but never declares $helper (CS0103 in the consumer); " +
            "generatedCSharp:\n$cs",
      )
    }
  }

  @Test
  fun `an interface plus an extension function over it declares the helpers it calls`() {
    assertReferencedImpliesDeclared(
      """
      package tier1.corehelpers.ifaceextfun

      interface Sitter {
        fun house(): String
      }

      fun Sitter.address(): String = "at ${'$'}{house()}"
      """.trimIndent(),
    )
  }

  @Test
  fun `an interface plus an extension property over it declares the helpers it calls`() {
    assertReferencedImpliesDeclared(
      """
      package tier1.corehelpers.ifaceextprop

      interface Sitter {
        fun house(): String
      }

      val Sitter.address: String get() = "at ${'$'}{house()}"
      """.trimIndent(),
    )
  }

  @Test
  fun `an extension over a stdlib receiver alone declares the helpers it calls`() {
    assertReferencedImpliesDeclared(
      """
      package tier1.corehelpers.stdlibreceiver

      val String.shout: String get() = uppercase()
      """.trimIndent(),
      // The receiver is a `string` wire value, so nothing mints a handle here; what reaches out of
      // the module is the ADR-033 error slot every planned accessor carries.
      loadBearing = listOf("NugetErrorNative"),
    )
  }

  @Test
  fun `a top-level property alone declares the helpers it calls`() {
    assertReferencedImpliesDeclared(
      """
      package tier1.corehelpers.topleveproperty

      val answer: Int = 42

      var greeting: String = "hi"
      """.trimIndent(),
      loadBearing = listOf("NugetErrorNative"),
    )
  }

  @Test
  fun `a generic function alone declares the helpers it calls`() {
    assertReferencedImpliesDeclared(
      """
      package tier1.corehelpers.genericfunction

      fun <T> pick(x: T): T = x
      """.trimIndent(),
    )
  }

  /**
   * ROADMAP line 248, "a consumer file containing only generic classes fails generation outright":
   * the same emission family, one kind list over. Measured 2026-09-20 on the unmodified gate: this
   * shape did NOT reproduce (a generic class is in `classes`, which was on the allow-list, and the
   * run produced a complete `Interop.cs` with both helpers declared and no KSP error). Pinned here
   * anyway so the line closes against a test rather than against an argument.
   */
  @Test
  fun `a generic class alone generates cleanly and declares the helpers it calls`() {
    val source: String = """
      package tier1.corehelpers.genericclass

      class Box<T>(val value: T) {
        fun unwrap(): T = value
      }
      """.trimIndent()
    val result = Tier1Harness.run(source)

    assertTrue(
      result.kspErrors.isEmpty(),
      "generation must not fail for a module of generic classes; got: ${result.kspErrors}",
    )
    assertTrue(
      "class Box" in result.generatedCSharp,
      "expected the generic class binding itself; generatedCSharp:\n${result.generatedCSharp}",
    )
    assertReferencedImpliesDeclared(source)
  }

  @Test
  fun `a value class alone declares the helpers it calls`() {
    assertReferencedImpliesDeclared(
      """
      package tier1.corehelpers.valueclass

      @JvmInline
      value class ChartId(val value: String) {
        init { require(value.isNotBlank()) }

        fun isValid(): Boolean = value.isNotBlank()
      }
      """.trimIndent(),
      loadBearing = listOf("NugetErrorNative"),
    )
  }

  /**
   * The accepted scope change (human gate, ROADMAP line 28): an enum-only module references no core
   * helper at all, and now gets them anyway. Uniform surface per generated assembly is the price of
   * the fix being a deletion rather than a smarter allow-list; `Tier1RuntimeVersionTest` used to
   * pin the opposite and is re-pinned alongside this cell.
   */
  @Test
  fun `an enum-only module gets the core helpers it never calls`() {
    val cs: String = Tier1Harness.run(
      """
      package tier1.corehelpers.enumonly

      enum class Collar { RED, BLUE }
      """.trimIndent(),
    ).generatedCSharp

    // Marshal-free by construction: an enum renders as a C# `enum` plus ordinal bridges and reaches
    // no helper (`CirEnumRenderer` spells neither name). That cannot be asserted as
    // `"NugetMarshal." !in cs` any more, because the helper bodies now present in this file
    // reference each other; the fixture's shape is the control.
    assertTrue("enum Collar" in cs, "expected the enum binding itself; generatedCSharp:\n$cs")
    coreHelpers.forEach { helper ->
      assertTrue(
        "static class $helper" in cs,
        "the core helpers are unconditional since ROADMAP line 28: expected $helper declared " +
            "even here; generatedCSharp:\n$cs",
      )
    }
  }
}
