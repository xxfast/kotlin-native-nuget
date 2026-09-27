package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-164 on the plan route: a defaulted parameter whose omission would leave a real sibling
 * overload's exact parameter list stays REQUIRED. Kotlin resolves `Kitten(name)` to the real
 * `Kitten(name)`, never to `Kitten(name, lives = 9)` with `lives` defaulted, so a widened
 * `int? lives = null` would dispatch its "unset" arm to the other overload. The runtime half is
 * `IntegrationTests.ShadowedOverloadDefaultTests` over `test-library/.../test/shadowed/`.
 */
class Tier1ShadowedDefaultTest {

  private val fixture: String = """
    package tier1.shadowed

    class Kitten(val name: String, val lives: Int = 9) {
      constructor(name: String) : this(name, 1)
      fun greet(visitor: String): String = "short"
      fun greet(visitor: String, times: Int = 2): String = "long"
      // A sibling of another type does not shadow: `fetch(1)` never resolves to `fetch(name)`.
      fun fetch(name: String): String = name
      fun fetch(count: Int, times: Int = 2): String = "" + count + times
    }

    fun summon(name: String): String = "short"
    fun summon(name: String, loud: Boolean = true): String = "long"

    fun Kitten.purr(): String = "short"
    fun Kitten.purr(loud: Boolean = true): String = "long"
    // Another receiver type does not shadow: `"x".hiss()` never resolves to `Kitten.hiss()`.
    fun String.hiss(): String = this
    fun Kitten.hiss(loud: Boolean = true): String = "long"
  """.trimIndent()

  private val result: Tier1Result by lazy {
    Tier1Harness.run(fixture, fileName = "Shadowed.kt", processorOptions = mapOf("nuget.rootPackage" to "tier1"))
  }

  @Test
  fun `the run succeeds and the generated Kotlin compiles`() {
    assertEquals(emptyList(), result.kspErrors, "KSP errors (an ADR-055 mismatch lands here)")
    assertTrue(result.compiledClean, "generated Kotlin must compile; got: ${result.compileErrors}")
  }

  @Test
  fun `a default a real shorter overload shadows stays required on every plan route`() {
    val csharp: String = result.generatedCSharp
    assertTrue(csharp.contains("public Kitten(string name, int lives)"), csharp)
    assertTrue(csharp.contains("public string Greet(string visitor, int times)"), csharp)
    assertTrue(csharp.contains("public static string Summon(string name, bool loud)"), csharp)
    assertFalse(csharp.contains("int? lives"), "lives must not widen")
    assertFalse(csharp.contains("Greet(string visitor, int? times"), "greet's times must not widen")
    assertFalse(result.generated.contains("0 -> tier1.shadowed.summon(name)"), result.generated)
  }

  @Test
  fun `an extension default a same-receiver extension shadows stays required`() {
    val csharp: String = result.generatedCSharp
    val purr = Regex("""Purr\(this [\w.:]*Kitten \w+, bool loud\)""")
    val hiss = Regex("""Hiss\(this [\w.:]*Kitten \w+, bool\? loud = null\)""")
    assertTrue(purr.containsMatchIn(csharp), csharp)
    assertTrue(hiss.containsMatchIn(csharp), csharp)
  }

  @Test
  fun `a sibling of another type leaves the default widened`() {
    assertTrue(
      result.generatedCSharp.contains("public string Fetch(int count, int? times = null)"),
      result.generatedCSharp,
    )
  }
}
