package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-129 amendment: under `NUGET_INTEROP_TRACE`, every Kotlin exception that crosses the forward
 * bridge writes one `[nuget:interop] error ...` line from `NugetErrorNative.BuildException`, the
 * single C# site every forward error passes through. The member name comes from
 * `[CallerMemberName]`, so no call site changes; the success path is untouched.
 */
class Tier1ForwardErrorTraceTest {

  private val fixture: String = """
    package tier1.errortrace

    fun fetchToy(label: String): String = require(label.isNotEmpty()) { "no toy" }.let { label }
  """.trimIndent()

  @Test
  fun `the error builder takes the caller member name and Check forwards it`() {
    val cs: String = Tier1Harness.run(fixture).generatedCSharp

    assertContains(
      cs,
      "internal static Exception BuildException(IntPtr errorPtr, " +
        "[System.Runtime.CompilerServices.CallerMemberName] string caller = \"\")",
    )
    assertContains(
      cs,
      "internal static T Check<T>(T result, IntPtr error, " +
        "[System.Runtime.CompilerServices.CallerMemberName] string caller = \"\")",
    )
    // Without the explicit argument every `Check` site would report "Check".
    assertContains(cs, "if (error != IntPtr.Zero) throw BuildException(error, caller);")
  }

  @Test
  fun `the error builder traces the mapped exception through the runtime helper`() {
    val cs: String = Tier1Harness.run(fixture).generatedCSharp

    assertContains(cs, "Exception built = global::Kotlin.Native.Interop.KotlinException.CreateMapped(")
    assertContains(cs, "NugetRuntime.TraceError(caller, kotlinType, mappedType, msg, built);")
    // The ADR-161 original-fault branch traces too, as a rethrow of the managed exception.
    assertContains(cs, "NugetRuntime.TraceError(caller, kotlinType, mappedType, msg, original);")
    assertContains(cs, "internal static void TraceError(")
    assertContains(cs, "\"[nuget:interop] error {caller}: {kotlinType} -> {exception.GetType().Name}")
    // The gate is the first statement of the helper, before any string is built.
    val helper: String = cs.substringAfter("internal static void TraceError(")
    val gate: Int = helper.indexOf("GetEnvironmentVariable(\"NUGET_INTEROP_TRACE\")")
    val line: Int = helper.indexOf("[nuget:interop] error")
    assertTrue(gate in 0 until line, "the env check must precede the line; helper=$helper")
    assertEquals(
      1,
      Regex("internal static void TraceError\\(").findAll(cs).count(),
      "one helper per library; cs=$cs",
    )
  }

  @Test
  fun `the traced error builder compiles against the shared contract`() {
    Tier1CSharpCompile.assertCompiles(
      Tier1Harness.run(fixture),
      """
      namespace Consumer
      {
          public static class Probe
          {
              public static void Touch() { }
          }
      }
      """.trimIndent(),
    )
  }
}
