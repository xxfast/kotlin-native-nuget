package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-179: `@CSharpName` sets one member's exact C# name. Verbatim (no `Async`), validated,
 * keyword-escaped, inherited by overrides, and a declared name that still collides is the same
 * fatal collision naming the annotation. The `@CName` symbol keeps the Kotlin name.
 */
class Tier1CSharpNameTest {

  private fun run(body: String): Tier1Result = Tier1Harness.run(
    "package tier1.csname\n\n" +
        "import io.github.xxfast.kotlin.native.nuget.annotations.CSharpName\n\n" + body,
    libraries = listOf(csharpNameLibrary),
  )

  private fun Tier1Result.assertClean() {
    assertTrue(kspErrors.isEmpty(), "kspErrors=$kspErrors")
    assertTrue(compiledClean, "compileErrors=$compileErrors")
  }

  @Test
  fun `a declared name on a suspend member renders verbatim with no Async suffix`() {
    val result = run(
      """
      class Reader(val owner: String) {
        @CSharpName("Fetch")
        suspend fun fetch(prefix: String): String = prefix + owner
      }
      """.trimIndent(),
    )
    result.assertClean()
    assertContains(result.generatedCSharp, "Task<string> Fetch(string prefix")
    assertFalse(" FetchAsync(" in result.generatedCSharp, result.generatedCSharp)
  }

  @Test
  fun `a name that is not a C# identifier is fatal`() {
    val result = run(
      """
      class Reader {
        @CSharpName("collar tag")
        fun tag(): Int = 1
      }
      """.trimIndent(),
    )
    assertTrue(
      result.kspErrors.any { "ERROR_CSHARP_NAME_INVALID" in it || "not a C# identifier" in it },
      "kspErrors=${result.kspErrors}",
    )
  }

  @Test
  fun `a keyword name is escaped`() {
    val result = run(
      """
      class Reader {
        @CSharpName("event")
        fun tag(): Int = 1
      }
      """.trimIndent(),
    )
    result.assertClean()
    assertContains(result.generatedCSharp, "int @event()")
  }

  @Test
  fun `an override inherits the declared name of the interface member`() {
    val result = run(
      """
      class Tag(val label: String)
      interface Record {
        val payload: Tag?
        @CSharpName("PayloadBytes")
        fun payload(code: Int): ByteArray?
      }
      class Collar(override val payload: Tag?) : Record {
        override fun payload(code: Int): ByteArray? = byteArrayOf(code.toByte())
      }
      """.trimIndent(),
    )
    result.assertClean()
    assertContains(result.generatedCSharp, "byte[]? PayloadBytes(int code);")
    assertContains(result.generatedCSharp, "public virtual byte[]? PayloadBytes(int code)")
    // ABI untouched: the export symbol keeps the Kotlin name.
    assertFalse("PayloadBytes" in result.generated, "a C# name leaked into the @CName side")
  }

  @Test
  fun `an override repeating the same declared name is allowed`() {
    val result = run(
      """
      interface Record {
        @CSharpName("PayloadBytes")
        fun payload(code: Int): Int
      }
      class Collar : Record {
        @CSharpName("PayloadBytes")
        override fun payload(code: Int): Int = code
      }
      """.trimIndent(),
    )
    result.assertClean()
    assertContains(result.generatedCSharp, "int PayloadBytes(int code)")
  }

  @Test
  fun `an override that declares a different name is fatal`() {
    val result = run(
      """
      interface Record {
        @CSharpName("PayloadBytes")
        fun payload(code: Int): Int
      }
      class Collar : Record {
        @CSharpName("Other")
        override fun payload(code: Int): Int = code
      }
      """.trimIndent(),
    )
    assertTrue(
      result.kspErrors.any { "ERROR_CSHARP_NAME_OVERRIDE_MISMATCH" in it || "disagrees" in it },
      "kspErrors=${result.kspErrors}",
    )
  }

  @Test
  fun `a declared name that still collides is fatal and names the annotation`() {
    val result = run(
      """
      class Tag(val label: String)
      class Collar(val payload: Tag?) {
        @CSharpName("Payload")
        fun payload(code: Int): Int = code
      }
      """.trimIndent(),
    )
    assertTrue(
      result.kspErrors.any { "@CSharpName(\"Payload\")" in it },
      "kspErrors=${result.kspErrors}",
    )
  }

  @Test
  fun `a top-level function takes its declared name`() {
    val result = run(
      """
      @CSharpName("CountCollars")
      fun count(): Int = 3
      """.trimIndent(),
    )
    result.assertClean()
    assertContains(result.generatedCSharp, "int CountCollars()")
  }

  @Test
  fun `a property takes its declared name`() {
    val result = run(
      """
      class Collar(@CSharpName("TagLabel") val label: String)
      """.trimIndent(),
    )
    result.assertClean()
    assertContains(result.generatedCSharp, "string TagLabel")
  }

  @Test
  fun `a companion member takes its declared name`() {
    val result = run(
      """
      class Collar {
        companion object {
          @CSharpName("Make")
          fun create(): Int = 1
        }
      }
      """.trimIndent(),
    )
    result.assertClean()
    assertContains(result.generatedCSharp, "int Make()")
  }
}
