package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-154 amendment (2026-10-08): a non-public *primary* constructor is filtered before planning,
 * like a non-public secondary already was. `constructorEntries` filtered the secondaries by
 * visibility but took `cls.primaryConstructor` as is, so an `internal` primary was planned: on
 * ktor's `Url` (a klib, whose internal constructor the generated Kotlin cannot call) it surfaced
 * as a `Url.<init>` SKIPPED row, and in the author's own module it would bind as a public C#
 * constructor onto an internal Kotlin one.
 */
class Tier1InternalPrimaryConstructorTest {

  private val source: String = """
    package tier1.internalctor

    class Ticket internal constructor(val id: Int) {
      constructor(name: String) : this(name.length)
    }

    class Stub internal constructor(val id: Int)

    class Vault private constructor(val id: Int)

    fun issueStub(): Stub = Stub(1)
  """.trimIndent()

  @Test
  fun `an internal primary constructor is not exported`() {
    val result = Tier1Harness.run(source)

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val generated: String = result.generated
    for (owner in listOf("ticket", "stub", "vault")) {
      assertFalse(
        generated.contains("_${owner}_create\""),
        "expected no export for $owner's non-public primary constructor; generated=" +
            generated.lines().filter { it.contains("_create") },
      )
    }
    assertFalse(
      result.generatedCSharp.contains("public Stub(int"),
      "expected no public C# constructor onto Stub's internal one; generatedCSharp=" +
          result.generatedCSharp.lines().filter { it.contains("Stub(") },
    )
  }

  @Test
  fun `a public secondary beside an internal primary still exports`() {
    val result = Tier1Harness.run(source)

    assertTrue(
      result.generated.contains("_ticket_create_2\""),
      "expected Ticket's public secondary to export; generated=" +
          result.generated.lines().filter { it.contains("_create") },
    )
    assertTrue(
      result.generatedCSharp.contains("public Ticket(string name)"),
      "expected Ticket's public secondary in C#; generatedCSharp=" +
          result.generatedCSharp.lines().filter { it.contains("Ticket(") },
    )
    assertFalse(
      result.generatedCSharp.contains("public Ticket(int"),
      "expected no C# constructor onto Ticket's internal primary",
    )
  }

  @Test
  fun `a class with no public constructor is neither skipped nor warned`() {
    val result = Tier1Harness.run(source)

    val noise: List<String> = (result.kspWarnings + result.kspErrors).filter { line ->
      listOf("Stub", "Vault", "Ticket").any { owner -> line.contains("$owner.<init>") } ||
          line.contains(ForwardDiagnosticKind.WARNING_NO_PUBLIC_CONSTRUCTOR.name)
    }
    assertTrue(noise.isEmpty(), "expected no constructor diagnostics; got: $noise")
    assertTrue(
      result.generatedCSharp.contains("Stub IssueStub()"),
      "expected the factory to bind; generatedCSharp=" +
          result.generatedCSharp.lines().filter { it.contains("IssueStub") },
    )
  }
}
