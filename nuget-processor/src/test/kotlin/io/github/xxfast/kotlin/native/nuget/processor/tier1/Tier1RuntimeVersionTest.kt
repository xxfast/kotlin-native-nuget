package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-129: `NugetRuntime` is the only helper in `Interop.cs` that is emitted unconditionally. Every
 * other one sits inside `CirTranslator`'s `needsMarshalHelper` gate, so a library that marshals
 * nothing would otherwise have no way to say which `nuget-runtime` its binary carries.
 *
 * The enum-only fixture is the load-bearing one: `needsCoreMarshal` is
 * `functions.isNotEmpty() || classes.isNotEmpty() || objects... || sealedClasses...`, so an enum on
 * its own is the shape that leaves the gate shut. (ADR-129's §4 table says "one top-level scalar
 * function"; that shape opens the gate through `needsCoreMarshal` and would have proved nothing.)
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
    assertTrue(
      "internal static class NugetMarshal" !in csharp,
      "the fixture must genuinely leave `needsMarshalHelper` shut, otherwise this test proves " +
          "nothing about the gate; generatedCSharp:\n$csharp",
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
