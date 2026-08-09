package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * A `DllImport` `string` parameter marshals as Ansi by default, i.e. the active Windows code page,
 * while every Kotlin/Native export reads UTF-8. `BidirectionalTests
 * .CSharpDog_PropertyGetterAndMethodReturn_CrossBridgeWithNonAsciiAndEmptyString` saw
 * `"Röver 🐕"` arrive as `"R?ver ??"` on windows-latest. macOS/Linux cannot reproduce that (their
 * code page *is* UTF-8), so the pin is the generated annotation text.
 */
class Tier1Utf8StringParameterTest {

  private val fixture: String = """
    package tier1.utf8

    class Owner(val name: String) {
      fun rename(next: String): String = next
      fun decorate(prefix: String, transform: (String) -> String): String = transform(prefix + name)
    }

    fun greet(name: String): String = "hi ${'$'}name"

    fun tag(labels: List<String>): String = labels.joinToString()

    fun shout(name: String, decorate: (String) -> String): String = decorate(name)
  """.trimIndent()

  @Test
  fun `every string parameter of a native import is annotated LPUTF8Str`() {
    val generated: String = Tier1Harness.run(fixture).generatedCSharp

    val offenders: List<String> = generated.lines()
      .map { line -> line.trim() }
      .filter { line -> line.contains("static extern ") }
      .filter { line -> line.parameters().any { it.isUnannotatedString() } }

    assertTrue(offenders.isEmpty(), "unannotated string parameters: ${offenders.joinToString("\n")}")
  }

  @Test
  fun `the ordinary plan route annotates function and constructor parameters`() {
    val generated: String = Tier1Harness.run(fixture).generatedCSharp

    assertContains(
      generated,
      "Native_greet([MarshalAs(UnmanagedType.LPUTF8Str)] string name, out IntPtr error);",
    )
    assertContains(
      generated,
      "Native_Create([MarshalAs(UnmanagedType.LPUTF8Str)] string name, out IntPtr error);",
    )
  }

  @Test
  fun `the shared marshal helper annotates its string box wrapper`() {
    val generated: String = Tier1Harness.run(fixture).generatedCSharp

    assertContains(
      generated,
      "IntPtr nuget_wrap_string([MarshalAs(UnmanagedType.LPUTF8Str)] string value);",
    )
  }

  private fun String.parameters(): List<String> =
    substringAfter("(").substringBeforeLast(")").split(",").map { it.trim() }

  private fun String.isUnannotatedString(): Boolean {
    val type: String = substringBeforeLast(" ").trim()
    return type == "string" || type == "string?"
  }
}
