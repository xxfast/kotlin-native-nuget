package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ADR-202 / ADR-241: every export that hands C# a value a runtime-owned route later invokes
 * (`nuget_suspend_func*`) installs this module's classifier first, so an exported exception thrown
 * there maps to its generated type whatever ran before it in the process. Structural on purpose:
 * the install is process-global, so no consumer-level row can prove a given site installs it.
 *
 * Also ADR-241: at an erased `T`, a Kotlin-backed exception keeps its handle (identity) rather than
 * crossing as managed-exception text.
 *
 * Oreo hands Mylo a treat he has to unwrap later.
 */
class Tier1ExportedExceptionRuntimeRoutesTest {

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      """
      package tier1.routes

      class Spoiled(message: String) : Exception(message)

      interface Taster {
        suspend fun taste(sample: suspend (String) -> String): String
      }

      class Pantry {
        fun later(): suspend (String) -> String = { key -> throw Spoiled(key) }
        // A `Throwable` lambda component: what renders the erased `Wrap<T>` exception arm.
        fun spoiler(): (String) -> Throwable = { key -> Spoiled(key) }
        fun <T> echo(value: T): T = value
      }

      suspend fun tasting(taster: Taster): String = taster.taste { key -> throw Spoiled(key) }
      """.trimIndent(),
      processorOptions = mapOf("nuget.rootPackage" to "tier1"),
    )
  }

  /** The body of the generated export function annotated with [cName]. */
  private fun export(cName: String): String {
    val kt: String = result.generated
    val start: Int = kt.indexOf("@CName(\"$cName\")")
    assertTrue(start >= 0, "no export $cName; exports=${Regex("""@CName\("([^"]+)"\)""").findAll(kt).map { it.groupValues[1] }.toList()}")
    val next: Int = kt.indexOf("@CName(", start + 1).let { if (it < 0) kt.length else it }
    return kt.substring(start, next)
  }

  @Test
  fun `a returned suspend lambda installs the classifier`() {
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    val body: String = export("library_routes__pantry_later")
    assertTrue("nugetInstallMappedType(::nugetMappedType)" in body, body)
  }

  @Test
  fun `a bridge whose slot hands C# a suspend lambda installs the classifier`() {
    val body: String = export("library_routes__taster_bridge_create")
    assertTrue("nugetInstallMappedType(::nugetMappedType)" in body, body)
  }

  @Test
  fun `an erased exception keeps its handle before it would cross as text`() {
    val code: List<String> = result.generatedCSharp.lines().filterNot { it.trimStart().startsWith("///") }
    assertTrue(
      code.any { line ->
        "if (value is Exception exception && value is not INugetHandle) return Native_throwable_from_text(" in line
      },
      "expected the managed-text arm to exclude Kotlin-backed exceptions; " +
        "lines=${code.filter { "throwable_from_text(" in it }}",
    )
  }
}
