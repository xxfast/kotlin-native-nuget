package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #131: a callable dropped because one of its *parameters* is a nullable type with no wire
 * reported `SKIPPED_UNSUPPORTED_RETURN` and a hint that said "at this position" without naming one,
 * so the author read the (perfectly exportable) return type first. ADR-064's own doc comment
 * already prescribed the fix: the planner carries the position explicitly instead of relying on a
 * fixed reason-to-kind table.
 *
 * The control is the return half: a nullable specialized protocol at a return position still has
 * nowhere to put the absence, so it keeps `SKIPPED_UNSUPPORTED_RETURN` and the unnamed sentence.
 *
 * `hub` / `Hub` are the second half of the issue (part 2): a nullable exported class handle *is* a
 * supported parameter, on the top-level and constructor routes as well as the class-method one, so
 * nothing here may skip. The end-to-end half lives in `HubSample.kt` / `Issue131Tests.cs`.
 */
class Tier1NullableParameterDiagnosticTest {

  private val source: String = """
    package tier1.nullableparameter

    import kotlinx.coroutines.flow.Flow

    class Settings(val level: Int = 0)

    class Logger(val tag: String)

    class Hub(val settings: Settings, val logger: Logger?, val note: String? = null)

    fun hub(settings: Settings = Settings(), logger: Logger? = null, note: String? = null): Hub =
      Hub(settings, logger, note)

    fun hubWithEvents(settings: Settings = Settings(), events: Flow<Int>? = null): Hub =
      Hub(settings, null)

    fun latest(): Flow<Int>? = null
  """.trimIndent()

  private fun diagnostic(result: Tier1Result, kind: ForwardDiagnosticKind, member: String): String =
    requireNotNull(
      result.kspWarnings.firstOrNull { it.contains(kind.name) && it.contains(member) },
    ) { "expected a ${kind.name} skip for $member; kspWarnings=${result.kspWarnings}" }

  @Test
  fun `a nullable parameter with no wire is named an input skip, not a return one`() {
    val result = Tier1Harness.run(source)

    diagnostic(result, ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT, "hubWithEvents")
    assertTrue(
      result.kspWarnings.none {
        it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN.name) &&
            it.contains("hubWithEvents")
      },
      "expected no return-position skip for hubWithEvents; kspWarnings=${result.kspWarnings}",
    )
  }

  @Test
  fun `the diagnostic names the offending parameter`() {
    val result = Tier1Harness.run(source)

    val diagnostic: String =
      diagnostic(result, ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT, "hubWithEvents")
    assertTrue(
      diagnostic.contains("`events`"),
      "expected the diagnostic to name the offending parameter; got: $diagnostic",
    )
    assertFalse(
      diagnostic.contains("at this position"),
      "expected the named parameter to replace the unnamed position wording; got: $diagnostic",
    )
  }

  @Test
  fun `a nullable return keeps the return-position skip and its unnamed hint`() {
    val result = Tier1Harness.run(source)

    val diagnostic: String =
      diagnostic(result, ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN, "latest")
    assertTrue(
      diagnostic.contains("at this position"),
      "expected the shipped return-position hint to be unchanged; got: $diagnostic",
    )
  }

  @Test
  fun `a nullable class handle parameter binds on the top-level and constructor routes`() {
    val result = Tier1Harness.run(source)

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    assertTrue(
      result.kspWarnings.none { it.contains("tier1.nullableparameter.hub:") },
      "expected no skip for hub; kspWarnings=${result.kspWarnings}",
    )
    assertTrue(
      result.kspWarnings.none { it.contains("tier1.nullableparameter.Hub.<init>") },
      "expected no skip for the Hub constructor; kspWarnings=${result.kspWarnings}",
    )
    assertTrue(
      result.generatedCSharp.contains("Logger? logger"),
      "expected a nullable C# handle parameter; generated=${result.generatedCSharp}",
    )
  }
}
