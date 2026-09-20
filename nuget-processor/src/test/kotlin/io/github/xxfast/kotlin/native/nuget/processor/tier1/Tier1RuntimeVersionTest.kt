package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-129: `NugetRuntime` is emitted for every library, so one that marshals nothing still
 * has a way to say which `nuget-runtime` its binary carries.
 *
 * The enum-only fixture is the load-bearing one: it is the shape that marshals nothing at
 * all, which is where an emission rule keyed off "does this module marshal anything" shows
 * itself. (ADR-129's §4 table says "one top-level scalar function"; that shape marshals
 * a string and would have proved nothing.) Since ROADMAP line 28 this no longer proves
 * `NugetRuntime` is emitted ALONE: the `needsCoreMarshal` gate it used to be the exception to
 * was an allow-list of declaration kinds that left whole module shapes calling `NugetMarshal`
 * without declaring it, so the core helpers are now unconditional too and this fixture gets
 * them. `Tier1CoreHelpersAlwaysEmittedTest` owns that invariant; the subject here stays
 * `NugetRuntime` itself, its env-check-before-P/Invoke order and its single emission.
 */
class Tier1RuntimeVersionTest {

  private val enumOnlyFixture = """
    package tier1.runtimeversion

    enum class Collar { RED, BLUE }
    """.trimIndent()

  @Test
  fun `a marshal-free module still imports nuget_runtime_version`() {
    val csharp: String = Tier1Harness.run(enumOnlyFixture).generatedCSharp

    assertContains(
      csharp,
      "internal static class NugetRuntime",
      message = "expected the always-emitted runtime helper in a module with no marshal helpers; " +
          "generatedCSharp:\n$csharp",
    )
    assertContains(
      csharp,
      "EntryPoint = \"nuget_runtime_version\"",
      message = "expected the 67th runtime export to be imported; generatedCSharp:\n$csharp",
    )
    // ROADMAP line 28: this fixture used to pin `NugetMarshal` ABSENT, which is what made it the
    // gate's negative case. The core helpers no longer depend on the module's declaration kinds, so
    // an enum-only module carries them too, unused; the fixture stays marshal-free by construction
    // (an enum renders as a C# `enum` plus ordinal bridges and calls no helper).
    assertContains(
      csharp,
      "internal static class NugetMarshal",
      message = "expected the now-unconditional core marshal helper; generatedCSharp:\n$csharp",
    )
  }

  @Test
  fun `the module initializer checks the trace variable before the P-Invoke`() {
    val csharp: String = Tier1Harness.run(enumOnlyFixture).generatedCSharp

    assertContains(
      csharp,
      "[System.Runtime.CompilerServices.ModuleInitializer]",
      message = "expected the fully qualified attribute (the using is only added for async); " +
          "generatedCSharp:\n$csharp",
    )

    assertContains(
      csharp,
      "#pragma warning disable CA2255",
      message = "CA2255 fails any consumer that builds the generated source with analyzers as " +
          "errors (it failed GeneratedBindingsCheck), so the suppression travels with the " +
          "attribute; generatedCSharp:\n$csharp",
    )

    val gate: Int = csharp.indexOf("GetEnvironmentVariable(\"NUGET_INTEROP_TRACE\")")
    val call: Int = csharp.indexOf("runtime {Version} loaded from")
    assertTrue(gate >= 0, "expected the NUGET_INTEROP_TRACE gate; generatedCSharp:\n$csharp")
    assertTrue(call >= 0, "expected the trace line; generatedCSharp:\n$csharp")
    assertTrue(
      gate < call,
      "the environment check must precede the P/Invoke, so a trace-off process pays nothing and " +
          "the native library's first load stays where it is; generatedCSharp:\n$csharp",
    )
    assertContains(
      csharp,
      "catch (Exception e) { line = \$\"[nuget:interop] runtime version unavailable from",
      message = "an initializer that throws is a load failure with a useless stack; the P/Invoke " +
          "must be wrapped; generatedCSharp:\n$csharp",
    )
  }

  /**
   * Positive control for the placement: when the gate IS open, the helper is emitted once, not
   * twice, and it lands beside the marshal helpers rather than in a second namespace.
   */
  @Test
  fun `a marshalling module gets exactly one runtime helper`() {
    val csharp: String = Tier1Harness.run(
      """
      package tier1.runtimeversionopen

      class Kennel(val name: String) {
        fun describe(): String = name
      }
      """.trimIndent(),
    ).generatedCSharp

    assertContains(csharp, "internal static class NugetMarshal")
    assertEquals(
      1,
      Regex("internal static class NugetRuntime").findAll(csharp).count(),
      "expected exactly one NugetRuntime; generatedCSharp:\n$csharp",
    )
  }
}
