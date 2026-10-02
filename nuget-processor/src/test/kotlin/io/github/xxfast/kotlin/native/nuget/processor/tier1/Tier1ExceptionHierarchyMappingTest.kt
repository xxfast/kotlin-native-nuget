package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.cir.KOTLIN_EXCEPTION_TYPES
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-177 (issue #349): exception mapping by class hierarchy. The Kotlin side classifies each
 * error node with `is` against an ordered row table; the optional `kotlinx.io.IOException` row
 * joins the generated `nugetMappedType` only when KSP resolves that class on the classpath.
 */
class Tier1ExceptionHierarchyMappingTest {

  private val scoop: String = """
    package tier1.litterbox

    fun scoop(catName: String): String = "${'$'}catName's litter box is spotless"
  """.trimIndent()

  // Stands in for the kotlinx-io klib, which a JVM compilation cannot link. `internal` so it is
  // never itself an export root; the generated `is` still resolves it inside the same module.
  private val kotlinxIo: String = """
    package kotlinx.io

    internal open class IOException(message: String? = null) : Exception(message)
  """.trimIndent()

  @Test
  fun `the IO row is emitted when kotlinx io IOException is on the classpath`() {
    val result = Tier1Harness.run(mapOf("Fixture.kt" to scoop, "KotlinxIo.kt" to kotlinxIo))

    assertTrue(
      result.compiledClean,
      "expected the classifier to compile; got: ${result.compileErrors}",
    )
    assertTrue(
      "t is kotlinx.io.IOException -> \"kotlinx.io.IOException\"" in result.generated,
      "expected the IO row in nugetMappedType; generated=${result.generated}",
    )
    assertTrue("else -> nugetStdlibMappedType(t)" in result.generated, result.generated)
    assertTrue("buildError(e, ::nugetMappedType)" in result.generated, result.generated)
  }

  @Test
  fun `the IO row is omitted when kotlinx io is absent`() {
    val result = Tier1Harness.run(scoop)

    assertTrue(
      result.compiledClean,
      "expected the classifier to compile; got: ${result.compileErrors}",
    )
    assertFalse("kotlinx.io.IOException" in result.generated, result.generated)
    assertTrue(
      "internal fun nugetMappedType(t: Throwable): String? = nugetStdlibMappedType(t)" in
        result.generated,
      "expected the stdlib-only classifier; generated=${result.generated}",
    )
  }

  @Test
  fun `the C# switch keys on the mapped type and names the new classes`() {
    val result = Tier1Harness.run(scoop)
    val cs: String = result.generatedCSharp

    assertTrue("mappedType switch" in cs, cs)
    assertTrue("EntryPoint = \"nuget_error_cause_mapped_type\"" in cs, cs)
    assertTrue("string mappedType = CauseMappedType(errorPtr, 0);" in cs, cs)
    assertTrue(
      "new global::Kotlin.Native.Interop.KotlinIOException(kotlinType, message, stackTrace, inner)" in cs,
      cs,
    )
    assertTrue(
      "new global::Kotlin.Native.Interop.KotlinNullReferenceException(kotlinType, message, stackTrace, inner)" in cs,
      cs,
    )
    assertTrue(
      "new global::Kotlin.Native.Interop.KotlinOperationCanceledException(kotlinType, message, stackTrace, inner)" in cs,
      cs,
    )
    assertTrue("global::Kotlin.Native.Interop.KotlinException.Create(" in cs, cs)
    // Most specific first: the CancellationException arm precedes the IllegalStateException arm.
    assertTrue(
      cs.indexOf("\"kotlin.coroutines.cancellation.CancellationException\" =>") <
        cs.indexOf("\"kotlin.IllegalStateException\" =>"),
      cs,
    )
  }

  @Test
  fun `a documented throws of a user subclass crefs the row it IS-A`() {
    val result = Tier1Harness.run(
      mapOf(
        "Fixture.kt" to """
          package tier1.litterbox

          class LitterBoxJammedException(message: String) : kotlinx.io.IOException(message)

          class LitterBoxGrumble : IllegalStateException()

          /**
           * Rakes the litter.
           *
           * @throws LitterBoxJammedException when Oreo buried the rake
           * @throws LitterBoxGrumble when Oreo sulks
           */
          fun rake(catName: String): String = "${'$'}catName's litter is raked"
        """.trimIndent(),
        "KotlinxIo.kt" to kotlinxIo.replace("internal open class", "public open class"),
      ),
    )
    val cs: String = result.generatedCSharp

    assertTrue(
      "<exception cref=\"KotlinIOException\">when Oreo buried the rake</exception>" in cs,
      cs,
    )
    assertTrue(
      "<exception cref=\"KotlinInvalidOperationException\">when Oreo sulks</exception>" in cs,
      cs,
    )
  }

  /**
   * The runtime's `nugetStdlibMappedType` and this table must name the same stdlib rows in the
   * same order: the C# switch is rendered from this table and keys on what the runtime returns.
   * Read from the runtime source, the same honesty device as `NUGET_RUNTIME_EXPORTS`.
   */
  @Test
  fun `the runtime stdlib rows equal the table's non-optional rows in order`() {
    val source: String = File(
      "../nuget-runtime/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/runtime/NugetRuntime.kt",
    ).readText()
    val body: String = source
      .substringAfter("public fun nugetStdlibMappedType(t: Throwable): String? = when {")
      .substringBefore("  else -> null")
    val runtimeRows: List<String> = Regex("""->\s*"([^"]+)"""")
      .findAll(body)
      .map { match -> match.groupValues[1] }
      .toList()

    val tableRows: List<String> = KOTLIN_EXCEPTION_TYPES
      .filterNot { row -> row.optional }
      .map { row -> row.kotlinType }

    assertEquals(tableRows, runtimeRows)
  }
}
