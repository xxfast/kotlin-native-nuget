package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-133 flips this file: a plain nested `class` or `object` under a supported owner is no longer
 * skipped, it is declared as the C# nested type `Owner.Nested` / `Owner.Single`, and every member
 * typed with one binds instead of skipping `UNDECLARED_CLASS`.
 *
 * What the file keeps, and why it is still called a skip test: the two cells that must NOT flip.
 * A `companion object` is still not a nested declaration (ADR-013 folds it into its owner's
 * statics), and nothing under a **private** owner is API at all, so neither may be declared and
 * neither may be warned about. Those two carve-outs are what a "declare every nested declaration"
 * implementation is most likely to sweep up.
 *
 * The full presence surface (all four kinds, depth 2, object owner, export prefix chain, deferred
 * owner shapes) lives in [Tier1NestedTypesTest]; this file stays on the class/object pair its
 * fixture already pinned so the flip is readable as a diff.
 */
class Tier1NestedClassSkipTest {

  private val source: String = """
    package tier1.nestedclass

    class Owner {
      data class Nested(val x: String?)
      object Single

      companion object {
        fun create(): Owner = Owner()
      }

      fun make(): Nested = Nested("a")
      fun maybe(): Nested? = null
      val stored: Nested = Nested("a")

      val label: String = "owner"
    }

    class Quiet {
      class Unused {
        class Deeper
      }

      private class Hushed {
        class Buried
      }
    }
  """.trimIndent()

  @Test
  fun `a nested class or object is declared as a nested C# type`() {
    val result = Tier1Harness.run(source)

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val csharp: String = result.generatedCSharp
    listOf(
      "public class Nested",
      "public static class Single",
      "public class Unused",
      // Depth 2 was already in this fixture: `Quiet.Unused.Deeper`.
      "public class Deeper",
    ).forEach { declaration ->
      assertContains(csharp, declaration, message = "expected `$declaration`; csharp=$csharp")
    }
    // Was: every one of these exports had to be ABSENT.
    listOf(
      "export_library_tier1_nestedclass__owner_make",
      "export_library_tier1_nestedclass__owner_maybe",
      "export_library_tier1_nestedclass__owner_get_stored",
    ).forEach { export ->
      assertContains(
        result.generated,
        export,
        message = "expected $export to bind now that its type is declared; generated=${result.generated}",
      )
    }
    assertTrue(
      result.generated.contains("export_library_tier1_nestedclass__owner_get_label"),
      "expected the control member to keep binding; generated=${result.generated}",
    )
  }

  @Test
  fun `the export prefix is the enclosing chain, not the bare simple name`() {
    val result = Tier1Harness.run(source)

    // ADR-117 would raise ERROR_C_ENTRY_POINT_COLLISION if `Owner.Nested` exported `nested_create`
    // against an unrelated top-level `Nested`; the chain is what keeps the symbol unique.
    assertContains(result.generated, "@CName(\"library_tier1_nestedclass__owner_nested_create\")")
    assertContains(result.generated, "@CName(\"library_tier1_nestedclass__quiet_unused_deeper_create\")")
    assertFalse(
      result.generated.contains("@CName(\"library_tier1_nestedclass__nested_create\")") ||
          result.generated.contains("@CName(\"library_tier1_nestedclass__deeper_create\")"),
      "expected no unchained entry point; generated=${result.generated}",
    )
  }

  @Test
  fun `the companion and everything under a private owner are still not declared`() {
    val result = Tier1Harness.run(source)

    val nestedWarnings: List<String> = result.kspWarnings
      .filter { it.contains(ForwardDiagnosticKind.SKIPPED_NESTED_DECLARATION.name) }
    // Was: each of Owner.Nested, Owner.Single, Quiet.Unused and Quiet.Unused.Deeper had to be
    // named here. They are declared now, so a warning for any of them is a bug.
    listOf(
      "tier1.nestedclass.Owner.Nested",
      "tier1.nestedclass.Owner.Single",
      "tier1.nestedclass.Quiet.Unused",
      "tier1.nestedclass.Quiet.Unused.Deeper",
    ).forEach { declaration ->
      assertFalse(
        nestedWarnings.any { it.contains(declaration) },
        "expected $declaration to be declared, not skipped; nestedWarnings=$nestedWarnings",
      )
    }
    // Carve-out 1: a companion is its owner's statics, never a nested type.
    assertFalse(
      nestedWarnings.any { it.contains("Companion") },
      "expected no nested-declaration warning for the companion; nestedWarnings=$nestedWarnings",
    )
    assertFalse(
      result.generatedCSharp.contains("class Companion"),
      "expected the companion to stay folded into its owner's statics; csharp=" +
          "${result.generatedCSharp.lines().filter { it.contains("Companion") }}",
    )
    assertTrue(
      result.generated.contains("export_library_tier1_nestedclass__owner_create"),
      "expected the companion's own member to keep binding; generated=${result.generated}",
    )
    // Carve-out 2: what hides under a private owner is not reachable API, so it is neither
    // declared nor reported -- the walk still descends through public children only.
    assertFalse(
      nestedWarnings.any { it.contains("Hushed") || it.contains("Buried") },
      "expected no warning under the private owner; nestedWarnings=$nestedWarnings",
    )
    assertFalse(
      result.generatedCSharp.contains("Hushed") || result.generatedCSharp.contains("Buried"),
      "expected nothing under a private owner to be declared; csharp=" +
          "${result.generatedCSharp.lines().filter { it.contains("Hushed") || it.contains("Buried") }}",
    )
  }

  @Test
  fun `the nullable nested return binds and keeps its nullability`() {
    val result = Tier1Harness.run(source)

    // Was: `Owner.maybe` had to skip, and the assertion was only that it did not blame NULLABLE.
    // The nullable position was a separate code path then and stays a separate cell now.
    assertContains(result.generated, "export_library_tier1_nestedclass__owner_maybe")
    assertFalse(
      result.kspWarnings.any { it.contains("SKIPPED_") && it.contains("Owner.maybe") },
      "expected no skip for the nullable nested return; kspWarnings=${result.kspWarnings}",
    )
  }
}
